package fr.thegamerfarmer.jype;

import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.tree.JCTree;
import org.jetbrains.annotations.Nullable;

import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.type.TypeMirror;
import java.util.*;

/**
 * Builds mixed superclass arguments after Enter, when TypeVars and injection history exist.
 *
 * <p>Original ordinary arguments stay positional. Managed positions use the current
 * class's TypeVars or resolved package associations, never a new unbound symbol.
 */
final class ProcessorTypeArguments {
    private final JypeAstUtils utils;
    private final Map<String, Set<String>> injectedTypeParams;

    /** Retains the live pass-one table, which is reset at the start of each processing round. */
    ProcessorTypeArguments(JypeAstUtils utils, Map<String, Set<String>> injectedTypeParams) {
        this.utils = utils;
        this.injectedTypeParams = injectedTypeParams;
    }

    /**
     * Completes either original pre-injection arguments or only ordinary arguments.
     *
     * @return the complete order, or null when arity or required concrete associations are missing
     */
    @Nullable List<JCTree.JCExpression> buildMixedTypeArgs(
            TypeElement superEl,
            List<? extends TypeParameterElement> superParams,
            com.sun.tools.javac.util.List<JCTree.JCExpression> providedArgs,
            Set<String> ownTypeParamNames,
            Map<String, TypeMirror> resolvedTypes,
            @Nullable Map<String, Type.TypeVar> ownTypeVars) {
        Set<String> injectedInSuper = injectedTypeParams.getOrDefault(
                superEl.getQualifiedName().toString(), Collections.emptySet());
        int originalCount = 0;
        for (TypeParameterElement tp : superParams) {
            if (!injectedInSuper.contains(tp.getSimpleName().toString())) originalCount++;
        }
        if (providedArgs.size() == originalCount) {
            return buildInjectedArguments(
                    superParams, injectedInSuper, providedArgs,
                    ownTypeParamNames, resolvedTypes, ownTypeVars);
        }
        int nonJypeCount = 0;
        for (TypeParameterElement tp : superParams) {
            String n = tp.getSimpleName().toString();
            if (!resolvedTypes.containsKey(n) && !ownTypeParamNames.contains(n)) nonJypeCount++;
        }
        if (providedArgs.size() != nonJypeCount) return null;
        List<JCTree.JCExpression> full = new ArrayList<>();
        int providedIdx = 0;
        for (TypeParameterElement tp : superParams) {
            String n = tp.getSimpleName().toString();
            if (ownTypeParamNames.contains(n)) {
                Type.TypeVar tv = ownTypeVars != null ? ownTypeVars.get(n) : null;
                full.add(utils.makeIdent(n, tv));
            } else if (resolvedTypes.containsKey(n)) {
                full.add(utils.buildTypeRef(resolvedTypes.get(n)));
            } else {
                full.add(providedArgs.get(providedIdx++));
            }
        }
        return full;
    }

    private @Nullable List<JCTree.JCExpression> buildInjectedArguments(
            List<? extends TypeParameterElement> superParams,
            Set<String> injectedInSuper,
            com.sun.tools.javac.util.List<JCTree.JCExpression> providedArgs,
            Set<String> ownTypeParamNames,
            Map<String, TypeMirror> resolvedTypes,
            @Nullable Map<String, Type.TypeVar> ownTypeVars) {
        List<JCTree.JCExpression> full = new ArrayList<>();
        int providedIdx = 0;
        for (TypeParameterElement tp : superParams) {
            String n = tp.getSimpleName().toString();
            if (injectedInSuper.contains(n)) {
                if (ownTypeParamNames.contains(n)) {
                    Type.TypeVar tv = ownTypeVars != null ? ownTypeVars.get(n) : null;
                    full.add(utils.makeIdent(n, tv));
                } else {
                    TypeMirror concrete = resolvedTypes.get(n);
                    if (concrete == null) return null;
                    full.add(utils.buildTypeRef(concrete));
                }
            } else {
                if (providedIdx >= providedArgs.size()) return null;
                JCTree.JCExpression provided = providedArgs.get(providedIdx++);
                if (resolvedTypes.containsKey(n)) {
                    if (ownTypeParamNames.contains(n)) {
                        Type.TypeVar tv = ownTypeVars != null ? ownTypeVars.get(n) : null;
                        full.add(utils.makeIdent(n, tv));
                    } else {
                        TypeMirror concrete = resolvedTypes.get(n);
                        if (concrete == null) return null;
                        full.add(utils.buildTypeRef(concrete));
                    }
                } else {
                    full.add(provided);
                }
            }
        }
        return full;
    }
}
