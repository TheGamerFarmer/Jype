/**
 * Phase-separated javac transformation engine.
 *
 * <p>{@link fr.thegamerfarmer.jype.JypePlugin} collects parsed units and delegates
 * symbol-free rewriting to the pre-Enter transformer. The processor then validates
 * package annotations, injects missing parameters, and synchronizes AST bounds with
 * javac TypeVars. Javadoc copying is a final, best-effort output operation, not a
 * generated-source round.
 *
 * <p>Compiler contexts, trees and type mirrors are task-local. Only JDK compatibility
 * reflection handles may be shared across tasks. New transformations must respect the
 * Enter boundary and must not cache project-specific symbols in static state.
 */
package fr.thegamerfarmer.jype;
