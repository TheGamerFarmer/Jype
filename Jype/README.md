# Jype — javac engine

Jype reduces repetition of generic parameters in Java hierarchies. A type system
declared on a package associates **parameter names** with **class or interface
bounds**. The engine then completes declarations, generic applications, and
their recursive bounds in the javac AST. It does not add helper classes to the
program.

This guide describes using the engine in an already configured compilation.
Editor integration is documented in the IntelliJ module README.

For a complete Maven consumer setup (repository, dependency, compiler plugin,
processor path and compiler JVM options), follow
[Maven installation](../README.md#using-the-engine-from-maven). Adding the
dependency or installing the IDE plugin alone does not activate compilation.

## Defining a type system

```java
// api/package-info.java
@TypeSystem({
    @TypeDef(name = "ENTITY", bound = Entity.class),
    @TypeDef(name = "CLUSTER", bound = Cluster.class)
})
package api;

import fr.thegamerfarmer.jype.annotations.TypeDef;
import fr.thegamerfarmer.jype.annotations.TypeSystem;
```

```java
// api/Entity.java
package api;

public abstract class Entity {
    public CLUSTER cluster;
    public abstract ENTITY self();
}
```

```java
// api/Cluster.java
package api;

public abstract class Cluster {
    public java.util.List<ENTITY> members;
}
```

Using a managed name in a signature or method body triggers the addition of the
class's missing parameters. Definition order is preserved. Here, both classes
become two-parameter templates:

```java
abstract class Entity<
    ENTITY extends Entity<ENTITY, CLUSTER>,
    CLUSTER extends Cluster<ENTITY, CLUSTER>> { /* members preserved */ }
```

These **F-bounds** describe a coherent family: the bound of `ENTITY` refers to
both `ENTITY` and `CLUSTER`. The engine therefore propagates dependencies until
they stabilize rather than scanning the files only once. Bounds explicitly
written by the user are preserved.

## Inheriting and overriding mappings

```java
// impl/package-info.java
@TypeSystem(
    inherit = "api",
    overrides = {
        @TypeDef(name = "ENTITY", bound = AppEntity.class),
        @TypeDef(name = "CLUSTER", bound = AppCluster.class)
    }
)
package impl;

import fr.thegamerfarmer.jype.annotations.TypeDef;
import fr.thegamerfarmer.jype.annotations.TypeSystem;
```

```java
package impl;

public final class AppEntity extends api.Entity {
    @Override public AppEntity self() { return this; }
}
```

```java
package impl;

public final class AppCluster extends api.Cluster {}
```

Raw clauses are completed with the package's concrete mappings. `@TypeSystem`
metadata is retained in `package-info.class`, allowing a separately compiled
consumer to inherit from a library.

Rules:

- `value` defines a standalone type system; do not combine it with `inherit`.
- `overrides` requires `inherit` and can replace only existing names.
- Names are case-sensitive Java identifiers with no whitespace or keywords.
- Duplicate names and inheritance cycles are errors, not silently ignored values.
- A bound must be a declared type, including an interface or generic class;
  primitives and arrays are not accepted as bounds.
- An override is **not required to be a subtype of the previous bound**.
  Java still checks generic constraints in the resulting hierarchy.
- A subpackage uses the nearest annotated type system. Neighboring systems can
  map the same name to different types without affecting each other.
- Without a local annotation, a class can also inherit the type system of its
  source superclass or a metadata-bearing compiled superclass. A consumer
  defining new mappings still declares `inherit` and `overrides` on its package.
- In a consumer with `overrides`, names it does not override whose inherited
  bound is still a generic library template (for example an inherited
  `J_STORE` bound to `StoreBase`) remain the only technical parameters.
  Local bindings, abstract classes, and leaves are specialized with them
  (`AppEntity` becomes `AppEntity<J_STORE>` extending
  `ApiEntity<AppEntity<J_STORE>, …, J_STORE>`), so `ENTITY` really
  means `AppEntity` in that package. Static members only receive those residual
  parameters. Javadoc and public views still show `AppEntity`.

## Ordinary parameters, partial ownership, and references

Unmanaged parameters remain under the programmer's control:

```java
package api;

public abstract class Listener<ENTITY, CLUSTER, E> {
    public abstract void accept(ENTITY entity, E event);
}

// In another class in the same type system:
abstract class ClickListener<ENTITY, CLUSTER> extends Listener<ClickEvent> {}
```

The `ClickEvent` argument remains in the `E` position; Jype fills the `ENTITY`
and `CLUSTER` positions. An invalid number of arguments is not "fixed" by
removing user-supplied arguments.

Completion also reads the generic signatures of separately compiled classes
from the classpath, before javac validates arity. Consumers of a Jype library
can therefore omit its managed arguments in inheritance, fields, return and
parameter types, locals, casts, collections and creations while supplying
ordinary arguments explicitly. Completion uses lexical managed variables for
templates, or the effective package's concrete mappings for bound consumers.
The consumer must have a resolvable type system and run both the javac plugin
and the processor. Genuine raw Java types and unchecked conversions remain
diagnostics; no blanket warning suppression is installed.

A template that owns only some managed parameters can receive the missing ones.
Abstract classes also allow propagation from a generic superclass. A concrete
class that already supplies all mappings remains concrete: the engine must not
arbitrarily turn it into a new generic template.

Completion covers, among other cases:

| Surface | Comportement |
|---|---|
| Source superclasses | Raw/partial clauses completed before Enter |
| Compiled superclasses | Package metadata and parameters read during processing |
| Member classes | Recursive processing, resolution by qualified names |
| Fields, signatures, locals, casts and creations | Raw/partial managed types completed with lexical variables or concrete mappings |
| Local types in methods | Detection in blocks, loops, switch statements, lambdas, and anonymous bodies |
| Generic bounds | Dependencies propagated; explicit bounds preserved |
| Nested wildcards | Missing arguments completed when their declaration is known |
| Imports | Explicit imports, wildcard imports, and member types; no arbitrary choice when ambiguous |

 A method parameter such as `<ENTITY>` shadows the class's managed name.
A **value** identifier named `ENTITY`, or a qualified class `other.ENTITY`, must
not trigger parameter injection. Ordinary generics and genuine raw usages
without managed context remain normal Java constructs; Jype does not replace
javac inference.

## Public declarations versus JVM signatures

The application JAR retains its complete generic `Signature` attributes, JVM
descriptors, and static storage behavior. Jype-aware tools use CLASS-retained
origin metadata to distinguish the technical parameters from ordinary Java
generics. An IDE without Jype can still show the internal parameters.

`fr.thegamerfarmer.jype.annotations.JypeClass` has version `1`,
`managedParameters` (final technical order), `declaredParameters` (the names
written by the author **before Enter**), and `typeSystem` (the effective
declaring package). `JypeMember` records the original field/return `sourceType`,
original method `declaredParameters`, and `generated` helper provenance.
Neither annotation is runtime-retained or changes binary method signatures.
Unsupported versions or inconsistent metadata fail explicitly rather than
silently hiding Java parameters.

The public signature remains `ENTITY getNode()` or `List<ENTITY>`, even when
the caller's effective mapping is `ConcreteNode`. That spelling describes the
declaration; compilation still checks the caller's concrete type. Ordinary
method parameters such as `<ENTITY extends Number>` that shadow an alias remain
ordinary generics. Older JARs remain usable through package metadata and JVM
signatures where the existing rules establish ownership; recompilation provides
precise declaration origins.

## Static members: shared storage, generic views

```java
package api;

public class Registry {
    @lombok.Getter @lombok.Setter
    private static java.util.Map<String, ENTITY> entities =
        new java.util.HashMap<>();

    static ENTITY first(ENTITY fallback) {
        return entities.getOrDefault("first", fallback);
    }
}
```

Java normally prohibits class type variables in static members. Jype lowers
static storage to an erased type, then creates view methods whose type
parameters belong **to the method**. Contextual accesses, direct assignments,
and static calls use these views. Ordinary parameters already declared on a
static method remain independent.

**There is one storage location per loaded class, not one per generic mapping.**
Two consumers using incompatible mappings can cause a cast failure at runtime.
The engine emits a shared-storage warning; it provides no isolation,
synchronization, or additional runtime type checks. Avoid this model when
incompatible families must coexist; use an instance registry instead.

Standard Lombok `@Getter` / `@Setter` accessors with `AccessLevel` are supported,
including package/protected/private levels and `NONE`. A `final` field does not
receive a setter. Explicit accessors are not duplicated. Lazy variants,
`onMethod`/`onParam`, and custom `@Accessors` names on these fields are rejected;
write an explicit static method for these cases.

Static imports, inherited fields, and compiled generic signatures are tested.
Overloads with incompatible generic parameter orders do not provide a reliable
order for automatic rewriting. Ordinary class parameters remain illegal in a
static context.

## Compiler execution and processor coexistence

Full operation requires **both components**:

- `-Xplugin:Jype` enables transformations before Enter;
- `fr.thegamerfarmer.jype.JypeProcessorShim` runs passes after Enter.

The processor alone runs too late to fix some partial arities. The plugin alone
does not perform all bound injections or create Javadoc copies. Each incomplete
configuration produces an explanatory warning. `-proc:none` disables the
processor, not a plugin requested with `-Xplugin:Jype`.

If an explicit processor list or processor path is used, include Jype **and**
the other required processors. Declaring an explicit path can disable implicit
discovery of others. Jype returns `false` from `process` and does not claim
exclusive ownership of its annotation. Lombok is tested with transformed
signatures; any other processor that modifies the same trees must be validated
in its actual configuration.

The JAR must be visible to both the plugin loader and the processor loader. On
JDKs that do not discover processors implicitly, select them explicitly.
`--add-exports` / `--add-opens` are options for the **JVM running javac**, not an
alternative to selecting the processor. `consumer.jvm.config` lists the
required exports and opens. With the `javac` launcher, prefix JVM options with
`-J`; with an embedded compiler, pass them to its host JVM.

The shims check exports/opens before loading internal classes. When explicit
options are complete, no automatic use of Unsafe is needed. Otherwise, an
automatic opening is attempted; a JDK or policy that prohibits it produces an
error listing the expected options. This internal mechanism does not guarantee
compatibility with future JDKs.

## Javadoc sources

After a successful final round, the processor writes copies to
`jype-javadoc-src`, next to the CLASS_OUTPUT directory. For a standard Maven
build: `target/jype-javadoc-src`.

The original sources are never modified. The patcher uses syntax positions to
preserve comments, strings, dimension annotations, and ordinary arguments.
Paths follow the declared package, including for generated or in-memory
sources. The documentary copies hide managed class parameters, technical
arguments, recursive expansions and static helper implementations. Ordinary
generics, their bounds, comments and method-level alias shadows are preserved.
Technical `@param` tags disappear with their parameters.
Shared ordinary parameters omitted in abbreviated inheritance are restored:
`Partial<A> extends Generic<String>` is documented as `Generic<A, String>`
when `Generic` declares `<A, B>`. These are ordinary Java parameters, not
technical slots to hide.

Documentary alias interfaces, such as `ENTITY`, provide real Javadoc links.
Source copies are reserved before aliases are written. If an alias differs
from a declaration only by case (for example `MARK` and `Mark`), the alias is
placed in a separate documentary package and imported into the copies. This
avoids filename collisions on case-insensitive filesystems while keeping
`MARK` visible in signatures and preserving its documentation link.
Metadata-bearing compiled ancestors and referenced managed types receive
doc-only declaration shells so their internal signatures do not leak into
hierarchy and inherited-member sections. Dependency shells cannot recover
comments absent from a compiled JAR; a library's own copies preserve its source
comments. **Never compile `jype-javadoc-src` into the application**: its aliases
and shells are documentary types, not runtime replacements.

Use these copies as input to the Javadoc tool, not the original abbreviated
sources. A generation failure is a warning separate from compilation. Invalid
files, duplicate destinations, and ambiguous imports are not guessed
silently. Previously generated Java copies are cleared when the output path is
initialized; a failed generation is not presented as newly generated output.
The engine generates sources, not HTML: run the standard Javadoc tool over
these copies to publish documentation.

## Diagnostics

Parameters injected after Enter receive Java's implicit `Object` upper bound
before their symbols become visible to other classes. The following injection
pass replaces this initial bound with the effective Jype bound; cross-class
bound inspection must not depend on source processing order.

A template whose alias is overridden by a concrete subclass of a self-bounded
generic hierarchy keeps the ancestor's recursive bound. An unrelated generic
ancestor is not a replacement for the configured bound: a manager extending a
shared base still requires the manager contract, not just that base contract.
Source binding classes inheriting an abbreviated local template also acquire
its parameters instead of leaving inherited collections erased.

Contextual instance arguments are not inserted inside `java.lang.Class` type
tokens. Reflection APIs taking `SomeType.class` operate on the runtime class,
not on a reified version of its Jype arguments.

| Symptom | Check |
|---|---|
| Wrong number of arguments before annotation processing | Check that `-Xplugin:Jype` is enabled in the javac actually being used |
| Parameters exist but bound members cannot be found | Check that `JypeProcessorShim` runs and that names match |
| `IllegalAccessError` / inaccessible javac field | Apply `consumer.jvm.config` options to the compiler JVM |
| Parent or annotation not found | Check the classpath and the library's `package-info.class` |
| Another processor no longer runs | Check the explicit processor list/path, not only Jype |
| Static storage warning | Check that only one compatible family uses the storage |
| Javadoc copies missing | Check the final round, earlier errors, and CLASS_OUTPUT |
| IDE/compilation differ | The IDE is an assistance view; validate with the actual compiler |

`-Ajype.debug=true` enables detailed processor diagnostics. When reporting an
issue, provide JDK versions, compiler options, a minimal example with
`package-info.java`, and complete diagnostics, without secrets.

## License

The engine is distributed under [Apache License 2.0](../LICENSE), like the IDE
plugin. Copyright 2026 TheGamerFarmer and Jype contributors. See
[NOTICE](../NOTICE) for attributions and [LICENSING.md](../LICENSING.md) for a
summary of rights, obligations, and dependency licenses. The distributed JAR
includes the license text and attribution notices.

## Architecture

| Component | Responsibility |
|---|---|
| `JypePlugin` | Collects PARSE events and triggers one transformation at the first Enter |
| `PreEnterTransformer` | Coordinates pre-Enter collaborators and isolates type systems |
| `DeclarationOrigins` | Captures source provenance before Enter and publishes metadata on final generation symbols |
| `SourceHierarchy` | Canonical index of declarations and concrete mappings per type system |
| `ParsedTypeSystems` | Reads managed names before symbols exist |
| `SourceTypeResolver` | Lexical/import resolution and classpath probing without loading classes |
| `ManagedTypeUsage` | Detects names only in type positions |
| `ManagedParameters` / `ManagedParameterPropagation` | Injects class parameters and propagates dependencies to a fixed point |
| `InheritanceCompletion` / `BoundTypeCompletion` / `MemberTypeCompletion` | Separately completes inheritance, bounds, and member signatures |
| `PreEnterTrees` / `TypeArgumentCompletion` | Builds trees without symbols and preserves user arguments |
| `StaticBoundResolver` / `StaticOwners` | Package bounds and metadata for inherited/compiled static owners |
| `StaticTypeSupport` / `StaticAccessRewriter` | Lowers static declarations, then rewrites accesses while tracking local scopes |
| `JypeProcessor` | Coordinates rounds and selects a type system for each root |
| `TypeSystemResolver` | Validates annotations and resolves TypeMirrors |
| `TypeParamInjector` | Adds missing parameters to AST, symbols, and scope after Enter |
| `TypeInjectionPass` | Adds recursive bounds and field/superclass arguments after Enter |
| `ProcessorTypeArguments` | Adds mixed arguments after Enter, tracking injections and existing TypeVars |
| `JavadocSourceGenerator` | Collects and writes copies at the end of processing |
| `DocumentaryDependencies` | Builds doc-only public declaration shells for compiled managed dependencies |
| `JavadocSourcePatcher` / `JavadocPatchCollector` / `JavadocTypeScope` | Separates parsing/tokenization, insertion collection, and lexical resolution |
| `TypeSystemClassFileReader` | Metadata and signatures for separate compilation |
| Shims and `JypeModuleOpener` | Loading boundary and access to javac internals |

Trees, symbols, project caches, and TypeMirrors remain local to a compilation
task. Pre-Enter passes must not create symbols that Enter will create later.
Post-Enter passes must keep ASTs and symbols synchronized.

## Development and validation

**JDK 21 minimum.** The suites run with JDK 21 and 25. The engine depends on
javac internals and is compiled with the JDK it targets. Build the Java 21
distribution with **JDK 21**, not with `--release 21` from JDK 25. A build run
under JDK 25 produces Java 25 classes.

From this module:

```powershell
mvn -B clean verify
mvn -B "-Dtest=JypeStaticTest,ManagedTypeUsageTest" test
mvn -B javadoc:javadoc
```

`verify` runs real compilation tests, runtime verification programs, separate
compilation scenarios, and Javadoc tests. It also generates engine
documentation (including internal members) with doclint to check syntax and
links; `javadoc:javadoc` reruns that step alone. Documentation is written to
`target/reports/apidocs`. JaCoCo 0.8.14 measures **all production classes**,
without exclusions, and enforces **80% line and 80% branch coverage at the
module level**. HTML and XML reports are in `target/site/jacoco`; test
diagnostics are in `target/surefire-reports`. Test workspaces are confined to
`target` and cleaned up afterward.

Compatibility scenarios specific to one JDK version may be skipped on the
other; shared behavior assertions remain active. Changes to javac internals
must be checked on both JDKs, not only by compiling the public annotations.
