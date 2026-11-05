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
`package-info` with the small Jype icon. Its on-disk name remains
`package-info.java`; other files are displayed as before.

## Java analysis

Member completion relies on PSI inheritance from the bound, including members
provided by other plugins such as Lombok. The plugin no longer globally
suppresses errors mentioning `API_*`: unresolved calls, type incompatibilities,
and nullability warnings remain visible.

Cast analysis accounts for concrete aliases: a cast such as
`(API_PLAYER) new APIPlayer()` does not receive a false `ClassCastException`
warning if the operand's type is assignable to the declared bound. The
adaptation affects only the data-flow inspection of the cast type, not its
other diagnostics. Genuine generic parameters, ordinary Java classes, and
casts to a narrower bound are not exempt.

**Limitation:** virtual classes provide a view of bounds, not a complete
execution of the engine's AST transformations. Partial generics and some
F-bounded relationships can therefore still produce IDE diagnostics that
differ from javac. Resolving a name successfully does not guarantee that the
code compiles; the build with the engine remains authoritative.

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
The ZIP was also checked with an IntelliJ IDEA 2026.2.4 installation
(IU-262.11879.100): no binary incompatibilities were detected. Read-action
and daemon-restart APIs remain deprecated on this version but are retained for
compatibility with the 2024.1 SDK.

`gradle\gradle-daemon-jvm.properties` selects Java 21 for Gradle without a
machine-specific absolute path. If no JDK 21 is detected, set `JAVA_HOME` to
its installation before launching the wrapper. Gradle 8.11.1 must not run its
daemon under Java 25.

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
