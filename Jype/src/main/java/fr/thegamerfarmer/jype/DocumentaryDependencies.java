package fr.thegamerfarmer.jype;

import fr.thegamerfarmer.jype.annotations.JypeClass;
import fr.thegamerfarmer.jype.annotations.JypeMember;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.*;
import javax.lang.model.type.*;
import java.util.*;
import java.util.function.BiConsumer;

/** Builds doc-only declaration shells for metadata-bearing compiled ancestors, never runtime classes. */
final class DocumentaryDependencies {
    private final ProcessingEnvironment env;
    private final Map<String, TypeElement> dependencies = new LinkedHashMap<>();
    private final BiConsumer<String, Set<String>> aliases;

    DocumentaryDependencies(ProcessingEnvironment env, BiConsumer<String, Set<String>> aliases) {
        this.env = env;
        this.aliases = aliases;
    }

    void collect(TypeMirror mirror) {
        if (mirror instanceof ArrayType array) { collect(array.getComponentType()); return; }
        if (!(mirror instanceof DeclaredType type) || !(type.asElement() instanceof TypeElement element)) return;
        type.getTypeArguments().forEach(this::collect);
        JypeClass metadata = element.getAnnotation(JypeClass.class);
        if (metadata == null) return;
        if (metadata.version() != 1) throw new IllegalArgumentException("Unsupported compiled JypeClass version");
        if (dependencies.putIfAbsent(element.getQualifiedName().toString(), element) != null) return;
        aliases.accept(env.getElementUtils().getPackageOf(element).getQualifiedName().toString(),
                new LinkedHashSet<>(Arrays.asList(metadata.managedParameters())));
        collect(element.getSuperclass());
        element.getInterfaces().forEach(this::collect);
        for (Element member : element.getEnclosedElements()) {
            if (member instanceof VariableElement field) collect(field.asType());
            if (member instanceof ExecutableElement method) {
                collect(method.getReturnType());
                method.getParameters().forEach(parameter -> collect(parameter.asType()));
            }
        }
    }

    Map<String, String> sources() {
        Map<String, String> result = new LinkedHashMap<>();
        for (TypeElement element : dependencies.values()) {
            if (element.getNestingKind() != NestingKind.TOP_LEVEL) continue;
            String pkg = env.getElementUtils().getPackageOf(element).getQualifiedName().toString();
            result.put(element.getQualifiedName().toString(), "package " + pkg + ";\n" + declaration(element));
        }
        return result;
    }

    Map<String, List<String[]>> parameterOrders(boolean original) {
        Map<String, List<String[]>> result = new LinkedHashMap<>();
        dependencies.forEach((name, element) -> {
            JypeClass metadata = element.getAnnotation(JypeClass.class);
            Set<String> managed = Set.of(metadata.managedParameters());
            List<String> names = original ? Arrays.asList(metadata.declaredParameters())
                    : element.getTypeParameters().stream().map(p -> p.getSimpleName().toString()).toList();
            result.put(name, names.stream().map(parameter ->
                    new String[]{parameter, Boolean.toString(managed.contains(parameter))}).toList());
        });
        return result;
    }

    private String declaration(TypeElement element) {
        boolean contract = element.getKind() == ElementKind.INTERFACE;
        JypeClass metadata = element.getAnnotation(JypeClass.class);
        Set<String> managed = metadata == null ? Set.of() : Set.of(metadata.managedParameters());
        StringBuilder source = new StringBuilder("public ");
        if (element.getModifiers().contains(Modifier.STATIC)) source.append("static ");
        source.append(contract ? "interface " : "abstract class ").append(element.getSimpleName());
        source.append(parameters(element.getTypeParameters(), managed));
        String parent = type(element.getSuperclass());
        if (!contract && !parent.equals("none") && !parent.equals("java.lang.Object")) source.append(" extends ").append(parent);
        if (!element.getInterfaces().isEmpty()) source.append(contract ? " extends " : " implements ")
                .append(String.join(", ", element.getInterfaces().stream().map(this::type).toList()));
        source.append(" {\n");
        for (Element member : element.getEnclosedElements()) {
            if (member.getModifiers().contains(Modifier.PRIVATE)) continue;
            if (member instanceof TypeElement nested) {
                source.append(declaration(nested));
                continue;
            }
            JypeMember origin = member.getAnnotation(JypeMember.class);
            if (origin != null && origin.version() != 1) throw new IllegalArgumentException("Unsupported JypeMember version");
            if (origin != null && origin.generated()) continue;
            if (member instanceof ExecutableElement method && member.getKind() == ElementKind.METHOD) {
                Set<String> hidden = origin == null ? Set.of() : new HashSet<>(method.getTypeParameters().stream()
                        .map(p -> p.getSimpleName().toString()).toList());
                if (origin != null) hidden.removeAll(Arrays.asList(origin.declaredParameters()));
                source.append("public ");
                boolean staticMethod = member.getModifiers().contains(Modifier.STATIC);
                if (staticMethod) source.append("static ");
                else if (!contract) source.append("abstract ");
                source.append(parameters(method.getTypeParameters(), hidden)).append(" ")
                        .append(sourceType(method.getReturnType(), origin)).append(" ").append(method.getSimpleName())
                        .append("(").append(String.join(", ", method.getParameters().stream()
                                .map(p -> type(p.asType()) + " " + p.getSimpleName()).toList())).append(")");
                if (!method.getThrownTypes().isEmpty()) source.append(" throws ")
                        .append(String.join(", ", method.getThrownTypes().stream().map(this::type).toList()));
                source.append(staticMethod ? " { throw new UnsupportedOperationException(); }\n" : ";\n");
            } else if (member instanceof VariableElement field && member.getKind() == ElementKind.FIELD) {
                source.append("public ");
                if (member.getModifiers().contains(Modifier.STATIC)) source.append("static ");
                source.append(sourceType(field.asType(), origin)).append(" ").append(field.getSimpleName());
                if (contract) source.append(" = ").append(field.asType().getKind().isPrimitive() ? "0" : "null");
                source.append(";\n");
            }
        }
        return source.append("}\n").toString();
    }

    private String sourceType(TypeMirror mirror, JypeMember origin) {
        if (origin == null || origin.sourceType().isEmpty()) return type(mirror);
        Map<String, String> qualified = new HashMap<>();
        collectNames(mirror, qualified);
        String original = origin.sourceType();
        StringBuilder result = new StringBuilder(original);
        var tokens = JavadocSourcePatcher.tokenize(original);
        for (int i = tokens.size() - 1; i >= 0; i--) {
            var token = tokens.get(i);
            if (i > 0 && tokens.get(i - 1).text().equals(".")) continue;
            String replacement = qualified.get(token.text());
            if (replacement != null) result.replace(token.start(), token.end(), replacement);
        }
        return result.toString();
    }

    private void collectNames(TypeMirror mirror, Map<String, String> names) {
        if (mirror instanceof DeclaredType declared) {
            TypeElement element = (TypeElement) declared.asElement();
            names.put(element.getSimpleName().toString(), element.getQualifiedName().toString());
            declared.getTypeArguments().forEach(argument -> collectNames(argument, names));
        } else if (mirror instanceof ArrayType array) collectNames(array.getComponentType(), names);
    }

    private String parameters(List<? extends TypeParameterElement> parameters, Set<String> hidden) {
        List<String> visible = new ArrayList<>();
        for (var parameter : parameters) {
            if (hidden.contains(parameter.getSimpleName().toString())) continue;
            List<String> bounds = parameter.getBounds().stream().map(this::type)
                    .filter(bound -> !bound.equals("java.lang.Object")).toList();
            visible.add(parameter.getSimpleName() + (bounds.isEmpty() ? "" : " extends " + String.join(" & ", bounds)));
        }
        return visible.isEmpty() ? "" : "<" + String.join(", ", visible) + ">";
    }

    private String type(TypeMirror mirror) {
        if (mirror instanceof DeclaredType declared) {
            TypeElement element = (TypeElement) declared.asElement();
            JypeClass metadata = element.getAnnotation(JypeClass.class);
            Set<String> managed = metadata == null ? Set.of() : Set.of(metadata.managedParameters());
            List<String> arguments = new ArrayList<>();
            for (int i = 0; i < declared.getTypeArguments().size(); i++) {
                if (!managed.contains(element.getTypeParameters().get(i).getSimpleName().toString())) {
                    arguments.add(type(declared.getTypeArguments().get(i)));
                }
            }
            String name = declared.getEnclosingType() instanceof DeclaredType enclosing
                    ? type(enclosing) + "." + element.getSimpleName() : element.getQualifiedName().toString();
            return name + (arguments.isEmpty() ? "" : "<" + String.join(", ", arguments) + ">");
        }
        if (mirror instanceof ArrayType array) return type(array.getComponentType()) + "[]";
        if (mirror instanceof WildcardType wildcard) {
            return wildcard.getExtendsBound() != null ? "? extends " + type(wildcard.getExtendsBound())
                    : wildcard.getSuperBound() != null ? "? super " + type(wildcard.getSuperBound()) : "?";
        }
        return mirror.toString();
    }
}
