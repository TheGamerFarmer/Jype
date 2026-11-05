package fr.thegamerfarmer.jype;

import com.sun.source.util.JavacTask;
import com.sun.source.util.Plugin;

import java.lang.reflect.InvocationTargetException;

/**
 * Entry point for the javac plugin, registered in META-INF/services.
 *
 * <p>Imports no {@code com.sun.tools.javac.*} types: first opens the modules
 * through {@link JypeModuleOpener}, then loads {@link JypePlugin} reflectively;
 * the packages have already been exported by then.
 */
public class JypePluginShim implements Plugin {

    /** Creates the service-loaded boundary without linking javac implementation classes. */
    public JypePluginShim() {}

    /** Exposes the same plugin name as the deferred implementation. */
    @Override
    public String getName() {
        return "Jype";
    }

    /**
     * Checks JVM module access before constructing the task-local implementation.
     * Compatibility failures retain their cause and executing JDK; fatal JVM errors propagate.
     */
    @Override
    public void init(JavacTask task, String... args) {
        JypeModuleOpener.open();
        try {
            Plugin impl = (Plugin) Class.forName("fr.thegamerfarmer.jype.JypePlugin", true,
                            JypePluginShim.class.getClassLoader())
                    .getDeclaredConstructor()
                    .newInstance();
            impl.init(task, args);
        } catch (ReflectiveOperationException | LinkageError e) {
            Throwable cause = e instanceof InvocationTargetException invocation ? invocation.getCause() : e;
            if (cause instanceof ThreadDeath death) throw death;
            if (cause instanceof VirtualMachineError fatal) throw fatal;
            throw new IllegalStateException("[Jype] Cannot initialize the plugin on JDK "
                    + Runtime.version() + "; check this Jype version's compatibility.", cause);
        }
    }
}
