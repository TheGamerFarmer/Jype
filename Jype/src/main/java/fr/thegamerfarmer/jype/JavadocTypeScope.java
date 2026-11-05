package fr.thegamerfarmer.jype;

import com.sun.source.tree.*;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import java.util.*;
import java.util.function.Predicate;

/**
 * Resolves source type names and managed parameter availability for Javadoc edits.
 * Lexical type shadowing precedes imports; static/implicitly static scopes stop class-parameter capture.
 */
final class JavadocTypeScope {
    private final CompilationUnitTree unit;
    private final SourcePositions positions;
    private final Map<String, List<String[]>> declarationPatches;
    private final Set<String> allTargets;
    private final Predicate<String> knownType;
    private final java.util.function.Supplier<TreePath> currentPath;
    private final String packageName;
    private final Map<String, String> explicitImports = new HashMap<>();
    private final List<String> wildcardImports = new ArrayList<>();

    /** Binds lookup to the collector's current tree path without retaining attributed symbols. */
    JavadocTypeScope(CompilationUnitTree unit, SourcePositions positions,
                     Map<String, List<String[]>> declarationPatches, Set<String> allTargets,
                     Predicate<String> knownType, java.util.function.Supplier<TreePath> currentPath) {
        this.unit = unit; this.positions = positions; this.declarationPatches = declarationPatches;
        this.allTargets = allTargets; this.knownType = name -> allTargets.contains(name) || knownType.test(name);
        this.currentPath = currentPath;
        packageName = unit.getPackageName() == null ? "" : unit.getPackageName().toString();
        for (ImportTree importTree : unit.getImports()) {
            String imported = importTree.getQualifiedIdentifier().toString();
            if (imported.endsWith(".*")) {
                wildcardImports.add(imported.substring(0, imported.length() - 2));
            } else {
                int dot = imported.lastIndexOf('.');
                String previous = explicitImports.putIfAbsent(imported.substring(dot + 1), imported);
                if (previous != null && !previous.equals(imported)) {
                    throw new IllegalArgumentException("Conflicting imports for " + imported.substring(dot + 1));
                }
            }
        }
    }

    private TreePath getCurrentPath() { return currentPath.get(); }

    /** Removes type annotations/applications while retaining the canonical dotted source spelling. */
    String typeName(Tree tree) {
        if (tree instanceof AnnotatedTypeTree annotated) return typeName(annotated.getUnderlyingType());
        if (tree instanceof ParameterizedTypeTree parameterized) return typeName(parameterized.getType());
        if (tree instanceof MemberSelectTree selected) {
            return typeName(selected.getExpression()) + "." + selected.getIdentifier();
        }
        return tree.toString();
    }

    /** Returns a canonical member-class name; local/anonymous declarations are not patch targets. */
    String qualifiedName(ClassTree tree) {
        List<String> names = new ArrayList<>();
        names.add(tree.getSimpleName().toString());
        for (TreePath parent = getCurrentPath().getParentPath(); parent != null;
             parent = parent.getParentPath()) {
            if (!(parent.getLeaf() instanceof ClassTree)
                    && !(parent.getLeaf() instanceof CompilationUnitTree)) return null;
            if (parent.getLeaf() instanceof ClassTree parentClass) {
                names.add(parentClass.getSimpleName().toString());
            }
        }
        Collections.reverse(names);
        String nestedName = String.join(".", names);
        return packageName.isEmpty() ? nestedName : packageName + "." + nestedName;
    }

    /**
     * Finds a managed declaration from the current lexical use site.
     *
     * @return the canonical target, or null for shadowed/unmanaged names
     * @throws IllegalStateException when wildcard imports offer competing known targets
     */
    String resolveTarget(String typeName) {
        String first = typeName.split("\\.", 2)[0];
        String suffix = typeName.substring(first.length());
        // Resolve the first component lexically before considering imports/packages.
        for (TreePath parent = getCurrentPath().getParentPath(); parent != null;
             parent = parent.getParentPath()) {
            Tree scope = parent.getLeaf();
            if (scope instanceof MethodTree method
                    && method.getTypeParameters().stream().anyMatch(p -> p.getName().contentEquals(first))) {
                return null;
            }
            if (scope instanceof ClassTree clazz) {
                if (clazz.getTypeParameters().stream().anyMatch(p -> p.getName().contentEquals(first))
                        || declarationParams(parent).stream()
                            .anyMatch(p -> p[0].equals(first))) return null;
                for (Tree member : clazz.getMembers()) {
                    if (member instanceof ClassTree nested && nested.getSimpleName().contentEquals(first)) {
                        return targetOrNull(qualifiedNameAt(parent) + "." + first + suffix);
                    }
                }
                if (clazz.getSimpleName().contentEquals(first)) {
                    return targetOrNull(qualifiedNameAt(parent) + suffix);
                }
            }
            if (scope instanceof BlockTree block) {
                long use = positions.getStartPosition(unit, getCurrentPath().getLeaf());
                for (StatementTree statement : block.getStatements()) {
                    if (statement instanceof ClassTree local && local.getSimpleName().contentEquals(first)
                            && positions.getStartPosition(unit, local) <= use) return null;
                }
            }
        }
        for (Tree declaration : unit.getTypeDecls()) {
            if (declaration instanceof ClassTree clazz && clazz.getSimpleName().contentEquals(first)) {
                return targetOrNull((packageName.isEmpty() ? "" : packageName + ".") + typeName);
            }
        }
        String imported = explicitImports.get(first);
        if (imported != null) return targetOrNull(imported + suffix);
        if (allTargets.contains(typeName)) return typeName;
        String samePackage = packageName.isEmpty() ? typeName : packageName + "." + typeName;
        if (allTargets.contains(samePackage)) return samePackage;
        List<String> candidates = new ArrayList<>(wildcardImports.stream()
                .map(pkg -> pkg + "." + typeName).filter(knownType).distinct().toList());
        String implicit = "java.lang." + typeName;
        if (knownType.test(implicit) && !candidates.contains(implicit)) candidates.add(implicit);
        if (candidates.stream().anyMatch(allTargets::contains)) {
            if (candidates.size() > 1) {
                throw new IllegalStateException("Cannot resolve Javadoc type through ambiguous wildcard imports: " + typeName);
            }
            return targetOrNull(candidates.get(0));
        }
        return null;
    }

    private String targetOrNull(String name) {
        return allTargets.contains(name) ? name : null;
    }

    private List<String[]> declarationParams(TreePath path) {
        String name = qualifiedNameAt(path);
        return name == null ? List.of() : declarationPatches.getOrDefault(name, List.of());
    }

    private String qualifiedNameAt(TreePath path) {
        List<String> names = new ArrayList<>();
        for (; path != null && !(path.getLeaf() instanceof CompilationUnitTree); path = path.getParentPath()) {
            if (!(path.getLeaf() instanceof ClassTree clazz)) return null;
            names.add(clazz.getSimpleName().toString());
        }
        Collections.reverse(names);
        return (packageName.isEmpty() ? "" : packageName + ".") + String.join(".", names);
    }

    /** Checks lexical parameter ownership, stopping at static boundaries and same-name type shadowing. */
    boolean typeParameterInScope(String name) {
        for (TreePath path = getCurrentPath(); path != null; path = path.getParentPath()) {
            Tree tree = path.getLeaf();
            if (tree instanceof MethodTree method) {
                if (method.getTypeParameters().stream().anyMatch(p -> p.getName().contentEquals(name))) return true;
                if (method.getModifiers().getFlags().contains(javax.lang.model.element.Modifier.STATIC)) return false;
            }
            if (tree instanceof VariableTree variable
                    && variable.getModifiers().getFlags().contains(javax.lang.model.element.Modifier.STATIC)) return false;
            if (tree instanceof BlockTree block && block.isStatic()) return false;
            if (tree instanceof BlockTree block) {
                long use = positions.getStartPosition(unit, getCurrentPath().getLeaf());
                if (block.getStatements().stream().anyMatch(statement ->
                        statement instanceof ClassTree local && local.getSimpleName().contentEquals(name)
                                && positions.getStartPosition(unit, local) <= use)) return false;
            }
            if (tree instanceof ClassTree clazz) {
                if (clazz.getTypeParameters().stream().anyMatch(p -> p.getName().contentEquals(name))
                        || declarationParams(path).stream()
                            .anyMatch(p -> p[0].equals(name))) return true;
                if (clazz.getSimpleName().contentEquals(name)
                        || clazz.getMembers().stream().anyMatch(member ->
                            member instanceof ClassTree nested && nested.getSimpleName().contentEquals(name))) {
                    return false;
                }
                if (clazz.getModifiers().getFlags().contains(javax.lang.model.element.Modifier.STATIC)
                        || clazz.getKind() == Tree.Kind.INTERFACE || clazz.getKind() == Tree.Kind.ENUM
                        || clazz.getKind() == Tree.Kind.RECORD) return false;
                if (path.getParentPath() != null
                        && path.getParentPath().getLeaf() instanceof ClassTree enclosing
                        && (enclosing.getKind() == Tree.Kind.INTERFACE
                            || enclosing.getKind() == Tree.Kind.ANNOTATION_TYPE)) return false;
            }
        }
        return false;
    }
}
