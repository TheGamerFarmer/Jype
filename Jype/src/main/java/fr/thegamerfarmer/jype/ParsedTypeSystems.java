package fr.thegamerfarmer.jype;

import com.sun.tools.javac.tree.JCTree;

import javax.lang.model.SourceVersion;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.util.*;

/**
 * Reads package-managed names before annotation symbols exist.
 *
 * <p>Source package definitions take precedence over compiled package-info metadata.
 * Cycles are bounded here so malformed input cannot hang Enter; the processor performs
 * authoritative annotation validation and reports cycles, duplicate names and invalid bounds.
 * Encounter order is retained because it defines the injected parameter order.
 */
final class ParsedTypeSystems {
    private final JavaFileManager fileManager;

    /** Uses the current javac task's classpath to follow separately compiled parent systems. */
    ParsedTypeSystems(JavaFileManager fileManager) {
        this.fileManager = fileManager;
    }

    /** Returns each annotated source package and its transitive, insertion-ordered names. */
    Map<String, Set<String>> managedNames(List<JCTree.JCCompilationUnit> units) {
        Map<String, Set<String>> definitions = new LinkedHashMap<>();
        Map<String, String> parents = new LinkedHashMap<>();
        for (var unit : units) {
            for (var definition : unit.defs) {
                if (!(definition instanceof JCTree.JCPackageDecl declaration)) continue;
                String packageName = declaration.pid == null ? "" : declaration.pid.toString();
                for (var annotation : declaration.annotations) {
                    if (!annotationName(annotation).equals("TypeSystem")) continue;
                    Set<String> names = definitions.computeIfAbsent(packageName, ignored -> new LinkedHashSet<>());
                    annotation.args.forEach(argument -> collectNames(argument, names));
                    String parent = inheritedPackage(annotation);
                    if (!parent.isEmpty()) parents.put(packageName, parent);
                }
            }
        }
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (String packageName : definitions.keySet()) {
            Set<String> names = new LinkedHashSet<>();
            collectInheritedNames(packageName, definitions, parents, new HashSet<>(), names);
            result.put(packageName, names);
        }
        return result;
    }

    private void collectInheritedNames(String packageName, Map<String, Set<String>> definitions,
                                       Map<String, String> parents, Set<String> visited, Set<String> names) {
        if (!visited.add(packageName)) return;
        names.addAll(definitions.getOrDefault(packageName, Set.of()));
        String parent = parents.get(packageName);
        if (parent != null) {
            if (definitions.containsKey(parent)) collectInheritedNames(parent, definitions, parents, visited, names);
            else collectCompiledNames(parent, visited, names);
        }
        visited.remove(packageName);
    }

    private void collectCompiledNames(String packageName, Set<String> visited, Set<String> names) {
        if (!visited.add(packageName)) return;
        try {
            var file = fileManager.getJavaFileForInput(
                    StandardLocation.CLASS_PATH, packageName + ".package-info", JavaFileObject.Kind.CLASS);
            if (file != null) {
                var data = TypeSystemClassFileReader.read(file);
                if (data != null) {
                    names.addAll(data.names());
                    if (!data.parentPackage().isEmpty()) collectCompiledNames(data.parentPackage(), visited, names);
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("[Jype] Cannot read inherited package-info.class for " + packageName, exception);
        }
        visited.remove(packageName);
    }

    /** Returns a literal inherited package, or an empty string when absent or not yet resolvable. */
    static String inheritedPackage(JCTree.JCAnnotation annotation) {
        for (var argument : annotation.args) {
            if (argument instanceof JCTree.JCAssign assignment
                    && assignment.lhs instanceof JCTree.JCIdent identifier
                    && identifier.name.contentEquals("inherit")
                    && assignment.rhs instanceof JCTree.JCLiteral literal
                    && literal.value instanceof String parent) return parent;
        }
        return "";
    }

    private void collectNames(JCTree.JCExpression expression, Set<String> names) {
        if (expression instanceof JCTree.JCAssign assignment) {
            collectNames(assignment.rhs, names);
        } else if (expression instanceof JCTree.JCNewArray array) {
            array.elems.forEach(element -> collectNames(element, names));
        } else if (expression instanceof JCTree.JCAnnotation annotation) {
            if (!annotationName(annotation).equals("TypeDef")) return;
            for (var argument : annotation.args) {
                if (!(argument instanceof JCTree.JCAssign assignment)) continue;
                if (!(assignment.lhs instanceof JCTree.JCIdent identifier)) continue;
                if (!identifier.name.contentEquals("name")) continue;
                if (assignment.rhs instanceof JCTree.JCLiteral literal && literal.value instanceof String name
                        && SourceVersion.isIdentifier(name) && !SourceVersion.isKeyword(name)) names.add(name);
            }
        }
    }

    /** Extracts simple annotation names in both imported and qualified source spellings. */
    static String annotationName(JCTree.JCAnnotation annotation) {
        if (annotation.annotationType instanceof JCTree.JCIdent identifier) return identifier.name.toString();
        if (annotation.annotationType instanceof JCTree.JCFieldAccess selected) return selected.name.toString();
        return "";
    }
}
