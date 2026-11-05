package fr.thegamerfarmer.jype;

import com.sun.source.util.TreePath;
import com.sun.tools.javac.tree.JCTree;

import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.util.*;

/**
 * Resolves pre-Enter type names without symbols or accidental simple-name collisions.
 *
 * <p>Lexical member types precede explicit imports, the current package and wildcard imports.
 * A wildcard candidate is accepted only when unique. Nested source names are translated to
 * binary names for classpath probes. Caches are confined to one compilation, whose classpath
 * is fixed; the source parameter index remains live while propagation adds parameters.
 */
final class SourceTypeResolver {
    private final JavaFileManager fileManager;
    private final Map<JCTree.JCClassDecl, String> classNames;
    private final Map<String, Boolean> classpathTypes = new HashMap<>();
    private final Map<JCTree.JCExpression, List<String>> lexicalScopes = new IdentityHashMap<>();

    /** Uses canonical names populated for every parsed member class before resolution begins. */
    SourceTypeResolver(JavaFileManager fileManager, Map<JCTree.JCClassDecl, String> classNames) {
        this.fileManager = fileManager;
        this.classNames = classNames;
    }

    /**
     * Returns a canonical source name, or {@code null} for missing or ambiguous types.
     * Classpath probing is optional; class-file signatures can supply pre-Enter parameter orders.
     */
    String resolve(Map<String, List<String>> index, JCTree.JCCompilationUnit unit,
                   JCTree.JCExpression expression, boolean includeClasspath) {
        String sourceName = expression.toString();
        String packageName = unit.getPackageName() == null ? "" : unit.getPackageName().toString();
        List<String> scopes = lexicalScopes.computeIfAbsent(expression, ignored -> {
            List<String> result = new ArrayList<>();
            for (TreePath path = TreePath.getPath(unit, expression); path != null; path = path.getParentPath()) {
                if (path.getLeaf() instanceof JCTree.JCClassDecl declaration) {
                    String enclosing = classNames.get(declaration);
                    if (enclosing != null) result.add(enclosing);
                }
            }
            return result;
        });
        for (String enclosing : scopes) {
            String candidate = enclosing + "." + sourceName;
            if (isKnown(index, candidate, includeClasspath)) return candidate;
        }
        String imported = explicitImport(unit, sourceName);
        if (imported != null) return isKnown(index, imported, includeClasspath) ? imported : null;
        String samePackage = packageName.isEmpty() ? sourceName : packageName + "." + sourceName;
        if (isKnown(index, samePackage, includeClasspath)) return samePackage;
        if (sourceName.contains(".") && isKnown(index, sourceName, includeClasspath)) return sourceName;
        Set<String> candidates = new LinkedHashSet<>();
        if (isKnown(index, "java.lang." + sourceName, includeClasspath)) candidates.add("java.lang." + sourceName);
        for (JCTree definition : unit.defs) {
            if (!(definition instanceof JCTree.JCImport importTree)) continue;
            String name = importTree.qualid.toString();
            if (!name.endsWith(".*")) continue;
            String candidate = name.substring(0, name.length() - 1) + sourceName;
            if (isKnown(index, candidate, includeClasspath)) candidates.add(candidate);
        }
        return candidates.size() == 1 ? candidates.iterator().next() : null;
    }

    private boolean isKnown(Map<String, List<String>> index, String name, boolean includeClasspath) {
        if (index.containsKey(name)) return true;
        if (!includeClasspath) return false;
        return classpathTypes.computeIfAbsent(name, candidate -> {
            try {
                return findClass(candidate) != null;
            } catch (IOException exception) {
                throw new IllegalStateException("[Jype] Cannot resolve compiled type " + candidate, exception);
            }
        });
    }

    /** Reads class signatures without loading classes; an absent class is not a read failure. */
    TypeSystemClassFileReader.ClassData readClass(String qualifiedName) {
        try {
            JavaFileObject file = findClass(qualifiedName);
            return file == null ? null : TypeSystemClassFileReader.readClass(file);
        } catch (IOException exception) {
            throw new IllegalStateException("[Jype] Cannot read class signatures for " + qualifiedName, exception);
        }
    }

    private JavaFileObject findClass(String qualifiedName) throws IOException {
        for (StandardLocation location : List.of(StandardLocation.CLASS_PATH, StandardLocation.PLATFORM_CLASS_PATH)) {
            if (!fileManager.hasLocation(location)) continue;
            String binaryName = qualifiedName;
            while (true) {
                JavaFileObject file = fileManager.getJavaFileForInput(location, binaryName, JavaFileObject.Kind.CLASS);
                if (file != null) return file;
                int dot = binaryName.lastIndexOf('.');
                if (dot < 0) break;
                binaryName = binaryName.substring(0, dot) + "$" + binaryName.substring(dot + 1);
            }
        }
        return null;
    }

    private String explicitImport(JCTree.JCCompilationUnit unit, String sourceName) {
        for (JCTree definition : unit.defs) {
            if (!(definition instanceof JCTree.JCImport importTree)) continue;
            String imported = importTree.qualid.toString();
            if (imported.endsWith(".*")) continue;
            String simpleName = imported.substring(imported.lastIndexOf('.') + 1);
            if (sourceName.equals(simpleName) || sourceName.startsWith(simpleName + ".")) {
                return imported + sourceName.substring(simpleName.length());
            }
        }
        return null;
    }
}
