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
    final DeclarationOrigins origins = new DeclarationOrigins();

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
    }

    /** Returns the live URI-keyed edits consumed only after this transformation has completed. */
    Map<String, List<JavadocSourceGenerator.SourceEdit>> staticSourceEdits() {
        return staticSourceEdits;
    }

    /** Completes all source declarations once, before the first Enter event. */
    void transform(List<JCTree.JCCompilationUnit> cus,
                                    TreeMaker make, Names names) {
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
        for (String scope : new LinkedHashSet<>(scopes.values())) {
            staticResolver.effectiveBounds(scope);
            Set<JCTree.JCClassDecl> roots = Collections.newSetFromMap(new IdentityHashMap<>());
            scopes.forEach((cls, owner) -> { if (owner.equals(scope)) roots.add(cls); });
            hierarchy.activate(scope, roots);
            fixTypeSystem(cus, cus, systems.getOrDefault(scope, Collections.emptySet()), make, names);
        }
    }

    private void fixTypeSystem(List<JCTree.JCCompilationUnit> cus,
                               List<JCTree.JCCompilationUnit> allSources,
                               Set<String> managedNames, TreeMaker make, Names names) {

        Map<String, List<String>> typeParamMap = hierarchy.buildTypeParamMap(allSources);

        // Canonical binding names distinguish unrelated classes with the same simple name.
        Set<String> concreteBindings = new HashSet<>();

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
                                staticResolver.bounds.getOrDefault(hierarchy.currentTypeSystem, Map.of()), make, names);
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
                if (parameters != null && parameters.isEmpty()) preConcreteTypeMap.putIfAbsent(entry.getKey(), entry.getValue());
            }
            concreteBindings.addAll(preConcreteTypeMap.values());

            propagation.propagateBoundDependencies(cus, typeParamMap, managedNames, concreteBindings, make, names);
        }

        Map<String, String> concreteTypeMap = hierarchy.buildConcreteTypeMap(cus, typeParamMap, managedNames);
        for (var entry : staticResolver.bounds.getOrDefault(hierarchy.currentTypeSystem, Map.of()).entrySet()) {
            if (concreteBindings.contains(entry.getValue())) concreteTypeMap.putIfAbsent(entry.getKey(), entry.getValue());
        }

        for (JCTree.JCCompilationUnit cu : cus) {
            for (JCTree def : cu.defs) {
                if (def instanceof JCTree.JCClassDecl && hierarchy.activeRoots.contains(def)) {
                    inheritance.fixClassDecl(cu, (JCTree.JCClassDecl) def, typeParamMap, managedNames,
                            concreteTypeMap, concreteBindings, make, names);
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
            statics.process(cus, hierarchy.activeRoots);
            for (JCTree.JCCompilationUnit cu : cus) {
                for (JCTree def : cu.defs) {
                    if (def instanceof JCTree.JCClassDecl cls && hierarchy.activeRoots.contains(cls)) {
                        Map<String, String> bindings = new LinkedHashMap<>(
                                staticResolver.bounds.getOrDefault(hierarchy.currentTypeSystem, Map.of()));
                        bindings.putAll(concreteTypeMap);
                        members.completeMemberTypes(cu, cls, typeParamMap, managedNames, bindings, make, names);
                    }

                }
            }
        }
    }

    /** Reads package names without requiring Enter-created symbols. */
    Map<String, Set<String>> extractManagedNames(List<JCTree.JCCompilationUnit> units) {
        return parsedSystems.managedNames(units);
    }
}
