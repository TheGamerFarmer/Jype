package fr.thegamerfarmer.jype;

import com.sun.source.util.Trees;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.Names;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import java.util.*;

/**
 * Pass 1 — injects missing Jype type parameters into partially managed classes.
 *
 * <p>A class has <em>partial ownership</em> if it has SOME Jype type parameters
 * (from {@code @TypeSystem}) but not ALL of them. For each missing parameter, this pass:
 * <ol>
 *   <li>Creates a {@code Type.TypeVar} and adds it to {@code ClassType.typarams_field}
 *       so {@code getTypeParameters()} sees it in pass 2.</li>
 *   <li>Enters the corresponding {@code TypeVariableSymbol} into the class scope.</li>
 *   <li>Creates the corresponding {@code JCTypeParameter} node (through
 *       {@link #newJCTypeParameter}) and adds it to {@code classDecl.typarams}.</li>
 * </ol>
 *
 * <p>Injected parameter names and the original parameter order before injection
 * are stored in {@link #getInjectedTypeParams()} and {@link #getOriginalParamOrders()}
 * for use by pass 2 and Javadoc source generation.
 */
class TypeParamInjector {

    private final Trees trees;
    private final TreeMaker make;
    private final Names names;
    private final ProcessingEnvironment env;

    /**
     * Parameters injected by Jype in pass 1 into partially managed classes.
     * Key: qualified class name; value: injected parameter names (insertion order preserved).
     * Cleared at the start of each round through {@link #clearRound()}.
     */
    private final Map<String, Set<String>> injectedTypeParams = new LinkedHashMap<>();

    /**
     * For each class receiving Jype parameters in pass 1: the ordered original
     * parameters (before injection), represented as
     * {@code [paramName, "true" if Jype parameter, "false" otherwise]}.
     * Key: qualified class name. Never cleared (used in the final round).
     */
    private final Map<String, List<String[]>> originalParamOrders = new LinkedHashMap<>();

    /** Creates round-local injection state while retaining original orders until final processing. */
    TypeParamInjector(Trees trees, TreeMaker make, Names names, ProcessingEnvironment env) {
        this.trees = trees;
        this.make = make;
        this.names = names;
        this.env = env;
    }

    /** Clears the injection table at the start of a new round. */
    void clearRound() {
        injectedTypeParams.clear();
    }

    /** Returns the injected-parameter table (direct reference, not a copy). */
    Map<String, Set<String>> getInjectedTypeParams() {
        return injectedTypeParams;
    }

    /** Returns the original parameter-order table (direct reference, not a copy). */
    Map<String, List<String[]>> getOriginalParamOrders() {
        return originalParamOrders;
    }

    /**
     * If the class has SOME but not ALL Jype parameters (partial ownership),
     * injects the missing parameters as new {@code JCTypeParameter} nodes with
     * empty bounds (filled by {@link TypeInjectionPass#processClass} in pass 2)
     * and records the names in {@link #injectedTypeParams}.
     *
     * <p>Concrete classes (with no Jype parameters) are not affected:
     * {@code ownsAny = false}, so the condition does not trigger.
     */
    void injectMissingTypeParams(TypeElement classElement, Map<String, TypeMirror> resolvedTypes) {
        for (Element enclosed : classElement.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.CLASS || enclosed.getKind() == ElementKind.INTERFACE) {
                injectMissingTypeParams((TypeElement) enclosed, resolvedTypes);
            }
        }

        JCTree.JCClassDecl classDecl = (JCTree.JCClassDecl) trees.getTree(classElement);
        if (classDecl == null) return;

        Set<String> ownTypeParamNames = new HashSet<>();
        for (TypeParameterElement tp : classElement.getTypeParameters()) {
            ownTypeParamNames.add(tp.getSimpleName().toString());
        }

        TypeMirror superMirror = classElement.getSuperclass();
        if (!(superMirror instanceof DeclaredType)) return;
        TypeElement superElement = (TypeElement) ((DeclaredType) superMirror).asElement();
        List<? extends TypeParameterElement> superParams = superElement.getTypeParameters();
        if (superParams.isEmpty()) return;

        boolean ownsAny = false, missingAny = false;
        List<String> missingParams = new ArrayList<>();
        for (TypeParameterElement tp : superParams) {
            String n = tp.getSimpleName().toString();
            if (!resolvedTypes.containsKey(n)) continue;
            if (ownTypeParamNames.contains(n)) ownsAny = true;
            else { missingAny = true; missingParams.add(n); }
        }
        if (!missingAny) return;
        if (!ownsAny && !classElement.getModifiers().contains(Modifier.ABSTRACT)) return;

        // For zero-ownership abstract classes, if extends is already fully
        // parameterized with explicit arguments (JCTypeApply argument count ==
        // superclass parameter count), there is no need to inject anything:
        // the user already handles it explicitly.
        if (!ownsAny && classDecl.extending instanceof JCTree.JCTypeApply ta
                && ta.arguments.size() == superParams.size()) {
            return;
        }

        // Record the original order before injection to restore Javadoc sources.
        List<String[]> paramOrder = new ArrayList<>();
        for (TypeParameterElement tp : classElement.getTypeParameters()) {
            String n = tp.getSimpleName().toString();
            paramOrder.add(new String[]{n, resolvedTypes.containsKey(n) ? "true" : "false"});
        }
        originalParamOrders.put(classElement.getQualifiedName().toString(), paramOrder);

        Set<String> injected = new LinkedHashSet<>();
        for (String paramName : missingParams) {
            injectTypeParam(classDecl, classElement, paramName);
            injected.add(paramName);
        }
        injectedTypeParams.put(classElement.getQualifiedName().toString(), injected);

        JypeDiagnostics.debug(env,
                "[Jype] injected type params into " + classElement.getSimpleName()
                + ": " + injected);
    }

    private void injectTypeParam(JCTree.JCClassDecl classDecl,
                                  TypeElement classElement,
                                  String paramName) {
        Symbol.ClassSymbol classSym = (Symbol.ClassSymbol) classElement;

        Type.TypeVar newTypeVar = new Type.TypeVar(
                names.fromString(paramName), classSym, Type.noType);

        JCTree.JCTypeParameter newTypeParam = newJCTypeParameter(paramName, classDecl.pos, newTypeVar);

        // Add to ClassType.typarams_field so getTypeParameters() sees it.
        Type.ClassType classType = (Type.ClassType) classSym.type;
        classType.typarams_field = classType.typarams_field.append(newTypeVar);

        // Enter the TypeVar in scope so Attr can resolve it by name.
        if (classSym.members_field != null) {
            classSym.members_field.enter(newTypeVar.tsym);
        }

        classDecl.typarams = classDecl.typarams.append(newTypeParam);
    }

    /** Builds the node before modifying symbols or their scope. */
    private JCTree.JCTypeParameter newJCTypeParameter(String paramName, int pos,
                                                                  Type.TypeVar typeVar) {
        JCTree.JCTypeParameter parameter = JypeAstUtils.newTypeParameter(make, names, paramName, pos);
        parameter.type = typeVar;
        return parameter;
    }
}
