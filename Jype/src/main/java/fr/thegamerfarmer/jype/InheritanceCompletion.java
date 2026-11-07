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
    private List<String> residual = List.of();
    private Set<String> specializedBindings = Set.of();

    /** Couples declaration completion to the same canonical index and bound-completion pass. */
    InheritanceCompletion(SourceHierarchy hierarchy, BoundTypeCompletion bounds) {
        this.hierarchy = hierarchy;
        this.bounds = bounds;
    }

    /**
     * Selects the inherited generic aliases that remain class parameters of a specialized
     * system, and the source bindings that are applied to them.
     */
    void residual(List<String> residual, Set<String> specializedBindings) {
        this.residual = List.copyOf(residual);
        this.specializedBindings = Set.copyOf(specializedBindings);
    }

    /** A concrete binding, applied to the residual aliases when it owns them. */
    JCTree.JCExpression concreteExpression(String fqn, TreeMaker make, Names names) {
        JCTree.JCExpression type = buildQualifiedName(fqn, make, names);
        if (residual.isEmpty() || !specializedBindings.contains(fqn)) return type;
        List<JCTree.JCExpression> arguments = new ArrayList<>();
        for (String parameter : residual) arguments.add(make.Ident(names.fromString(parameter)));
        return make.TypeApply(type, com.sun.tools.javac.util.List.from(arguments));
    }

    /** Residual aliases stay lexical; every other managed position uses its specialized binding. */
    private List<JCTree.JCExpression> specializedPartialArguments(JCTree.JCCompilationUnit cu,
            JCTree.JCClassDecl cls, Map<String, List<String>> index, List<String> parameters,
            com.sun.tools.javac.util.List<JCTree.JCExpression> provided, Set<String> own,
            Set<String> managed, Map<String, String> concrete, TreeMaker make, Names names) {
        long ordinary = parameters.stream().filter(parameter -> !managed.contains(parameter)).count();
        if (ordinary != provided.size()) return null;
        boolean needsResidual = false;
        for (String parameter : parameters) {
            if (!managed.contains(parameter) || own.contains(parameter)) continue;
            if (residual.contains(parameter)) needsResidual = true;
            else if (!concrete.containsKey(parameter)) return null;
            else if (specializedBindings.contains(concrete.get(parameter))) needsResidual = true;
        }
        if (needsResidual) {
            for (String parameter : residual) {
                if (own.add(parameter)) {
                    cls.typarams = cls.typarams.append(makeTypeParam(parameter, cls.pos, make, names));
                }
            }
            hierarchy.updateTypeParamMap(index, cu, cls,
                    cls.typarams.stream().map(parameter -> parameter.name.toString()).toList());
        }
        var supplied = provided.iterator();
        List<JCTree.JCExpression> arguments = new ArrayList<>();
        for (String parameter : parameters) {
            if (!managed.contains(parameter)) arguments.add(supplied.next());
            else if (own.contains(parameter)) arguments.add(make.Ident(names.fromString(parameter)));
            else arguments.add(concreteExpression(concrete.get(parameter), make, names));
        }
        return arguments;
    }

    /** Residual aliases stay lexical; every other managed position uses its specialized binding. */
    private List<JCTree.JCExpression> residualArguments(List<String> parameters, Set<String> own,
            Set<String> managed, Map<String, String> concrete, TreeMaker make, Names names) {
        if (residual.isEmpty() || !own.containsAll(residual)) return null;
        List<JCTree.JCExpression> arguments = new ArrayList<>();
        for (String parameter : parameters) {
            if (own.contains(parameter)) arguments.add(make.Ident(names.fromString(parameter)));
            else if (managed.contains(parameter) && concrete.containsKey(parameter)) {
                arguments.add(concreteExpression(concrete.get(parameter), make, names));
            } else return null;
        }
        return arguments;
    }

    /** Completes inheritance first, then signatures/bounds using any parameters just injected. */
    void fixClassDecl(JCTree.JCCompilationUnit cu, JCTree.JCClassDecl cls,
            Map<String, List<String>> typeParamMap, Set<String> managedNames,
            Map<String, String> concreteTypeMap, Set<String> concreteBindings,
            boolean specializedInheritedSystem, TreeMaker make, Names names) {
        completeSuperclass(cu, cls, typeParamMap, managedNames, concreteTypeMap, concreteBindings,
                specializedInheritedSystem, make, names);
        completeInterfaces(cu, cls, typeParamMap, managedNames, concreteTypeMap, make, names);
        completeRawMethodParameters(cu, cls, typeParamMap, managedNames, concreteTypeMap, make, names);
        if (!managedNames.isEmpty()) {
            Set<String> own = new HashSet<>();
            cls.typarams.forEach(parameter -> own.add(parameter.name.toString()));
            bounds.fixTypeParamBounds(cu, cls, typeParamMap, own, managedNames, make, names);
        }
        for (JCTree member : cls.defs) {
            if (member instanceof JCTree.JCClassDecl inner) {
                fixClassDecl(cu, inner, typeParamMap, managedNames, concreteTypeMap, concreteBindings,
                        specializedInheritedSystem, make, names);
            }
        }
    }

    private void completeSuperclass(JCTree.JCCompilationUnit cu,
                               JCTree.JCClassDecl cls,
                               Map<String, List<String>> typeParamMap,
                               Set<String> managedNames,
                               Map<String, String> concreteTypeMap,
                               Set<String> concreteBindings,
                               boolean specializedInheritedSystem,
                               TreeMaker make, Names names) {
        Set<String> ownParams = new HashSet<>();
        for (JCTree.JCTypeParameter tp : cls.typarams) {
            ownParams.add(tp.name.toString());
        }

        if (cls.extending instanceof JCTree.JCTypeApply existing) {
            completePartialSuperclass(cu, cls, existing, typeParamMap, ownParams, managedNames,
                    concreteTypeMap, make, names);
        } else if (cls.extending != null && !managedNames.isEmpty()) {
            completeRawSuperclass(cu, cls, typeParamMap, ownParams, managedNames, concreteTypeMap,
                    concreteBindings, specializedInheritedSystem, make, names);
        }
    }

    private void completePartialSuperclass(JCTree.JCCompilationUnit cu, JCTree.JCClassDecl cls,
            JCTree.JCTypeApply existing, Map<String, List<String>> index, Set<String> own,
            Set<String> managed, Map<String, String> concrete, TreeMaker make, Names names) {
        if (getSimpleName(existing.clazz) == null) return;
        List<String> parameters = hierarchy.resolveTypeParams(index, cu, existing.clazz);
        if (parameters == null || existing.arguments.size() >= parameters.size()) return;
        List<JCTree.JCExpression> arguments = buildFixedArgs(parameters, existing.arguments, own, make, names);
        if (arguments == null && !residual.isEmpty()) {
            arguments = specializedPartialArguments(cu, cls, index, parameters, existing.arguments,
                    own, managed, concrete, make, names);
        }
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
            Map<String, String> concrete, Set<String> bindings, boolean specializedInheritedSystem,
            TreeMaker make, Names names) {
        if (getSimpleName(cls.extending) == null) return;
        List<String> parameters = hierarchy.resolveTypeParams(index, cu, cls.extending);
        if (parameters == null || parameters.isEmpty()) return;
        boolean abstractClass = (cls.mods.flags & Flags.ABSTRACT) != 0;
        boolean binding = bindings.contains(hierarchy.classNames.get(cls));
        boolean allManaged = managed.containsAll(parameters);
        boolean specializeAbstract = !residual.isEmpty();
        List<JCTree.JCExpression> arguments = null;
        if (abstractClass && !binding && allManaged && Collections.disjoint(own, managed) && !specializeAbstract) {
            arguments = genericSuperclassArguments(cu, cls, index, own, parameters, make, names);
        }
        if (arguments == null) arguments = buildConcreteArgs(parameters, own, managed, concrete, make, names);
        if (arguments == null && binding && allManaged) {
            arguments = residualArguments(parameters, own, managed, concrete, make, names);
        }
        if (arguments == null && (!abstractClass || specializeAbstract) && !binding && allManaged
                && specializedInheritedSystem) {
            arguments = partialConcreteSuperclassArguments(
                    cu, cls, index, own, parameters, managed, concrete, make, names);
        }
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

    private List<JCTree.JCExpression> partialConcreteSuperclassArguments(JCTree.JCCompilationUnit cu,
            JCTree.JCClassDecl cls, Map<String, List<String>> index, Set<String> own,
            List<String> parameters, Set<String> managed, Map<String, String> concrete,
            TreeMaker make, Names names) {
        List<JCTree.JCExpression> arguments = new ArrayList<>();
        for (String parameter : parameters) {
            if (own.contains(parameter)) {
                arguments.add(make.Ident(names.fromString(parameter)));
            } else if (managed.contains(parameter) && concrete.containsKey(parameter)) {
                arguments.add(concreteExpression(concrete.get(parameter), make, names));
            } else if (managed.contains(parameter)) {
                cls.typarams = cls.typarams.append(makeTypeParam(parameter, cls.pos, make, names));
                own.add(parameter);
                arguments.add(make.Ident(names.fromString(parameter)));
            } else {
                return null;
            }
        }
        hierarchy.updateTypeParamMap(index, cu, cls,
                cls.typarams.stream().map(parameter -> parameter.name.toString()).toList());
        return arguments;
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
        // E.g. ConcreteImpl implements AbstractContract (raw) → AbstractContract has Jype parameters →
        // Jype injects AbstractContract<J_A,...> if all parameters are in ownParams,
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
                if (ifaceArgs == null && managedNames.containsAll(ifaceParams)) {
                    ifaceArgs = residualArguments(ifaceParams, currentOwn, managedNames, concreteTypeMap, make, names);
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
        // Example: processEvent(EventWrapper infos)
        //           → processEvent(EventWrapper<J_A,...> infos)
        // Needed when a generic static inner class (e.g. EventWrapper)
        // declares managed-type fields and is used raw as a method parameter in
        // the outer class: without this fix, infos.get() returns Object (erasure).
        // → "Object cannot be converted to J_A".
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
                        if (paramArgs == null && managedNames.containsAll(paramTypeParams)) {
                            paramArgs = residualArguments(paramTypeParams, currentOwnForParams, managedNames,
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
