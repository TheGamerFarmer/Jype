package fr.thegamerfarmer.jype;

import com.sun.source.tree.*;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePathScanner;
import java.util.*;
import java.util.function.Predicate;

import fr.thegamerfarmer.jype.JavadocSourcePatcher.Token;

/**
 * Collects declaration and use-site insertions at original parsed source positions.
 * Edits at a shared position compose deterministically and are applied from right to left.
 */
final class JavadocPatchCollector extends TreePathScanner<Void, Void> {
    private final JavadocTypeScope scope;

    private final String source;
    private final CompilationUnitTree unit;
    private final SourcePositions positions;
    private final Map<String, List<String[]>> usePatches;
    private final Map<String, List<String[]>> declarationPatches;
    private final Map<String, List<String[]>> originalParamOrders;
    private final List<Token> tokens;
    private final List<TextEdit> edits = new ArrayList<>();

    /** Uses positions from precisely this source text; generated edits never invalidate later positions. */
    JavadocPatchCollector(String source, CompilationUnitTree unit,
                                SourcePositions positions,
                                Map<String, List<String[]>> usePatches,
                                Map<String, List<String[]>> declarationPatches,
                                Map<String, List<String[]>> originalParamOrders,
                                Predicate<String> knownType) {
        this.source = source;
        this.unit = unit;
        this.positions = positions;
        this.usePatches = usePatches;
        this.declarationPatches = declarationPatches;
        this.originalParamOrders = originalParamOrders;
        this.tokens = JavadocSourcePatcher.tokenize(source);
        this.scope = new JavadocTypeScope(unit, positions, declarationPatches, usePatches.keySet(),
                knownType, this::getCurrentPath);
    }

    @Override
    public Void visitClass(ClassTree tree, Void unused) {
        String name = scope.qualifiedName(tree);
        List<String[]> params = name == null ? null : declarationPatches.get(name);
        if (params != null) patchDeclaration(tree, params);
        return super.visitClass(tree, unused);
    }

    @Override
    public Void visitParameterizedType(ParameterizedTypeTree tree, Void unused) {
        String target = scope.resolveTarget(scope.typeName(tree.getType()));
        List<String[]> params = usePatches.get(target);
        if (params != null) patchTypeArguments(tree, target, params);
        return super.visitParameterizedType(tree, unused);
    }

    @Override
    public Void visitIdentifier(IdentifierTree tree, Void unused) {
        patchRawType(tree);
        return super.visitIdentifier(tree, unused);
    }

    @Override
    public Void visitMemberSelect(MemberSelectTree tree, Void unused) {
        patchRawType(tree);
        return super.visitMemberSelect(tree, unused);
    }

    private void patchRawType(Tree tree) {
        Tree parent = getCurrentPath().getParentPath().getLeaf();
        // Only type slots are eligible: never imports, class literals, constructor names,
        // qualified-name components, or expressions that happen to have a type's name.
        boolean typeSlot = parent instanceof VariableTree variable && variable.getType() == tree
                || parent instanceof MethodTree method && method.getReturnType() == tree
                || parent instanceof ArrayTypeTree
                || parent instanceof AnnotatedTypeTree
                || parent instanceof TypeParameterTree parameter && parameter.getBounds().contains(tree)
                || parent instanceof ClassTree clazz
                    && (clazz.getExtendsClause() == tree || clazz.getImplementsClause().contains(tree))
                || parent instanceof ParameterizedTypeTree parameter && parameter.getTypeArguments().contains(tree);
        if (!typeSlot) return;
        List<String[]> params = usePatches.get(scope.resolveTarget(scope.typeName(tree)));
        if (params == null || params.isEmpty()
                || params.stream().anyMatch(p -> !"true".equals(p[1]))
                || params.stream().anyMatch(p -> !scope.typeParameterInScope(p[0]))) return;
        addInsertion(rawTypeEnd(tree),
                "<" + String.join(", ", params.stream().map(p -> p[0]).toList()) + ">", true);
    }

    private int rawTypeEnd(Tree tree) {
        long start = positions.getStartPosition(unit, tree);
        if (start < 0 || start > source.length()) {
            throw new IllegalStateException("Missing raw type source position: " + tree);
        }
        int token = firstTokenAt((int) start);
        String[] components = scope.typeName(tree).split("\\.");
        // javac can include dimension annotations in a raw type's end position.
        // Locate the actual qualified name instead, leaving annotations and dimensions intact.
        for (int component = 0; component < components.length; component++) {
            if (component > 0) {
                if (token >= tokens.size() || !tokens.get(token++).text().equals(".")) {
                    throw new IllegalStateException("Cannot locate raw type name: " + tree);
                }
            }
            if (token >= tokens.size() || !tokens.get(token).text().equals(components[component])) {
                throw new IllegalStateException("Cannot locate raw type name: " + tree);
            }
            if (component == components.length - 1) return tokens.get(token).end();
            token++;
        }
        throw new IllegalStateException("Empty raw type name");
    }

    private void patchTypeArguments(ParameterizedTypeTree tree, String target, List<String[]> paramOrder) {
        int nonJypeCount = (int) paramOrder.stream().filter(p -> "false".equals(p[1])).count();
        List<? extends com.sun.source.tree.Tree> arguments = tree.getTypeArguments();
        if (arguments.isEmpty()) return; // Diamond inference already accounts for the complete declaration.
        if (arguments.size() == paramOrder.size()) return;
        List<String[]> original = originalParamOrders.get(target);
        Set<String> explicitNames;
        if (original != null && arguments.size() == original.size()) {
            explicitNames = new LinkedHashSet<>(original.stream().map(p -> p[0]).toList());
            List<String> retainedOrder = paramOrder.stream().map(p -> p[0]).filter(explicitNames::contains).toList();
            if (!retainedOrder.equals(original.stream().map(p -> p[0]).toList())) {
                throw new IllegalStateException("Inconsistent original Javadoc parameter order for " + target);
            }
        } else if (nonJypeCount > 0 && arguments.size() == nonJypeCount) {
            explicitNames = new LinkedHashSet<>(paramOrder.stream()
                    .filter(p -> "false".equals(p[1])).map(p -> p[0]).toList());
        } else if (nonJypeCount == 0 && arguments.size() < paramOrder.size()) {
            // All-managed partial applications retain their explicit prefix (e.g. APIPower<T>).
            explicitNames = new LinkedHashSet<>(paramOrder.subList(0, arguments.size()).stream()
                    .map(p -> p[0]).toList());
        } else {
            throw new IllegalStateException("Unexpected Javadoc type argument count for " + tree);
        }
        int consumedUserArgs = 0;
        for (String[] param : paramOrder) {
            if (explicitNames.contains(param[0])) {
                consumedUserArgs++;
                continue;
            }
            if (!"true".equals(param[1])) {
                throw new IllegalStateException("Missing original Javadoc argument " + param[0] + " for " + target);
            }
            String argument = scope.typeParameterInScope(param[0]) ? param[0] : "?";
            int insertion = consumedUserArgs == 0
                    ? (int) positions.getStartPosition(unit, arguments.get(0))
                    : consumedUserArgs < arguments.size()
                            ? (int) positions.getStartPosition(unit, arguments.get(consumedUserArgs))
                            : (int) positions.getEndPosition(unit, arguments.get(arguments.size() - 1));
            addInsertion(insertion, consumedUserArgs < arguments.size()
                    ? argument + ", " : ", " + argument);
        }
    }

    private void patchDeclaration(ClassTree tree, List<String[]> paramOrder) {
        long start = positions.getStartPosition(unit, tree);
        long end = positions.getEndPosition(unit, tree);
        if (start < 0 || end < 0) throw new IllegalStateException("Missing declaration source positions");
        if (tree.getKind() != Tree.Kind.CLASS && tree.getKind() != Tree.Kind.INTERFACE) {
            throw new IllegalStateException("Cannot add type parameters to " + tree.getKind());
        }
        if (paramOrder.isEmpty()) return;
        int nameToken = -1;
        for (int i = firstTokenAt((int) start); i + 1 < tokens.size()
                && tokens.get(i).start() < end; i++) {
            if (Set.of("class", "interface", "enum", "record").contains(tokens.get(i).text())
                    && tokens.get(i + 1).text().equals(tree.getSimpleName().toString())) {
                nameToken = i + 1;
                break;
            }
        }
        if (nameToken < 0) throw new IllegalStateException("Cannot locate declaration name: " + tree.getSimpleName());
        if (tree.getTypeParameters().isEmpty()) {
            if (paramOrder.stream().anyMatch(p -> !"true".equals(p[1]))) {
                throw new IllegalStateException("Missing original Javadoc parameters for " + tree.getSimpleName());
            }
            String parameters = "<" + String.join(", ", paramOrder.stream().map(p -> p[0]).toList()) + ">";
            addInsertion(tokens.get(nameToken).end(), parameters);
            return;
        }

        var existing = tree.getTypeParameters();
        int nextExisting = 0;
        for (String[] param : paramOrder) {
            if (nextExisting < existing.size()
                    && existing.get(nextExisting).getName().contentEquals(param[0])) {
                nextExisting++;
                continue;
            }
            if (!"true".equals(param[1])) {
                throw new IllegalStateException("Missing original Javadoc parameter " + param[0]);
            }
            boolean beforeExisting = nextExisting < existing.size();
            int insertion = beforeExisting
                    ? (int) positions.getStartPosition(unit, existing.get(nextExisting))
                    : (int) positions.getEndPosition(unit, existing.get(existing.size() - 1));
            addInsertion(insertion, beforeExisting ? param[0] + ", " : ", " + param[0]);
        }
        if (nextExisting != existing.size()) {
            throw new IllegalStateException("Inconsistent Javadoc parameter order for " + tree.getSimpleName());
        }
    }

    private int firstTokenAt(int position) {
        for (int i = 0; i < tokens.size(); i++) {
            if (tokens.get(i).end() > position) return i;
        }
        return tokens.size();
    }

    private void addInsertion(int position, String text) {
        addInsertion(position, text, false);
    }

    private void addInsertion(int position, String text, boolean beforeExisting) {
        if (position < 0 || position > source.length()) {
            throw new IllegalStateException("Invalid Javadoc source position: " + position);
        }
        for (int i = 0; i < edits.size(); i++) {
            TextEdit edit = edits.get(i);
            if (edit.start == position && edit.end == position) {
                edits.set(i, new TextEdit(position, position,
                        beforeExisting ? text + edit.replacement : edit.replacement + text));
                return;
            }
        }
        edits.add(new TextEdit(position, position, text));
    }

    /** Applies composed insertions in reverse source order, preserving all unpatched text. */
    String apply() {
        edits.sort(Comparator.comparingInt(TextEdit::start).reversed()
                .thenComparing(Comparator.comparingInt(TextEdit::end).reversed()));
        StringBuilder result = new StringBuilder(source);
        for (TextEdit edit : edits) result.replace(edit.start, edit.end, edit.replacement);
        return result.toString();
    }

    private record TextEdit(int start, int end, String replacement) {}
}
