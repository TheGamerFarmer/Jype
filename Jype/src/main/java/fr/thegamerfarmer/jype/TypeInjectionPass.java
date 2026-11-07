package fr.thegamerfarmer.jype;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.Names;
import org.jetbrains.annotations.NotNull;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Pass 2 — injects F-bounds and completes {@code extends} clauses.
 *
 * <p>For each compiled class:
 * <ol>
 *   <li>Injects missing F-bounds on its own type parameters
 *       ({@link #injectTypeBounds}).</li>
 *   <li>Injects missing Jype type arguments into raw fields
 *       ({@link #injectFieldTypeArgs}).</li>
 *   <li>Completes or rewrites raw or partially parameterized {@code extends} clauses.</li>
 * </ol>
 *
 * <p>The {@code injectedTypeParams} table produced by {@link TypeParamInjector}
 * in pass 1 is consumed by {@link ProcessorTypeArguments} to distinguish the
 * superclass's original parameters from those injected by Jype.
 */
class TypeInjectionPass {

    private final Trees trees;
    private final TreeMaker make;
    private final Names names;
    private final ProcessingEnvironment env;
    private final JypeAstUtils utils;

    private final ProcessorTypeArguments arguments;

    /** Consumes the live injection table from pass one within the same compiler context. */
    TypeInjectionPass(Trees trees, TreeMaker make, Names names, ProcessingEnvironment env,
                      JypeAstUtils utils, Map<String, Set<String>> injectedTypeParams) {
        this.trees = trees;
        this.make = make;
        this.names = names;
        this.env = env;
        this.utils = utils;
        arguments = new ProcessorTypeArguments(utils, injectedTypeParams);
    }

    /**
     * Completes bounds, instance fields and superclass arguments for one source root
     * and its member classes. Already explicit bounds/complete arguments are preserved.
     * AST nodes and existing TypeVar/field symbols must be updated together after Enter.
     */
    void processClass(@NotNull TypeElement classElement, Map<String, TypeMirror> resolvedTypes) {
        JypeDiagnostics.debug(env,
                "[Jype] processClass: " + classElement.getQualifiedName());

        for (Element enclosed : classElement.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.CLASS || enclosed.getKind() == ElementKind.INTERFACE) {
                processClass((TypeElement) enclosed, resolvedTypes);
            }
        }

        JCTree.JCClassDecl classDecl = (JCTree.JCClassDecl) trees.getTree(classElement);
        if (classDecl == null) {
            JypeDiagnostics.debug(env,
                    "[Jype] classDecl is null for: " + classElement.getQualifiedName());
            return;
        }

        TreePath path = trees.getPath(classElement);
        if (path == null) return;
        CompilationUnitTree cu = path.getCompilationUnit();

        // Map names to TypeVars owned by this class (including those injected in pass 1).
        Set<String> ownTypeParamNames = new HashSet<>();
        Map<String, Type.TypeVar> ownTypeVars = new LinkedHashMap<>();
        for (TypeParameterElement tp : classElement.getTypeParameters()) {
            String n = tp.getSimpleName().toString();
            ownTypeParamNames.add(n);
            if (tp.asType() instanceof Type.TypeVar tv) {
                ownTypeVars.put(n, tv);
            }
        }

        injectTypeBounds(classDecl, classElement, ownTypeParamNames, resolvedTypes);
        injectFieldTypeArgs(classDecl, classElement, ownTypeParamNames, resolvedTypes, ownTypeVars, cu);

        if (classDecl.extending == null) return;

        if (classDecl.extending instanceof JCTree.JCTypeApply existing) {
            // Partial parameterization: extends Foo<X>, where Foo also has Jype parameters.
            TypeElement superEl = utils.resolveExtends(existing.clazz, cu);
            if (superEl == null) {
                env.getMessager().printMessage(Diagnostic.Kind.WARNING,
                        "[Jype] resolveExtends returned null for " + classElement.getSimpleName()
                        + " extends " + existing.clazz);
                return;
            }
            List<? extends TypeParameterElement> superParams = superEl.getTypeParameters();
            if (existing.arguments.size() == superParams.size()) return; // Already complete.

            make.at(existing.pos);
            List<JCTree.JCExpression> fullArgs = arguments.buildMixedTypeArgs(
                    superEl, superParams, existing.arguments,
                    ownTypeParamNames, resolvedTypes, ownTypeVars);
            if (fullArgs == null) {
                env.getMessager().printMessage(Diagnostic.Kind.WARNING,
                        "[Jype] buildMixedTypeArgs returned null for " + classElement.getSimpleName()
                        + " — superParams=" + superParams + " providedArgs=" + existing.arguments
                        + " ownParams=" + ownTypeParamNames);
                return;
            }
            existing.arguments = com.sun.tools.javac.util.List.from(fullArgs);
            return;
        }

        // Raw extends — inject all arguments.
        TypeElement superElement = utils.resolveExtends(classDecl.extending, cu);
        if (superElement == null) return;

        List<? extends TypeParameterElement> superTypeParams = superElement.getTypeParameters();
        if (superTypeParams.isEmpty()) return;

        make.at(classDecl.extending.pos);

        // Residual case: partial ownership not resolved in pass 1 (should not happen).
        boolean ownsAnyJypeParam = false, missingJypeParam = false;
        for (TypeParameterElement tp : superTypeParams) {
            String n = tp.getSimpleName().toString();
            if (!resolvedTypes.containsKey(n)) continue;
            if (ownTypeParamNames.contains(n)) ownsAnyJypeParam = true;
            else                               missingJypeParam  = true;
        }
        if (ownsAnyJypeParam && missingJypeParam) {
            JypeDiagnostics.debug(env,
                    "[Jype] skip extends injection (partial ownership residual): "
                    + classElement.getSimpleName() + " extends " + superElement.getSimpleName());
            return;
        }

        if (utils.hasTypeArgConflict(superTypeParams, ownTypeParamNames, resolvedTypes)) return;
        List<JCTree.JCExpression> typeArgExprs =
                utils.buildTypeArgs(superTypeParams, ownTypeParamNames, resolvedTypes, ownTypeVars);
        if (typeArgExprs == null) return;

        classDecl.extending = make.TypeApply(
                classDecl.extending,
                com.sun.tools.javac.util.List.from(typeArgExprs));
    }

    /**
     * Injects missing F-bounds on the class's type parameters.
     * Updates both the AST node {@code JCTypeParameter.bounds} and the
     * {@code TypeVar._bound} symbol field. The latter is necessary because javac
     * has already created symbols during Enter, before annotation processors run.
     */
    private void injectTypeBounds(JCTree.JCClassDecl classDecl,
                                   TypeElement classElement,
                                   Set<String> ownTypeParamNames,
                                   Map<String, TypeMirror> resolvedTypes) {
        Map<String, Type.TypeVar> ownTypeVars = new LinkedHashMap<>();
        for (TypeParameterElement tp : classElement.getTypeParameters()) {
            ownTypeVars.put(tp.getSimpleName().toString(), (Type.TypeVar) tp.asType());
        }

        List<? extends TypeParameterElement> tpeList = classElement.getTypeParameters();
        int size = Math.min(classDecl.typarams.size(), tpeList.size());
        for (int idx = 0; idx < size; idx++) {
            JCTree.JCTypeParameter typeParam = classDecl.typarams.get(idx);
            TypeParameterElement tpe = tpeList.get(idx);
            String paramName = typeParam.name.toString();

            TypeMirror boundMirror = resolvedTypes.get(paramName);
            if (boundMirror == null) continue; // Not a parameter in the type system.
            if (!typeParam.bounds.isEmpty()) continue; // Explicit bound — preserve it.

            TypeElement boundElement = (TypeElement) ((DeclaredType) boundMirror).asElement();
            Symbol.ClassSymbol sym = (Symbol.ClassSymbol) boundElement;
            make.at(typeParam.pos);

            List<? extends TypeParameterElement> boundTypeParams = boundElement.getTypeParameters();
            boolean specializedBinding = !boundTypeParams.isEmpty() && boundTypeParams.stream()
                    .allMatch(parameter -> resolvedTypes.containsKey(parameter.getSimpleName().toString())
                            && !parameter.getSimpleName().contentEquals(paramName));
            if (boundTypeParams.isEmpty() || specializedBinding) {
                // Keep the recursive contract without narrowing a template to its concrete binding.
                TypeElement genericContract = managedGenericSupertype(
                        boundElement, paramName, new HashSet<>());
                if (genericContract == null && specializedBinding) continue;
                if (genericContract == null) {
                    typeParam.bounds = com.sun.tools.javac.util.List.of(make.QualIdent(sym));
                    JypeAstUtils.setTypeVarBound((Type.TypeVar) tpe.asType(), sym.type);
                    continue;
                }
                boundElement = genericContract;
                sym = (Symbol.ClassSymbol) boundElement;
                boundTypeParams = boundElement.getTypeParameters();
            }

            // If this class does not yet own some Jype parameters of the bound class,
            // they would be injected as raw types, violating the F-bounds that Jype
            // will itself inject on those parameters in this round.
            boolean allJypeParamsOwned = true;
            for (TypeParameterElement bp : boundTypeParams) {
                String bpName = bp.getSimpleName().toString();
                if (resolvedTypes.containsKey(bpName) && !ownTypeParamNames.contains(bpName)) {
                    allJypeParamsOwned = false;
                    break;
                }
            }

            if (!allJypeParamsOwned) continue;

            List<JCTree.JCExpression> boundArgs =
                    utils.buildTypeArgs(boundTypeParams, ownTypeParamNames, resolvedTypes, ownTypeVars);
            if (boundArgs == null) continue;
            typeParam.bounds = com.sun.tools.javac.util.List.of(
                    make.TypeApply(make.QualIdent(sym),
                            com.sun.tools.javac.util.List.from(boundArgs)));

            com.sun.tools.javac.util.List<Type> symArgs =
                    utils.buildSymbolTypeArgs(boundTypeParams, ownTypeVars, resolvedTypes);
            if (symArgs == null) continue;
            JypeAstUtils.setTypeVarBound((Type.TypeVar) tpe.asType(),
                    new Type.ClassType(sym.type.getEnclosingType(), symArgs, sym));
        }
    }

    private TypeElement managedGenericSupertype(TypeElement element, String parameterName, Set<TypeElement> visited) {
        if (!visited.add(element)) return null;
        List<TypeMirror> parents = new ArrayList<>();
        parents.add(element.getSuperclass());
        parents.addAll(element.getInterfaces());
        for (TypeMirror parent : parents) {
            if (!(parent instanceof DeclaredType declared) || !(declared.asElement() instanceof TypeElement type)) continue;
            for (TypeParameterElement parameter : type.getTypeParameters()) {
                if (!parameter.getSimpleName().contentEquals(parameterName)) continue;
                Type upperBound = ((Type.TypeVar) parameter.asType()).getUpperBound();
                if (upperBound != null && upperBound.tsym == type) return type;
            }
            TypeElement inherited = managedGenericSupertype(type, parameterName, visited);
            if (inherited != null) return inherited;
        }
        return null;
    }

    /**
     * For each class field whose type is a raw generic type, injects missing
     * Jype type arguments into the AST node ({@code var.vartype}) and symbol
     * ({@code var.sym.type}).
     *
     * <p>Static fields and fields already parameterized ({@code JCTypeApply}) are skipped.
     */
    private void injectFieldTypeArgs(JCTree.JCClassDecl classDecl,
                                      TypeElement classElement,
                                      Set<String> ownTypeParamNames,
                                      Map<String, TypeMirror> resolvedTypes,
                                      Map<String, Type.TypeVar> ownTypeVars,
                                      CompilationUnitTree cu) {
        for (JCTree member : classDecl.defs) {
            if (!(member instanceof JCTree.JCVariableDecl var)) continue;
            if (var.vartype instanceof JCTree.JCTypeApply) continue;
            if (!(var.vartype instanceof JCTree.JCIdent || var.vartype instanceof JCTree.JCFieldAccess)) continue;
            if (var.sym != null && var.sym.isStatic()) continue;

            TypeElement fieldTypeEl = utils.resolveExtends(var.vartype, cu);
            if (fieldTypeEl == null) continue;

            List<? extends TypeParameterElement> fieldTypeParams = fieldTypeEl.getTypeParameters();
            if (fieldTypeParams.isEmpty()) continue;

            boolean hasAnyJypeParam = false;
            for (TypeParameterElement tp : fieldTypeParams) {
                if (resolvedTypes.containsKey(tp.getSimpleName().toString())) {
                    hasAnyJypeParam = true;
                    break;
                }
            }
            if (!hasAnyJypeParam) continue;

            if (utils.hasTypeArgConflict(fieldTypeParams, ownTypeParamNames, resolvedTypes)) continue;

            List<JCTree.JCExpression> typeArgExprs =
                    utils.buildTypeArgs(fieldTypeParams, ownTypeParamNames, resolvedTypes, ownTypeVars);
            if (typeArgExprs == null) continue;

            make.at(var.vartype.pos);
            var.vartype = make.TypeApply(
                    var.vartype,
                    com.sun.tools.javac.util.List.from(typeArgExprs));

            if (var.sym != null) {
                com.sun.tools.javac.util.List<Type> symArgs =
                        utils.buildSymbolTypeArgs(fieldTypeParams, ownTypeVars, resolvedTypes);
                if (symArgs != null) {
                    Symbol.ClassSymbol fieldTypeSym = (Symbol.ClassSymbol) fieldTypeEl;
                    var.sym.type = new Type.ClassType(
                            fieldTypeSym.type.getEnclosingType(), symArgs, fieldTypeSym);
                }
            }

            JypeDiagnostics.debug(env,
                    "[Jype] injectFieldTypeArgs: " + classElement.getSimpleName()
                    + "." + var.name + " : " + fieldTypeEl.getSimpleName() + "<"
                    + fieldTypeParams.stream()
                            .map(tp -> tp.getSimpleName().toString())
                            .collect(Collectors.joining(","))
                    + ">");
        }
    }

}
