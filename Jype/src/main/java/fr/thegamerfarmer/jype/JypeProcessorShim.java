package fr.thegamerfarmer.jype;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import java.lang.reflect.InvocationTargetException;
import java.util.Set;

/**
 * Entry point registered in {@code META-INF/services}.
 *
 * <p>Imports <b>no</b> {@code com.sun.tools.javac.*} types so the JVM can load
 * this class without an {@code IllegalAccessError}. The static initializer
 * opens the modules through {@link JypeModuleOpener}, then loads
 * {@link JypeProcessor} reflectively; the packages have already been exported
 * by then.
 */
@SupportedAnnotationTypes("*")
@SupportedOptions("jype.debug")
public class JypeProcessorShim extends AbstractProcessor {

    static {
        JypeModuleOpener.open();
    }

    private AbstractProcessor delegate;

    /** Creates the service-loaded wrapper after the module-access check completes. */
    public JypeProcessorShim() {}

    /**
     * Constructs the implementation only after javac internals are accessible.
     * Wrapped environments are passed through unchanged for the implementation to unwrap.
     */
    @Override
    public synchronized void init(ProcessingEnvironment env) {
        super.init(env);
        try {
            delegate = (AbstractProcessor) Class.forName("fr.thegamerfarmer.jype.JypeProcessor", true,
                            JypeProcessorShim.class.getClassLoader())
                    .getDeclaredConstructor()
                    .newInstance();
            delegate.init(env);
        } catch (ReflectiveOperationException | LinkageError e) {
            Throwable cause = e instanceof InvocationTargetException invocation ? invocation.getCause() : e;
            if (cause instanceof ThreadDeath death) throw death;
            if (cause instanceof VirtualMachineError fatal) throw fatal;
            throw new IllegalStateException("[Jype] Cannot initialize the processor on JDK "
                    + Runtime.version() + "; check this Jype version's compatibility.", cause);
        }
    }

    /** Delegates every round without claiming annotations on the implementation's behalf. */
    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        return delegate.process(annotations, roundEnv);
    }

    /** Matches the executing compiler rather than emitting a stale source-version warning. */
    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }
}
