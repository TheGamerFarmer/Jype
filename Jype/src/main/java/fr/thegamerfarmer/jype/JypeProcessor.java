package fr.thegamerfarmer.jype;

import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.processing.JavacProcessingEnvironment;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Names;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import java.lang.reflect.Field;
import java.util.*;

/**
 * Jype annotation processor — main orchestrator.
 *
 * <p>Reads the compiled package's {@code @TypeSystem} (or inherits one from a
 * parent on the classpath), resolves the name-to-type map, then delegates to
 * specialized passes:
 * <ol>
 *   <li>{@link TypeParamInjector} — pass 1: injects missing Jype parameters into
 *       partially managed classes.</li>
 *   <li>{@link TypeInjectionPass} — pass 2: injects F-bounds and completes
 *       {@code extends} clauses.</li>
 *   <li>{@link JavadocSourceGenerator} — final pass: copies sources with AST
 *       patches for Javadoc.</li>
 * </ol>
 *
 * <p>No Java files are generated for recompilation; javac continues with the
 * modified in-memory AST. Only Javadoc copies are written at the end of processing.
 *
 * <h2>Traitement en deux passes</h2>
 * <ol>
 *   <li><strong>Pass 1</strong>: for partially managed classes (which have some
 *       but not all Jype parameters), injects missing parameters as new
 *       {@code JCTypeParameter} and {@code Type.TypeVar} nodes into the AST and symbol.</li>
 *   <li><strong>Pass 2</strong>: injects bounds and {@code extends} clauses for
 *       all classes, using the parameter table populated in pass 1.</li>
 * </ol>
 */
@SupportedAnnotationTypes("fr.thegamerfarmer.jype.annotations.TypeSystem")
@SupportedOptions("jype.debug")
public class JypeProcessor extends AbstractProcessor {

    private Trees trees;
    private TypeSystemResolver typeSystemResolver;
    private TypeParamInjector typeParamInjector;
    private TypeInjectionPass typeInjectionPass;
    private JavadocSourceGenerator javadocGenerator;
    private final Map<String, Map<String, TypeMirror>> typeSystems = new LinkedHashMap<>();

    /**
     * Source files collected during regular rounds (URI → JavaFileObject).
     */
    private final Map<String, javax.tools.JavaFileObject> processedSourceFiles = new LinkedHashMap<>();

    /** Creates an uninitialized processor; javac supplies its processing environment once. */
    public JypeProcessor() {}

    /** Uses the executing compiler's source level; the engine requires JDK 21 or newer. */
    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    /** Initializes phase-specific passes and warns if pre-Enter transformation was not enabled. */
    @Override
    public synchronized void init(ProcessingEnvironment env) {
        super.init(env);
        Context ctx = resolveContext(env);
        ctx.put(JypeProcessor.class, this);
        if (ctx.get(JypePlugin.class) == null) {
            env.getMessager().printMessage(javax.tools.Diagnostic.Kind.WARNING,
                    "[Jype] javac plugin is not active. Add -Xplugin:Jype for pre-ENTER transformations; "
                            + "the processor alone cannot repair partial type arguments before ENTER.");
        }
        trees = Trees.instance(env);
        TreeMaker make = TreeMaker.instance(ctx);
        Names names = Names.instance(ctx);

        JypeAstUtils astUtils = new JypeAstUtils(make, names, env);
        typeSystemResolver = new TypeSystemResolver(env);
        typeParamInjector = new TypeParamInjector(trees, make, names, env);
        typeInjectionPass = new TypeInjectionPass(trees, make, names, env, astUtils,
                typeParamInjector.getInjectedTypeParams());
        javadocGenerator = new JavadocSourceGenerator(env, typeParamInjector.getOriginalParamOrders());
        JypePlugin plugin = ctx.get(JypePlugin.class);
        if (plugin != null) javadocGenerator.addStaticEdits(plugin.staticSourceEdits());
    }

    /**
     * Processes all roots with their nearest package system, then writes Javadoc copies
     * only after a successful final round. Returns false so other processors may observe
     * the annotations and transformed signatures; Jype does not claim annotation ownership.
     */
    @Override
    public boolean process(Set<? extends TypeElement> annotations, @NotNull RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) {
            if (!roundEnv.errorRaised()) javadocGenerator.generate(processedSourceFiles.values());
            return false;
        }

        TypeElement tsAnnotation = processingEnv.getElementUtils()
                .getTypeElement("fr.thegamerfarmer.jype.annotations.TypeSystem");
        Set<? extends Element> annotated = roundEnv.getElementsAnnotatedWith(tsAnnotation);
        for (Element element : annotated) {
            if (!(element instanceof PackageElement pkgElement)) continue;
            Map<String, TypeMirror> resolvedTypes = typeSystemResolver.resolve(pkgElement);
            if (resolvedTypes != null && !resolvedTypes.isEmpty()) {
                typeSystems.put(pkgElement.getQualifiedName().toString(), resolvedTypes);
            }
        }
        if (typeSystems.isEmpty()) return false;

        typeParamInjector.clearRound();

        List<TypeElement> classes = new ArrayList<>();
        Map<TypeElement, Map<String, TypeMirror>> classTypeSystems = new IdentityHashMap<>();
        for (Element root : roundEnv.getRootElements()) {
            if (root.getKind() == ElementKind.CLASS || root.getKind() == ElementKind.INTERFACE) {
                TypeElement classElement = (TypeElement) root;
                Map<String, TypeMirror> resolvedTypes = findClassTypeSystem(classElement, new HashSet<>());
                if (resolvedTypes != null) {
                    classes.add(classElement);
                    classTypeSystems.put(classElement, resolvedTypes);
                }
            }
        }

        javadocGenerator.ensureOutputDir();
        collectSourceFiles(roundEnv);

        for (TypeElement classElement : classes) {
            typeParamInjector.injectMissingTypeParams(classElement, classTypeSystems.get(classElement));
        }
        for (TypeElement classElement : classes) {
            typeInjectionPass.processClass(classElement, classTypeSystems.get(classElement));
        }

        // Record complete parameter orders needed by Javadoc sources.
        for (TypeElement classElement : classes) {
            trackAllJypeClassForJavadoc(classElement, classTypeSystems.get(classElement));
        }
        return false;
    }

    @Nullable
    private Map<String, TypeMirror> findClassTypeSystem(TypeElement cls, Set<String> visited) {
        if (!visited.add(cls.getQualifiedName().toString())) return null;
        Map<String, TypeMirror> local = findTypeSystem(
                processingEnv.getElementUtils().getPackageOf(cls), typeSystems);
        if (local != null) return local;
        if (cls.getSuperclass() instanceof javax.lang.model.type.DeclaredType parent
                && parent.asElement() instanceof TypeElement parentElement) {
            return findClassTypeSystem(parentElement, visited);
        }
        return null;
    }

    @Nullable
    private static Map<String, TypeMirror> findTypeSystem(
            PackageElement pkg, Map<String, Map<String, TypeMirror>> typeSystems) {
        String packageName = pkg.getQualifiedName().toString();
        Map<String, TypeMirror> bestMatch = null;
        int bestLength = -1;
        for (Map.Entry<String, Map<String, TypeMirror>> entry : typeSystems.entrySet()) {
            String rootPackage = entry.getKey();
            if ((packageName.equals(rootPackage) || packageName.startsWith(rootPackage + "."))
                    && rootPackage.length() > bestLength) {
                bestMatch = entry.getValue();
                bestLength = rootPackage.length();
            }
        }
        return bestMatch;
    }

    private void trackAllJypeClassForJavadoc(TypeElement classElement,
                                              Map<String, TypeMirror> resolvedTypes) {
        for (Element enclosed : classElement.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.CLASS || enclosed.getKind() == ElementKind.INTERFACE) {
                trackAllJypeClassForJavadoc((TypeElement) enclosed, resolvedTypes);
            }
        }

        String qualName = classElement.getQualifiedName().toString();

        List<String[]> paramOrder = classElement.getTypeParameters().stream()
                .map(tp -> {
                    String name = tp.getSimpleName().toString();
                    return new String[]{name, resolvedTypes.containsKey(name) ? "true" : "false"};
                })
                .collect(java.util.stream.Collectors.toList());

        boolean hasJypeParam = paramOrder.stream().anyMatch(p -> "true".equals(p[1]));
        if (!hasJypeParam) return;

        javadocGenerator.addDeclPatch(qualName, paramOrder);
    }

    private void collectSourceFiles(@NotNull RoundEnvironment roundEnv) {
        for (Element root : roundEnv.getRootElements()) {
            javax.tools.JavaFileObject sf = null;
            TreePath rootPath = trees.getPath(root);
            if (rootPath != null) {
                sf = rootPath.getCompilationUnit().getSourceFile();
            } else if (root instanceof Symbol.ClassSymbol cs) {
                sf = cs.sourcefile;
            }
            if (sf != null && sf.getKind() == javax.tools.JavaFileObject.Kind.SOURCE) {
                processedSourceFiles.put(sf.toUri().toString(), sf);
            }
        }
    }

    /**
     * Walks the delegation chain to obtain the javac {@link Context}.
     * Never returns {@link JavacProcessingEnvironment} directly, avoiding a
     * false "resource not closed" warning for callers; javac, not Jype, manages
     * this object's lifecycle.
     */
    private Context resolveContext(ProcessingEnvironment env) {
        ProcessingEnvironment current = env;
        while (!(current instanceof JavacProcessingEnvironment)) {
            try {
                Field f = current.getClass().getDeclaredField("delegate");
                f.setAccessible(true);
                current = (ProcessingEnvironment) f.get(current);
            } catch (NoSuchFieldException | IllegalAccessException e) {
                throw new RuntimeException(
                        "[Jype] Cannot access JavacProcessingEnvironment through "
                        + env.getClass().getName(), e);
            }
        }
        return ((JavacProcessingEnvironment) current).getContext();
    }
}
