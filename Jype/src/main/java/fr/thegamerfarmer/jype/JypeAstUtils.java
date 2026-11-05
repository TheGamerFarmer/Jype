package fr.thegamerfarmer.jype;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.ImportTree;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.Names;
import org.jetbrains.annotations.Nullable;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AST utilities shared by Jype passes.
 *
 * <p>Groups AST node construction ({@code buildTypeRef}, {@code buildTypeArgs},
 * {@code buildSymbolTypeArgs}), type resolution ({@code resolveExtends}), and
 * bound-conflict detection ({@code hasTypeArgConflict},
 * {@code boundClassHasExplicitFBounds}).
 */
class JypeAstUtils {
    private static final Method TYPE_PARAMETER_FACTORY = findTypeParameterFactory();

    private static Method findTypeParameterFactory() {
        for (Method method : TreeMaker.class.getMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if (method.getName().equals("TypeParameter") && (params.length == 2 || params.length == 3)
                    && params[0] == com.sun.tools.javac.util.Name.class
                    && params[1] == com.sun.tools.javac.util.List.class
                    && (params.length == 2 || params[2] == com.sun.tools.javac.util.List.class)) {
                return method;
            }
        }
        throw new IllegalStateException("[Jype] Unsupported javac type-parameter factory on JDK "
                + Runtime.version());
    }

    /**
     * Creates an unbounded parameter at a real source position, before or after Enter.
     * The caller installs any TypeVar and bounds appropriate to its compilation phase.
     * Factory lookup tolerates supported javac overloads, but never silently drops a parameter.
     */
    static JCTree.JCTypeParameter newTypeParameter(TreeMaker make, Names names,
                                                  String name, int position) {
        make.at(position);
        try {
            Object result = TYPE_PARAMETER_FACTORY.getParameterCount() == 2
                    ? TYPE_PARAMETER_FACTORY.invoke(make, names.fromString(name),
                            com.sun.tools.javac.util.List.nil())
                    : TYPE_PARAMETER_FACTORY.invoke(make, names.fromString(name),
                            com.sun.tools.javac.util.List.nil(), com.sun.tools.javac.util.List.nil());
            return (JCTree.JCTypeParameter) result;
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("[Jype] Cannot create javac type parameter " + name
                    + " on JDK " + Runtime.version(), e);
        }
    }

    final TreeMaker make;
    final Names names;
    private final ProcessingEnvironment env;

    /** {@code TypeVar}'s {@code _bound} field, resolved once per JVM and cached. */
    private static volatile Field typeVarBoundField;

    /** Shares one compiler context's tree factories and authoritative element lookup. */
    JypeAstUtils(TreeMaker make, Names names, ProcessingEnvironment env) {
        this.make = make;
        this.names = names;
        this.env = env;
    }

    /** Builds a {@code JCExpression} node (qualified name) from a {@code TypeMirror}. */
    JCTree.JCExpression buildTypeRef(TypeMirror tm) {
        Symbol.ClassSymbol sym = (Symbol.ClassSymbol) ((DeclaredType) tm).asElement();
        return make.QualIdent(sym);
    }

    /**
     * Creates a {@code JCIdent} for a type parameter owned by the current class.
     *
     * <p>Uses the {@code make.Ident(Symbol)} overload when the {@code TypeVar} is
     * available. This pre-resolves the symbol and avoids "cannot find symbol" for
     * injected TypeVars that MemberEnter did not register in scope (it ran before
     * annotation processing).
     */
    JCTree.JCExpression makeIdent(String paramName, @Nullable Type.TypeVar tv) {
        return tv != null ? make.Ident(tv.tsym) : make.Ident(names.fromString(paramName));
    }

    /**
     * Builds the AST type-argument list for a generic type.
     *
     * @param ownTypeVars name-to-TypeVar map for type parameters owned by the current class
     *                    (including TypeVars injected in pass 1)
     * @return the list, or {@code null} if an unowned Jype parameter is absent from {@code resolvedTypes}
     */
    @Nullable List<JCTree.JCExpression> buildTypeArgs(
            List<? extends TypeParameterElement> typeParams,
            Set<String> ownTypeParamNames,
            Map<String, TypeMirror> resolvedTypes,
            @Nullable Map<String, Type.TypeVar> ownTypeVars) {
        List<JCTree.JCExpression> args = new ArrayList<>();
        for (TypeParameterElement tp : typeParams) {
            String paramName = tp.getSimpleName().toString();
            if (ownTypeParamNames.contains(paramName)) {
                Type.TypeVar tv = ownTypeVars != null ? ownTypeVars.get(paramName) : null;
                args.add(makeIdent(paramName, tv));
            } else {
                TypeMirror resolved = resolvedTypes.get(paramName);
                if (resolved == null) return null;
                args.add(buildTypeRef(resolved));
            }
        }
        return args;
    }

    /**
     * Builds the {@link Type} list for a bound's symbol-level type arguments.
     *
     * @return the list, or {@code null} if an unowned Jype parameter is absent from {@code resolvedTypes}
     */
    @Nullable com.sun.tools.javac.util.List<Type> buildSymbolTypeArgs(
            List<? extends TypeParameterElement> typeParams,
            Map<String, Type.TypeVar> ownTypeVars,
            Map<String, TypeMirror> resolvedTypes) {
        com.sun.tools.javac.util.List<Type> args = com.sun.tools.javac.util.List.nil();
        for (TypeParameterElement bp : typeParams) {
            String bpName = bp.getSimpleName().toString();
            if (ownTypeVars.containsKey(bpName)) {
                args = args.append(ownTypeVars.get(bpName));
            } else {
                TypeMirror concrete = resolvedTypes.get(bpName);
                if (concrete == null) return null;
                args = args.append((Type) concrete);
            }
        }
        return args;
    }

    /**
     * {@code true} if injecting type arguments would cause a bound conflict:
     * at least one Jype parameter not owned by the current class has a bound
     * class with explicit F-bounds, which would prevent injecting it as raw.
     *
     * <p>Also detects bound classes that have Jype parameters themselves (even
     * if their F-bounds have not yet been injected), making this order-independent.
     */
    boolean hasTypeArgConflict(List<? extends TypeParameterElement> typeParams,
                                Set<String> ownTypeParamNames,
                                Map<String, TypeMirror> resolvedTypes) {
        for (TypeParameterElement tp : typeParams) {
            String n = tp.getSimpleName().toString();
            if (!resolvedTypes.containsKey(n)) continue;
            if (ownTypeParamNames.contains(n)) continue;
            TypeElement boundClass = (TypeElement) ((DeclaredType) resolvedTypes.get(n)).asElement();
            if (boundClassHasExplicitFBounds(boundClass)) return true;
            // Even if bounds have not yet been injected, a bound class with Jype
            // parameters will receive them — treat this as a conflict to avoid
            // premature injection.
            for (TypeParameterElement boundTp : boundClass.getTypeParameters()) {
                if (resolvedTypes.containsKey(boundTp.getSimpleName().toString())) return true;
            }
        }
        return false;
    }

    /**
     * {@code true} if the class has at least one type parameter with an
     * explicit, non-trivial bound (other than {@code java.lang.Object}).
     */
    boolean boundClassHasExplicitFBounds(TypeElement cls) {
        for (TypeParameterElement tp : cls.getTypeParameters()) {
            List<? extends TypeMirror> bounds = tp.getBounds();
            if (!bounds.isEmpty()
                    && !(bounds.size() == 1 && bounds.getFirst().toString().equals("java.lang.Object"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resolves the {@link TypeElement} from an {@code extends} clause or field type.
     *
     * <p>Search order: direct qualified name, explicit imports, same package,
     * wildcard imports.
     */
    @Nullable TypeElement resolveExtends(JCTree.JCExpression extending, CompilationUnitTree cu) {
        if (extending instanceof JCTree.JCIdent id && id.sym instanceof TypeElement type) return type;
        if (extending instanceof JCTree.JCFieldAccess access && access.sym instanceof TypeElement type) return type;
        String simpleName = extending.toString();
        TypeElement direct = env.getElementUtils().getTypeElement(simpleName);
        if (direct != null && simpleName.contains(".")) return direct;

        for (ImportTree imp : cu.getImports()) {
            String fqn = imp.getQualifiedIdentifier().toString();
            if (fqn.endsWith(".*")) continue;
            String importedName = fqn.substring(fqn.lastIndexOf('.') + 1);
            if (simpleName.equals(importedName) || simpleName.startsWith(importedName + ".")) {
                return env.getElementUtils().getTypeElement(fqn + simpleName.substring(importedName.length()));
            }
        }

        ExpressionTree pkg = cu.getPackageName();
        if (pkg != null) {
            TypeElement te = env.getElementUtils().getTypeElement(pkg + "." + simpleName);
            if (te != null) return te;
        }

        Set<TypeElement> candidates = new java.util.LinkedHashSet<>();
        TypeElement javaLang = env.getElementUtils().getTypeElement("java.lang." + simpleName);
        if (javaLang != null) candidates.add(javaLang);
        for (ImportTree imp : cu.getImports()) {
            String fqn = imp.getQualifiedIdentifier().toString();
            if (fqn.endsWith(".*")) {
                String pkgName = fqn.substring(0, fqn.length() - 2);
                TypeElement te = env.getElementUtils().getTypeElement(pkgName + "." + simpleName);
                if (te != null) candidates.add(te);
            }
        }

        return candidates.size() == 1 ? candidates.iterator().next() : null;
    }

    /**
     * Writes {@code bound} to a {@code TypeVar} using reflection.
     * Java 25 makes the {@code _bound} field inaccessible by default;
     * {@code setAccessible} is called once and the {@code Field} is cached.
     */
    static void setTypeVarBound(Type.TypeVar tv, Type bound) {
        try {
            Field f = typeVarBoundField;
            if (f == null) {
                synchronized (JypeAstUtils.class) {
                    f = typeVarBoundField;
                    if (f == null) {
                        f = Type.TypeVar.class.getDeclaredField("_bound");
                        f.setAccessible(true);
                        typeVarBoundField = f;
                    }
                }
            }
            f.set(tv, bound);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException(
                    "[Jype] Cannot update a javac parameter's internal bound; "
                            + "check the compiler module-opening options.",
                    e);
        }
    }
}
