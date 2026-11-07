package fr.thegamerfarmer.jype;

import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.Names;
import java.util.*;
import static fr.thegamerfarmer.jype.PreEnterTrees.*;

/**
 * Adds class-owned managed parameters triggered by source type usage or partial applications.
 * Bounds remain empty until the symbol-aware processor runs; method/local type shadowing is preserved.
 */
final class ManagedParameters {
    private ManagedParameters() {}

    /**
     * If the class uses names from {@code @TypeSystem} as type identifiers in
     * its fields or method signatures (without declaring them as type
     * parameters), injects <em>all</em> missing managed names as empty
     * {@code JCTypeParameter} nodes. {@link TypeInjectionPass} adds F-bounds
     * during annotation processing, after Enter creates the TypeVar symbols.
     *
     * <p>Applies to abstract AND concrete classes: a concrete class such as
     * {@code ConcreteRegistry} that uses {@code J_A} in its fields needs
     * the parameters just as much as an abstract class.
     *
     * <p>Rule: if one managed name is used in the body, all managed names are
     * injected because each one's F-bounds depend on the others.
     */
    static void injectBodyDetectedTypeParams(JCTree.JCClassDecl cls,
                                               Set<String> managedNames,
                                               TreeMaker make, Names names) {
        // Recurse into inner classes first.
        for (JCTree member : cls.defs) {
            if (member instanceof JCTree.JCClassDecl inner) {
                injectBodyDetectedTypeParams(inner, managedNames, make, names);
            }
        }

        // Already-declared parameters.
        Set<String> declared = new LinkedHashSet<>();
        for (JCTree.JCTypeParameter tp : cls.typarams) {
            declared.add(tp.name.toString());
        }

        // If all managed names are already declared, there is nothing to do.
        boolean allDeclared = true;
        for (String n : managedNames) {
            if (!declared.contains(n)) { allDeclared = false; break; }
        }
        if (allDeclared) return;

        // Managed names used in the body (fields + method signatures).
        Set<String> usedInBody = ManagedTypeUsage.find(cls, managedNames);

        // Trigger: at least one managed name is used in the body without being declared.
        // Do NOT act if Jype parameters are already declared but not used in the body:
        // that is partial ownership, handled by TypeParamInjector (processor pass 1).
        Set<String> missingUsedInBody = new LinkedHashSet<>(usedInBody);
        missingUsedInBody.removeAll(declared);
        if (missingUsedInBody.isEmpty()) return;

        // Inject all missing managed names.
        for (String managedName : managedNames) {
            if (declared.contains(managedName)) continue;
            JCTree.JCTypeParameter tp = makeTypeParam(managedName, cls.pos, make, names);
            if (tp != null) {
                cls.typarams = cls.typarams.append(tp);
            }
        }
    }

    /**
     * Attempts to inject missing Jype parameters into the subclass when it
     * supplies exactly the superclass's unmanaged arguments.
     *
     * <p>Typical case: {@code class EventSub extends Listener<EventType>}, where
     * {@code Listener} declares {@code <TYPE_A, TYPE_B, E>} (2 Jype + 1 unmanaged).
     * The subclass supplies 1 argument == nonManagedCount, so TYPE_A and TYPE_B
     * are injected into EventSub for {@link TypeArgumentCompletion#buildFixedArgs}
     * to complete the extends clause.
     *
     * @return the injected parameter names, or {@code null} if the condition is
     *         not met (argument count != nonManagedCount).
     */
    static List<String> tryInjectManagedParams(
            JCTree.JCClassDecl cls,
            List<String> superParams,
            com.sun.tools.javac.util.List<JCTree.JCExpression> providedArgs,
            Set<String> ownParams,
            Set<String> managedNames,
            TreeMaker make, Names names) {

        // Number of unmanaged superclass parameters (supplied by the user).
        int nonManagedCount = 0;
        for (String sp : superParams) {
            if (!managedNames.contains(sp)) nonManagedCount++;
        }

        // Proceed only if the argument count exactly matches the unmanaged parameters.
        if (providedArgs.size() != nonManagedCount) return null;

        // Inject missing managed parameters into the subclass.
        List<String> injected = new ArrayList<>();
        for (String sp : superParams) {
            if (!managedNames.contains(sp)) continue;
            if (ownParams.contains(sp)) continue;
            JCTree.JCTypeParameter tp = makeTypeParam(sp, cls.pos, make, names);
            if (tp != null) {
                cls.typarams = cls.typarams.append(tp);
                injected.add(sp);
            }
        }
        return injected;
    }
}
