# Jype for IntelliJ IDEA

This plugin complements the Jype javac engine; it does not replace it or modify
Java sources for compilation.

## Installation

Publishing a GitHub Release automatically builds and verifies the plugin from
its tag, then attaches the plugin ZIP to that release's assets. Download that
ZIP and install it through **Settings > Plugins > Install Plugin from Disk**
in IntelliJ IDEA. The attachment appears after the plugin workflow finishes
successfully; rerunning the workflow replaces the matching asset.

Push and pull-request builds also provide the ZIP in the
`jype-intellij-plugin` artifact in GitHub Actions.

## Resolution and diagnostics

Virtual names come exclusively from the
`fr.thegamerfarmer.jype.annotations.TypeSystem` and `TypeDef` annotations. They
are not limited to the `API_` prefix: all valid Java identifiers are accepted.

The plugin selects the type system from the most specific package, respects
module resolution scope, and reads `inherit` and `overrides`, including from a
library's `package-info.class`. Constant names and imported, nested, or
interface bounds are resolved through the IDE's Java resolver. Cycles,
duplicates, and invalid definitions are reported on the annotation; an invalid
type system does not silently fall back to its parent's definitions.

Each virtual type shares one instance for a given type-system declaration,
regardless of search scope. Scope filters visible declarations but does not
create a new Java identity for the same type.
Its non-physical PSI file is attached to the declaring `package-info`, so field
and method references in subpackages remain visible in module scope, including
through inheritance. Generic collections use this same identity; their
inspections are not disabled for Jype types.
Caches are project-scoped and invalidated when PSI or project roots change. No
index search is performed during indexing. No stub file is created or deleted
on disk. Bounds in a source `package-info` are read by resolving imports
directly, without re-entering resolution of that file's own `.class`
expression. A result interrupted by an IDE recursion guard is not cached.

Compiled dependencies can carry Jype's versioned `JypeClass` metadata. It
identifies managed parameter positions without changing the library's Java
generic signatures. The plugin reads this metadata through PSI, never by
loading application classes. Older libraries use their visible package type
system and parameter names; unknown or invalid metadata is reported in the IDE
log and is not replaced with guessed mappings.

## Renaming and project tree

Pressing Shift+F6 on a Jype type usage or its `@TypeDef(name=...)` string
renames the declaration and its Java references, including qualified
references, subpackages, and systems derived through `inherit` and `overrides`.
Renaming uses the IDE's standard dialog and can be undone with Ctrl+Z.
Unrelated types with the same name, Java generic parameters, and ordinary
strings are not changed. Name conflicts are reported before modification. A
declaration from a library, or one whose name is a constant, must be renamed in
its source; the plugin refuses to modify it as an editable string.

In the project tree, a `package-info.java` with Jype's `@TypeSystem` is shown as
`package-info` with the small Jype icon, including in the editor tab. Its on-disk name remains
`package-info.java`; other files are displayed as before.

Inside `@TypeSystem(inherit = "...")`, basic completion (`Ctrl+Space`) suggests
visible packages declaring valid type systems, including library declarations.
It excludes the current package and choices that would introduce an inheritance
cycle. Inside an override's `@TypeDef(name = "...")`, it suggests names from the
inherited system, excluding names already overridden in that declaration.
These suggestions respect the file's module scope and wait for indexing;
unrelated strings keep normal Java completion.
Jype `TypeDef.name` strings used to create definitions do not offer existing
names or Java word-completion suggestions. Override suggestions exclude only
other overrides, not the name currently being edited.

An explicitly declared `overrides` attribute requires a non-empty `inherit`,
even for `overrides = {}`. An override name must exist in the inherited system,
including its transitive parents. These violations are errors in both the IDE
and compilation with the Jype engine. On missing inheritance, **Alt+Enter >
Add inherit to @TypeSystem** inserts `inherit = ""` (or replaces an empty
value), places the caret inside the string, and opens package completion.
The quick fix preserves existing definitions and supports undo.

## Java analysis

Member completion relies on PSI inheritance from the bound, including members
provided by other plugins such as Lombok. The plugin no longer globally
suppresses errors mentioning `API_*`: unresolved calls, type incompatibilities,
and nullability warnings remain visible.

Partial `extends` and `implements` arguments are recognized using Jype's
shared-name and trailing-managed-parameter rules, including classes from
compiled libraries. The IDE does not report a false generic arity error when
the missing arguments can be supplied by a valid visible type system.
Bounds are checked against the completed argument list; incompatible explicit
arguments or overridden bounds still produce errors. Missing ordinary arguments,
extra arguments and code outside a valid type system retain Java diagnostics.

Cast analysis accounts for concrete aliases: a cast such as
`(ApiEntity) new AbstractEntity()` does not receive a false `ClassCastException`
warning if the operand's type is assignable to the declared bound. The
adaptation affects only the data-flow inspection of the cast type, not its
other diagnostics. Genuine generic parameters, ordinary Java classes, and
casts to a narrower bound are not exempt.

In a valid caller type system, managed arguments of Jype library types are
supplied automatically in fields, method signatures, local types and nested
generic applications. Java member resolution receives the complete substitution:
a `LibCore` variable can return `ENTITY` or `List<ENTITY>` through inherited
methods, rather than their raw erasures. Views of inherited non-generic methods
and fields also support abbreviated source inheritance. User declarations,
accessibility and ordinary method parameters remain under Java's control.

As in the engine, an alias whose binding is a class without type parameters
*is* that class in a class that names no alias: in a consumer overriding
`ENTITY` and `KIND` with `ImplAlpha` and `ImplBeta`, `item.getKind()` is an
`ImplBeta`, so `instanceof Tag`, `Map<String, ImplBeta>.put(...)` or
`this.item = item` are accepted. Template bindings (an inherited
`J_STORE` bound to the library's `StoreBase`) and classes that name
aliases themselves keep the alias type. A method such as
`process(ImplAlpha)` or `inject(ImplAlpha)` in a class directly extending or
implementing a raw Jype parent implements the completed member, as javac sees
it; the corresponding `@Override` and "must implement" diagnostics are hidden,
while a genuinely incompatible signature is still reported. Views exist only
for members whose generic signature raw inheritance would erase; the
resulting same-erasure clash with the raw inherited member is hidden.
Completed types are never treated as `var`-like inferred types.

Template aliases (an inherited `J_STORE` bound to `StoreBase`) extend
their template applied to the system's own bindings, so `store.builder()`
or `registry.resolve(…).apply(…)` keep their generic types. IntelliJ still
sees `class ImplAlpha extends ApiAlpha` as raw; an `unchecked` warning is hidden
only when every call and conversion it concerns is valid **without** unchecked
conversion once raw Jype parents are completed (`super(item)`,
`super.inject(item)`, passing `this` or `new Collection()` where
`Contract<…>` is expected). A cast written without arguments, such as
`(LibCore) plugin`, is a checked cast to a raw type and is not reported either.
Other unchecked casts, raw values written by the user and unrelated raw
generics keep their warnings.

Raw-reference diagnostics are suppressed only when all omitted arguments are
managed and a completed substitution satisfies their bounds. Missing ordinary
arguments, invalid overrides, genuine unchecked operations and code without a
valid visible system retain their diagnostics.

Creations such as `new LINK(entity)` or `ENTITY::new` resolve against the
non-generic constructors of the alias binding, mirrored on its virtual class;
missing constructors are still reported. A `private` or `protected` binding
constructor keeps its access, as in the engine: a singleton may write
`new SERVICE()` inside its own class, while an inaccessible creation is an
error. The engine's generated
`Jype$New$…` factories and `Jype$Install` classes are hidden from completion.
Which class is created at runtime is decided by the engine, not by the IDE.

## Public signatures and documentation

Documentation, quick navigation, completion and parameter help present Jype
types by their declared name: `ENTITY getNode()`, not the caller's concrete
`ConcreteNode`, and `LibCore`, not its injected parameter list. Ordinary Java
generics and their bounds remain visible. The documentation keeps Java's
comment content and provides links to the Jype aliases; both the declaration
and the containing-class presentation omit technical expansions.

Versioned `JypeMember` metadata preserves the original return and field type
syntax, including aliases erased by static lowering. It also distinguishes
injected method parameters and technical helpers from ordinary generic
parameters and business methods. Completion hides only helpers identified by
valid metadata, never user members merely named `$jype$...`. Missing source
imports are reconstructed only from unambiguous binary type names; invalid
metadata or unresolved original types are logged and retain Java's view.

This adaptation is automatic. The canonical Java types and substitutions keep
their complete generic information; hiding a parameter in presentation never
removes it from the library's binary contract. Recompile libraries with the
updated engine to publish precise metadata. An IDE without Jype can still show
the implementation's generic signatures.

**Limitations:** virtual classes still model bounds rather than executing every
compiler transformation. In the supported IntelliJ API, `new` expressions do
not use the type-element inference hook. Generic methods (with their own type
parameters) inherited through an abbreviated, raw source superclass also
retain Java's raw analysis. Some recursive
F-bounds and static transformations can consequently differ from javac.
These paths are not covered by blanket unchecked-warning suppression.
The build with the engine remains authoritative.

## Maven configuration

The banner in Java files, including `package-info.java`, offers to configure the
Jype dependency, `-Xplugin:Jype`, and the Maven Compiler plugin's processor
path. XML editing is structural, preserves other plugins and processors,
respects unsaved documents, and can be undone with Ctrl+Z. A new Jype
dependency uses the `provided` scope.

If a processor path must be created and a Lombok dependency is present, Lombok
is retained with its locally declared or managed version. Invalid XML or an
undeterminable Lombok version is reported without modifying the file. The
Ignore button hides the banner for that file during the session.

The configurator reads **only the local POM**, not the effective Maven model:
parents, imported BOMs, and profiles are not evaluated. An inherited
configuration may therefore be reported as missing. Other processors that
were previously discovered automatically must be declared in
`annotationProcessorPaths` before using the configurator. Gradle projects
retain their manual configuration.

## Development and compatibility

The build and test SDK is IntelliJ IDEA Community 2024.1.7, so the plugin's
minimum build is 241. Later versions must be checked with Plugin Verifier
before distribution; having no upper bound does not guarantee compatibility
with every future version.
The plugin was also checked with an IntelliJ IDEA 2026.2.4 installation
(IU-262.11879.100): no binary incompatibilities were detected. Read-action
and daemon-restart APIs remain deprecated on this version but are retained for
compatibility with the 2024.1 SDK.

`gradle\gradle-daemon-jvm.properties` selects Java 21 for Gradle without a
machine-specific absolute path. If no JDK 21 is detected, set `JAVA_HOME` to
its installation before launching the wrapper. Gradle 8.11.1 must not run its
daemon under Java 25.
If Java 21 is installed outside Gradle's discovery locations, pass
`"-Dorg.gradle.java.installations.paths=C:\path\to\jdk-21"` to the wrapper.
The same option can be added to **Run Plugin > Gradle arguments** in the IDE.
This selects an existing installation without changing the shared Java 21
requirement or embedding a machine-specific path in the project.

```powershell
.\gradlew.bat check buildPlugin verifyPlugin
```

The built artifact is `build\distributions\JypePlugin-1.0.0.zip`.
For public distribution, see [Installation](#installation).

To verify binary compatibility with a local installation:

```powershell
.\gradlew.bat runPluginVerifier "-PjypeVerifierIde=C:\path\IntelliJ IDEA"
```

Tests use the IDE's real PSI and cover resolution, scopes, compiled inheritance,
bound changes, indexing, actual diagnostics, XML modifications, and undo.
No engine JAR is required to build the plugin.
The transitive fixture compiles and reads separate library and API JARs before
compiling their consumer. To additionally exercise actual engine transformations
instead of the fixture's pre-expanded signatures, build the Java 21 engine JAR
and run:

```powershell
.\gradlew.bat test --tests "*JypeTransitiveLibraryTest" "-PjypeTestEngine=C:\path\Jype.jar"
```

This property configures only the development fixture, not plugin behavior.
All three compilation stages use strict raw/unchecked lint, and the fixture
checks real member substitutions, diagnostics and rendered documentation.

`check` also generates Javadoc and enforces at least 80% production line and
branch coverage with JaCoCo. Reports are in
`build\reports\jacoco\test` (HTML and XML), and Javadoc in
`build\docs\javadoc`. Tests measure source-code bytecode, not nullability checks
added by the IntelliJ compiler; the ZIP retains its normal instrumentation.

The plugin does not simulate the engine's static member storage: a Jype
specialization does not have its own `static` field. See the
[engine contract](../Jype/README.md) before sharing a field across bindings.
The [main README](../README.md) introduces the language, and the
[licensing documentation](../LICENSING.md) explains rights and obligations.

## License

This plugin is distributed under [Apache License 2.0](../LICENSE), like the
engine. Copyright 2026 TheGamerFarmer and Jype contributors. Attribution
notices are in [NOTICE](../NOTICE) and are included with the license text in
the distributed JAR.
