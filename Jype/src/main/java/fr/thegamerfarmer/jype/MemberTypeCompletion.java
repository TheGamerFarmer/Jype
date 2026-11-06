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

    /** Template-valued library uses need lexical variables before recursive bounds are entered. */
    void injectRequiredClassParameters(JCTree.JCCompilationUnit cu, JCTree.JCClassDecl cls,
                                       Map<String, List<String>> index, Set<String> managed,
                                       Map<String, String> bindings, TreeMaker make, Names names) {
        Set<String> own = new HashSet<>();
        cls.typarams.forEach(parameter -> own.add(parameter.name.toString()));
        boolean[] needed = {false};
        for (var member : cls.defs) {
            if (member instanceof JCTree.JCClassDecl nested) {
                injectRequiredClassParameters(cu, nested, index, managed, bindings, make, names);
                continue;
            }
            if (member instanceof JCTree.JCMethodDecl method && (method.mods.flags & Flags.STATIC) != 0
                    || member instanceof JCTree.JCVariableDecl field && (field.mods.flags & Flags.STATIC) != 0
                    || member instanceof JCTree.JCBlock block && (block.flags & Flags.STATIC) != 0) continue;
            Set<String> available = new HashSet<>(own);
            if (member instanceof JCTree.JCMethodDecl method) {
                method.typarams.forEach(parameter -> available.add(parameter.name.toString()));
            }
            var types = new com.sun.tools.javac.tree.TreeScanner() {
                private void inspect(JCTree.JCExpression expression, int supplied) {
                    List<String> parameters = hierarchy.resolveTypeParams(index, cu, expression);
                    if (parameters == null || parameters.size() <= supplied) return;
                    String target = hierarchy.resolveTypeName(index, cu, expression, true);
                    if (target == null) return;
                    Set<String> technical = managed;
                    if (!index.containsKey(target)) {
                        var data = hierarchy.typeResolver.readClass(target);
                        if (data == null || data.publicView() == null) return;
                        technical = new HashSet<>(data.publicView().managedParameters());
                    }
                    Set<String> technicalParameters = technical;
                    long ordinary = parameters.stream().filter(p -> !technicalParameters.contains(p)).count();
                    if (ordinary != supplied) return;
                    for (String parameter : parameters) {
                        if (!technical.contains(parameter) || available.contains(parameter)) continue;
                        String binding = bindings.get(parameter);
                        List<String> bindingParameters = binding == null ? null : index.get(binding);
                        if (binding != null && bindingParameters == null) {
                            var data = hierarchy.typeResolver.readClass(binding);
                            bindingParameters = data == null ? null : data.parameters();
                        }
                        if (bindingParameters == null || !bindingParameters.isEmpty()) needed[0] = true;
                    }
                }
                @Override public void visitIdent(JCTree.JCIdent tree) { inspect(tree, 0); }
                @Override public void visitSelect(JCTree.JCFieldAccess tree) { inspect(tree, 0); }
                @Override public void visitTypeApply(JCTree.JCTypeApply tree) {
                    inspect(tree.clazz, tree.arguments.size());
                    scan(tree.arguments);
                }
            };
            new com.sun.tools.javac.tree.TreeScanner() {
                @Override public void visitClassDef(JCTree.JCClassDecl tree) {}
                @Override public void visitVarDef(JCTree.JCVariableDecl tree) {
                    types.scan(tree.vartype);
                    scan(tree.init);
                }
                @Override public void visitMethodDef(JCTree.JCMethodDecl tree) {
                    types.scan(tree.restype);
                    tree.typarams.forEach(parameter -> types.scan(parameter.bounds));
                    scan(tree.params);
                    scan(tree.body);
                }
                @Override public void visitNewClass(JCTree.JCNewClass tree) {
                    types.scan(tree.clazz);
                    scan(tree.args);
                }
                @Override public void visitTypeCast(JCTree.JCTypeCast tree) {
                    types.scan(tree.clazz);
                    scan(tree.expr);
                }
            }.scan(member);
        }
        if (needed[0]) {
            for (String parameter : managed) {
                if (!own.contains(parameter)) cls.typarams = cls.typarams.append(
                        PreEnterTrees.makeTypeParam(parameter, cls.pos, make, names));
            }
            hierarchy.updateTypeParamMap(index, cu, cls, cls.typarams.stream().map(p -> p.name.toString()).toList());
        }
    }

    /** Completes member type positions recursively without rewriting nested class bodies twice. */
    void completeMemberTypes(JCTree.JCCompilationUnit cu, JCTree.JCClassDecl cls,
                                      Map<String, List<String>> index, Set<String> managed,
                                      Map<String, String> bindings,
                                      TreeMaker make, Names names) {
        completeMemberTypes(cu, cls, index, managed, bindings, make, names, Set.of());
    }

    private void completeMemberTypes(JCTree.JCCompilationUnit cu, JCTree.JCClassDecl cls,
                                     Map<String, List<String>> index, Set<String> managed,
                                     Map<String, String> bindings, TreeMaker make, Names names,
                                     Set<String> enclosing) {
        Set<String> owner = new HashSet<>(enclosing);
        cls.typarams.forEach(parameter -> owner.add(parameter.name.toString()));
        for (var member : cls.defs) {
            if (member instanceof JCTree.JCClassDecl inner) {
                completeMemberTypes(cu, inner, index, managed, bindings, make, names);
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
                private Set<String> technical(JCTree.JCExpression type) {
                    String target = hierarchy.resolveTypeName(index, cu, type, true);
                    if (target == null) return Set.of();
                    if (index.containsKey(target)) return managed;
                    var data = hierarchy.typeResolver.readClass(target);
                    if (data != null && data.publicView() != null) {
                        return new HashSet<>(data.publicView().managedParameters());
                    }
                    return target.startsWith(hierarchy.currentTypeSystem + ".") ? managed : Set.of();
                }

                private JCTree.JCExpression argument(String parameter, int pos) {
                    if (available.contains(parameter)) return make.at(pos).Ident(names.fromString(parameter));
                    String binding = bindings.get(parameter);
                    if (binding == null) return null;
                    JCTree.JCExpression result = null;
                    for (String component : binding.split("\\.")) {
                        result = result == null ? make.at(pos).Ident(names.fromString(component))
                                : make.at(pos).Select(result, names.fromString(component));
                    }
                    List<String> boundParameters = hierarchy.resolveTypeParams(index, cu, result);
                    // Recursive templates require lexical variables; do not erase them to raw types.
                    if (boundParameters != null && !boundParameters.isEmpty()) return null;
                    return result;
                }

                private JCTree.JCExpression raw(JCTree.JCExpression type) {
                    List<String> parameters = hierarchy.resolveTypeParams(index, cu, type);
                    Set<String> technical = technical(type);
                    if (parameters == null || parameters.isEmpty() || !technical.containsAll(parameters)) return type;
                    List<JCTree.JCExpression> arguments = new ArrayList<>();
                    for (String parameter : parameters) {
                        var argument = argument(parameter, type.pos);
                        if (argument == null) return type;
                        arguments.add(argument);
                    }
                    return make.at(type.pos).TypeApply(type, com.sun.tools.javac.util.List.from(arguments));
                }

                @Override public void visitIdent(JCTree.JCIdent type) { result = raw(type); }
                @Override public void visitSelect(JCTree.JCFieldAccess type) { result = raw(type); }
                @Override public void visitTypeApply(JCTree.JCTypeApply apply) {
                    // The class name is not a raw application itself.
                    apply.arguments = translate(apply.arguments);
                    result = apply;
                    List<String> parameters = hierarchy.resolveTypeParams(index, cu, apply.clazz);
                    if (parameters == null || apply.arguments.isEmpty()
                            || apply.arguments.size() >= parameters.size()) return;
                    Set<String> technical = technical(apply.clazz);
                    long ordinary = parameters.stream().filter(parameter -> !technical.contains(parameter)).count();
                    if (ordinary != apply.arguments.size()) return;
                    var supplied = apply.arguments.iterator();
                    List<JCTree.JCExpression> arguments = new ArrayList<>();
                    for (String parameter : parameters) {
                        if (!technical.contains(parameter)) arguments.add(supplied.next());
                        else {
                            var argument = argument(parameter, apply.pos);
                            if (argument == null) argument = make.at(apply.pos).Wildcard(
                                    make.TypeBoundKind(com.sun.tools.javac.code.BoundKind.UNBOUND), null);
                            arguments.add(argument);
                        }
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
                    if (tree.def != null) {
                        completeMemberTypes(cu, tree.def, index, managed, bindings, make, names, available);
                    }
                    result = tree;
                }
            }.translate(member);
        }
    }
}
