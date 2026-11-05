package fr.thegamerfarmer.jype;

import sun.misc.Unsafe;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;

/**
 * Opens {@code jdk.compiler}'s internal packages for the current unnamed module.
 *
 * <p>Explicit exports/opens are used without Unsafe. Otherwise, a
 * "trusted" {@link MethodHandles.Lookup} enables automatic opening.
 *
 * <p>Must be called from {@link JypeProcessorShim}'s {@code static} block
 * <em>before</em> {@link JypeProcessor} is loaded through {@code Class.forName}.
 */
@SuppressWarnings("deprecation")
class JypeModuleOpener {

    private static final String[] PACKAGES = {
            "com.sun.tools.javac.api",
            "com.sun.tools.javac.code",
            "com.sun.tools.javac.model",
            "com.sun.tools.javac.processing",
            "com.sun.tools.javac.tree",
            "com.sun.tools.javac.util",
    };

    /**
     * Ensures exported javac packages and reflective TypeVar access for this module.
     * Idempotent and synchronized; explicit JVM options avoid the automatic-opening fallback.
     *
     * @throws IllegalStateException with actionable JVM flags if access cannot be established
     */
    static synchronized void open() {
        try {
            Module jdkCompiler = ModuleLayer.boot().findModule("jdk.compiler")
                    .orElseThrow(() -> new IllegalStateException("jdk.compiler module not found"));
            Module target = JypeModuleOpener.class.getModule();
            if (isAccessible(jdkCompiler, target)) return;

            MethodHandles.Lookup lookup = AutomaticOpening.trustedLookup();
            MethodType mt = MethodType.methodType(void.class, String.class, Module.class);
            MethodHandle implAddExports = lookup.findVirtual(Module.class, "implAddExports", mt);
            MethodHandle implAddOpens = lookup.findVirtual(Module.class, "implAddOpens", mt);

            for (String pkg : PACKAGES) {
                implAddExports.invoke(jdkCompiler, pkg, target);
            }
            implAddOpens.invoke(jdkCompiler, "com.sun.tools.javac.code", target);
            if (!isAccessible(jdkCompiler, target)) {
                throw new IllegalStateException("Incomplete jdk.compiler exports/opens");
            }
        } catch (ThreadDeath | VirtualMachineError e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException(
                    "[Jype] Cannot open jdk.compiler's internal packages. "
                            + "Runtime JDK: " + Runtime.version() + ". "
                            + "Use a full JDK and add these options to the JVM running javac "
                            + "(prefix with -J for the command-line javac launcher; these are not javac source options): "
                            + explicitOptions(),
                    e);
        }
    }

    private static boolean isAccessible(Module compiler, Module target) {
        for (String pkg : PACKAGES) {
            if (!compiler.isExported(pkg, target)) return false;
        }
        return compiler.isOpen("com.sun.tools.javac.code", target);
    }

    private static String explicitOptions() {
        StringBuilder options = new StringBuilder();
        for (String pkg : PACKAGES) {
            options.append("--add-exports=jdk.compiler/").append(pkg).append("=ALL-UNNAMED ");
        }
        return options.append("--add-opens=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED").toString();
    }

    private static class AutomaticOpening {
        static MethodHandles.Lookup trustedLookup() throws ReflectiveOperationException {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            Unsafe unsafe = (Unsafe) field.get(null);
            Field lookup = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
            return (MethodHandles.Lookup) unsafe.getObject(
                    unsafe.staticFieldBase(lookup), unsafe.staticFieldOffset(lookup));
        }
    }
}