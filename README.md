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
repository and dependency below, replacing `OWNER` and `REPOSITORY` with the
GitHub account and repository name:

```xml
<repositories>
    <repository>
        <id>jype-github-pages</id>
        <url>https://OWNER.github.io/REPOSITORY/maven/</url>
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
engine is built with Java 21; consumers need JDK 21 or newer. The compiler
integration requirements are described in the [engine guide](Jype/README.md).

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
