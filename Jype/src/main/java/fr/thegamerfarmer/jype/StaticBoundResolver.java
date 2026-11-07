package fr.thegamerfarmer.jype;

import com.sun.tools.javac.tree.JCTree;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.util.*;
import static fr.thegamerfarmer.jype.PreEnterTrees.*;
import static fr.thegamerfarmer.jype.ParsedTypeSystems.*;

/**
 * Resolves source and compiled package bounds needed for generic static bridge signatures.
 * Overrides are collected first; inherited associations fill only missing names.
 */
final class StaticBoundResolver {
    final Map<String, Map<String, String>> bounds = new LinkedHashMap<>();
    private final Map<String, String> parents = new LinkedHashMap<>();
    private final Set<String> localDefinitions = new HashSet<>();
    private final JavaFileManager fileManager;
    private final SourceHierarchy hierarchy;

    /** Retains task-local package metadata access; no user classes are loaded. */
    StaticBoundResolver(JavaFileManager fileManager, SourceHierarchy hierarchy) {
        this.fileManager = fileManager;
        this.hierarchy = hierarchy;
    }

    /** Collects source bounds, then merges transitive parents without replacing explicit overrides. */
    void collectStaticBounds(List<JCTree.JCCompilationUnit> cus, Map<String, List<String>> index) {
        for (var cu : cus) {
            for (var def : cu.defs) {
                if (!(def instanceof JCTree.JCPackageDecl pkg)) continue;
                for (var annotation : pkg.annotations) {
                    if (!annotationName(annotation).equals("TypeSystem")) continue;
                    String scope = getCuPackage(cu);
                    Map<String, String> packageBounds = bounds.computeIfAbsent(scope, ignored -> new LinkedHashMap<>());
                    new com.sun.tools.javac.tree.TreeScanner() {
                        @Override public void visitAnnotation(JCTree.JCAnnotation tree) {
                            if (annotationName(tree).equals("TypeDef")) {
                                String name = null;
                                JCTree.JCExpression bound = null;
                                for (var arg : tree.args) {
                                    if (!(arg instanceof JCTree.JCAssign assignment)) continue;
                                    if (assignment.lhs.toString().equals("name")
                                            && assignment.rhs instanceof JCTree.JCLiteral literal
                                            && literal.value instanceof String value) name = value;
                                    if (assignment.lhs.toString().equals("bound")
                                            && assignment.rhs instanceof JCTree.JCFieldAccess access
                                            && access.name.contentEquals("class")) bound = access.selected;
                                }
                                if (name != null && bound != null) {
                                    String qualified = hierarchy.resolveTypeName(index, cu, bound, true);
                                    if (qualified != null) {
                                        packageBounds.put(name, qualified);
                                        localDefinitions.add(scope);
                                    }
                                }
                            }
                            super.visitAnnotation(tree);
                        }
                    }.scan(annotation);
                    String parent = inheritedPackage(annotation);
                    if (!parent.isEmpty()) parents.put(scope, parent);
                }
            }
        }
        for (String scope : bounds.keySet()) {
            inheritStaticBounds(scope, parents, new HashSet<>());
        }
    }

    /** Reports whether a local system explicitly inherits another package's bindings. */
    boolean inherits(String scope) {
        return parents.containsKey(scope);
    }

    boolean hasLocalDefinitions(String scope) {
        return localDefinitions.contains(scope);
    }

    Map<String, String> effectiveBounds(String scope) {
        return bounds.computeIfAbsent(scope, key -> readStaticBounds(key, new HashSet<>()));
    }

    private void inheritStaticBounds(String scope, Map<String, String> parents, Set<String> visited) {
        if (!visited.add(scope)) return;
        String parent = parents.get(scope);
        if (parent == null) return;
        Map<String, String> inherited = bounds.get(parent);
        if (inherited != null) {
            inheritStaticBounds(parent, parents, visited);
        } else {
            inherited = readStaticBounds(parent, new HashSet<>());
        }
        Map<String, String> own = bounds.get(scope);
        inherited.forEach(own::putIfAbsent);
    }

    private Map<String, String> readStaticBounds(String scope, Set<String> visited) {
        Map<String, String> result = new LinkedHashMap<>();
        if (!visited.add(scope)) return result;
        try {
            JavaFileObject file = fileManager.getJavaFileForInput(
                    StandardLocation.CLASS_PATH, scope + ".package-info", JavaFileObject.Kind.CLASS);
            if (file != null) {
                var data = TypeSystemClassFileReader.read(file);
                if (data != null) {
                    result.putAll(data.bounds());
                    if (!data.parentPackage().isEmpty()) {
                        readStaticBounds(data.parentPackage(), visited).forEach(result::putIfAbsent);
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("[Jype] Cannot read static bounds for " + scope, e);
        }
        return result;
    }
}
