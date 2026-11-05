# Jype Licensing

The javac engine, IntelliJ plugin, and original project files are distributed
under **Apache License, Version 2.0**, SPDX identifier `Apache-2.0`. The full
official text is in [LICENSE](LICENSE). Project attributions are in
[NOTICE](NOTICE).

Copyright 2026 TheGamerFarmer and Jype contributors.
Contributions remain the property of their authors; this license does not
transfer their copyright.

## What you may do

You may use Jype free of charge, copy and modify it, create and redistribute
forks, submit pull requests, and integrate it into commercial projects.
Selling copies and modified versions is also permitted. Changes do not have
to be made public or submitted to the original project.

Compiling a project with Jype does not automatically place that project's code
under Apache-2.0. Copying Jype code or redistributing Jype itself remains
subject to its license terms.

## Redistribution obligations

- Include a copy of the license with Jype or derivative works.
- Clearly mark changes in modified files.
- Retain relevant copyright, patent, trademark, and attribution notices in
  redistributed derivative works.
- Reproduce relevant `NOTICE` attributions in one of the locations permitted
  by section 4 of the license.

The license does not grant general permission to use the authors' trademarks,
names, or logos to promote a product. Reasonable attribution of the code's
origin remains permitted under section 6.
The software is provided without warranty; warranty disclaimers and limitations
of liability apply to the extent permitted by applicable law. The patent grant
and its termination clause are defined in section 3.

This summary is explanatory; the text of `LICENSE` governs and is not modified
by this document.

## Contributions

Unless expressly stated otherwise, a contribution intentionally submitted for
inclusion in Jype is offered under Apache-2.0, in accordance with section 5.
Submit only code that you are entitled to contribute, and retain notices and
licenses for third-party files. A pull request does not assign copyright or
obligate maintainers to accept it.

## Dependencies and third-party files

Apache-2.0 does not replace the licenses of third-party components. Notices
already present in third-party files, including the Gradle wrapper, must remain
intact. Test tools and dependencies are not included in Jype distributions:

| Component | Upstream license | Usage |
|---|---|---|
| JetBrains Java annotations | Apache-2.0 | Engine dependency |
| Gradle and wrapper | Apache-2.0 and applicable third-party notices | Build and wrapper in the repository |
| IntelliJ Community SDK | Apache-2.0 for the platform; third-party components have their own licenses | Build and tests; SDK not bundled |
| JUnit 4 | EPL-1.0 | Plugin tests |
| JUnit 5 | EPL-2.0 | Engine tests |
| Lombok | MIT | Engine integration tests |
| JaCoCo | EPL-2.0 | Coverage measurement |

JDKs and consumer projects have their own terms and are not included under this
license. Before distributing an archive with additional dependencies, verify
the licenses and notices of all components actually included.

The engine and plugin JARs include `META-INF/LICENSE` and `META-INF/NOTICE`.
They do not redistribute the IntelliJ SDK or test dependencies.

Upstream sources:
[JetBrains annotations](https://github.com/JetBrains/java-annotations/blob/24.0.0/LICENSE.txt),
[Gradle](https://github.com/gradle/gradle/blob/master/LICENSE),
[JUnit 4](https://junit.org/junit4/license.html),
[JUnit 5](https://github.com/junit-team/junit5/blob/main/LICENSE.md),
[Lombok](https://projectlombok.org/LICENSE),
[JaCoCo](https://www.jacoco.org/jacoco/trunk/doc/license.html).
