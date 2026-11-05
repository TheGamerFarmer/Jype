package fr.thegamerfarmer.jype;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.tree.TreeTranslator;
import com.sun.tools.javac.util.Names;
import java.util.*;

/**
 * Completes partial member type applications using lexical class or method parameters.
 * Unavailable managed positions become wildcards; static members cannot capture class parameters.
 */
final class MemberTypeCompletion {
    private final SourceHierarchy hierarchy;

    /** Uses the final declaration index after static lowering. */
    MemberTypeCompletion(SourceHierarchy hierarchy) { this.hierarchy = hierarchy; }

    /** Completes member type positions recursively without rewriting nested class bodies twice. */
    void completeMemberTypes(JCTree.JCCompilationUnit cu, JCTree.JCClassDecl cls,
                                      Map<String, List<String>> index, Set<String> managed,
                                      TreeMaker make, Names names) {
        Set<String> owner = new HashSet<>();
        cls.typarams.forEach(parameter -> owner.add(parameter.name.toString()));
        for (var member : cls.defs) {
            if (member instanceof JCTree.JCClassDecl inner) {
                completeMemberTypes(cu, inner, index, managed, make, names);
                continue;
            }
            boolean staticMember = member instanceof JCTree.JCVariableDecl field
                    ? (field.mods.flags & Flags.STATIC) != 0
                    : member instanceof JCTree.JCMethodDecl method ? (method.mods.flags & Flags.STATIC) != 0
                    : member instanceof JCTree.JCBlock block && (block.flags & Flags.STATIC) != 0;
            Set<String> available = new HashSet<>(staticMember ? Set.of() : owner);
            if (member instanceof JCTree.JCMethodDecl method) {
                method.typarams.forEach(parameter -> available.add(parameter.name.toString()));
            }
            TreeTranslator types = new TreeTranslator() {
                @Override public void visitTypeApply(JCTree.JCTypeApply apply) {
                    super.visitTypeApply(apply);
                    List<String> parameters = hierarchy.resolveTypeParams(index, cu, apply.clazz);
                    if (parameters == null || apply.arguments.isEmpty()
                            || apply.arguments.size() >= parameters.size()) return;
                    long ordinary = parameters.stream().filter(parameter -> !managed.contains(parameter)).count();
                    if (ordinary != apply.arguments.size()) return;
                    var supplied = apply.arguments.iterator();
                    List<JCTree.JCExpression> arguments = new ArrayList<>();
                    for (String parameter : parameters) {
                        if (!managed.contains(parameter)) arguments.add(supplied.next());
                        else if (available.contains(parameter)) arguments.add(make.at(apply.pos).Ident(names.fromString(parameter)));
                        else arguments.add(make.at(apply.pos).Wildcard(
                                make.TypeBoundKind(com.sun.tools.javac.code.BoundKind.UNBOUND), null));
                    }
                    apply.arguments = com.sun.tools.javac.util.List.from(arguments);
                }
            };
            new TreeTranslator() {
                @Override public void visitClassDef(JCTree.JCClassDecl tree) { result = tree; }
                @Override public void visitVarDef(JCTree.JCVariableDecl tree) {
                    tree.vartype = types.translate(tree.vartype);
                    tree.init = translate(tree.init);
                    result = tree;
                }
                @Override public void visitMethodDef(JCTree.JCMethodDecl tree) {
                    tree.restype = types.translate(tree.restype);
                    tree.typarams.forEach(parameter -> parameter.bounds = types.translate(parameter.bounds));
                    tree.params = translate(tree.params);
                    tree.body = translate(tree.body);
                    result = tree;
                }
                @Override public void visitTypeCast(JCTree.JCTypeCast tree) {
                    tree.clazz = types.translate(tree.clazz);
                    tree.expr = translate(tree.expr);
                    result = tree;
                }
                @Override public void visitNewClass(JCTree.JCNewClass tree) {
                    tree.clazz = types.translate(tree.clazz);
                    tree.args = translate(tree.args);
                    result = tree;
                }
            }.translate(member);
        }
    }
}
