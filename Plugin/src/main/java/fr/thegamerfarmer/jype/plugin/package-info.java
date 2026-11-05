/**
 * IntelliJ integration for package-declared Jype aliases.
 *
 * <p>Java lookup, completion, highlighting and refactoring share one project-owned PSI cache.
 * Annotation syntax and inheritance resolution remain separate from virtual-class publication
 * so recursive Java lookups cannot publish incomplete mappings. Unless documented otherwise,
 * PSI-facing entrypoints require the platform's read access; callers must not retain resolved
 * PSI beyond the corresponding modification-tracked generation.
 *
 * <p>Maven support inspects only declarations written in a local POM. The configurator owns
 * editor and command lifecycle, the model owns read-only namespace-aware navigation, and the
 * editor owns structural insertions. French strings are editor-facing diagnostics; internal
 * contracts and validation logic are documented in English.
 */
package fr.thegamerfarmer.jype.plugin;
