package fr.thegamerfarmer.jype;

import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.Names;
import java.util.*;
import static fr.thegamerfarmer.jype.PreEnterTrees.*;

/**
 * Builds complete argument lists without changing declarations.
 * Ordinary arguments retain their order. A null result means the requested completion is unsafe.
 */
final class TypeArgumentCompletion {
    private TypeArgumentCompletion() {}

    /**
     * Attempts to complete a partially parameterized extends clause, assuming
     * the provided arguments cover the first {@code provided.size()} positions
     * and all remaining positions are Jype parameters present in {@code ownParams}.
     *
     * <p>Typical case: {@code class SubPower<MASTER_POWER,…> extends APISubPower<SubPower<?>, MASTER_POWER>},
     * where APISubPower declares {@code <API_SUB_POWER, MASTER_POWER, API_PLAYER, …>}.
     * {@link #buildFixedArgs} fails because MASTER_POWER is "shared" (in ownParams)
     * and is not counted as unshared, even though the user supplied it explicitly.
     * This method keeps the 2 provided arguments and adds {@code Ident} nodes for
     * the 6 trailing positions.
     *
     * @return the complete list, or {@code null} if a trailing position cannot be filled
     */
    static List<JCTree.JCExpression> tryBuildTrailingManagedArgs(
            List<String> superParams,
            com.sun.tools.javac.util.List<JCTree.JCExpression> providedArgs,
            Set<String> ownParams,
            Set<String> managedNames,
            TreeMaker make, Names names, int pos) {

        if (providedArgs.size() >= superParams.size()) return null;

        // All trailing positions must be managed parameters in ownParams.
        for (int i = providedArgs.size(); i < superParams.size(); i++) {
            String sp = superParams.get(i);
            if (!managedNames.contains(sp) || !ownParams.contains(sp)) return null;
        }

        // Keep the provided arguments and add Idents for the trailing positions.
        List<JCTree.JCExpression> result = new ArrayList<>(providedArgs);
        make.at(pos);
        for (int i = providedArgs.size(); i < superParams.size(); i++) {
            result.add(make.Ident(names.fromString(superParams.get(i))));
        }
        return result;
    }

    /**
     * Builds the complete argument list for a partially parameterized
     * type-parameter bound.
     *
     * <p>Heuristic: {@code providedArgs} correspond to the first
     * {@code providedArgs.size()} superclass parameters (written by the user).
     * All remaining positions must be managed Jype parameters present in the
     * current class's {@code ownParams}.
     *
     * @return the complete list, or {@code null} if a remaining position cannot
     *         be filled from {@code ownParams}
     */
    static List<JCTree.JCExpression> buildBoundArgs(
            List<String> superParams,
            com.sun.tools.javac.util.List<JCTree.JCExpression> providedArgs,
            Set<String> ownParams,
            Set<String> managedNames,
            TreeMaker make, Names names, int pos) {

        if (providedArgs.size() > superParams.size()) return null;

        for (int i = providedArgs.size(); i < superParams.size(); i++) {
            String sp = superParams.get(i);
            if (!managedNames.contains(sp) || !ownParams.contains(sp)) return null;
        }

        List<JCTree.JCExpression> result = new ArrayList<>(superParams.size());
        result.addAll(providedArgs);
        make.at(pos);
        for (int i = providedArgs.size(); i < superParams.size(); i++) {
            result.add(make.Ident(names.fromString(superParams.get(i))));
        }
        return result;
    }

    /**
     * Builds the complete argument list for a raw extends clause using:
     * <ul>
     *   <li>parameters owned by the class (injected as {@code Ident(name)}),</li>
     *   <li>managed parameters not owned by the class (resolved through {@code concreteTypeMap}).</li>
     * </ul>
     *
     * <p>Returns {@code null} if an unmanaged, unowned parameter is encountered
     * (it cannot be filled without a user-provided argument) or if a concrete
     * type is missing from the map.
     */
    static List<JCTree.JCExpression> buildConcreteArgs(List<String> superParams,
                                                          Set<String> ownParams,
                                                          Set<String> managedNames,
                                                          Map<String, String> concreteTypeMap,
                                                          TreeMaker make, Names names) {
        // Partial ownership: some Jype parameters are owned, others are not.
        // The unowned parameters will become TypeVars through TypeParamInjector,
        // so they cannot be filled here with concrete types (which may not be imported).
        boolean anyOwned = false, anyManagedUnowned = false;
        for (String sp : superParams) {
            if (!managedNames.contains(sp)) continue;
            if (ownParams.contains(sp)) anyOwned = true;
            else anyManagedUnowned = true;
        }
        if (anyOwned && anyManagedUnowned) return null;

        List<JCTree.JCExpression> args = new ArrayList<>();
        for (String sp : superParams) {
            if (ownParams.contains(sp)) {
                args.add(make.Ident(names.fromString(sp)));
            } else if (managedNames.contains(sp)) {
                String concreteFqn = concreteTypeMap.get(sp);
                if (concreteFqn == null) return null;
                // Use the FQN to avoid problems with missing imports.
                args.add(buildQualifiedName(concreteFqn, make, names));
            } else {
                // Unmanaged and unowned parameter: cannot fill it from a raw type.
                return null;
            }
        }
        return args;
    }

    /**
     * Builds the complete argument list for an extends clause.
     *
     * <p>A superclass parameter is "shared" (managed by Jype) if it also
     * appears among the subclass's own parameters; in that case, injects
     * {@code Ident(name)}. Otherwise, it was supplied by the user and the next
     * argument from {@code providedArgs} is used.
     *
     * <p>Returns {@code null} if the number of provided arguments does not match
     * the number of unshared parameters.
     */
    static List<JCTree.JCExpression> buildFixedArgs(
            List<String> superParams,
            com.sun.tools.javac.util.List<JCTree.JCExpression> providedArgs,
            Set<String> ownParams,
            TreeMaker make, Names names) {

        int nonSharedCount = 0;
        for (String sp : superParams) {
            if (!ownParams.contains(sp)) nonSharedCount++;
        }
        if (providedArgs.size() != nonSharedCount) return null;

        List<JCTree.JCExpression> result = new ArrayList<>();
        int idx = 0;
        for (String sp : superParams) {
            if (ownParams.contains(sp)) {
                result.add(make.Ident(names.fromString(sp)));
            } else {
                result.add(providedArgs.get(idx++));
            }
        }
        return result;
    }
}
