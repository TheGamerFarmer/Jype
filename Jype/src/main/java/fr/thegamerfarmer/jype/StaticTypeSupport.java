package fr.thegamerfarmer.jype;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeCopier;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.tree.TreeScanner;
import com.sun.tools.javac.tree.TreeTranslator;
import com.sun.tools.javac.util.Log;
import com.sun.tools.javac.util.Names;

import java.util.*;
import static fr.thegamerfarmer.jype.StaticOwners.*;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Lowers managed static declarations before Enter, including signatures consumed by Lombok.
 *
 * <p>Storage is erased to a declared bound while generic bridge methods expose method-owned
 * F-bounded views. Storage remains shared across bindings; callers must use compatible values.
 * This is neither per-specialization storage nor a runtime type check.
 *
 * <p>Declaration lowering precedes {@link StaticAccessRewriter}. {@link StaticOwners} resolves
 * inherited and compiled members without loading user classes. Source edits keep Javadoc copies
 * consistent without exposing generated bridge implementations.
 */
final class StaticTypeSupport {
    private DeclarationOrigins origins;
    final TreeMaker make;
    private final StaticOwners owners;
    final Names names;
    private final Log log;
    final Set<String> managed;
    final Map<String, String> bounds;
    private final Map<String, List<String>> parameters;
    private final Map<JCTree.JCClassDecl, String> classNames;
    final BiFunction<JCTree.JCCompilationUnit, JCTree.JCExpression, String> resolve;
    final Set<JCTree.JCMethodDecl> accessors = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<String, List<JavadocSourceGenerator.SourceEdit>> sourceEdits;
    private List<String> residual = List.of();
    private Map<String, String> concrete = Map.of();
    private Function<String, JCTree.JCExpression> concreteExpression = name -> null;

    /** Retains task-local factories, package bounds, canonical source orders and edit destinations. */
    StaticTypeSupport(TreeMaker make, Names names, Log log, Set<String> managed,
                      Map<String, String> bounds, Map<String, List<String>> parameters,
                      Map<JCTree.JCClassDecl, String> classNames,
                      Map<String, List<JavadocSourceGenerator.SourceEdit>> sourceEdits,
                      BiFunction<JCTree.JCCompilationUnit, JCTree.JCExpression, String> resolve,
                      Function<String, TypeSystemClassFileReader.ClassData> readClass) {
        this.make = make;
        this.names = names;
        this.log = log;
        this.managed = managed;
        this.bounds = bounds;
        this.parameters = parameters;
        this.classNames = classNames;
        this.sourceEdits = sourceEdits;
        this.resolve = resolve;
        owners = new StaticOwners(managed, parameters, resolve, readClass);
    }

    /** Lowers selected roots and member classes before rewriting contextual accesses. */
    void process(List<JCTree.JCCompilationUnit> units, Set<JCTree.JCClassDecl> roots) {
        for (var unit : units) {
            for (var declaration : unit.defs) {
                if (declaration instanceof JCTree.JCClassDecl cls && roots.contains(cls)) collect(unit, cls);
            }
        }
        List<Owner> sources = List.copyOf(owners.owners.values());
        for (Owner owner : sources) lower(owner);
        for (Owner owner : sources) new StaticAccessRewriter(this, owners, owner).rewrite();
    }

    private void collect(JCTree.JCCompilationUnit unit, JCTree.JCClassDecl cls) {
        String name = classNames.get(cls);
        owners.owners.put(name, new Owner(name, cls, unit, new LinkedHashMap<>(), new LinkedHashMap<>()));
        for (var member : cls.defs) {
            if (member instanceof JCTree.JCClassDecl inner) collect(unit, inner);
        }
    }

    private void lower(Owner owner) {
        List<JCTree> generated = new ArrayList<>();
        for (var member : owner.tree.defs) {
            if (member instanceof JCTree.JCVariableDecl field && isStatic(field.mods.flags)) {
                JCTree.JCExpression view = complete(owner.unit, copy(field.vartype));
                if (!usesManaged(view)) continue;
                String bridgeName = VIEW_PREFIX + field.name + "$"
                        + HexFormat.of().formatHex(owner.name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                var bridge = accessor(field, view, bridgeName, true,
                        field.mods.flags & (Flags.PUBLIC | Flags.PROTECTED | Flags.PRIVATE));
                generated.add(bridge);
                accessors.add(bridge);
                String writerName = null;
                if ((field.mods.flags & Flags.FINAL) == 0) {
                    writerName = WRITE_PREFIX + bridgeName.substring(VIEW_PREFIX.length());
                    var writer = accessor(field, view, writerName, false,
                            field.mods.flags & (Flags.PUBLIC | Flags.PROTECTED | Flags.PRIVATE));
                    writer.restype = copy(view);
                    writer.body.stats = writer.body.stats.append(make.Return(make.Ident(names.fromString("$jype$value"))));
                    generated.add(writer);
                    accessors.add(writer);
                }
                owner.fields.put(field.name.toString(), new Field(owner.name, bridgeName, writerName, List.copyOf(managed)));
                owner.methods.put(bridgeName, List.copyOf(managed));
                var storedType = storage(view);
                edit(owner, field.vartype, storedType.toString());
                field.vartype = storedType;
                field.init = eraseTypes(owner, field.init);
                for (String annotation : List.of("Getter", "Setter")) {
                    JCTree.JCAnnotation lombok = lombokAnnotation(owner.unit, field.mods, annotation);
                    if (lombok == null || disabled(lombok)) continue;
                    if (!supportedAccessor(owner, field, lombok)) continue;
                    if (annotation.equals("Setter") && (field.mods.flags & Flags.FINAL) != 0) continue;
                    String suffix = Character.toUpperCase(field.name.charAt(0)) + field.name.toString().substring(1);
                    String methodName = (annotation.equals("Getter") ? "get" : "set") + suffix;
                    boolean exists = owner.tree.defs.stream().anyMatch(tree ->
                            tree instanceof JCTree.JCMethodDecl method && method.name.contentEquals(methodName)
                                    && method.params.size() == (annotation.equals("Getter") ? 0 : 1));
                    if (!exists) {
                        var accessor = accessor(field, view, methodName, annotation.equals("Getter"), access(lombok));
                        if (origins != null) origins.registerAccessor(owner.name, accessor, field, annotation.equals("Getter"));
                        accessors.add(accessor);
                        generated.add(accessor);
                    }
                    field.mods.annotations = com.sun.tools.javac.util.List.filter(field.mods.annotations, lombok);
                    if (!exists) owner.methods.put(methodName, List.copyOf(managed));
                }
            } else if (member instanceof JCTree.JCMethodDecl method && isStatic(method.mods.flags)) {
                new TreeScanner() {
                    @Override public void visitMethodDef(JCTree.JCMethodDecl tree) {
                        tree.restype = completeForMethod(owner.unit, tree.restype);
                        super.visitMethodDef(tree);
                    }
                    @Override public void visitVarDef(JCTree.JCVariableDecl tree) {
                        tree.vartype = completeForMethod(owner.unit, tree.vartype);
                        scan(tree.init);
                    }
                    @Override public void visitTypeCast(JCTree.JCTypeCast tree) {
                        if (tree.clazz instanceof JCTree.JCExpression type) {
                            tree.clazz = completeForMethod(owner.unit, type);
                        }
                        scan(tree.expr);
                    }
                    @Override public void visitNewClass(JCTree.JCNewClass tree) {
                        tree.clazz = completeForMethod(owner.unit, tree.clazz);
                        scan(tree.args);
                    }
                }.scan(method);
                Set<String> explicit = new HashSet<>();
                method.typarams.forEach(parameter -> explicit.add(parameter.name.toString()));
                if (usesManaged(method, explicit)) {
                    extendMethod(owner, method);
                }
            } else if (member instanceof JCTree.JCBlock block && isStatic(block.flags)) {
                eraseTypes(owner, block);
            }
        }
        owner.tree.defs = owner.tree.defs.appendList(com.sun.tools.javac.util.List.from(generated));
        if (!owner.fields.isEmpty()) {
            var previous = log.useSource(owner.unit.sourcefile);
            try {
                log.warning(owner.tree.pos, new com.sun.tools.javac.util.JCDiagnostic.Warning(
                        "compiler", "proc.messager", "[Jype] Managed static fields in " + owner.tree.name
                        + " use shared storage and unchecked generic views; all callers must use one compatible type binding."));
            } finally {
                log.useSource(previous);
            }
        }
    }

    private JCTree.JCMethodDecl accessor(JCTree.JCVariableDecl field, JCTree.JCExpression view,
                                        String name, boolean getter, long access) {
        make.at(field.pos);
        var typeParameters = methodParameters(com.sun.tools.javac.util.List.nil(), field.pos);
        var arguments = com.sun.tools.javac.util.List.<JCTree.JCVariableDecl>nil();
        JCTree.JCStatement statement;
        JCTree.JCExpression returnType;
        if (getter) {
            returnType = copy(view);
            statement = make.Return(make.TypeCast(copy(view), make.Ident(field.name)));
        } else {
            returnType = make.TypeIdent(com.sun.tools.javac.code.TypeTag.VOID);
            arguments = com.sun.tools.javac.util.List.of(make.VarDef(make.Modifiers(Flags.PARAMETER),
                    names.fromString("$jype$value"), copy(view), null));
            statement = make.Exec(make.Assign(make.Ident(field.name), make.Ident(names.fromString("$jype$value"))));
        }

        return make.MethodDef(make.Modifiers(access | Flags.STATIC), names.fromString(name),
                returnType, typeParameters, arguments, com.sun.tools.javac.util.List.nil(),
                make.Block(0, com.sun.tools.javac.util.List.of(statement)), null);
    }

    void origins(DeclarationOrigins sourceOrigins) { origins = sourceOrigins; }

    /**
     * In a specialized inherited system, static contexts only abstract over the residual aliases;
     * every other alias is its local binding, applied to those residual parameters.
     */
    void specialize(List<String> residual, Map<String, String> concrete,
                    Function<String, JCTree.JCExpression> concreteExpression) {
        this.residual = List.copyOf(residual);
        this.concrete = Map.copyOf(concrete);
        this.concreteExpression = concreteExpression;
    }

    /** Aliases a static member must own to call managed views. */
    Collection<String> contextParameters() { return residual.isEmpty() ? managed : residual; }

    boolean specialized() { return !residual.isEmpty(); }

    /** Whether {@code alias} can be expressed by its specialized binding with the given lexical parameters. */
    boolean specializedContext(String alias, Set<String> available) {
        return !residual.isEmpty() && concrete.containsKey(alias) && available.containsAll(residual);
    }

    JCTree.JCExpression specializedArgument(String alias) {
        return concreteExpression.apply(concrete.get(alias));
    }

    /** Adds method-owned parameters and records source insertions, preserving explicit ordinary ones. */
    void extendMethod(Owner owner, JCTree.JCMethodDecl method) {
        var original = method.typarams;
        method.typarams = methodParameters(original, method.pos);
        List<String> added = method.typarams.stream().skip(original.size()).map(Object::toString).toList();
        if (!added.isEmpty()) {
            if (original.isEmpty()) {
                insert(owner, method.restype.getStartPosition(), "<" + String.join(", ", added) + "> ");
            } else {
                int end = original.last().getEndPosition(owner.unit.endPositions);
                insert(owner, end, ", " + String.join(", ", added));
            }
        }
        List<String> order = method.typarams.stream().map(p -> p.name.toString()).toList();
        String name = method.name.toString();
        if (owner.methods.containsKey(name) && !owner.methods.get(name).equals(order)) order = List.of();
        owner.methods.put(name, order);
    }

    private com.sun.tools.javac.util.List<JCTree.JCTypeParameter> methodParameters(
            com.sun.tools.javac.util.List<JCTree.JCTypeParameter> existing, int position) {
        var result = existing;
        Set<String> declared = new HashSet<>();
        existing.forEach(parameter -> declared.add(parameter.name.toString()));
        for (String name : contextParameters()) {
            if (declared.contains(name)) continue;
            String bound = bounds.get(name);
            if (bound == null) {
                throw new IllegalStateException("[Jype] Cannot resolve static bound for " + name);
            }
            make.at(position);
            JCTree.JCExpression type = qualified(bound);
            List<String> boundParameters = owners.typeParameters(bound);
            if (!boundParameters.isEmpty()) {
                var args = new ArrayList<JCTree.JCExpression>();
                for (String parameter : boundParameters) {
                    if (!residual.isEmpty() && !residual.contains(parameter) && concrete.containsKey(parameter)) {
                        args.add(specializedArgument(parameter));
                        continue;
                    }
                    make.at(position);
                    args.add(contextParameters().contains(parameter) ? make.Ident(names.fromString(parameter))
                            : make.Wildcard(make.TypeBoundKind(com.sun.tools.javac.code.BoundKind.UNBOUND), null));
                }
                make.at(position);
                type = make.TypeApply(type, com.sun.tools.javac.util.List.from(args));
            }
            result = result.append(make.TypeParameter(names.fromString(name), com.sun.tools.javac.util.List.of(type)));
        }
        return result;
    }

    private JCTree.JCExpression completeForMethod(JCTree.JCCompilationUnit unit, JCTree.JCExpression type) {
        boolean template = managed.stream().anyMatch(parameter -> {
            String bound = bounds.get(parameter);
            return bound != null && !owners.typeParameters(bound).isEmpty();
        });
        return template ? complete(unit, type, true) : type;
    }

    private JCTree.JCExpression complete(JCTree.JCCompilationUnit unit, JCTree.JCExpression type) {
        return complete(unit, type, false);
    }

    private JCTree.JCExpression complete(JCTree.JCCompilationUnit unit, JCTree.JCExpression type,
                                       boolean compiledOnly) {
        return new TreeTranslator() {
            private JCTree.JCExpression argument(String parameter, int position) {
                if (!residual.isEmpty() && !residual.contains(parameter) && concrete.containsKey(parameter)) {
                    make.at(position);
                    return specializedArgument(parameter);
                }
                return make.at(position).Ident(names.fromString(parameter));
            }
            private JCTree.JCExpression raw(JCTree.JCExpression tree) {
                String target = resolve.apply(unit, tree);
                if (target == null) return tree;
                if (parameters.containsKey(target)) return tree;
                List<String> params = owners.typeParameters(target);
                Set<String> technical = owners.managedParameters(target);
                if (params.isEmpty() || !technical.containsAll(params)) return tree;
                return make.at(tree.pos).TypeApply(tree, com.sun.tools.javac.util.List.from(params.stream()
                        .map(parameter -> argument(parameter, tree.pos)).toList()));
            }
            @Override public void visitIdent(JCTree.JCIdent tree) { result = raw(tree); }
            @Override public void visitSelect(JCTree.JCFieldAccess tree) { result = raw(tree); }
            @Override public void visitTypeApply(JCTree.JCTypeApply tree) {
                tree.arguments = translate(tree.arguments);
                result = tree;
                String target = resolve.apply(unit, tree.clazz);
                if (target == null) return;
                if (compiledOnly && parameters.containsKey(target)) return;
                List<String> params = owners.typeParameters(target);
                Set<String> technical = owners.managedParameters(target);
                if (tree.arguments.isEmpty() || tree.arguments.size() >= params.size()) return;
                if (params.stream().filter(p -> !technical.contains(p)).count() != tree.arguments.size()) return;
                var supplied = tree.arguments.iterator();
                var args = new ArrayList<JCTree.JCExpression>();
                for (String param : params) {
                    args.add(technical.contains(param) ? argument(param, tree.pos) : supplied.next());
                }
                tree.arguments = com.sun.tools.javac.util.List.from(args);
            }
        }.translate(type);
    }

    private JCTree.JCExpression storage(JCTree.JCExpression view) {
        if (view instanceof JCTree.JCTypeApply apply) return copy(apply.clazz);
        if (view instanceof JCTree.JCArrayTypeTree array) return make.at(view.pos).TypeArray(storage(array.elemtype));
        if (view instanceof JCTree.JCAnnotatedType annotated) {
            return make.at(view.pos).AnnotatedType(copyList(annotated.annotations), storage(annotated.underlyingType));
        }
        if (view instanceof JCTree.JCIdent ident && managed.contains(ident.name.toString())) {
            String bound = bounds.get(ident.name.toString());
            if (bound == null) throw new IllegalStateException("[Jype] Cannot resolve static bound for " + ident.name);
            return qualified(bound);
        }
        return copy(view);
    }

    private <T extends JCTree> T eraseTypes(Owner owner, T tree) {
        return new TreeTranslator() {
            private JCTree.JCExpression erase(JCTree.JCExpression type) {
                if (!usesManaged(type)) return type;
                var result = storage(type);
                edit(owner, type, result.toString());
                return result;
            }
            @Override public void visitTypeCast(JCTree.JCTypeCast cast) {
                if (cast.clazz instanceof JCTree.JCExpression type) cast.clazz = erase(type);
                cast.expr = translate(cast.expr);
                result = cast;
            }
            @Override public void visitVarDef(JCTree.JCVariableDecl variable) {
                variable.vartype = erase(variable.vartype);
                variable.init = translate(variable.init);
                result = variable;
            }
        }.translate(tree);
    }

    private void edit(Owner owner, JCTree tree, String replacement) {
        int start = tree.getStartPosition();
        int end = tree.getEndPosition(owner.unit.endPositions);
        if (start < 0 || end < start) throw new IllegalStateException("[Jype] Missing static source positions for " + tree);
        var edits = sourceEdits.computeIfAbsent(owner.unit.sourcefile.toUri().toString(), ignored -> new ArrayList<>());
        var edit = new JavadocSourceGenerator.SourceEdit(start, end, replacement);
        if (!edits.contains(edit)) edits.add(edit);
    }

    private void insert(Owner owner, int position, String text) {
        if (position < 0) throw new IllegalStateException("[Jype] Missing static method source position");
        sourceEdits.computeIfAbsent(owner.unit.sourcefile.toUri().toString(), ignored -> new ArrayList<>())
                .add(new JavadocSourceGenerator.SourceEdit(position, position, text));
    }

    private boolean usesManaged(JCTree tree) {
        return usesManaged(tree, Set.of());
    }

    private boolean usesManaged(JCTree tree, Set<String> excluded) {
        boolean[] found = {false};
        new TreeScanner() {
            @Override public void visitIdent(JCTree.JCIdent ident) {
                String name = ident.name.toString();
                if (managed.contains(name) && !excluded.contains(name)) found[0] = true;
            }
        }.scan(tree);
        return found[0];
    }

    private JCTree.JCAnnotation lombokAnnotation(JCTree.JCCompilationUnit unit, JCTree.JCModifiers modifiers, String name) {
        return modifiers.annotations.stream().filter(annotation ->
                annotation.annotationType instanceof JCTree.JCExpression type
                        && ("lombok." + name).equals(resolve.apply(unit, type))).findFirst().orElse(null);
    }

    private boolean disabled(JCTree.JCAnnotation annotation) {
        return annotation.args.stream().anyMatch(argument -> argument.toString().endsWith(".NONE"));
    }

    private boolean supportedAccessor(Owner owner, JCTree.JCVariableDecl field, JCTree.JCAnnotation annotation) {
        boolean advanced = annotation.args.stream().anyMatch(argument ->
                argument instanceof JCTree.JCAssign assignment && !assignment.lhs.toString().equals("value"));
        boolean customNaming = lombokAnnotation(owner.unit, field.mods, "experimental.Accessors") != null
                || lombokAnnotation(owner.unit, owner.tree.mods, "experimental.Accessors") != null;
        if (!advanced && !customNaming) return true;
        var previous = log.useSource(owner.unit.sourcefile);
        try {
            log.error(field.pos, new com.sun.tools.javac.util.JCDiagnostic.Error("compiler", "proc.messager",
                    "[Jype] Managed static fields support standard Lombok @Getter/@Setter with AccessLevel only; "
                            + "use an explicit static method for custom accessors, lazy getters or onMethod/onParam."));
        } finally {
            log.useSource(previous);
        }
        return false;
    }

    private long access(JCTree.JCAnnotation annotation) {
        for (var argument : annotation.args) {
            String value = argument.toString();
            if (value.endsWith(".PRIVATE")) return Flags.PRIVATE;
            if (value.endsWith(".PROTECTED")) return Flags.PROTECTED;
            if (value.endsWith(".PACKAGE") || value.endsWith(".MODULE")) return 0;
        }
        return Flags.PUBLIC;
    }

    /** Tests javac's static bit for declarations and initializer blocks. */
    boolean isStatic(long flags) { return (flags & Flags.STATIC) != 0; }
    private <T extends JCTree> T copy(T tree) { return new TreeCopier<Void>(make).copy(tree); }
    private <T extends JCTree> com.sun.tools.javac.util.List<T> copyList(com.sun.tools.javac.util.List<T> trees) {
        return new TreeCopier<Void>(make).copy(trees);
    }
    /** Builds a fresh source-spelled qualified name using this compiler's Name table. */
    JCTree.JCExpression qualified(String name) {
        JCTree.JCExpression result = null;
        for (String part : name.split("\\.")) {
            result = result == null ? make.Ident(names.fromString(part)) : make.Select(result, names.fromString(part));
        }
        return result;
    }
}
