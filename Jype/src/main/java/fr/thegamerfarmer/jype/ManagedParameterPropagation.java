package fr.thegamerfarmer.jype;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.Names;
import java.util.*;
import static fr.thegamerfarmer.jype.PreEnterTrees.*;

/**
 * Propagates missing F-bound dependencies to a fixed point before Enter.
 * Concrete binding classes stay concrete; only eligible templates acquire class parameters.
 */
final class ManagedParameterPropagation {
    private final SourceHierarchy hierarchy;

    /** Shares the live source index so later iterations observe newly added parameters. */
    ManagedParameterPropagation(SourceHierarchy hierarchy) { this.hierarchy = hierarchy; }

    /**
     * Propagates missing Jype parameters to classes whose type-parameter bounds
     * reference classes that received additional Jype parameters through
     * {@link ManagedParameters#injectBodyDetectedTypeParams}.
     *
     * <p>Example: {@code AbstractSecondary<CHILD extends AbstractPrimary<CHILD>>} — if
     * AbstractPrimary changes from {@code <API_CHILD>} to
     * {@code <API_CHILD, J_A, …>} after injection, the bound is
     * missing 6 parameters. This method injects those 6 parameters into
     * AbstractSecondary.
     *
     * <p>Iterates until convergence to handle cascades.
     */
    void propagateBoundDependencies(List<JCTree.JCCompilationUnit> cus,
                                             Map<String, List<String>> typeParamMap,
                                             Set<String> managedNames,
                                             Set<String> concreteBindings,
                                             boolean specialized,
                                             TreeMaker make, Names names) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (JCTree.JCCompilationUnit cu : cus) {
                for (JCTree def : cu.defs) {
                    if (def instanceof JCTree.JCClassDecl cls && hierarchy.activeRoots.contains(cls)) {
                        if (injectParamsFromBounds(cu, cls, typeParamMap, managedNames,
                                concreteBindings, specialized, make, names)) {
                            changed = true;
                        }
                    }
                }
            }
        }
    }

    /**
     * For each type-parameter bound (JCTypeApply) in the class that references
     * a superclass with more parameters than supplied, injects the missing Jype
     * parameters into the class.
     *
     * @return {@code true} if at least one parameter was injected.
     */
    private boolean injectParamsFromBounds(JCTree.JCCompilationUnit cu,
                                            JCTree.JCClassDecl cls,
                                            Map<String, List<String>> typeParamMap,
                                            Set<String> managedNames,
                                            Set<String> concreteBindings,
                                            boolean specialized,
                                            TreeMaker make, Names names) {
        boolean injected = false;

        // Recurse into inner classes first.
        for (JCTree member : cls.defs) {
            if (member instanceof JCTree.JCClassDecl inner) {
                if (injectParamsFromBounds(cu, inner, typeParamMap, managedNames,
                        concreteBindings, specialized, make, names)) {
                    injected = true;
                }
            }
        }

        // Set of owned parameters — updated as parameters are injected.
        Set<String> ownParams = new HashSet<>();
        for (JCTree.JCTypeParameter tp : cls.typarams) {
            ownParams.add(tp.name.toString());
        }

        injected |= injectDeclaredBoundParameters(cu, cls, typeParamMap, managedNames, ownParams, make, names);
        // Specialized systems complete abstract classes with their bindings instead of templates.
        if (!specialized) {
            injected |= injectSuperclassParameters(cu, cls, typeParamMap, managedNames, concreteBindings,
                    ownParams, make, names);
        }

        if (injected) {
            List<String> updatedParams = new ArrayList<>();
            for (JCTree.JCTypeParameter tp : cls.typarams) {
                updatedParams.add(tp.name.toString());
            }
            hierarchy.updateTypeParamMap(typeParamMap, cu, cls, updatedParams);
        }

        return injected;
    }

    private boolean injectDeclaredBoundParameters(JCTree.JCCompilationUnit cu, JCTree.JCClassDecl cls,
            Map<String, List<String>> typeParamMap, Set<String> managedNames, Set<String> ownParams,
            TreeMaker make, Names names) {
        boolean injected = false;
        // Iterate over the current type parameters (snapshot before injection).
        for (JCTree.JCTypeParameter tp : cls.typarams) {
            if (tp.bounds == null || tp.bounds.isEmpty()) continue;
            for (JCTree.JCExpression bound : tp.bounds) {
                if (!(bound instanceof JCTree.JCTypeApply ta)) continue;
                String superName = getSimpleName(ta.clazz);
                if (superName == null) continue;
                List<String> superParams = hierarchy.resolveTypeParams(typeParamMap, cu, ta.clazz);
                if (superParams == null || ta.arguments.size() >= superParams.size()) continue;

                // Inject missing managed parameters (positions beyond the provided count).
                for (int i = ta.arguments.size(); i < superParams.size(); i++) {
                    String sp = superParams.get(i);
                    if (!managedNames.contains(sp) || ownParams.contains(sp)) continue;
                    JCTree.JCTypeParameter newTp = makeTypeParam(sp, cls.pos, make, names);
                    if (newTp != null) {
                        cls.typarams = cls.typarams.append(newTp);
                        ownParams.add(sp);
                        injected = true;
                    }
                }
            }
        }

        // Scan raw (JCIdent) bounds of the class's type parameters:
        // <T extends Foo> where Foo is fully managed — inject Foo's parameters.
        // Partial JCTypeApply bounds are already handled by the loop above.
        for (JCTree.JCTypeParameter tp : cls.typarams) {
            if (tp.bounds == null || tp.bounds.isEmpty()) continue;
            for (JCTree.JCExpression bound : tp.bounds) {
                if (!(bound instanceof JCTree.JCIdent boundId)) continue;
                String superName = boundId.name.toString();
                List<String> superParams = hierarchy.resolveTypeParams(typeParamMap, cu, bound);
                if (superParams == null || superParams.isEmpty()) continue;
                boolean allManaged = true;
                for (String sp : superParams) {
                    if (!managedNames.contains(sp)) { allManaged = false; break; }
                }
                if (!allManaged) continue;
                for (String sp : superParams) {
                    if (!ownParams.contains(sp)) {
                        JCTree.JCTypeParameter newTp = makeTypeParam(sp, cls.pos, make, names);
                        if (newTp != null) {
                            cls.typarams = cls.typarams.append(newTp);
                            ownParams.add(sp);
                            injected = true;
                        }
                    }
                }
            }
        }

        return injected;
    }

    private boolean injectSuperclassParameters(JCTree.JCCompilationUnit cu, JCTree.JCClassDecl cls,
            Map<String, List<String>> typeParamMap, Set<String> managedNames, Set<String> concreteBindings,
            Set<String> ownParams, TreeMaker make, Names names) {
        boolean injected = false;
        // Propagate from extends clause for abstract non-binding classes only.
        // Binding classes (concreteBindings) get concrete extends in fixClassDecl.
        // Concrete classes keep their concrete-args extends (FQN) — NOT made generic here.
        boolean isAbstract = (cls.mods.flags & com.sun.tools.javac.code.Flags.ABSTRACT) != 0;
        boolean isConcreteBound = concreteBindings.contains(
                hierarchy.classNames.get(cls));
        if (isAbstract && !isConcreteBound) {
            boolean hasAnyManagedOwn = false;
            for (String sp : ownParams) {
                if (managedNames.contains(sp)) { hasAnyManagedOwn = true; break; }
            }
            if (!hasAnyManagedOwn) {
                if (cls.extending instanceof JCTree.JCTypeApply ta) {
                    String superName = getSimpleName(ta.clazz);
                    if (superName != null) {
                        List<String> superParams = hierarchy.resolveTypeParams(typeParamMap, cu, ta.clazz);
                        if (superParams != null && ta.arguments.size() < superParams.size()) {
                            for (int i = ta.arguments.size(); i < superParams.size(); i++) {
                                String sp = superParams.get(i);
                                if (!managedNames.contains(sp) || ownParams.contains(sp)) continue;
                                JCTree.JCTypeParameter newTp = makeTypeParam(sp, cls.pos, make, names);
                                if (newTp != null) {
                                    cls.typarams = cls.typarams.append(newTp);
                                    ownParams.add(sp);
                                    injected = true;
                                }
                            }
                        }
                    }
                } else if (cls.extending instanceof JCTree.JCIdent extId) {
                    String superName = extId.name.toString();
                    List<String> superParams = hierarchy.resolveTypeParams(typeParamMap, cu, cls.extending);
                    if (superParams != null && !superParams.isEmpty()) {
                        boolean allManaged = true;
                        for (String sp : superParams) {
                            if (!managedNames.contains(sp)) { allManaged = false; break; }
                        }
                        if (allManaged) {
                            for (String sp : superParams) {
                                if (!ownParams.contains(sp)) {
                                    JCTree.JCTypeParameter newTp = makeTypeParam(sp, cls.pos, make, names);
                                    if (newTp != null) {
                                        cls.typarams = cls.typarams.append(newTp);
                                        ownParams.add(sp);
                                        injected = true;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        return injected;
    }
}
