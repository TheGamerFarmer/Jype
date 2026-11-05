package fr.thegamerfarmer.jype;

import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.Names;
import java.util.*;

/**
 * Constructs symbol-free tree names and unbounded parameters before Enter.
 * All factories use the caller's compiler context and source positions.
 */
final class PreEnterTrees {
    private PreEnterTrees() {}

    static String getSimpleName(JCTree.JCExpression expr) {
        if (expr instanceof JCTree.JCIdent) {
            return ((JCTree.JCIdent) expr).name.toString();
        }
        if (expr instanceof JCTree.JCFieldAccess) {
            return ((JCTree.JCFieldAccess) expr).name.toString();
        }
        return null;
    }

    /**
     * Returns the compilation unit's package name (e.g. {@code "fr.pkg"}).
     * Uses {@code cu.getPackageName()} from the {@code CompilationUnitTree} interface.
     */
    static String getCuPackage(JCTree.JCCompilationUnit cu) {
        com.sun.source.tree.ExpressionTree pkgTree = cu.getPackageName();
        if (pkgTree != null) return pkgTree.toString();
        return "";
    }

    /**
     * Builds an AST node representing a qualified name (e.g. {@code fr.pkg.Foo}).
     *
     * <p>For a simple name (without a dot), returns a {@code JCIdent}.
     * For a qualified name, builds a chain of {@code JCFieldAccess} nodes.
     */
    static JCTree.JCExpression buildQualifiedName(String fqn, TreeMaker make, Names names) {
        String[] parts = fqn.split("\\.", -1);
        JCTree.JCExpression expr = make.Ident(names.fromString(parts[0]));
        for (int i = 1; i < parts.length; i++) {
            expr = make.Select(expr, names.fromString(parts[i]));
        }
        return expr;
    }

    /**
     * Creates an unbounded {@code JCTypeParameter} through reflection on {@code TreeMaker},
     * to tolerate signature variations across javac versions.
     *
     * <p>Attempts, in order:
     * <ol>
     *   <li>{@code TypeParameter(Name, List)} — forme standard pre-Java 25.</li>
     *   <li>{@code TypeParameter(Name, List, List)} — variante 3-args.</li>
     * </ol>
     *
     * <p>Note: {@code TypeParam(Name, TypeVar)} is not attempted here because this
     * runs at {@code AFTER_PARSE}, before TypeVar symbols exist; Enter will create
     * them naturally from the injected AST node.
     */
    static JCTree.JCTypeParameter makeTypeParam(String paramName, int pos,
                                                  TreeMaker make, Names names) {
        return JypeAstUtils.newTypeParameter(make, names, paramName, pos);
    }
}
