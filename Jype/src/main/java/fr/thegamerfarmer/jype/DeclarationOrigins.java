package fr.thegamerfarmer.jype;

import com.sun.tools.javac.code.Attribute;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.util.Pair;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.TypeElement;
import java.util.*;

/** Task-local source provenance captured before mutation, also covering later processing rounds. */
final class DeclarationOrigins {
    private final Map<String, List<String>> declared = new LinkedHashMap<>();
    private record MemberOrigin(String type, List<String> parameters) {}
    private final Map<String, Map<String, MemberOrigin>> members = new LinkedHashMap<>();
    private final Map<String, Map<JCTree.JCMethodDecl, MemberOrigin>> methodOrigins = new LinkedHashMap<>();
    private final Set<Symbol> published = Collections.newSetFromMap(new IdentityHashMap<>());

    List<String[]> parameterOrder(String name, Set<String> managed) {
        return declared.getOrDefault(name, List.of()).stream()
                .map(parameter -> new String[]{parameter, Boolean.toString(managed.contains(parameter))}).toList();
    }

    void capture(JCTree.JCClassDecl tree, String qualifiedName) {
        if (declared.containsKey(qualifiedName)) return;
        declared.put(qualifiedName, tree.typarams.stream().map(p -> p.name.toString()).toList());
        Map<String, MemberOrigin> originals = members.computeIfAbsent(qualifiedName, ignored -> new LinkedHashMap<>());
        Map<JCTree.JCMethodDecl, MemberOrigin> methods = methodOrigins.computeIfAbsent(qualifiedName,
                ignored -> new IdentityHashMap<>());
        for (var member : tree.defs) {
            if (member instanceof JCTree.JCClassDecl nested) {
                capture(nested, qualifiedName + "." + nested.name);
            } else if (member instanceof JCTree.JCMethodDecl method && method.restype != null) {
                MemberOrigin origin = new MemberOrigin(method.restype.toString(),
                        method.typarams.stream().map(p -> p.name.toString()).toList());
                methods.put(method, origin);
                originals.putIfAbsent(methodKey(method), origin);
            } else if (member instanceof JCTree.JCVariableDecl field) {
                originals.putIfAbsent(field.name.toString(), new MemberOrigin(field.vartype.toString(), List.of()));
            }
        }
    }

    private static String methodKey(JCTree.JCMethodDecl method) {
        return method.name + "(" + String.join(",", method.params.stream().map(p -> p.name.toString()).toList()) + ")";
    }

    void registerAccessor(String owner, JCTree.JCMethodDecl method, JCTree.JCVariableDecl field, boolean getter) {
        Map<String, MemberOrigin> originals = members.get(owner);
        MemberOrigin origin = originals == null ? null : originals.get(field.name.toString());
        if (origin != null) originals.putIfAbsent(methodKey(method),
                new MemberOrigin(getter ? origin.type() : "void", List.of()));
        if (origin != null) methodOrigins.get(owner).put(method,
                new MemberOrigin(getter ? origin.type() : "void", List.of()));
    }

    void publishMembers(TypeElement element, ProcessingEnvironment env) {
        Symbol.ClassSymbol annotation = (Symbol.ClassSymbol) env.getElementUtils()
                .getTypeElement("fr.thegamerfarmer.jype.annotations.JypeMember");
        Map<String, MemberOrigin> originals = members.getOrDefault(element.getQualifiedName().toString(), Map.of());
        Map<Symbol, MemberOrigin> methods = new IdentityHashMap<>();
        methodOrigins.getOrDefault(element.getQualifiedName().toString(), Map.of())
                .forEach((tree, origin) -> methods.put(tree.sym, origin));
        for (var member : element.getEnclosedElements()) {
            Symbol symbol;
            String key;
            if (member instanceof javax.lang.model.element.ExecutableElement method
                    && member.getKind() == javax.lang.model.element.ElementKind.METHOD) {
                symbol = (Symbol) member;
                key = method.getSimpleName() + "(" + String.join(",", method.getParameters().stream()
                        .map(p -> p.getSimpleName().toString()).toList()) + ")";
            } else if (member instanceof javax.lang.model.element.VariableElement field) {
                symbol = (Symbol) member;
                key = field.getSimpleName().toString();
            } else continue;
            if (symbol == null) continue;
            MemberOrigin origin = methods.getOrDefault(symbol, originals.get(key));
            boolean generated = origin == null && key.startsWith("$jype$");
            // Lombok and other processors retain their generated business members.
            if (origin == null && !generated) continue;
            if (!published.add(symbol)) continue;
            var values = com.sun.tools.javac.util.List.<Pair<Symbol.MethodSymbol, Attribute>>nil();
            for (Symbol attribute : annotation.members().getSymbols()) {
                if (!(attribute instanceof Symbol.MethodSymbol method)) continue;
                Attribute value = switch (method.name.toString()) {
                    case "version" -> new Attribute.Constant(method.getReturnType(), 1);
                    case "sourceType" -> new Attribute.Constant(method.getReturnType(), origin == null ? "" : origin.type());
                    case "declaredParameters" -> strings(method.getReturnType(), origin == null ? List.of() : origin.parameters());
                    case "generated" -> new Attribute.Constant(method.getReturnType(), generated ? 1 : 0);
                    default -> throw new IllegalStateException("Unknown JypeMember member: " + method.name);
                };
                values = values.append(Pair.of(method, value));
            }
            symbol.appendAttributes(com.sun.tools.javac.util.List.of(new Attribute.Compound(annotation.type, values)));
        }
    }

    void publish(TypeElement element, Set<String> managed, String typeSystem,
                 ProcessingEnvironment env) {
        Symbol.ClassSymbol symbol = (Symbol.ClassSymbol) element;
        if (!published.add(symbol)) return;
        String name = element.getQualifiedName().toString();
        List<String> parameters = element.getTypeParameters().stream()
                .map(p -> p.getSimpleName().toString()).filter(managed::contains).toList();
        Symbol.ClassSymbol annotation = (Symbol.ClassSymbol) env.getElementUtils()
                .getTypeElement("fr.thegamerfarmer.jype.annotations.JypeClass");
        com.sun.tools.javac.util.List<Pair<Symbol.MethodSymbol, Attribute>> values =
                com.sun.tools.javac.util.List.nil();
        for (Symbol member : annotation.members().getSymbols()) {
            if (!(member instanceof Symbol.MethodSymbol method)) continue;
            Attribute value = switch (method.name.toString()) {
                case "version" -> new Attribute.Constant(method.getReturnType(), 1);
                case "typeSystem" -> new Attribute.Constant(method.getReturnType(), typeSystem);
                case "managedParameters" -> strings(method.getReturnType(), parameters);
                case "declaredParameters" -> strings(method.getReturnType(), declared.getOrDefault(name, List.of()));
                default -> throw new IllegalStateException("Unknown JypeClass metadata member: " + method.name);
            };
            values = values.append(Pair.of(method, value));
        }
        symbol.appendAttributes(com.sun.tools.javac.util.List.of(new Attribute.Compound(annotation.type, values)));
    }

    private static Attribute.Array strings(Type array, List<String> names) {
        Type component = ((Type.ArrayType) array).elemtype;
        return new Attribute.Array(array, names.stream()
                .map(name -> new Attribute.Constant(component, name)).toArray(Attribute[]::new));
    }
}
