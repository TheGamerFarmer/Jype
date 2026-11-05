package fr.thegamerfarmer.jype;

import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeTranslator;

import java.util.*;

import static fr.thegamerfarmer.jype.StaticOwners.*;

/**
 * Rewrites field reads, assignments and method calls through contextual static views.
 * Tracks lexical locals and evaluates receivers/arguments once in Java order; generated accessors are excluded.
 */
final class StaticAccessRewriter extends TreeTranslator {
    private final StaticTypeSupport support;
    private final StaticOwners owners;

    private final Owner owner;
    private final Deque<Map<String, JCTree.JCExpression>> locals = new ArrayDeque<>();
    private Set<String> available = Set.of();
    private boolean writing;
    private JCTree.JCMethodDecl currentMethod;

    StaticAccessRewriter(StaticTypeSupport support, StaticOwners owners, Owner owner) {
        this.support = support;
        this.owners = owners;
        this.owner = owner;
    }

    void rewrite() {
        for (var member : owner.tree.defs) {
            if (member instanceof JCTree.JCClassDecl) continue;
            long flags = member instanceof JCTree.JCMethodDecl method ? method.mods.flags
                    : member instanceof JCTree.JCVariableDecl variable ? variable.mods.flags
                    : member instanceof JCTree.JCBlock block ? block.flags : 0;
            if (support.isStatic(flags) && (member instanceof JCTree.JCVariableDecl || member instanceof JCTree.JCBlock)) {
                continue;
            }
            available = new HashSet<>();
            if (!support.isStatic(flags)) owner.tree.typarams.forEach(p -> available.add(p.name.toString()));
            if (member instanceof JCTree.JCMethodDecl method) {
                method.typarams.forEach(p -> available.add(p.name.toString()));
            }
            // Accessor bodies already implement the storage boundary.
            if (member instanceof JCTree.JCMethodDecl method && support.accessors.contains(method)) continue;
            locals.push(new HashMap<>());
            currentMethod = member instanceof JCTree.JCMethodDecl method ? method : null;
            translate(member);
            currentMethod = null;
            locals.pop();
        }
    }

    @Override public void visitClassDef(JCTree.JCClassDecl tree) { result = tree; }
    @Override public void visitTypeApply(JCTree.JCTypeApply tree) { result = tree; }
    @Override public void visitTypeCast(JCTree.JCTypeCast tree) {
        tree.expr = translate(tree.expr);
        result = tree;
    }
    @Override public void visitNewClass(JCTree.JCNewClass tree) {
        tree.encl = translate(tree.encl);
        tree.args = translate(tree.args);
        result = tree;
    }
    @Override public void visitNewArray(JCTree.JCNewArray tree) {
        tree.dims = translate(tree.dims);
        tree.elems = translate(tree.elems);
        result = tree;
    }
    @Override public void visitIndexed(JCTree.JCArrayAccess tree) {
        boolean previous = writing;
        writing = false;
        tree.indexed = translate(tree.indexed);
        tree.index = translate(tree.index);
        writing = previous;
        result = tree;
    }
    @Override public void visitMethodDef(JCTree.JCMethodDecl tree) {
        for (var param : tree.params) locals.peek().put(param.name.toString(), param.vartype);
        tree.body = translate(tree.body);
        result = tree;
    }
    @Override public void visitBlock(JCTree.JCBlock tree) {
        locals.push(new HashMap<>());
        super.visitBlock(tree);
        locals.pop();
    }
    @Override public void visitLambda(JCTree.JCLambda tree) {
        locals.push(new HashMap<>());
        for (var param : tree.params) locals.peek().put(param.name.toString(), param.vartype);
        tree.body = translate(tree.body);
        locals.pop();
        result = tree;
    }
    @Override public void visitVarDef(JCTree.JCVariableDecl tree) {
        locals.peek().put(tree.name.toString(), tree.vartype);
        tree.init = translate(tree.init);
        result = tree;
    }
    @Override public void visitForeachLoop(JCTree.JCEnhancedForLoop tree) {
        tree.expr = translate(tree.expr);
        locals.push(new HashMap<>());
        locals.peek().put(tree.var.name.toString(), tree.var.vartype);
        tree.body = translate(tree.body);
        locals.pop();
        result = tree;
    }
    @Override public void visitForLoop(JCTree.JCForLoop tree) {
        locals.push(new HashMap<>());
        super.visitForLoop(tree);
        locals.pop();
    }
    @Override public void visitCatch(JCTree.JCCatch tree) {
        locals.push(new HashMap<>());
        locals.peek().put(tree.param.name.toString(), tree.param.vartype);
        tree.body = translate(tree.body);
        locals.pop();
        result = tree;
    }
    @Override public void visitTry(JCTree.JCTry tree) {
        locals.push(new HashMap<>());
        super.visitTry(tree);
        locals.pop();
    }
    @Override public void visitAssign(JCTree.JCAssign tree) {
        Field field = assignedField(tree.lhs);
        ensureContext(field);
        boolean previous = writing;
        writing = true;
        tree.lhs = translate(tree.lhs);
        writing = false;
        tree.rhs = translate(tree.rhs);
        writing = previous;
        result = field != null && field.writer != null && hasContext(field.parameters)
                ? bridgeCall(tree.lhs, field, field.writer, com.sun.tools.javac.util.List.of(tree.rhs)) : tree;
    }
    @Override public void visitIdent(JCTree.JCIdent tree) {
        Field field = local(tree.name.toString()) ? null : owners.field(owner, tree.name.toString(), new HashSet<>());
        if (field == null && !local(tree.name.toString()) && !owners.declaresField(owner, tree.name.toString(), new HashSet<>())) {
            Owner imported = importedOwner(tree.name.toString(), true);
            if (imported != null) field = owners.field(imported, tree.name.toString(), new HashSet<>());
        }
        result = view(tree, field);
    }
    @Override public void visitSelect(JCTree.JCFieldAccess tree) {
        Owner target = target(tree.selected);
        boolean previous = writing;
        writing = false;
        tree.selected = translate(tree.selected);
        writing = previous;
        result = view(tree, target == null ? null : owners.field(target, tree.name.toString(), new HashSet<>()));
    }
    @Override public void visitApply(JCTree.JCMethodInvocation tree) {
        Owner target = tree.meth instanceof JCTree.JCFieldAccess select ? target(select.selected) : owner;
        String name = tree.meth instanceof JCTree.JCFieldAccess select ? select.name.toString()
                : tree.meth instanceof JCTree.JCIdent ident ? ident.name.toString() : "";
        if (tree.meth instanceof JCTree.JCIdent && owners.methodOrder(owner, name, new HashSet<>()).isEmpty()
                && owner.tree.defs.stream().noneMatch(member ->
                member instanceof JCTree.JCMethodDecl method && method.name.contentEquals(name))) {
            Owner imported = importedOwner(name, false);
            if (imported != null) target = imported;
        }
        tree.meth = translate(tree.meth);
        tree.args = translate(tree.args);
        result = tree;
        List<String> order = owners.methodOrder(target, name, new HashSet<>());
        if (tree.typeargs.isEmpty() && !order.isEmpty() && order.stream().allMatch(support.managed::contains)
                && hasContext(order)) {
            tree.typeargs = com.sun.tools.javac.util.List.from(order.stream()
                    .map(n -> contextArgument(n, tree.pos)).toList());
        } else if (!tree.typeargs.isEmpty() && tree.typeargs.size() < order.size()
                && order.stream().filter(p -> !support.managed.contains(p)).count() == tree.typeargs.size()
                && hasContext(List.copyOf(support.managed))) {
            var supplied = tree.typeargs.iterator();
            var arguments = new ArrayList<JCTree.JCExpression>();
            for (String parameter : order) {
                arguments.add(support.managed.contains(parameter) ? contextArgument(parameter, tree.pos)
                        : supplied.next());
            }
            tree.typeargs = com.sun.tools.javac.util.List.from(arguments);
        }
    }
    private JCTree.JCExpression view(JCTree.JCExpression expression, Field field) {
        if (!writing) ensureContext(field);
        if (writing || field == null || !hasContext(field.parameters)) return expression;
        return bridgeCall(expression, field, field.bridge, com.sun.tools.javac.util.List.nil());
    }

    private void ensureContext(Field field) {
        if (field == null || currentMethod == null || !support.isStatic(currentMethod.mods.flags)
                || available.containsAll(support.managed)) return;
        support.extendMethod(owner, currentMethod);
        currentMethod.typarams.forEach(parameter -> available.add(parameter.name.toString()));
    }

    private Field assignedField(JCTree.JCExpression expression) {
        if (expression instanceof JCTree.JCFieldAccess select) {
            Owner target = target(select.selected);
            return target == null ? null : owners.field(target, select.name.toString(), new HashSet<>());
        }
        if (expression instanceof JCTree.JCIdent ident && !local(ident.name.toString())) {
            String name = ident.name.toString();
            Field field = owners.field(owner, name, new HashSet<>());
            if (field != null || owners.declaresField(owner, name, new HashSet<>())) return field;
            Owner imported = importedOwner(name, true);
            return imported == null ? null : owners.field(imported, name, new HashSet<>());
        }
        return null;
    }

    private JCTree.JCExpression bridgeCall(JCTree.JCExpression expression, Field field, String bridge,
                                           com.sun.tools.javac.util.List<JCTree.JCExpression> arguments) {
        support.make.at(expression.pos);
        JCTree.JCExpression method = expression instanceof JCTree.JCFieldAccess select
                ? support.make.Select(select.selected, support.names.fromString(bridge))
                : support.make.Select(support.qualified(field.owner), support.names.fromString(bridge));
        var args = com.sun.tools.javac.util.List.from(field.parameters.stream()
                .map(name -> contextArgument(name, expression.pos)).toList());
        return support.make.Apply(args, method, arguments);
    }

    private boolean hasContext(List<String> order) {
        return order.stream().allMatch(name -> available.contains(name)
                || support.bounds.containsKey(name) && owners.typeParameters(support.bounds.get(name)).isEmpty());
    }

    private JCTree.JCExpression contextArgument(String name, int position) {
        support.make.at(position);
        return available.contains(name) ? support.make.Ident(support.names.fromString(name)) : support.qualified(support.bounds.get(name));
    }

    private boolean local(String name) { return locals.stream().anyMatch(scope -> scope.containsKey(name)); }

    private Owner importedOwner(String name, boolean field) {
        Owner exact = null;
        Owner wildcard = null;
        boolean ambiguousExact = false;
        boolean ambiguousWildcard = false;
        for (var definition : owner.unit.defs) {
            if (!(definition instanceof JCTree.JCImport imported) || !imported.staticImport) continue;
            var select = imported.qualid;
            boolean star = select.name.contentEquals("*");
            if (!star && !select.name.contentEquals(name)) continue;
            Owner candidate = owners.ownerFor(support.resolve.apply(owner.unit, select.selected));
            if (candidate == null || (field ? !owners.declaresField(candidate, name, new HashSet<>())
                    : owners.methodOrder(candidate, name, new HashSet<>()).isEmpty())) continue;
            if (star) {
                if (wildcard != null && wildcard != candidate) ambiguousWildcard = true;
                wildcard = candidate;
            } else {
                if (exact != null && exact != candidate) ambiguousExact = true;
                exact = candidate;
            }
        }
        return exact != null ? ambiguousExact ? null : exact : ambiguousWildcard ? null : wildcard;
    }
    private Owner target(JCTree.JCExpression expression) {
        if (expression instanceof JCTree.JCParens parentheses) return target(parentheses.expr);
        if (expression instanceof JCTree.JCTypeCast cast && cast.clazz instanceof JCTree.JCExpression type) {
            return typeOwner(type);
        }
        if (expression instanceof JCTree.JCMethodInvocation call) {
            Owner declaring = call.meth instanceof JCTree.JCFieldAccess select ? target(select.selected) : owner;
            String name = call.meth instanceof JCTree.JCFieldAccess select ? select.name.toString()
                    : call.meth instanceof JCTree.JCIdent ident ? ident.name.toString() : "";
            if (declaring == null || declaring.tree == null) return null;
            Set<Owner> candidates = new HashSet<>();
            for (var member : declaring.tree.defs) {
                if (!(member instanceof JCTree.JCMethodDecl method) || !method.name.contentEquals(name)
                        || method.params.size() != call.args.size()) continue;
                var type = method.restype;
                if (type instanceof JCTree.JCTypeApply apply) type = apply.clazz;
                Owner candidate = type == null ? null : owners.ownerFor(support.resolve.apply(declaring.unit, type));
                if (candidate == null) return null;
                candidates.add(candidate);
            }
            return candidates.size() == 1 ? candidates.iterator().next() : null;
        }
        if (expression instanceof JCTree.JCIdent ident) {
            String name = ident.name.toString();
            if (name.equals("this")) return owner;
            if (name.equals("super")) return owners.parent(owner);
            for (var scope : locals) {
                if (scope.containsKey(name)) {
                    return typeOwner(scope.get(name));
                }
            }
            for (var member : owner.tree.defs) {
                if (member instanceof JCTree.JCVariableDecl field && field.name.contentEquals(name)) {
                    return typeOwner(field.vartype);
                }
            }
            if (support.managed.contains(name)) return owners.ownerFor(support.bounds.get(name));
        }
        if (expression instanceof JCTree.JCTypeApply apply) expression = apply.clazz;
        return owners.isTypeName(expression) ? owners.ownerFor(support.resolve.apply(owner.unit, expression)) : null;
    }

    private Owner typeOwner(JCTree.JCExpression type) {
        if (type instanceof JCTree.JCTypeApply apply) type = apply.clazz;
        if (type instanceof JCTree.JCIdent parameter && support.managed.contains(parameter.name.toString())) {
            return owners.ownerFor(support.bounds.get(parameter.name.toString()));
        }
        return type == null ? null : owners.ownerFor(support.resolve.apply(owner.unit, type));
    }
}
