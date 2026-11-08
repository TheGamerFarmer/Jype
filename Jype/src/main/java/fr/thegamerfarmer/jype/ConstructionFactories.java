package fr.thegamerfarmer.jype;

import com.sun.source.util.Trees;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeCopier;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.tree.TreeTranslator;
import com.sun.tools.javac.util.Names;
import fr.thegamerfarmer.jype.annotations.TypeSystem;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.io.Writer;
import java.util.*;
import java.util.function.Function;

import static fr.thegamerfarmer.jype.ManagedConstruction.*;

/**
 * Turns recorded alias creations into calls to generated runtime factories.
 *
 * <p>Runs once, after every Jype pass of the first processing round, so a creation's class
 * expression already has its final form: a class or method type variable in templates, the erased
 * bound in static initializers, or the concrete binding in specialized classes. That expression
 * becomes the explicit type argument of {@code <system>.Jype$New$<ALIAS>.create(...)}.
 *
 * <p>A holder declares one functional shape per selected constructor of the system's binding. A
 * creation follows Java's access rules as if it named the binding, so a singleton may create its
 * alias through its own private or protected constructor; generated factories reach non-public
 * constructors by reflection, resolved once when the generated class initializes.
 *
 * <p>A holder's default factory creates that binding, or fails with a diagnostic when the binding is
 * abstract. A consumer's installer replaces the factories of every ancestor holder whose binding it
 * changes; the deepest type system wins, whatever the class-initialization order. Holders and
 * installers are ordinary generated sources, compiled in the next processing round.
 */
final class ConstructionFactories {
    /**
     * Constructor signature exposed by a holder.
     *
     * @param index stable member suffix ({@code F<index>}, {@code bind<index>})
     * @param parameters erased parameter types
     * @param constructor whether a binding constructor declares it, rather than an abstract arity
     * @param source declaring source constructor, or {@code null} for abstract arities and compiled holders
     */
    private record Shape(int index, List<TypeMirror> parameters, boolean varargs, boolean constructor,
                         ExecutableElement source) {
        boolean accepts(int arity) {
            return arity < 0 || parameters.size() == arity || varargs && arity >= parameters.size() - 1;
        }
    }

    private record Key(String scope, String alias) {}

    private final ProcessingEnvironment env;
    private final Trees trees;
    private final TreeMaker make;
    private final Names names;
    private final ManagedConstruction marks;
    private final Function<String, Map<String, TypeMirror>> systems;
    private final Map<String, List<Shape>> generated = new LinkedHashMap<>();
    private final Map<String, TypeElement> generatedBindings = new HashMap<>();

    ConstructionFactories(ProcessingEnvironment env, Trees trees, TreeMaker make, Names names,
                          ManagedConstruction marks, Function<String, Map<String, TypeMirror>> systems) {
        this.env = env;
        this.trees = trees;
        this.make = make;
        this.names = names;
        this.marks = marks;
        this.systems = systems;
    }

    /** Rewrites all recorded creations, then writes holders and installers. */
    void run() {
        Map<Key, Set<Integer>> uses = new LinkedHashMap<>();
        marks.allCreations().values().forEach(map -> map.values().forEach(creation -> {
            if (creation.error() == null) {
                uses.computeIfAbsent(new Key(creation.scope(), creation.alias()), key -> new TreeSet<>())
                        .add(creation.arity());
            }
        }));
        Map<Key, List<Shape>> available = new HashMap<>();
        for (var entry : uses.entrySet()) {
            Key key = entry.getKey();
            String holder = holderName(key.scope(), key.alias());
            if (marks.sourceSystems().contains(key.scope())) {
                TypeElement binding = binding(key.scope(), key.alias());
                if (binding == null) continue;
                List<Shape> shapes = shapes(binding, entry.getValue());
                generated.put(holder, shapes);
                generatedBindings.put(holder, binding);
                available.put(key, shapes);
            } else {
                List<Shape> shapes = compiledShapes(holder);
                if (shapes != null) available.put(key, shapes);
            }
        }
        marks.allCreations().forEach((unit, creations) -> rewrite(unit, creations, available));
        generated.forEach(this::writeHolder);
        for (String scope : marks.installers()) writeInstaller(scope);
    }

    private TypeElement binding(String scope, String alias) {
        Map<String, TypeMirror> system = systems.apply(scope);
        TypeMirror bound = system == null ? null : system.get(alias);
        return bound instanceof DeclaredType declared && declared.asElement() instanceof TypeElement element
                ? element : null;
    }

    /**
     * Selects constructors for the used arities, whatever their access: each creation site is then
     * checked against Java's rules. Abstract bindings get untyped shapes.
     */
    private List<Shape> shapes(TypeElement binding, Set<Integer> arities) {
        var types = env.getTypeUtils();
        List<Shape> shapes = new ArrayList<>();
        Set<List<String>> seen = new HashSet<>();
        for (ExecutableElement constructor : ElementFilter.constructorsIn(binding.getEnclosedElements())) {
            int size = constructor.getParameters().size();
            boolean varargs = constructor.isVarArgs();
            boolean used = arities.stream().anyMatch(arity -> arity < 0 || arity == size
                    || varargs && arity >= size - 1);
            if (!used) continue;
            List<TypeMirror> parameters = constructor.getParameters().stream()
                    .map(parameter -> types.erasure(parameter.asType())).toList();
            if (seen.add(parameters.stream().map(TypeMirror::toString).toList())) {
                shapes.add(new Shape(shapes.size(), parameters, varargs, true, constructor));
            }
        }
        if (!instantiable(binding)) {
            TypeMirror object = env.getElementUtils().getTypeElement("java.lang.Object").asType();
            Set<Integer> wanted = new TreeSet<>(arities);
            if (wanted.remove(-1) && shapes.isEmpty()) wanted.add(0);
            for (int arity : wanted) {
                if (shapes.stream().anyMatch(shape -> shape.accepts(arity))) continue;
                shapes.add(new Shape(shapes.size(), Collections.nCopies(arity, object), false, false, null));
            }
        }
        return shapes;
    }

    /** Generated factories call public constructors directly, and others by reflection. */
    private static boolean direct(ExecutableElement constructor) {
        return constructor.getModifiers().contains(Modifier.PUBLIC);
    }

    /**
     * Java's access rule for {@code new Binding(...)} written at the creation site. A protected
     * constructor of another package is only reachable through {@code super(...)}, never by
     * {@code new}, so it behaves like a package-private one here.
     */
    private boolean accessibleFrom(ExecutableElement constructor, Creation creation) {
        Set<Modifier> modifiers = constructor.getModifiers();
        if (modifiers.contains(Modifier.PUBLIC)) return true;
        TypeElement owner = (TypeElement) constructor.getEnclosingElement();
        if (modifiers.contains(Modifier.PRIVATE)) {
            TypeElement outermost = owner;
            while (outermost.getEnclosingElement() instanceof TypeElement enclosing) outermost = enclosing;
            return outermost.getQualifiedName().contentEquals(creation.site());
        }
        return env.getElementUtils().getPackageOf(owner).getQualifiedName().contentEquals(creation.sitePackage());
    }

    private boolean instantiable(TypeElement binding) {
        if (binding.getKind() != ElementKind.CLASS && binding.getKind() != ElementKind.RECORD) return false;
        if (binding.getModifiers().contains(Modifier.ABSTRACT)) return false;
        // Generated factories live in other classes: the binding and its enclosing types must be public,
        // and a member binding must not require an enclosing instance.
        for (Element current = binding; current instanceof TypeElement type; current = type.getEnclosingElement()) {
            if (!type.getModifiers().contains(Modifier.PUBLIC)) return false;
            if (type.getEnclosingElement() instanceof TypeElement && type.getKind() == ElementKind.CLASS
                    && !type.getModifiers().contains(Modifier.STATIC)) return false;
        }
        return true;
    }

    /** Reads the functional shapes of a holder compiled with an ancestor library. */
    private List<Shape> compiledShapes(String holder) {
        TypeElement element = env.getElementUtils().getTypeElement(holder);
        if (element == null) return null;
        List<Shape> shapes = new ArrayList<>();
        for (TypeElement member : ElementFilter.typesIn(element.getEnclosedElements())) {
            String name = member.getSimpleName().toString();
            if (!name.matches("F\\d+")) continue;
            for (ExecutableElement method : ElementFilter.methodsIn(member.getEnclosedElements())) {
                if (!method.getSimpleName().contentEquals("create")) continue;
                shapes.add(new Shape(Integer.parseInt(name.substring(1)),
                        method.getParameters().stream().map(VariableElement::asType).toList(),
                        method.isVarArgs(), true, null));
            }
        }
        shapes.sort(Comparator.comparingInt(Shape::index));
        return shapes;
    }

    private void rewrite(JCTree.JCCompilationUnit unit, Map<Integer, Creation> creations,
                         Map<Key, List<Shape>> available) {
        new TreeTranslator() {
            @Override public void visitNewClass(JCTree.JCNewClass tree) {
                super.visitNewClass(tree);
                Creation creation = creations.get(tree.pos);
                if (creation == null) return;
                result = replacement(unit, tree, tree.clazz, tree.args, creation, available);
            }

            @Override public void visitReference(JCTree.JCMemberReference tree) {
                super.visitReference(tree);
                Creation creation = creations.get(tree.pos);
                if (creation == null || tree.getMode() != com.sun.source.tree.MemberReferenceTree.ReferenceMode.NEW) return;
                result = replacement(unit, tree, tree.expr, null, creation, available);
            }
        }.translate(unit);
    }

    private JCTree.JCExpression replacement(JCTree.JCCompilationUnit unit, JCTree.JCExpression tree,
                                            JCTree.JCExpression type, com.sun.tools.javac.util.List<JCTree.JCExpression> args,
                                            Creation creation, Map<Key, List<Shape>> available) {
        String alias = creation.alias();
        if (creation.error() != null) {
            error(unit, tree, creation.error());
            return tree;
        }
        List<Shape> shapes = available.get(new Key(creation.scope(), alias));
        if (shapes == null) {
            TypeElement binding = binding(creation.scope(), alias);
            error(unit, tree, binding == null
                    ? "[Jype] Cannot resolve the binding of " + alias + " in type system " + creation.scope() + "."
                    : "[Jype] new " + alias + "(...) requires a local @TypeSystem package-info: type system "
                    + creation.scope() + " was compiled without a factory for " + alias + ".");
            return tree;
        }
        List<Shape> candidates = shapes.stream().filter(shape -> shape.accepts(creation.arity())).toList();
        if (candidates.isEmpty()) {
            error(unit, tree, "[Jype] " + alias + " is bound to " + bindingName(creation)
                    + ", which has no constructor with " + creation.arity() + " parameter(s).");
            return tree;
        }
        // An abstract binding is never created itself: only the overriding class's constructors run.
        TypeElement declared = binding(creation.scope(), alias);
        if (declared != null && instantiable(declared)
                && candidates.stream().allMatch(shape -> shape.source() != null)
                && candidates.stream().noneMatch(shape -> accessibleFrom(shape.source(), creation))) {
            error(unit, tree, "[Jype] " + alias + " is bound to " + bindingName(creation) + ", whose constructor"
                    + (creation.arity() < 0 ? "s are" : " with " + creation.arity() + " parameter(s) is")
                    + " not accessible from " + creation.site() + ".");
            return tree;
        }
        make.at(tree.pos);
        JCTree.JCExpression holder = PreEnterTrees.buildQualifiedName(holderName(creation.scope(), alias), make, names);
        var witness = creation.staticInitializer() ? erasedWitness(binding(creation.scope(), alias)) : witness(type);
        if (args == null) {
            return make.Reference(com.sun.source.tree.MemberReferenceTree.ReferenceMode.INVOKE,
                    names.fromString("create"), holder, witness);
        }
        return make.Apply(witness, make.Select(holder, names.fromString("create")), args);
    }

    private String bindingName(Creation creation) {
        TypeElement binding = binding(creation.scope(), creation.alias());
        return binding == null ? "an unresolved type" : binding.getQualifiedName().toString();
    }

    /** The completed class expression is the creation's static type; wildcards cannot be witnesses. */
    private com.sun.tools.javac.util.List<JCTree.JCExpression> witness(JCTree.JCExpression type) {
        if (type instanceof JCTree.JCIdent || type instanceof JCTree.JCFieldAccess || type instanceof JCTree.JCTypeApply) {
            return com.sun.tools.javac.util.List.of(new TreeCopier<Void>(make).copy(type));
        }
        return com.sun.tools.javac.util.List.nil();
    }

    /** Static initializers see the binding itself; wildcards avoid a raw type argument. */
    private com.sun.tools.javac.util.List<JCTree.JCExpression> erasedWitness(TypeElement binding) {
        if (binding == null) return com.sun.tools.javac.util.List.nil();
        JCTree.JCExpression type = PreEnterTrees.buildQualifiedName(binding.getQualifiedName().toString(), make, names);
        int parameters = binding.getTypeParameters().size();
        if (parameters > 0) {
            List<JCTree.JCExpression> wildcards = new ArrayList<>();
            for (int i = 0; i < parameters; i++) {
                wildcards.add(make.Wildcard(make.TypeBoundKind(com.sun.tools.javac.code.BoundKind.UNBOUND), null));
            }
            type = make.TypeApply(type, com.sun.tools.javac.util.List.from(wildcards));
        }
        return com.sun.tools.javac.util.List.of(type);
    }

    private void error(JCTree.JCCompilationUnit unit, JCTree tree, String message) {
        trees.printMessage(Diagnostic.Kind.ERROR, message, tree, unit);
    }

    private void writeHolder(String holder, List<Shape> shapes) {
        TypeElement binding = generatedBindings.get(holder);
        int dot = holder.lastIndexOf('.');
        String scope = holder.substring(0, dot);
        String simple = holder.substring(dot + 1);
        String alias = simple.substring(HOLDER_PREFIX.length());
        boolean instantiable = instantiable(binding);
        boolean reflective = false;
        StringBuilder source = new StringBuilder();
        source.append("package ").append(scope).append(";\n\n")
                .append("/**\n * Runtime factory of {@code new ").append(alias).append("(...)} in type system {@code ")
                .append(scope).append("}, generated by Jype.\n *\n * <p>The most specific type system overriding ")
                .append(alias).append(" installs its class here; the default creates {@code ")
                .append(binding.getQualifiedName()).append("}. Not intended for direct use.\n */\n")
                .append("@SuppressWarnings({\"unchecked\", \"rawtypes\"})\n")
                .append("public final class ").append(simple).append(" {\n")
                .append("    private ").append(simple).append("() {}\n");
        for (Shape shape : shapes) {
            int k = shape.index();
            String parameters = parameters(shape);
            String arguments = arguments(shape.parameters().size());
            String constructorField = "";
            if (instantiable && shape.constructor() && shape.source() != null && !direct(shape.source())) {
                reflective = true;
                constructorField = "    private static final java.lang.reflect.Constructor<?> c" + k + " = "
                        + reflectedConstructor(binding, shape.parameters()) + ";\n";
            }
            String factory = !constructorField.isEmpty()
                    ? lambda(shape.parameters().size(), reflectiveCreation("c" + k, shape.parameters().size()))
                    : instantiable && shape.constructor()
                    ? lambda(shape.parameters().size(), "new " + binding.getQualifiedName() + "(" + arguments + ")")
                    : failure(shape.parameters().size(), "[Jype] " + alias + " is bound to " + binding.getQualifiedName()
                    + " in type system " + scope + ", which cannot be instantiated. Override " + alias
                    + " with a concrete class in the type system of the running program.");
            source.append("\n    /** Factory shape {@code ").append(alias).append("(").append(parameters).append(")}. */\n")
                    .append("    @FunctionalInterface public interface F").append(k)
                    .append(" { Object create(").append(parameters).append("); }\n")
                    .append(constructorField)
                    .append("    private static volatile F").append(k).append(" f").append(k).append(" = ")
                    .append(factory).append(";\n")
                    .append("    private static int d").append(k).append(" = Integer.MIN_VALUE;\n")
                    .append("    /** Creates the installed class of {@code ").append(alias).append("}. */\n")
                    // Unbounded: a specialized template keeps its ancestor's recursive bound, while the
                    // call site's checkcast still verifies the erasure of the witness.
                    .append("    public static <J$T> J$T create(")
                    .append(parameters).append(") { return (J$T) f").append(k).append(".create(")
                    .append(arguments).append("); }\n")
                    .append("    /** Installs a factory unless a deeper type system already installed one. */\n")
                    .append("    public static synchronized void bind").append(k).append("(int depth, F").append(k)
                    .append(" factory) { if (depth >= d").append(k).append(") { d").append(k)
                    .append(" = depth; f").append(k).append(" = factory; } }\n");
        }
        if (reflective) source.append(REFLECTION);
        source.append("}\n");
        write(holder, source.toString(), binding);
    }

    /** Helpers of generated classes reaching non-public constructors; the target's failures propagate unchanged. */
    private static final String REFLECTION = """

                private static java.lang.reflect.Constructor<?> J$constructor(Class<?> type, Class<?>... parameters) {
                    try {
                        java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor(parameters);
                        constructor.setAccessible(true);
                        return constructor;
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        throw new IllegalStateException("[Jype] Cannot access the constructor of " + type.getName() + ".", e);
                    }
                }
                private static Object J$create(java.lang.reflect.Constructor<?> constructor, Object[] arguments) {
                    try {
                        return constructor.newInstance(arguments);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw J$rethrow(e.getCause());
                    } catch (ReflectiveOperationException e) {
                        throw new IllegalStateException("[Jype] Cannot create " + constructor.getDeclaringClass().getName() + ".", e);
                    }
                }
                private static <X extends Throwable> RuntimeException J$rethrow(Throwable failure) throws X {
                    throw (X) failure;
                }
            """;

    private static String reflectedConstructor(TypeElement binding, List<TypeMirror> parameters) {
        StringBuilder result = new StringBuilder("J$constructor(").append(binding.getQualifiedName()).append(".class");
        for (TypeMirror parameter : parameters) result.append(", ").append(parameter).append(".class");
        return result.append(")").toString();
    }

    private static String reflectiveCreation(String field, int size) {
        return "J$create(" + field + ", new Object[]{" + arguments(size) + "})";
    }

    private void writeInstaller(String scope) {
        PackageElement pkg = env.getElementUtils().getPackageElement(scope);
        List<String> ancestors = ancestors(pkg);
        Map<String, TypeMirror> own = systems.apply(scope);
        StringBuilder bindings = new StringBuilder();
        List<String> fields = new ArrayList<>();
        if (own != null) {
            for (String ancestor : ancestors) {
                Map<String, TypeMirror> inherited = systems.apply(ancestor);
                for (var entry : own.entrySet()) {
                    String alias = entry.getKey();
                    TypeMirror previous = inherited == null ? null : inherited.get(alias);
                    if (previous != null && env.getTypeUtils().isSameType(
                            env.getTypeUtils().erasure(previous), env.getTypeUtils().erasure(entry.getValue()))) continue;
                    String holder = holderName(ancestor, alias);
                    List<Shape> shapes = generated.containsKey(holder) ? generated.get(holder) : compiledShapes(holder);
                    if (shapes == null) continue;
                    TypeElement binding = binding(scope, alias);
                    for (Shape shape : shapes) {
                        bindings.append("        ").append(holder).append(".bind").append(shape.index()).append("(")
                                .append(ancestors.size()).append(", (").append(holder).append(".F")
                                .append(shape.index()).append(") ")
                                .append(consumerFactory(pkg, holder, alias, binding, shape, shapes, fields))
                                .append(");\n");
                    }
                }
            }
        }
        String source = "package " + scope + ";\n\n"
                + "/**\n * Installs the bindings of type system {@code " + scope
                + "} into the runtime factories of its ancestors, generated by Jype.\n"
                + " *\n * <p>Every top-level class of the type system loads this class first. Not intended for direct use.\n */\n"
                + "@SuppressWarnings({\"unchecked\", \"rawtypes\"})\n"
                + "public final class " + INSTALLER + " {\n"
                + "    private " + INSTALLER + "() {}\n"
                // Constructors are resolved before the static block that installs them.
                + String.join("", fields)
                + "    static {\n" + bindings + "    }\n"
                + "    /** Loads this class; its static initializer runs once. */\n"
                + "    public static void run() {}\n"
                + (fields.isEmpty() ? "" : REFLECTION)
                + "}\n";
        write(scope + "." + INSTALLER, source, pkg);
    }

    /**
     * A consumer factory casts each argument to the matching constructor of its binding. The
     * consumer's type system chose that class, so any of its constructors may serve; a non-public
     * one is resolved into a new installer field.
     */
    private String consumerFactory(PackageElement pkg, String holder, String alias, TypeElement binding,
                                   Shape shape, List<Shape> shapes, List<String> fields) {
        int size = shape.parameters().size();
        if (binding == null || !instantiable(binding)) {
            return failure(size, "[Jype] " + alias + " is bound to " + (binding == null ? "an unresolved type"
                    : binding.getQualifiedName()) + " in type system " + pkg.getQualifiedName()
                    + ", which cannot be instantiated. Override " + alias + " with a concrete class in a more specific type system.");
        }
        var types = env.getTypeUtils();
        ExecutableElement best = null;
        int bestScore = -1;
        boolean ambiguous = false;
        for (ExecutableElement constructor : ElementFilter.constructorsIn(binding.getEnclosedElements())) {
            if (constructor.getParameters().size() != size
                    || constructor.isVarArgs() != shape.varargs()) continue;
            int score = 0;
            for (int i = 0; i < size && score >= 0; i++) {
                TypeMirror expected = shape.parameters().get(i);
                TypeMirror actual = types.erasure(constructor.getParameters().get(i).asType());
                if (types.isSameType(expected, actual)) score += 2;
                else if (castable(expected, actual)) score += 1;
                else score = -1;
            }
            if (score < 0) continue;
            if (score > bestScore) {
                best = constructor;
                bestScore = score;
                ambiguous = false;
            } else if (score == bestScore) {
                ambiguous = true;
            }
        }
        String signature = alias + "(" + shape.parameters().stream().map(TypeMirror::toString)
                .reduce((left, right) -> left + ", " + right).orElse("") + ")";
        if (best == null || ambiguous) {
            boolean unique = shapes.stream().filter(other -> other.parameters().size() == size).count() == 1;
            String message = "[Jype] " + binding.getQualifiedName() + (best == null
                    ? " declares no constructor compatible with "
                    : " declares several constructors compatible with ")
                    + signature + ", used by new " + alias + "(...) in " + holder.substring(0, holder.lastIndexOf('.')) + ".";
            env.getMessager().printMessage(unique ? Diagnostic.Kind.ERROR : Diagnostic.Kind.WARNING, message, pkg);
            return failure(size, message);
        }
        if (!direct(best)) {
            String field = "c" + fields.size();
            List<TypeMirror> parameters = best.getParameters().stream()
                    .map(parameter -> types.erasure(parameter.asType())).toList();
            fields.add("    private static final java.lang.reflect.Constructor<?> " + field + " = "
                    + reflectedConstructor(binding, parameters) + ";\n");
            return lambda(size, reflectiveCreation(field, size));
        }
        StringBuilder arguments = new StringBuilder();
        for (int i = 0; i < size; i++) {
            if (i > 0) arguments.append(", ");
            TypeMirror expected = shape.parameters().get(i);
            TypeMirror actual = types.erasure(best.getParameters().get(i).asType());
            if (!types.isSameType(expected, actual)) arguments.append("(").append(actual).append(") ");
            arguments.append("a").append(i);
        }
        return lambda(size, "new " + binding.getQualifiedName() + "(" + arguments + ")");
    }

    private boolean castable(TypeMirror from, TypeMirror to) {
        var types = env.getTypeUtils();
        if (from.getKind().isPrimitive() || to.getKind().isPrimitive()) {
            return types.isAssignable(from, to) || from.getKind() == TypeKind.DECLARED
                    && types.isSameType(from, env.getElementUtils().getTypeElement("java.lang.Object").asType());
        }
        return types.isAssignable(to, from) || types.isAssignable(from, to);
    }

    /** Ancestor type systems from the direct parent to the root. */
    private List<String> ancestors(PackageElement pkg) {
        List<String> result = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        for (PackageElement current = pkg; current != null; ) {
            TypeSystem system = current.getAnnotation(TypeSystem.class);
            if (system == null || system.inherit().isEmpty() || !visited.add(system.inherit())) break;
            result.add(system.inherit());
            current = env.getElementUtils().getPackageElement(system.inherit());
        }
        return result;
    }

    private static String parameters(Shape shape) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < shape.parameters().size(); i++) {
            if (i > 0) result.append(", ");
            String type = shape.parameters().get(i).toString();
            if (shape.varargs() && i == shape.parameters().size() - 1 && type.endsWith("[]")) {
                type = type.substring(0, type.length() - 2) + "...";
            }
            result.append(type).append(" a").append(i);
        }
        return result.toString();
    }

    private static String arguments(int size) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < size; i++) result.append(i == 0 ? "" : ", ").append("a").append(i);
        return result.toString();
    }

    private static String lambda(int size, String body) {
        return "(" + arguments(size) + ") -> " + body;
    }

    private static String failure(int size, String message) {
        return "(" + arguments(size) + ") -> { throw new IllegalStateException(\""
                + message.replace("\\", "\\\\").replace("\"", "\\\"") + "\"); }";
    }

    private void write(String name, String source, Element origin) {
        try (Writer writer = env.getFiler().createSourceFile(name, origin).openWriter()) {
            writer.write(source);
        } catch (IOException e) {
            env.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "[Jype] Cannot generate " + name + ": " + e.getMessage(), origin);
        }
    }
}
