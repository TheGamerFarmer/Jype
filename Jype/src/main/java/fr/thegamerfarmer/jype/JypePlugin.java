package fr.thegamerfarmer.jype;

import com.sun.source.util.JavacTask;
import com.sun.source.util.Plugin;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.source.util.Trees;
import com.sun.tools.javac.api.BasicJavacTask;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.Names;

import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Coordinates Jype's pre-Enter lifecycle for a single javac task.
 *
 * <p>The SPI entry point is {@link JypePluginShim}, which checks module access before
 * loading javac internals. All initially parsed units are collected before the first
 * Enter event, then transformed exactly once. Annotation-processing rounds may add
 * sources later; those are handled by {@link JypeProcessor}, not replayed here.
 */
public final class JypePlugin implements Plugin {
    private PreEnterTransformer transformer;

    /** Creates an unbound plugin; javac calls {@link #init} before compilation. */
    public JypePlugin() {}

    /** Returns the case-sensitive name accepted by {@code -Xplugin:Jype}. */
    @Override
    public String getName() {
        return "Jype";
    }

    /**
     * Registers the listener and exposes this task's source edits to the processor.
     *
     * @param task a javac task; other compiler implementations are unsupported
     * @param args reserved plugin arguments, currently ignored
     */
    @Override
    public void init(JavacTask task, String... args) {
        var context = ((BasicJavacTask) task).getContext();
        context.put(JypePlugin.class, this);
        transformer = new PreEnterTransformer(context);
        var make = TreeMaker.instance(context);
        var names = Names.instance(context);
        List<JCTree.JCCompilationUnit> sources = new ArrayList<>();
        AtomicBoolean transformed = new AtomicBoolean();
        task.addTaskListener(new TaskListener() {
            @Override
            public void started(TaskEvent event) {
                if (event.getKind() == TaskEvent.Kind.ENTER && transformed.compareAndSet(false, true)) {
                    transformer.transform(sources, make, names);
                }
            }

            @Override
            public void finished(TaskEvent event) {
                if (event.getKind() == TaskEvent.Kind.PARSE) {
                    sources.add((JCTree.JCCompilationUnit) event.getCompilationUnit());
                }
                if (event.getKind() == TaskEvent.Kind.COMPILATION
                        && context.get(JypeProcessor.class) == null
                        && !sources.isEmpty()
                        && !transformer.extractManagedNames(sources).isEmpty()) {
                    var source = sources.get(0);
                    Trees.instance(task).printMessage(Diagnostic.Kind.WARNING,
                            "[Jype] @TypeSystem is present but the Jype processor did not run. "
                                    + "Check for javac errors before annotation processing first. Otherwise enable "
                                    + "annotation processing and JypeProcessorShim; bounds and Javadoc sources require it.",
                            source, source);
                }
            }
        });
    }

    /** Returns source-position edits recorded before Enter for the final Javadoc round. */
    Map<String, List<JavadocSourceGenerator.SourceEdit>> staticSourceEdits() {
        return transformer.staticSourceEdits();
    }

    void completeMemberTypesAfterInjection(String scope,
            Map<String, java.util.Set<String>> injectedParameters,
            Map<String, List<String[]>> originalParameterOrders) {
        transformer.completeMemberTypesAfterInjection(scope, injectedParameters, originalParameterOrders);
    }

    DeclarationOrigins origins() {
        return transformer.origins;
    }

    /** Alias creations recorded before Enter, rewritten by the processor after its passes. */
    ManagedConstruction construction() {
        return transformer.construction;
    }
}
