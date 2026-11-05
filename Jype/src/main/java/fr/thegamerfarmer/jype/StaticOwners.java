package fr.thegamerfarmer.jype;

import com.sun.tools.javac.tree.JCTree;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Indexes source and compiled owners for static access rewriting.
 * Owners use task-local identity: mutable declaration/member maps must never determine traversal identity.
 */
final class StaticOwners {
    static final String VIEW_PREFIX = "$jype$view$";
    static final String WRITE_PREFIX = "$jype$write$";
    /** Describes the generated read/write boundary; final storage has no writer. */
    static final class Field {
        final String owner, bridge, writer;
        final List<String> parameters;
        Field(String owner, String bridge, String writer, List<String> parameters) {
            this.owner = owner; this.bridge = bridge; this.writer = writer; this.parameters = parameters;
        }
    }
    /** A source owner has a tree/unit; a compiled owner has metadata only and null source trees. */
    static final class Owner {
        final String name;
        final JCTree.JCClassDecl tree;
        final JCTree.JCCompilationUnit unit;
        final Map<String, Field> fields;
        final Map<String, List<String>> methods;
        Owner(String name, JCTree.JCClassDecl tree, JCTree.JCCompilationUnit unit,
              Map<String, Field> fields, Map<String, List<String>> methods) {
            this.name = name; this.tree = tree; this.unit = unit; this.fields = fields; this.methods = methods;
        }
    }

    final Map<String, Owner> owners = new LinkedHashMap<>();
    private final Map<String, TypeSystemClassFileReader.ClassData> compiledClasses = new HashMap<>();
    private final Set<String> managed;
    private final Map<String, List<String>> parameters;
    private final BiFunction<JCTree.JCCompilationUnit, JCTree.JCExpression, String> resolve;
    private final Function<String, TypeSystemClassFileReader.ClassData> readClass;

    /** Shares current source orders and lazily decodes classpath signatures without loading classes. */
    StaticOwners(Set<String> managed, Map<String, List<String>> parameters,
                 BiFunction<JCTree.JCCompilationUnit, JCTree.JCExpression, String> resolve,
                 Function<String, TypeSystemClassFileReader.ClassData> readClass) {
        this.managed = managed; this.parameters = parameters; this.resolve = resolve; this.readClass = readClass;
    }

    /** Looks up method-owned parameter order, respecting declarations that hide inherited methods. */
    List<String> methodOrder(Owner owner, String name, Set<Owner> visited) {
        if (owner == null || !visited.add(owner)) return List.of();
        if (owner.methods.containsKey(name)) return owner.methods.get(name);
        if (owner.tree != null && owner.tree.defs.stream().anyMatch(tree ->
                tree instanceof JCTree.JCMethodDecl method && method.name.contentEquals(name))) return List.of();
        return methodOrder(parent(owner), name, visited);
    }

    /** Accepts source names only, not calls or value-producing expressions requiring separate resolution. */
    boolean isTypeName(JCTree.JCExpression expression) {
        return expression instanceof JCTree.JCIdent
                || expression instanceof JCTree.JCFieldAccess access && isTypeName(access.selected);
    }

    /** Finds a managed field bridge unless an ordinary field declaration hides the inherited one. */
    Field field(Owner owner, String name, Set<Owner> visited) {
        if (!visited.add(owner)) return null;
        if (owner.fields.containsKey(name)) return owner.fields.get(name);
        if (owner.tree == null && compiled(owner.name).fields().contains(name)) return null;
        if (owner.tree != null && owner.tree.defs.stream().anyMatch(tree ->
                tree instanceof JCTree.JCVariableDecl variable && variable.name.contentEquals(name))) return null;
        Owner parent = parent(owner);
        return parent == null ? null : field(parent, name, visited);
    }

    /** Includes ordinary fields, so local owner declarations correctly shadow imported static fields. */
    boolean declaresField(Owner owner, String name, Set<Owner> visited) {
        if (owner == null || !visited.add(owner)) return false;
        if (owner.fields.containsKey(name) || owner.tree == null && compiled(owner.name).fields().contains(name)
                || owner.tree != null && owner.tree.defs.stream().anyMatch(tree ->
                tree instanceof JCTree.JCVariableDecl field && field.name.contentEquals(name))) return true;
        return declaresField(parent(owner), name, visited);
    }

    /** Resolves source or compiled superclass metadata, without initializing user classes. */
    Owner parent(Owner owner) {
        if (owner.tree == null) return ownerFor(compiled(owner.name).parent());
        var type = owner.tree.extending;
        if (type instanceof JCTree.JCTypeApply apply) type = apply.clazz;
        return type == null ? null : ownerFor(resolve.apply(owner.unit, type));
    }

    /** Uses the live source order when present, otherwise the separately compiled generic signature. */
    List<String> typeParameters(String name) {
        if (parameters.containsKey(name)) return parameters.get(name);
        var data = compiled(name);
        return data == null ? List.of() : data.parameters();
    }

    private TypeSystemClassFileReader.ClassData compiled(String name) {
        if (!compiledClasses.containsKey(name)) compiledClasses.put(name, readClass.apply(name));
        return compiledClasses.get(name);
    }

    /** Lazily registers compiled owners and recognizes Jype's encoded field-view method names. */
    Owner ownerFor(String name) {
        if (name == null || name.isEmpty()) return null;
        if (owners.containsKey(name)) return owners.get(name);
        var data = compiled(name);
        if (data == null) {
            owners.put(name, null);
            return null;
        }
        Map<String, Field> fields = new LinkedHashMap<>();
        data.staticMethods().forEach((method, order) -> {
            if (method.startsWith(VIEW_PREFIX) && method.lastIndexOf('$') > VIEW_PREFIX.length()
                    && order.size() == managed.size() && order.containsAll(managed)) {
                String field = method.substring(VIEW_PREFIX.length(), method.lastIndexOf('$'));
                String writer = WRITE_PREFIX + method.substring(VIEW_PREFIX.length());
                fields.put(field, new Field(name, method, data.staticMethods().containsKey(writer) ? writer : null, order));
            }
        });
        Map<String, List<String>> methods = data.parameters().containsAll(managed) || !fields.isEmpty()
                ? data.staticMethods() : Map.of();
        Owner owner = new Owner(name, null, null, fields, methods);
        owners.put(name, owner);
        return owner;
    }
}
