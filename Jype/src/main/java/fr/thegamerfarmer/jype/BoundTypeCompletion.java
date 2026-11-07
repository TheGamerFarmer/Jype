package fr.thegamerfarmer.jype;

import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.Names;
import java.util.*;
import static fr.thegamerfarmer.jype.PreEnterTrees.*;
import static fr.thegamerfarmer.jype.TypeArgumentCompletion.*;

/**
 * Completes parsed class bounds and nested wildcard arities after parameter propagation.
 * Explicit prefixes are retained; only managed positions available in scope are filled.
 */
final class BoundTypeCompletion {
    private final SourceHierarchy hierarchy;

    /** Uses canonical source parameter orders after propagation has stabilized. */
    BoundTypeCompletion(SourceHierarchy hierarchy) { this.hierarchy = hierarchy; }

    /**
     * Completes partially parameterized bounds in the class's type-parameter
     * declarations.
     *
     * <p>After {@link ManagedParameterPropagation} injects the required Jype
     * parameters into the class, this method replaces each bound {@code Foo<T>}
     * (with too few arguments) with {@code Foo<T, J_A, …>} by filling the
     * missing positions from {@code ownParams}.
     */
    void fixTypeParamBounds(JCTree.JCCompilationUnit cu,
                                     JCTree.JCClassDecl cls,
                                     Map<String, List<String>> typeParamMap,
                                     Set<String> ownParams,
                                     Set<String> managedNames,
                                     TreeMaker make, Names names) {
        for (JCTree.JCTypeParameter tp : cls.typarams) {
            if (tp.bounds == null || tp.bounds.isEmpty()) continue;
            com.sun.tools.javac.util.ListBuffer<JCTree.JCExpression> newBounds =
                    new com.sun.tools.javac.util.ListBuffer<>();
            boolean boundsChanged = false;
            for (JCTree.JCExpression bound : tp.bounds) {
                if (bound instanceof JCTree.JCTypeApply ta) {
                    // Already parameterized bound — complete in place if needed.
                    String superName = getSimpleName(ta.clazz);
                    if (superName != null) {
                        List<String> superParams = hierarchy.resolveTypeParams(typeParamMap, cu, ta.clazz);
                        if (superParams != null && ta.arguments.size() < superParams.size()) {
                            List<JCTree.JCExpression> fullArgs = buildBoundArgs(
                                    superParams, ta.arguments, ownParams, managedNames, make, names, ta.pos);
                            if (fullArgs != null) {
                                ta.arguments = com.sun.tools.javac.util.List.from(fullArgs);
                            }
                        }
                    }
                    newBounds.add(bound);
                } else if (bound instanceof JCTree.JCIdent boundId) {
                    // Raw bound — convert to JCTypeApply if all superclass parameters
                    // are managed and present in ownParams.
                    String superName = boundId.name.toString();
                    List<String> superParams = hierarchy.resolveTypeParams(typeParamMap, cu, bound);
                    boolean converted = false;
                    if (superParams != null && !superParams.isEmpty()) {
                        boolean canFill = true;
                        for (String sp : superParams) {
                            if (!managedNames.contains(sp) || !ownParams.contains(sp)) {
                                canFill = false; break;
                            }
                        }
                        if (canFill) {
                            make.at(bound.pos);
                            List<JCTree.JCExpression> args = new ArrayList<>();
                            for (String sp : superParams) {
                                args.add(make.Ident(names.fromString(sp)));
                            }
                            newBounds.add(make.TypeApply(bound,
                                    com.sun.tools.javac.util.List.from(args)));
                            boundsChanged = true;
                            converted = true;
                        }
                    }
                    if (!converted) newBounds.add(bound);
                } else {
                    newBounds.add(bound);
                }
            }
            if (boundsChanged) {
                tp.bounds = newBounds.toList();
            }
        }
    }

    /**
     * Post-processing pass: expands nested JCTypeApply nodes that have too few
     * arguments in {@code extends} clauses and type-parameter bounds.
     *
     * <p>Example: {@code AbstractSecondary<?>} → {@code AbstractSecondary<?,?,?,?,?,?,?>} when
     * AbstractSecondary has 7 parameters after injection and all missing positions are
     * managed names.
     */
    void expandAllNestedWildcards(List<JCTree.JCCompilationUnit> cus,
                                           Map<String, List<String>> typeParamMap,
                                           Set<String> managedNames,
                                           TreeMaker make, Names names) {
        for (JCTree.JCCompilationUnit cu : cus) {
            for (JCTree def : cu.defs) {
                if (def instanceof JCTree.JCClassDecl cls && hierarchy.activeRoots.contains(cls)) {
                    expandWildcardsInClass(cu, cls, typeParamMap, managedNames, make, names);
                }
            }
        }
    }

    private void expandWildcardsInClass(JCTree.JCCompilationUnit cu,
                                         JCTree.JCClassDecl cls,
                                         Map<String, List<String>> typeParamMap,
                                         Set<String> managedNames,
                                         TreeMaker make, Names names) {
        // Extends
        if (cls.extending instanceof JCTree.JCTypeApply ta) {
            expandWildcardsInTypeApply(cu, ta, typeParamMap, managedNames, make, names);
        }
        // Type-parameter bounds
        for (JCTree.JCTypeParameter tp : cls.typarams) {
            if (tp.bounds != null) {
                for (JCTree.JCExpression bound : tp.bounds) {
                    if (bound instanceof JCTree.JCTypeApply ta) {
                        expandWildcardsInTypeApply(cu, ta, typeParamMap, managedNames, make, names);
                    }
                }
            }
        }
        // Classes internes
        for (JCTree member : cls.defs) {
            if (member instanceof JCTree.JCClassDecl inner) {
                expandWildcardsInClass(cu, inner, typeParamMap, managedNames, make, names);
            }
        }
    }

    /**
     * Recursively expands the arguments of a JCTypeApply: if the referenced
     * type has more parameters than supplied arguments and all missing
     * positions are managed names, adds {@code ?} wildcards to complete it.
     */
    private void expandWildcardsInTypeApply(JCTree.JCCompilationUnit cu,
                                             JCTree.JCTypeApply ta,
                                             Map<String, List<String>> typeParamMap,
                                             Set<String> managedNames,
                                             TreeMaker make, Names names) {
        // Recurse into existing arguments first.
        for (JCTree.JCExpression arg : ta.arguments) {
            if (arg instanceof JCTree.JCTypeApply argTa) {
                expandWildcardsInTypeApply(cu, argTa, typeParamMap, managedNames, make, names);
            }
        }
        // Check whether this node needs to be expanded.
        String clsName = getSimpleName(ta.clazz);
        if (clsName == null) return;
        List<String> clsParams = hierarchy.resolveTypeParams(typeParamMap, cu, ta.clazz);
        if (clsParams == null || ta.arguments.size() >= clsParams.size()) return;
        // All missing positions must be managed names.
        for (int i = ta.arguments.size(); i < clsParams.size(); i++) {
            if (!managedNames.contains(clsParams.get(i))) return;
        }
        // Expand with unbounded wildcards.
        List<JCTree.JCExpression> newArgs = new ArrayList<>(ta.arguments);
        make.at(ta.pos);
        for (int i = ta.arguments.size(); i < clsParams.size(); i++) {
            newArgs.add(make.Wildcard(
                    make.TypeBoundKind(com.sun.tools.javac.code.BoundKind.UNBOUND), null));
        }
        ta.arguments = com.sun.tools.javac.util.List.from(newArgs);
    }
}
