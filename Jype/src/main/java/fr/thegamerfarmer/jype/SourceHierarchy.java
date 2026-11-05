package fr.thegamerfarmer.jype;

import com.sun.source.util.TreePath;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.util.Log;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import java.util.*;
import static fr.thegamerfarmer.jype.PreEnterTrees.*;

/**
 * Owns canonical source identities, parameter orders and concrete binding discovery.
 * One instance belongs to one task. activate selects a package scope without leaking bindings to neighbors.
 */
final class SourceHierarchy {
    final Map<JCTree.JCClassDecl, String> classNames = new IdentityHashMap<>();
    Set<JCTree.JCClassDecl> activeRoots = Collections.emptySet();
    String currentTypeSystem = "";
    final SourceTypeResolver typeResolver;
    private final Log log;
    private final Set<String> reportedConcreteConflicts = new HashSet<>();
    private final Map<String, List<String>> compiledParameters = new HashMap<>();

    /** Creates the canonical index and task-local classpath resolver. */
    SourceHierarchy(JavaFileManager fileManager, Log log) {
        typeResolver = new SourceTypeResolver(fileManager, classNames);
        this.log = log;
    }

    /** Selects roots for one type system; each concrete-binding scan starts with a fresh map. */
    void activate(String scope, Set<JCTree.JCClassDecl> roots) {
        currentTypeSystem = scope;
        activeRoots = roots;
    }

    /** Rebuilds the canonical parameter index after declaration mutation; no simple-name aliases exist. */
    Map<String, List<String>> buildTypeParamMap(List<JCTree.JCCompilationUnit> cus) {
        Map<String, List<String>> result = new HashMap<>();
        for (JCTree.JCCompilationUnit cu : cus) {
            String packageName = getCuPackage(cu);
            for (JCTree def : cu.defs) {
                if (def instanceof JCTree.JCClassDecl cls) {
                    collectTypeParams(cls, packageName, result);
                }
            }
        }
        return result;
    }

    private void collectTypeParams(JCTree.JCClassDecl cls, String parentFqn,
                                   Map<String, List<String>> map) {
        List<String> params = new ArrayList<>();
        for (JCTree.JCTypeParameter tp : cls.typarams) {
            params.add(tp.name.toString());
        }
        String simpleName = cls.name.toString();
        String fqn = parentFqn.isEmpty() ? simpleName : parentFqn + "." + simpleName;
        classNames.put(cls, fqn);
        map.put(fqn, params);
        for (JCTree member : cls.defs) {
            if (member instanceof JCTree.JCClassDecl inner) {
                collectTypeParams(inner, fqn, map);
            }
        }
    }

    /** Updates a known declaration immediately so the next fixed-point iteration sees its parameters. */
    void updateTypeParamMap(Map<String, List<String>> typeParamMap,
                                    JCTree.JCCompilationUnit cu,
                                    JCTree.JCClassDecl cls,
                                    List<String> params) {
        String classFqn = classNames.get(cls);
        if (classFqn == null) return;
        typeParamMap.put(classFqn, params);
    }

    /** Returns source or class-file parameter order without loading classes before Enter. */
    List<String> resolveTypeParams(Map<String, List<String>> typeParamMap,
                                           JCTree.JCCompilationUnit cu,
                                           JCTree.JCExpression typeExpr) {
        String resolved = resolveTypeName(typeParamMap, cu, typeExpr, true);
        if (resolved == null) return null;
        if (typeParamMap.containsKey(resolved)) return typeParamMap.get(resolved);
        return compiledParameters.computeIfAbsent(resolved, name -> {
            var data = typeResolver.readClass(name);
            return data == null ? null : data.parameters();
        });
    }

    /** Delegates canonical name resolution while retaining the caller's current live source index. */
    String resolveTypeName(Map<String, List<String>> index, JCTree.JCCompilationUnit unit,
            JCTree.JCExpression type, boolean compiled) {
        return typeResolver.resolve(index, unit, type, compiled);
    }

    /**
     * Builds the concrete-type map by scanning all complete {@code extends}
     * clauses (argument count == parameter count).
     *
     * <p>For each {@code (managedParam, concreteArg)} pair, if the parameter is
     * a managed name and the argument is a {@code JCIdent} or
     * {@code JCFieldAccess} (not a wildcard or type application), records
     * {@code managedParam → FQN} using the compilation unit's imports.
     *
     * <p>The first concrete type found for each managed name is retained.
     */
    Map<String, String> buildConcreteTypeMap(List<JCTree.JCCompilationUnit> cus,
                                                      Map<String, List<String>> typeParamMap,
                                                      Set<String> managedNames) {
        Map<String, String> result = new HashMap<>();
        for (JCTree.JCCompilationUnit cu : cus) {
            for (JCTree def : cu.defs) {
                if (def instanceof JCTree.JCClassDecl cls && activeRoots.contains(cls)) {
                    collectConcreteTypes(cu, cls, typeParamMap, managedNames, result);
                }
            }
        }
        return result;
    }

    private void collectConcreteTypes(JCTree.JCCompilationUnit cu,
                                       JCTree.JCClassDecl cls,
                                       Map<String, List<String>> typeParamMap,
                                       Set<String> managedNames,
                                       Map<String, String> out) {
        for (JCTree member : cls.defs) {
            if (member instanceof JCTree.JCClassDecl inner) {
                collectConcreteTypes(cu, inner, typeParamMap, managedNames, out);
            }
        }
        if (!(cls.extending instanceof JCTree.JCTypeApply ta)) return;
        String superName = getSimpleName(ta.clazz);
        if (superName == null) return;
        List<String> superParams = resolveTypeParams(typeParamMap, cu, ta.clazz);
        if (superParams == null || ta.arguments.size() != superParams.size()) return;

        for (int i = 0; i < superParams.size(); i++) {
            String paramName = superParams.get(i);
            if (!managedNames.contains(paramName)) continue;
            JCTree.JCExpression arg = ta.arguments.get(i);
            if (arg instanceof JCTree.JCIdent id) {
                // Ignore type variables (managed names); they are not concrete types.
                if (managedNames.contains(id.name.toString())) continue;
                if (cls.typarams.stream().anyMatch(tp -> tp.name.equals(id.name))) continue;
                boolean typeVariable = false;
                for (TreePath path = TreePath.getPath(cu, arg); path != null; path = path.getParentPath()) {
                    if (path.getLeaf() instanceof JCTree.JCClassDecl enclosing
                            && enclosing.typarams.stream().anyMatch(tp -> tp.name.equals(id.name))) {
                        typeVariable = true;
                        break;
                    }
                }
                if (typeVariable) continue;
            } else if (!(arg instanceof JCTree.JCFieldAccess)) continue;
            List<String> argTypeParams = resolveTypeParams(typeParamMap, cu, arg);
            if (argTypeParams != null && argTypeParams.stream().anyMatch(managedNames::contains)) continue;
            String fqn = resolveTypeName(typeParamMap, cu, arg, true);
            if (fqn != null) recordConcreteType(cu, paramName, fqn, cls, ta, out);
        }
    }

    private void recordConcreteType(JCTree.JCCompilationUnit cu, String paramName, String candidate,
                                   JCTree.JCClassDecl bindingClass,
                                   JCTree.JCTypeApply extendsTree,
                                   Map<String, String> concreteTypes) {
        String previous = concreteTypes.putIfAbsent(paramName, candidate);
        if (previous == null || previous.equals(candidate)) return;

        String conflictKey = currentTypeSystem + ":" + paramName + ":"
                + (previous.compareTo(candidate) <= 0 ? previous + ":" + candidate : candidate + ":" + previous);
        if (reportedConcreteConflicts.add(conflictKey)) {
            JavaFileObject previousSource = log.useSource(cu.sourcefile);
            try {
                log.error(extendsTree.pos, "proc.messager",
                        "[Jype] Conflicting concrete bindings for " + paramName + " in @TypeSystem "
                                + currentTypeSystem + ": " + previous + " (existing) and " + candidate
                                + " in " + bindingClass.name + ". Define one consistent binding per type system.");
            } finally {
                log.useSource(previousSource);
            }
        }
    }
}
