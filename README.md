# Jype

Jype is a **javac** extension that declares families of types at the package
level. Instead of repeating the same generic parameters throughout a hierarchy,
declare their names and bounds in `package-info.java`; the engine propagates the
necessary parameters through classes and their usages. An IntelliJ plugin
provides navigation and Java analysis for these sources.

The engine is published as a Maven dependency for tagged releases. See
[Maven installation](#using-the-engine-from-maven) for the repository setup.
**License: Apache-2.0** for the engine and plugin. See [LICENSE](LICENSE),
[NOTICE](NOTICE), and [licensing terms](LICENSING.md).

## Two components, two roles

| Component | Responsibility | Documentation |
|---|---|---|
| [Engine](Jype/README.md) | AST transformations during javac compilation, diagnostics, and Javadoc sources | Syntax, generics, static members, options, and limitations |
| [IntelliJ plugin](Plugin/README.md) | Virtual PSI types, completion, renaming, diagnostics, and POM assistance | Editor usage and analysis limitations |

The IDE plugin does not compile the language or replace the engine. The engine
does not depend on IntelliJ. When an IDE diagnostic differs from the compilation
result, the javac build with Jype is the reference.

## Using the engine from Maven

Stable engine releases are published to a public Maven repository on GitHub
Pages when a `v*` version tag is pushed. Push the first version tag to create
the `gh-pages` branch, then configure **Settings → Pages** to publish from that
branch's root directory. Subsequent tags publish automatically. Add the
repository and dependency below:

```xml
<repositories>
    <repository>
        <id>jype-github-pages</id>
        <url>https://thegamerfarmer.github.io/Jype/maven/</url>
    </repository>
</repositories>

<dependencies>
    <dependency>
        <groupId>fr.thegamerfarmer.jype</groupId>
        <artifactId>Jype</artifactId>
        <version>1.0.0</version>
    </dependency>
</dependencies>
```

Replace `1.0.0` with the desired release version (tag `v1.0.0`). The published
engine is built with Java 21; Maven and its compiler must run on JDK 21 or
newer (the engine is tested on JDK 21 and 25).

**The dependency alone does not enable Jype.** Installing the IntelliJ plugin
does not enable it in Maven either. Every module compiling Jype sources,
including a consumer of a separately compiled Jype library, needs the
dependency on its compile classpath, the javac plugin, and the annotation
processor. A raw or partial superclass such as `APISubPower<MASTER_POWER>`
relies on these transformations to supply the managed type arguments.

### Activate the compiler

Add this plugin inside your POM's `<build><plugins>`, or merge the configuration
into your existing `maven-compiler-plugin`. Use the same Jype version in the
dependency and processor path:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <version>3.13.0</version>
    <configuration>
        <proc>full</proc>
        <compilerArgs>
            <arg>-Xplugin:Jype</arg>
        </compilerArgs>
        <annotationProcessorPaths>
            <path>
                <groupId>fr.thegamerfarmer.jype</groupId>
                <artifactId>Jype</artifactId>
                <version>1.0.0</version>
            </path>
        </annotationProcessorPaths>
        <annotationProcessors>
            <annotationProcessor>fr.thegamerfarmer.jype.JypeProcessorShim</annotationProcessor>
        </annotationProcessors>
    </configuration>
</plugin>
```

This example runs javac inside Maven (the default, `fork=false`). Create
`.mvn/jvm.config` at the Maven invocation root with:

```text
--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED
--add-exports=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED
--add-exports=jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED
--add-exports=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED
--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED
--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED
--add-opens=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED
```

These options belong to the **JVM running the compiler**, not the application
or the source language level. With `<fork>true</fork>`, pass each option to
the forked javac as a `<compilerArgs><arg>-J--add-exports=...</arg>` or
`<arg>-J--add-opens=...</arg>` instead. With toolchains or an explicit compiler
executable, check that the selected compiler also uses JDK 21 or newer.
Jype can attempt automatic module opening when options are absent, but
explicit options avoid relying on Unsafe and restrictive JVM policies.

### Other processors, scopes and verification

If using Lombok or another processor, **retain it in both explicit lists**:
add its dependency to `annotationProcessorPaths` and its processor class to
`annotationProcessors`. For Lombok, that class is
`lombok.launch.AnnotationProcessorHider$AnnotationProcessor`; select a Lombok
version compatible with the compiler JDK. Replacing the processor path/list
with Jype alone disables discovery of other processors. `-proc:none` disables
the Jype processor and must not be used for a Jype consumer build.

The dependency may use `<scope>provided</scope>` when the engine is only a
compile-time tool. Both `provided` and the default `compile` scope expose its
annotations during compilation; a processor path alone does not. A `provided`
dependency in the API library does not propagate to consumers: each consumer
must declare Jype itself. The intended application runtime and any other
dependencies still determine the appropriate output bytecode target; changing
that target does not change which JDK runs Maven.

Run `mvn -version` to check Maven's JDK, then `mvn clean compile`. In IntelliJ,
check the Maven runner JDK and reload the Maven project. If configuration is
inherited or profile-dependent, inspect `mvn help:effective-pom` to confirm
the effective compiler arguments, processor path and processor list.

The IntelliJ plugin displays a **Jype warning banner** in Java files governed
by a visible type system when the nearest local POM lacks the direct Jype
dependency, `-Xplugin:Jype`, or the Jype processor path. **Configure** inserts
those entries; it does not validate the effective POM, resolve dependencies,
configure the Maven JDK or module-opening options, or check an explicit
processor list / `proc` mode. It also does not add the Maven repository.
The banner is deferred during indexing, absent without a detected type system
or POM, and can be dismissed with **Ignore** for that file during the session.
Its absence is therefore not proof that the build is correctly configured.

See the [engine guide](Jype/README.md#compiler-execution-and-processor-coexistence)
for processor coexistence and the language contracts.

## Declaring a type family

```java
@TypeSystem({
    @TypeDef(name = "Player", bound = PlayerBase.class),
    @TypeDef(name = "Team", bound = TeamBase.class)
})
package example.game;

import fr.thegamerfarmer.jype.annotations.TypeDef;
import fr.thegamerfarmer.jype.annotations.TypeSystem;
import example.model.PlayerBase;
import example.model.TeamBase;
```

Bounds must be accessible Java classes or interfaces. Names must be valid Java
identifiers; no particular prefix is required. In this package and its
subpackages, you can write:

```java
package example.game;

public class Match {
    private Player player;
    private Team team;

    public Player getPlayer() {
        return player;
    }

    public Team getTeam() {
        return team;
    }
}
```

`Player` and `Team` are not new Java files to create. The engine represents these
names as bounded generic parameters and completes their usages according to the
visible type system. The accessible members are those of the bound: to call a
method specific to a subtype, an appropriate bound or a justified Java cast is
required. Jype does not add methods missing from the bounds.

## Inheriting and specializing

A type system can reuse definitions from another package:

```java
@TypeSystem(
    inherit = "example.game",
    overrides = @TypeDef(name = "Player", bound = RankedPlayer.class)
)
package example.ranked;

import fr.thegamerfarmer.jype.annotations.TypeDef;
import fr.thegamerfarmer.jype.annotations.TypeSystem;
import example.model.RankedPlayer;
```

An override replaces a bound without requiring the new bound to be a subtype of
the old one. javac still checks generic constraints resulting from usages.
Inherited definitions that are not overridden remain available. Independent
type systems are not merged; the most specific package declaration determines
the active system. An invalid declaration must not silently fall back to the
parent's type system.

Ordinary Java generic parameters remain explicit. The engine can complete Jype
arguments omitted from usages of a transformed class; this is not a new general
Java inference syntax. Ordering rules, F-bounds, separately compiled classes,
and partial-argument examples are detailed in the engine README.

## Static members: shared storage

Jype supports `static` fields and methods that use these types. Fields retain
**one shared storage location**, erased to their bounds: there is no separate
field for each generic specialization. Reads, writes, and adapted accessors
provide typed views of that same storage.

All consumers must therefore use compatible bindings. Writing a value through
one specialization and reading it through another can cause a
`ClassCastException`. Unchecked-conversion warnings are part of this contract
and should not be ignored without reviewing the usages. See the engine README
for Lombok, separate compilation, and limitations.

## Using the editor

Open the declared sources in a Java module; the types become resolvable after
indexing. Completion uses their bounds, and `Shift+F6` renames a declaration and
its family of usages; the operation can be undone.
The Jype banner offers assistance with configuring the **local POM**. It does
not calculate the effective Maven model, profiles, or BOMs.

The IDE shows a virtual representation of the bounds, not all javac
transformations. Differences are still possible, especially for partial
generics, F-bounded relationships, and static views. Ordinary Java errors are
not globally suppressed.

## Diagnostics and limitations

| Symptom | Check |
|---|---|
| Type not found | Exact name in `TypeDef`, file package, imports, active type system, and module scope |
| Method not found | Whether the method exists on the declared bound, not only on an implementation |
| Jype missing during the build | `-Xplugin:Jype` activation, processor path, and compatible JDK, as described in the engine README |
| Unexpected Maven banner | Configuration may be inherited; assistance reads only the local POM |
| IDE and javac differ | Rerun the build with the engine; report a minimal example with IDE/JDK versions |
| Static cast fails at runtime | Incompatible bindings sharing the same `static` field |

Jype depends on internal javac APIs: each new major Java version requires
explicit verification. The engine is tested on JDK 21 and 25; this does not
promise compatibility with every intermediate or future version. The plugin
targets the IntelliJ 2024.1 SDK (build 241); see its documentation for
verification of later versions.

## Contributing and understanding the code

The engine separates type-system reading, AST passes, static views, and Javadoc
source generation. The plugin separates PSI reading/caching, IDE extension
points, renaming, and Maven XML configuration. Engine tests compile real small
projects, including runtime and separate-compilation scenarios. IDE tests use
the real PSI.

From `Jype`, `mvn verify` validates the engine. From `Plugin`, with JDK 21 for
Gradle:

```powershell
.\gradlew.bat check buildPlugin verifyPlugin
```

Builds enforce a JaCoCo threshold of **80% line and 80% branch coverage per
module**, without excluding large production classes. HTML and XML reports are
produced in each module's build directory and retained by CI. This threshold
helps prevent regressions; it does not prove the absence of bugs.

When proposing a change, include a reproducible scenario, the expected behavior,
and a regression test. Do not include consumer projects, the IntelliJ SDK,
caches, generated JARs, credentials, or local settings in a contribution.
Sources of Minecraft consumer projects are not part of this repository.
