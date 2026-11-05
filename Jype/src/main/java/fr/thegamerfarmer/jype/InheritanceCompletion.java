package fr.thegamerfarmer.jype;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.Names;
import java.util.*;
import static fr.thegamerfarmer.jype.PreEnterTrees.*;
import static fr.thegamerfarmer.jype.ManagedParameters.*;
import static fr.thegamerfarmer.jype.TypeArgumentCompletion.*;

/**
 * Completes source inheritance and raw method-parameter surfaces before Enter.
 * Superclass and interface injection precede signature completion because they can add class parameters.
 */
final class InheritanceCompletion {
    private final SourceHierarchy hierarchy;
    private final BoundTypeCompletion bounds;

    /** Couples declaration completion to the same canonical index and bound-completion pass. */
    InheritanceCompletion(SourceHierarchy hierarchy, BoundTypeCompletion bounds) {
        this.hierarchy = hierarchy;
        this.bounds = bounds;
    }

    /** Completes inheritance first, then signatures/bounds using any parameters just injected. */
    void fixClassDecl(JCTree.JCCompilationUnit cu, JCTree.JCClassDecl cls,
            Map<String, List<String>> typeParamMap, Set<String> managedNames,
            Map<String, String> concreteTypeMap, Set<String> concreteBindings, TreeMaker make, Names names) {
        completeSuperclass(cu, cls, typeParamMap, managedNames, concreteTypeMap, concreteBindings, make, names);
        completeInterfaces(cu, cls, typeParamMap, managedNames, concreteTypeMap, make, names);
        completeRawMethodParameters(cu, cls, typeParamMap, managedNames, concreteTypeMap, make, names);
        if (!managedNames.isEmpty()) {
            Set<String> own = new HashSet<>();
            cls.typarams.forEach(parameter -> own.add(parameter.name.toString()));
            bounds.fixTypeParamBounds(cu, cls, typeParamMap, own, managedNames, make, names);
        }
        for (JCTree member : cls.defs) {
            if (member instanceof JCTree.JCClassDecl inner) {
                fixClassDecl(cu, inner, typeParamMap, managedNames, concreteTypeMap, concreteBindings, make, names);
            }
        }
    }

    private void completeSuperclass(JCTree.JCCompilationUnit cu,
                               JCTree.JCClassDecl cls,
                               Map<String, List<String>> typeParamMap,
                               Set<String> managedNames,
                               Map<String, String> concreteTypeMap,
                               Set<String> concreteBindings,
                               TreeMaker make, Names names) {
        Set<String> ownParams = new HashSet<>();
        for (JCTree.JCTypeParameter tp : cls.typarams) {
            ownParams.add(tp.name.toString());
        }

        if (cls.extending instanceof JCTree.JCTypeApply existing) {
            completePartialSuperclass(cu, cls, existing, typeParamMap, ownParams, managedNames, make, names);
        } else if (cls.extending != null && !managedNames.isEmpty()) {
            completeRawSuperclass(cu, cls, typeParamMap, ownParams, managedNames, concreteTypeMap,
                    concreteBindings, make, names);
        }
    }

    private void completePartialSuperclass(JCTree.JCCompilationUnit cu, JCTree.JCClassDecl cls,
            JCTree.JCTypeApply existing, Map<String, List<String>> index, Set<String> own,
            Set<String> managed, TreeMaker make, Names names) {
        if (getSimpleName(existing.clazz) == null) return;
        List<String> parameters = hierarchy.resolveTypeParams(index, cu, existing.clazz);
        if (parameters == null || existing.arguments.size() >= parameters.size()) return;
        List<JCTree.JCExpression> arguments = buildFixedArgs(parameters, existing.arguments, own, make, names);
        if (arguments == null) {
            List<String> injected = tryInjectManagedParams(
                    cls, parameters, existing.arguments, own, managed, make, names);
            if (injected != null && !injected.isEmpty()) {
                Set<String> completed = new HashSet<>(own);
                completed.addAll(injected);
                arguments = buildFixedArgs(parameters, existing.arguments, completed, make, names);
                if (arguments != null) hierarchy.updateTypeParamMap(index, cu, cls,
                        cls.typarams.stream().map(parameter -> parameter.name.toString()).toList());
            }
        }
        if (arguments == null) {
            arguments = tryBuildTrailingManagedArgs(
                    parameters, existing.arguments, own, managed, make, names, existing.pos);
        }
        if (arguments != null) {
            make.at(existing.pos);
            existing.arguments = com.sun.tools.javac.util.List.from(arguments);
        }
    }

    private void completeRawSuperclass(JCTree.JCCompilationUnit cu, JCTree.JCClassDecl cls,
            Map<String, List<String>> index, Set<String> own, Set<String> managed,
            Map<String, String> concrete, Set<String> bindings, TreeMaker make, Names names) {
        if (getSimpleName(cls.extending) == null) return;
        List<String> parameters = hierarchy.resolveTypeParams(index, cu, cls.extending);
        if (parameters == null || parameters.isEmpty()) return;
        boolean abstractClass = (cls.mods.flags & Flags.ABSTRACT) != 0;
        boolean binding = bindings.contains(hierarchy.classNames.get(cls));
        boolean allManaged = managed.containsAll(parameters);
        List<JCTree.JCExpression> arguments = null;
        if (abstractClass && !binding && allManaged && Collections.disjoint(own, managed)) {
            arguments = genericSuperclassArguments(cu, cls, index, own, parameters, make, names);
        }
        if (arguments == null) arguments = buildConcreteArgs(parameters, own, managed, concrete, make, names);
        // A non-binding leaf without concrete associations remains a generic template.
        // Injecting raw F-bounded classes here would violate their recursive constraints.
        if (arguments == null && !abstractClass && !binding && allManaged) {
            arguments = genericSuperclassArguments(cu, cls, index, own, parameters, make, names);
        }
        if (arguments != null) {
            make.at(cls.extending.pos);
            cls.extending = make.TypeApply(cls.extending, com.sun.tools.javac.util.List.from(arguments));
        }
    }

    private List<JCTree.JCExpression> genericSuperclassArguments(JCTree.JCCompilationUnit cu,
            JCTree.JCClassDecl cls, Map<String, List<String>> index, Set<String> own,
            List<String> parameters, TreeMaker make, Names names) {
        for (String parameter : parameters) {
            if (own.contains(parameter)) continue;
            cls.typarams = cls.typarams.append(makeTypeParam(parameter, cls.pos, make, names));
            own.add(parameter);
        }
        hierarchy.updateTypeParamMap(index, cu, cls,
                cls.typarams.stream().map(parameter -> parameter.name.toString()).toList());
        List<JCTree.JCExpression> arguments = new ArrayList<>();
        make.at(cls.extending.pos);
        for (String parameter : parameters) arguments.add(make.Ident(names.fromString(parameter)));
        return arguments;
    }

    private void completeInterfaces(JCTree.JCCompilationUnit cu, JCTree.JCClassDecl cls,
            Map<String, List<String>> typeParamMap, Set<String> managedNames,
            Map<String, String> concreteTypeMap, TreeMaker make, Names names) {
        // Complete raw implements clauses (same logic as for raw extends clauses).
        // E.g. VanillaSound implements APISound (raw) → APISound has Jype parameters →
        // Jype injects APISound<API_PLAYER,...> if all parameters are in ownParams,
        // or uses buildConcreteArgs if concrete types are available.
        if (cls.implementing != null && !cls.implementing.isEmpty()) {
            Set<String> currentOwn = new HashSet<>();
            for (JCTree.JCTypeParameter tp : cls.typarams) currentOwn.add(tp.name.toString());

            com.sun.tools.javac.util.ListBuffer<JCTree.JCExpression> newImpl =
                    new com.sun.tools.javac.util.ListBuffer<>();
            boolean implChanged = false;

            for (JCTree.JCExpression iface : cls.implementing) {
                if (iface instanceof JCTree.JCTypeApply applied) {
                    List<String> params = hierarchy.resolveTypeParams(typeParamMap, cu, applied.clazz);
                    if (params != null && applied.arguments.size() < params.size()) {
                        List<JCTree.JCExpression> full = buildFixedArgs(
                                params, applied.arguments, currentOwn, make, names);
                        if (full == null) {
                            List<String> injected = tryInjectManagedParams(
                                    cls, params, applied.arguments, currentOwn, managedNames, make, names);
                            if (injected != null) {
                                currentOwn.addAll(injected);
                                full = buildFixedArgs(params, applied.arguments, currentOwn, make, names);
                                hierarchy.updateTypeParamMap(typeParamMap, cu, cls,
                                        cls.typarams.stream().map(tp -> tp.name.toString()).toList());
                            }
                        }
                        if (full == null) {
                            full = tryBuildTrailingManagedArgs(params, applied.arguments, currentOwn,
                                    managedNames, make, names, applied.pos);
                        }
                        if (full != null) applied.arguments = com.sun.tools.javac.util.List.from(full);
                    }
                    newImpl.add(iface);
                    continue;
                }
                if (managedNames.isEmpty()) { newImpl.add(iface); continue; }
                String ifaceName = getSimpleName(iface);
                if (ifaceName == null) { newImpl.add(iface); continue; }
                List<String> ifaceParams = hierarchy.resolveTypeParams(typeParamMap, cu, iface);
                if (ifaceParams == null || ifaceParams.isEmpty()) { newImpl.add(iface); continue; }

                // buildFixedArgs: all interface parameters are in currentOwn → Idents.
                List<JCTree.JCExpression> ifaceArgs = buildFixedArgs(
                        ifaceParams, com.sun.tools.javac.util.List.nil(), currentOwn, make, names);
                // Otherwise, use buildConcreteArgs with the concreteTypeMap.
                if (ifaceArgs == null) {
                    ifaceArgs = buildConcreteArgs(
                            ifaceParams, currentOwn, managedNames, concreteTypeMap, make, names);
                }
                if (ifaceArgs != null) {
                    make.at(iface.pos);
                    newImpl.add(make.TypeApply(iface, com.sun.tools.javac.util.List.from(ifaceArgs)));
                    implChanged = true;
                } else {
                    newImpl.add(iface);
                }
            }
            if (implChanged) {
                cls.implementing = newImpl.toList();
            }
        }

    }

    private void completeRawMethodParameters(JCTree.JCCompilationUnit cu, JCTree.JCClassDecl cls,
            Map<String, List<String>> typeParamMap, Set<String> managedNames,
            Map<String, String> concreteTypeMap, TreeMaker make, Names names) {
        // Complete raw types in method parameters.
        // Example: setVictimeKilled(PlayerDeathEventInfo infos)
        //           → setVictimeKilled(PlayerDeathEventInfo<API_PLAYER,...> infos)
        // Needed when a generic static inner class (e.g. PlayerDeathEventInfo)
        // declares managed-type fields and is used raw as a method parameter in
        // the outer class: without this fix, infos.getVictime() returns Object (erasure).
        // → "Object cannot be converted to API_PLAYER".
        if (!managedNames.isEmpty()) {
            Set<String> currentOwnForParams = new HashSet<>();
            for (JCTree.JCTypeParameter tp : cls.typarams) currentOwnForParams.add(tp.name.toString());
            if (!currentOwnForParams.isEmpty()) {
                for (JCTree member : cls.defs) {
                    if (!(member instanceof JCTree.JCMethodDecl meth)) continue;
                    for (JCTree.JCVariableDecl param : meth.params) {
                        if (param.vartype instanceof JCTree.JCTypeApply) continue; // Already parameterized.
                        if (!(param.vartype instanceof JCTree.JCIdent
                                || param.vartype instanceof JCTree.JCFieldAccess)) continue;
                        String paramTypeName = getSimpleName(param.vartype);
                        if (paramTypeName == null) continue;
                        List<String> paramTypeParams = hierarchy.resolveTypeParams(typeParamMap, cu, param.vartype);
                        if (paramTypeParams == null || paramTypeParams.isEmpty()) continue;
                        boolean hasManaged = false;
                        for (String pp : paramTypeParams) {
                            if (managedNames.contains(pp)) { hasManaged = true; break; }
                        }
                        if (!hasManaged) continue;
                        // buildFixedArgs: managed parameters in currentOwnForParams → Idents.
                        List<JCTree.JCExpression> paramArgs = buildFixedArgs(
                                paramTypeParams,
                                com.sun.tools.javac.util.List.nil(),
                                currentOwnForParams, make, names);
                        if (paramArgs == null) {
                            paramArgs = buildConcreteArgs(
                                    paramTypeParams, currentOwnForParams, managedNames,
                                    concreteTypeMap, make, names);
                        }
                        if (paramArgs != null) {
                            make.at(param.vartype.pos);
                            param.vartype = make.TypeApply(
                                    param.vartype,
                                    com.sun.tools.javac.util.List.from(paramArgs));
                        }
                    }
                }
            }
        }

    }
}
