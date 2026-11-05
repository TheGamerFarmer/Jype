package fr.thegamerfarmer.jype;

import javax.annotation.processing.ProcessingEnvironment;
import javax.tools.Diagnostic;

/** Centralizes opt-in processor notes so normal compilation stays quiet. */
final class JypeDiagnostics {
    private JypeDiagnostics() {}

    /** Emits a NOTE only when {@code -Ajype.debug=true}; never changes compilation success. */
    static void debug(ProcessingEnvironment env, String message) {
        if (Boolean.parseBoolean(env.getOptions().get("jype.debug"))) {
            env.getMessager().printMessage(Diagnostic.Kind.NOTE, message);
        }
    }
}
