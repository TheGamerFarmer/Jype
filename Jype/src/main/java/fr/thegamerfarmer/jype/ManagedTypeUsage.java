package fr.thegamerfarmer.jype;

import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeScanner;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Finds managed names used in type positions without mistaking value identifiers for types.
 *
 * <p>Runs before attribution, so only simple managed names are eligible. Method type
 * parameters shadow package-managed names. Named member classes are processed separately
 * by the caller; anonymous and local class bodies retain the enclosing method scope.
 */
final class ManagedTypeUsage {
    private ManagedTypeUsage() {}

    /**
     * Collects names requiring class parameters in fields, signatures and method bodies.
     * The input sets and trees are not modified.
     */
    static Set<String> find(JCTree.JCClassDecl declaration, Set<String> managed) {
        Set<String> found = new LinkedHashSet<>();
        for (JCTree member : declaration.defs) {
            if (member instanceof JCTree.JCVariableDecl field) {
                collectType(field.vartype, managed, found);
            } else if (member instanceof JCTree.JCMethodDecl method) {
                scanMethod(method, managed, found);
            }
        }
        return found;
    }

    private static void scanMethod(JCTree.JCMethodDecl method, Set<String> managed, Set<String> found) {
        Set<String> visible = new HashSet<>(managed);
        method.typarams.forEach(parameter -> visible.remove(parameter.name.toString()));
        method.typarams.forEach(parameter -> parameter.bounds.forEach(bound -> collectType(bound, visible, found)));
        collectType(method.restype, visible, found);
        method.params.forEach(parameter -> collectType(parameter.vartype, visible, found));
        bodyScanner(visible, found).scan(method.body);
    }

    private static TreeScanner bodyScanner(Set<String> visible, Set<String> found) {
        return new TreeScanner() {
            @Override
            public void visitClassDef(JCTree.JCClassDecl nested) {
                Set<String> local = new HashSet<>(visible);
                nested.typarams.forEach(parameter -> local.remove(parameter.name.toString()));
                nested.typarams.forEach(parameter -> parameter.bounds.forEach(bound -> collectType(bound, local, found)));
                bodyScanner(local, found).scan(nested.defs);
            }

            @Override
            public void visitVarDef(JCTree.JCVariableDecl variable) {
                collectType(variable.vartype, visible, found);
                scan(variable.init);
            }

            @Override
            public void visitTypeCast(JCTree.JCTypeCast cast) {
                collectType(cast.clazz, visible, found);
                scan(cast.expr);
            }

            @Override
            public void visitNewClass(JCTree.JCNewClass creation) {
                collectType(creation.clazz, visible, found);
                scan(creation.encl);
                scan(creation.args);
                scan(creation.def);
            }

            @Override
            public void visitMethodDef(JCTree.JCMethodDecl nested) {
                scanMethod(nested, visible, found);
            }

            @Override
            public void visitSelect(JCTree.JCFieldAccess select) {
                // ALIAS.member names the alias as a type: its static members are reached through it.
                if (select.selected instanceof JCTree.JCIdent qualifier) collectType(qualifier, visible, found);
                scan(select.selected);
            }
        };
    }

    private static void collectType(JCTree type, Set<String> managed, Set<String> found) {
        new TreeScanner() {
            @Override
            public void visitIdent(JCTree.JCIdent identifier) {
                String name = identifier.name.toString();
                if (managed.contains(name)) found.add(name);
            }

            @Override
            public void visitSelect(JCTree.JCFieldAccess selected) {
                // Qualified names refer to declared types, not package-managed parameters.
            }
        }.scan(type);
    }
}
