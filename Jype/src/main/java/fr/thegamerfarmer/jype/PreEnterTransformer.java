package fr.thegamerfarmer.jype;

import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.Log;
import com.sun.tools.javac.util.Names;
import javax.tools.JavaFileManager;
import java.util.*;
import static fr.thegamerfarmer.jype.PreEnterTrees.*;
import static fr.thegamerfarmer.jype.ManagedParameters.*;

/**
 * Coordinates one compilation's symbol-free transformations before Enter.
 * Package scopes are isolated. Fixed-point parameter propagation precedes inheritance, bounds,
 * static lowering and member completion. Symbol bounds remain the processor's responsibility.
 */
final class PreEnterTransformer {
    private final SourceHierarchy hierarchy;
    private final ParsedTypeSystems parsedSystems;
    private final StaticBoundResolver staticResolver;
    private final ManagedParameterPropagation propagation;
    private final BoundTypeCompletion bounds;
    private final InheritanceCompletion inheritance;
    private final MemberTypeCompletion members;
    private final Log log;
    private final Map<String, List<JavadocSourceGenerator.SourceEdit>> staticSourceEdits = new LinkedHashMap<>();
    private List<JCTree.JCCompilationUnit> sourceUnits = List.of();
    private final Map<JCTree.JCClassDecl, String> rootScopes = new IdentityHashMap<>();
    /** Per type system: re-bound alias to the class that closed its F-bound at an ancestor level. */
    private final Map<String, Map<String, String>> aliasClosers = new HashMap<>();
    private TreeMaker treeMaker;
    private Names names;
    final DeclarationOrigins origins = new DeclarationOrigins();
    final ManagedConstruction construction;

    /** Creates task-local collaborators; no source trees or symbols survive this compilation. */
    PreEnterTransformer(com.sun.tools.javac.util.Context context) {
        JavaFileManager manager = context.get(JavaFileManager.class);
        log = Log.instance(context);
        hierarchy = new SourceHierarchy(manager, log);
        parsedSystems = new ParsedTypeSystems(manager);
        staticResolver = new StaticBoundResolver(manager, hierarchy);
        propagation = new ManagedParameterPropagation(hierarchy);
        bounds = new BoundTypeCompletion(hierarchy);
        inheritance = new InheritanceCompletion(hierarchy, bounds);
        members = new MemberTypeCompletion(hierarchy);
        construction = new ManagedConstruction();
    }

    /** Returns the live URI-keyed edits consumed only after this transformation has completed. */
    Map<String, List<JavadocSourceGenerator.SourceEdit>> staticSourceEdits() {
        return staticSourceEdits;
    }

    /** Completes all source declarations once, before the first Enter event. */
    void transform(List<JCTree.JCCompilationUnit> cus,
                                    TreeMaker make, Names names) {
        sourceUnits = List.copyOf(cus);
        treeMaker = make;
        this.names = names;
        Map<String, Set<String>> systems = extractManagedNames(cus);
        Map<String, List<String>> index = hierarchy.buildTypeParamMap(cus);
        hierarchy.classNames.forEach((tree, name) -> origins.capture(tree, name));
        Map<JCTree.JCClassDecl, String> scopes = new LinkedHashMap<>();
        Map<String, String> typeScopes = new HashMap<>();
        for (JCTree.JCCompilationUnit cu : cus) {
            String pkg = getCuPackage(cu);
            String scope = systems.keySet().stream()
                    .filter(root -> pkg.equals(root) || pkg.startsWith(root + "."))
                    .max(Comparator.comparingInt(String::length)).orElse("");
            for (JCTree def : cu.defs) {
                if (def instanceof JCTree.JCClassDecl cls) {
                    scopes.put(cls, scope);
                    rootScopes.put(cls, scope);
                    typeScopes.put(hierarchy.classNames.get(cls), scope);
                }
            }
        }
        boolean changed;
        do {
            changed = false;
            for (JCTree.JCCompilationUnit cu : cus) {
                for (JCTree def : cu.defs) {
                    if (!(def instanceof JCTree.JCClassDecl cls) || !scopes.get(cls).isEmpty()) continue;
                    JCTree.JCExpression parent = cls.extending instanceof JCTree.JCTypeApply applied
                            ? applied.clazz : cls.extending;
                    if (parent == null) continue;
                    String parentName = hierarchy.resolveTypeName(index, cu, parent, true);
                    String inheritedScope = typeScopes.get(parentName);
                    if (inheritedScope == null && parentName != null && !index.containsKey(parentName)) {
                        var data = hierarchy.typeResolver.readClass(parentName);
                        if (data != null && data.publicView() != null) {
                            inheritedScope = data.publicView().typeSystem();
                            systems.putIfAbsent(inheritedScope, parsedSystems.compiledNames(inheritedScope));
                        }
                    }
                    if (inheritedScope == null || inheritedScope.isEmpty()) continue;
                    scopes.put(cls, inheritedScope);
                    typeScopes.put(hierarchy.classNames.get(cls), inheritedScope);
                    changed = true;
                }
            }
        } while (changed);
        staticResolver.collectStaticBounds(cus, index);
        construction.sourceSystems().addAll(staticResolver.sourceSystems);
        // Creations are recorded on the parsed trees, before any pass rewrites alias positions.
        for (JCTree.JCCompilationUnit cu : cus) {
            for (JCTree def : cu.defs) {
                if (def instanceof JCTree.JCClassDecl cls && !scopes.get(cls).isEmpty()) {
                    construction.collect(cu, cls, scopes.get(cls), systems.getOrDefault(scopes.get(cls), Set.of()));
                }
            }
        }
        for (String scope : new LinkedHashSet<>(scopes.values())) {
            if (scope.isEmpty() || !staticResolver.inherits(scope) || !overridesFactory(scope)) continue;
            List<JCTree.JCClassDecl> roots = new ArrayList<>();
            scopes.forEach((cls, owner) -> { if (owner.equals(scope)) roots.add(cls); });
            construction.injectInstaller(scope, roots, make, names);
        }
        for (String scope : new LinkedHashSet<>(scopes.values())) {
            staticResolver.effectiveBounds(scope);
            Set<JCTree.JCClassDecl> roots = Collections.newSetFromMap(new IdentityHashMap<>());
            scopes.forEach((cls, owner) -> { if (owner.equals(scope)) roots.add(cls); });
            hierarchy.activate(scope, roots);
            fixTypeSystem(cus, cus, systems.getOrDefault(scope, Collections.emptySet()),
                    scope, make, names);
        }
    }

    private void fixTypeSystem(List<JCTree.JCCompilationUnit> cus,
                               List<JCTree.JCCompilationUnit> allSources,
                               Set<String> managedNames, String scope, TreeMaker make, Names names) {

        Map<String, List<String>> typeParamMap = hierarchy.buildTypeParamMap(allSources);

        // Canonical binding names distinguish unrelated classes with the same simple name.
        Set<String> concreteBindings = new HashSet<>();
        boolean specializedInheritedSystem = staticResolver.inherits(scope)
                && staticResolver.hasLocalDefinitions(scope);
        List<String> residual = specializedInheritedSystem
                ? residualAliases(typeParamMap, managedNames) : List.of();
        inheritance.residual(List.of(), Set.of());

        if (!managedNames.isEmpty()) {
            for (JCTree.JCCompilationUnit cu : cus) {
                for (JCTree def : cu.defs) {
                    if (def instanceof JCTree.JCClassDecl cls && hierarchy.activeRoots.contains(cls)) {
                        injectBodyDetectedTypeParams(cls, managedNames, make, names);
                    }
                }
            }
            typeParamMap = hierarchy.buildTypeParamMap(allSources);
            for (JCTree.JCCompilationUnit cu : cus) {
                for (JCTree def : cu.defs) {
                    if (def instanceof JCTree.JCClassDecl cls && hierarchy.activeRoots.contains(cls)) {
                        members.injectRequiredClassParameters(cu, cls, typeParamMap, managedNames,
                                staticResolver.bounds.getOrDefault(hierarchy.currentTypeSystem, Map.of()),
                                residual, make, names);
                    }
                }
            }
            typeParamMap = hierarchy.buildTypeParamMap(allSources);

            // Binding classes stay concrete while templates acquire recursive dependencies.
            Map<String, String> preConcreteTypeMap = hierarchy.buildConcreteTypeMap(cus, typeParamMap, managedNames);
            for (var entry : staticResolver.bounds.getOrDefault(hierarchy.currentTypeSystem, Map.of()).entrySet()) {
                List<String> parameters = typeParamMap.get(entry.getValue());
                if (parameters == null) {
                    var data = hierarchy.typeResolver.readClass(entry.getValue());
                    parameters = data == null ? null : data.parameters();
                }
                boolean managedParent = hasSourceManagedParent(
                        entry.getValue(), allSources, typeParamMap, managedNames);
                boolean concreteBinding = staticResolver.inherits(hierarchy.currentTypeSystem)
                        ? !managedParent || !isAbstractSourceClass(entry.getValue()) || !residual.isEmpty()
                        : !managedParent;
                if (parameters != null && parameters.isEmpty() && concreteBinding) {
                    preConcreteTypeMap.putIfAbsent(entry.getKey(), entry.getValue());
                }
            }
            concreteBindings.addAll(preConcreteTypeMap.values());

            propagation.propagateBoundDependencies(cus, typeParamMap, managedNames, concreteBindings,
                    !residual.isEmpty(), make, names);
        }

        Map<String, String> concreteTypeMap = hierarchy.buildConcreteTypeMap(cus, typeParamMap, managedNames);
        for (var entry : staticResolver.bounds.getOrDefault(hierarchy.currentTypeSystem, Map.of()).entrySet()) {
            if (specializedInheritedSystem) {
                List<String> parameters = typeParamMap.get(entry.getValue());
                if (parameters == null) {
                    var data = hierarchy.typeResolver.readClass(entry.getValue());
                    parameters = data == null ? null : data.parameters();
                }
                if (parameters != null && parameters.isEmpty()) {
                    concreteTypeMap.putIfAbsent(entry.getKey(), entry.getValue());
                }
            } else if (concreteBindings.contains(entry.getValue())) {
                concreteTypeMap.putIfAbsent(entry.getKey(), entry.getValue());
            }
        }
        residual = residual.stream().filter(alias -> !concreteTypeMap.containsKey(alias)).toList();
        inheritance.closers(Map.of(), Map.of());
        if (specializedInheritedSystem && !residual.isEmpty()) {
            closeReboundAliases(concreteTypeMap, typeParamMap, residual);
        }
        if (!residual.isEmpty()) {
            Set<String> specializedBindings = injectResidualIntoBindings(
                    cus, typeParamMap, concreteTypeMap, residual, make, names);
            inheritance.residual(residual, specializedBindings);
        }

        Map<String, List<String>> previousParameters;
        do {
            previousParameters = hierarchy.buildTypeParamMap(allSources);
            typeParamMap = hierarchy.buildTypeParamMap(allSources);
            for (JCTree.JCCompilationUnit cu : cus) {
                for (JCTree def : cu.defs) {
                    if (def instanceof JCTree.JCClassDecl && hierarchy.activeRoots.contains(def)) {
                        inheritance.fixClassDecl(cu, (JCTree.JCClassDecl) def, typeParamMap, managedNames,
                                concreteTypeMap, concreteBindings, specializedInheritedSystem, make, names);
                    }
                }
            }
        } while (!previousParameters.equals(hierarchy.buildTypeParamMap(allSources)));
        if (!residual.isEmpty()) {
            typeParamMap = hierarchy.buildTypeParamMap(allSources);
            for (JCTree.JCCompilationUnit cu : cus) {
                for (JCTree def : cu.defs) {
                    if (def instanceof JCTree.JCClassDecl cls && hierarchy.activeRoots.contains(cls)) {
                        boundResidualParameters(cu, cls, typeParamMap, managedNames, concreteTypeMap,
                                residual, make, names);
                    }
                }
            }
        }

        // Wildcard arity is stable only after every declaration has its final parameter order.
        if (!managedNames.isEmpty()) {
            typeParamMap = hierarchy.buildTypeParamMap(allSources);
            bounds.expandAllNestedWildcards(cus, typeParamMap, managedNames, make, names);
            Map<String, List<String>> finalIndex = typeParamMap;
            StaticTypeSupport statics = new StaticTypeSupport(make, names, log, managedNames,
                    staticResolver.bounds.getOrDefault(hierarchy.currentTypeSystem, Map.of()), finalIndex, hierarchy.classNames, staticSourceEdits,
                    (cu, type) -> hierarchy.resolveTypeName(finalIndex, cu, type, true), hierarchy.typeResolver::readClass);
            statics.origins(origins);
            if (!residual.isEmpty()) {
                statics.specialize(residual, concreteTypeMap,
                        fqn -> inheritance.concreteExpression(fqn, make, names));
            }
            statics.process(cus, hierarchy.activeRoots);
            for (JCTree.JCCompilationUnit cu : cus) {
                for (JCTree def : cu.defs) {
                    if (def instanceof JCTree.JCClassDecl cls && hierarchy.activeRoots.contains(cls)) {
                        Map<String, String> bindings = new LinkedHashMap<>(
                                staticResolver.bounds.getOrDefault(hierarchy.currentTypeSystem, Map.of()));
                        bindings.putAll(concreteTypeMap);
                        members.completeMemberTypes(cu, cls, typeParamMap, managedNames,
                                memberBindings(hierarchy.currentTypeSystem, bindings), make, names);
                    }

                }
            }
        }
    }

    /**
     * Finds, for each alias re-bound below a level that already closed its F-bound, the class that
     * closed it. Walking from the root system, a binding replaces the previous one only while the
     * previous one is still a template over the alias: {@code ApiEntity<ENTITY, …>} is replaced by
     * {@code AppEntity<CLUSTER>}, which a third-level {@code ExtEntity} cannot replace, because
     * {@code AppEntity} fixed {@code ENTITY} in its supertypes.
     */
    private void closeReboundAliases(Map<String, String> concrete, Map<String, List<String>> index,
                                     List<String> residual) {
        List<String> chain = new ArrayList<>(staticResolver.ancestors(hierarchy.currentTypeSystem));
        Collections.reverse(chain);
        chain.add(hierarchy.currentTypeSystem);
        Map<String, String> closers = new HashMap<>();
        Map<String, List<String>> closerParameters = new HashMap<>();
        for (var entry : concrete.entrySet()) {
            String alias = entry.getKey();
            String current = null;
            for (String system : chain) {
                String binding = staticResolver.effectiveBounds(system).get(alias);
                if (binding == null || binding.equals(current)) continue;
                List<String> previous = current == null ? null : parametersOf(current, index);
                if (current == null || previous != null && previous.contains(alias)) current = binding;
            }
            if (current == null || current.equals(entry.getValue())) continue;
            List<String> parameters = parametersOf(current, index);
            if (parameters == null || !residual.containsAll(parameters)) continue;
            closers.put(entry.getValue(), current);
            closerParameters.put(current, parameters);
            aliasClosers.computeIfAbsent(hierarchy.currentTypeSystem, ignored -> new HashMap<>()).put(alias, current);
        }
        inheritance.closers(closers, closerParameters);
    }

    /** Member bindings, with each re-bound alias represented by its closing class in type arguments. */
    private Map<String, String> memberBindings(String scope, Map<String, String> bindings) {
        Map<String, String> closed = aliasClosers.getOrDefault(scope, Map.of());
        members.closedBindings(new HashSet<>(closed.values()));
        if (closed.isEmpty()) return bindings;
        Map<String, String> result = new LinkedHashMap<>(bindings);
        result.putAll(closed);
        return result;
    }

    /**
     * Whether a consumer binds an alias differently from an ancestor whose runtime factory exists,
     * either compiled on the classpath or generated by this compilation.
     */
    private boolean overridesFactory(String scope) {
        Map<String, String> own = staticResolver.effectiveBounds(scope);
        for (String ancestor : staticResolver.ancestors(scope)) {
            Map<String, String> inherited = staticResolver.effectiveBounds(ancestor);
            for (var entry : own.entrySet()) {
                if (Objects.equals(inherited.get(entry.getKey()), entry.getValue())) continue;
                if (construction.createsIn(ancestor, entry.getKey())
                        || hierarchy.typeResolver.readClass(ManagedConstruction.holderName(ancestor, entry.getKey())) != null) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Reads package names without requiring Enter-created symbols. */
    Map<String, Set<String>> extractManagedNames(List<JCTree.JCCompilationUnit> units) {
        return parsedSystems.managedNames(units);
    }

    private List<String> parametersOf(String name, Map<String, List<String>> index) {
        List<String> parameters = index.get(name);
        if (parameters != null) return parameters;
        var data = hierarchy.typeResolver.readClass(name);
        return data == null ? null : data.parameters();
    }

    /**
     * Inherited aliases whose binding is still a recursive template. A specialized system
     * cannot close them with a concrete class, so they remain the only class parameters.
     */
    private List<String> residualAliases(Map<String, List<String>> index, Set<String> managed) {
        List<String> result = new ArrayList<>();
        for (var entry : staticResolver.bounds.getOrDefault(hierarchy.currentTypeSystem, Map.of()).entrySet()) {
            if (!managed.contains(entry.getKey())) continue;
            List<String> parameters = parametersOf(entry.getValue(), index);
            if (parameters == null || !managed.containsAll(parameters)) return List.of();
            if (!parameters.isEmpty()) result.add(entry.getKey());
        }
        return result;
    }

    /** Source bindings receive the residual aliases so they can satisfy the inherited F-bounds. */
    private Set<String> injectResidualIntoBindings(List<JCTree.JCCompilationUnit> cus,
            Map<String, List<String>> index, Map<String, String> concrete, List<String> residual,
            TreeMaker make, Names names) {
        Set<String> values = new HashSet<>(concrete.values());
        Set<String> result = new HashSet<>();
        for (JCTree.JCCompilationUnit cu : cus) {
            for (JCTree def : cu.defs) {
                if (def instanceof JCTree.JCClassDecl cls && hierarchy.activeRoots.contains(cls)) {
                    injectResidualIntoBindings(cu, cls, index, values, residual, result, make, names);
                }
            }
        }
        return result;
    }

    private void injectResidualIntoBindings(JCTree.JCCompilationUnit cu, JCTree.JCClassDecl cls,
            Map<String, List<String>> index, Set<String> bindings, List<String> residual,
            Set<String> result, TreeMaker make, Names names) {
        for (JCTree member : cls.defs) {
            if (member instanceof JCTree.JCClassDecl inner) {
                injectResidualIntoBindings(cu, inner, index, bindings, residual, result, make, names);
            }
        }
        String name = hierarchy.classNames.get(cls);
        if (name == null || !bindings.contains(name) || !cls.typarams.isEmpty() || cls.extending == null) return;
        JCTree.JCExpression parent = cls.extending instanceof JCTree.JCTypeApply applied
                ? applied.clazz : cls.extending;
        List<String> parentParameters = hierarchy.resolveTypeParams(index, cu, parent);
        if (parentParameters == null || !parentParameters.containsAll(residual)) return;
        for (String parameter : residual) {
            cls.typarams = cls.typarams.append(makeTypeParam(parameter, cls.pos, make, names));
        }
        hierarchy.updateTypeParamMap(index, cu, cls, List.copyOf(residual));
        result.add(name);
    }

    /** Residual parameters of specialized classes receive their inherited bound, applied to the bindings. */
    private void boundResidualParameters(JCTree.JCCompilationUnit cu, JCTree.JCClassDecl cls,
            Map<String, List<String>> index, Set<String> managed, Map<String, String> concrete,
            List<String> residual, TreeMaker make, Names names) {
        for (JCTree member : cls.defs) {
            if (member instanceof JCTree.JCClassDecl inner) {
                boundResidualParameters(cu, inner, index, managed, concrete, residual, make, names);
            }
        }
        Set<String> owned = new HashSet<>();
        cls.typarams.forEach(parameter -> {
            if (managed.contains(parameter.name.toString())) owned.add(parameter.name.toString());
        });
        if (!owned.equals(new HashSet<>(residual))) return;
        com.sun.tools.javac.tree.TreeTranslator applier = new com.sun.tools.javac.tree.TreeTranslator() {
            private JCTree.JCExpression apply(JCTree.JCExpression type) {
                List<String> parameters = hierarchy.resolveTypeParams(index, cu, type);
                if (parameters == null || parameters.isEmpty() || !owned.containsAll(parameters)) return type;
                List<JCTree.JCExpression> arguments = new ArrayList<>();
                for (String parameter : parameters) {
                    arguments.add(make.at(type.pos).Ident(names.fromString(parameter)));
                }
                return make.at(type.pos).TypeApply(type, com.sun.tools.javac.util.List.from(arguments));
            }
            @Override public void visitIdent(JCTree.JCIdent tree) { result = apply(tree); }
            @Override public void visitSelect(JCTree.JCFieldAccess tree) { result = apply(tree); }
            @Override public void visitTypeApply(JCTree.JCTypeApply tree) {
                tree.arguments = translate(tree.arguments);
                result = tree;
            }
        };
        if (cls.extending instanceof JCTree.JCTypeApply applied) applied.arguments = applier.translate(applied.arguments);
        for (JCTree.JCExpression implemented : cls.implementing) {
            if (implemented instanceof JCTree.JCTypeApply applied) applied.arguments = applier.translate(applied.arguments);
        }
        for (JCTree.JCTypeParameter parameter : cls.typarams) {
            if (!owned.contains(parameter.name.toString())) parameter.bounds = applier.translate(parameter.bounds);
        }
        Map<String, String> aliases = staticResolver.bounds.getOrDefault(hierarchy.currentTypeSystem, Map.of());
        for (JCTree.JCTypeParameter parameter : cls.typarams) {
            String alias = parameter.name.toString();
            if (!owned.contains(alias) || !parameter.bounds.isEmpty()) continue;
            String bound = aliases.get(alias);
            List<String> boundParameters = bound == null ? null : parametersOf(bound, index);
            if (boundParameters == null) continue;
            make.at(parameter.pos);
            List<JCTree.JCExpression> arguments = new ArrayList<>();
            for (String boundParameter : boundParameters) {
                if (owned.contains(boundParameter)) arguments.add(make.Ident(names.fromString(boundParameter)));
                else if (concrete.containsKey(boundParameter)) {
                    arguments.add(inheritance.concreteExpression(concrete.get(boundParameter), make, names));
                } else {
                    arguments = null;
                    break;
                }
            }
            if (arguments == null) continue;
            JCTree.JCExpression type = buildQualifiedName(bound, make, names);
            parameter.bounds = com.sun.tools.javac.util.List.of(arguments.isEmpty() ? type
                    : make.TypeApply(type, com.sun.tools.javac.util.List.from(arguments)));
        }
    }

    void completeMemberTypesAfterInjection(String scope,
                                           Map<String, Set<String>> injectedParameters,
                                           Map<String, List<String[]>> originalParameterOrders) {
        Set<JCTree.JCClassDecl> roots = Collections.newSetFromMap(new IdentityHashMap<>());
        rootScopes.forEach((cls, owner) -> { if (owner.equals(scope)) roots.add(cls); });
        if (roots.isEmpty()) return;

        hierarchy.activate(scope, roots);
        Map<String, List<String>> index = hierarchy.buildTypeParamMap(sourceUnits);
        Set<String> managed = parsedSystems.managedNames(sourceUnits).getOrDefault(scope, Set.of());
        Map<String, String> bindings = memberBindings(scope, staticResolver.effectiveBounds(scope));
        for (JCTree.JCCompilationUnit cu : sourceUnits) {
            for (JCTree def : cu.defs) {
                if (def instanceof JCTree.JCClassDecl cls && roots.contains(cls)) {
                    members.completeMemberTypesAfterInjection(cu, cls, index, managed, bindings,
                            treeMaker, names, injectedParameters, originalParameterOrders);
                }
            }
        }
    }

    private boolean hasSourceManagedParent(String name, List<JCTree.JCCompilationUnit> sources,
                                           Map<String, List<String>> index, Set<String> managed) {
        return hasSourceManagedParent(name, sources, index, managed, new HashSet<>());
    }

    private boolean hasSourceManagedParent(String name, List<JCTree.JCCompilationUnit> sources,
                                           Map<String, List<String>> index, Set<String> managed,
                                           Set<String> visited) {
        if (!visited.add(name)) return false;
        for (var unit : sources) {
            for (var definition : unit.defs) {
                if (!(definition instanceof JCTree.JCClassDecl cls)
                        || !name.equals(hierarchy.classNames.get(cls)) || cls.extending == null) continue;
                var parent = cls.extending instanceof JCTree.JCTypeApply applied ? applied.clazz : cls.extending;
                String parentName = hierarchy.resolveTypeName(index, unit, parent, false);
                List<String> parameters = parentName == null ? null : index.get(parentName);
                if (parameters == null && parentName != null) {
                    var data = hierarchy.typeResolver.readClass(parentName);
                    if (data == null) return false;
                    if (data.publicView() != null
                            && data.publicView().managedParameters().stream().anyMatch(managed::contains)) {
                        return true;
                    }
                    return !data.parent().isEmpty()
                            && hasSourceManagedParent(data.parent(), sources, index, managed, visited);
                }
                if (parameters == null) return false;
                if (cls.extending instanceof JCTree.JCTypeApply applied
                        && applied.arguments.size() == parameters.size()) return false;
                return parameters.stream().anyMatch(managed::contains)
                        || hasSourceManagedParent(parentName, sources, index, managed, visited);
            }
        }
        return false;
    }

    private boolean isAbstractSourceClass(String name) {
        return hierarchy.classNames.entrySet().stream()
                .anyMatch(entry -> entry.getValue().equals(name)
                        && (entry.getKey().mods.flags & com.sun.tools.javac.code.Flags.ABSTRACT) != 0);
    }
}
