package io.github.guqing.suite;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import org.pf4j.Extension;
import org.pf4j.processor.ExtensionStorage;
import org.pf4j.util.ClassUtils;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;

/**
 * Processes {@link Extension} annotations and generates an {@link ExtensionStorage}.
 * You can specify the concrete {@link ExtensionStorage} via processor's environment options
 * ({@link ProcessingEnvironment#getOptions()}) or system property.
 * In both variants the option/property name is {@code pf4j.storageClassName}.
 *
 * @author guqing
 */
public class PluggableAnnotationProcessor extends AbstractProcessor {
    private static final List<Class<? extends Annotation>> extensionAnnotationNames =
        List.of(Extension.class,
            Service.class,
            Controller.class,
            RestController.class,
            Component.class,
            Configuration.class);

    private static final String STORAGE_CLASS_NAME = "halo.pluggable.storageClassName";

    private static final String IGNORE_EXTENSION_POINT = "halo.pluggable.ignoreExtensionPoint";

    private final Set<String> components = new LinkedHashSet<>();

    private ComponentStorage storage;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);

        info("%s init", PluggableAnnotationProcessor.class.getName());
        info("Options %s", processingEnv.getOptions());

        initStorage();
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latest();
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Collections.singleton("*");
    }

    @Override
    public Set<String> getSupportedOptions() {
        Set<String> options = new HashSet<>();
        options.add(STORAGE_CLASS_NAME);
        options.add(IGNORE_EXTENSION_POINT);

        return options;
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) {
            return false;
        }

        info("Processing @%s", Extension.class.getName());
        for (Class<? extends Annotation> extensionAnnotation : extensionAnnotationNames) {
            for (Element element : roundEnv.getElementsAnnotatedWith(extensionAnnotation)) {
                if (element.getKind() != ElementKind.ANNOTATION_TYPE) {
                    processExtensionElement(element);
                }
            }
        }

        List<TypeElement> extensionAnnotations = getExtensionTypeElements(annotations);

        // process nested extension annotations
        for (TypeElement te : extensionAnnotations) {
            info("Processing @%s", te);
            for (Element element : roundEnv.getElementsAnnotatedWith(te)) {
                processExtensionElement(element);
            }
        }
        // write extensions
        storage.write(components);

        return false;
    }

    private List<TypeElement> getExtensionTypeElements(Set<? extends TypeElement> annotations) {
        // collect nested extension annotations
        List<TypeElement> extensionAnnotations = new ArrayList<>();
        for (TypeElement annotation : annotations) {
            for (Class<? extends Annotation> extensionAnnotation : extensionAnnotationNames) {
                if (ClassUtils.getAnnotationMirror(annotation, extensionAnnotation) != null) {
                    extensionAnnotations.add(annotation);
                }
            }
        }
        return extensionAnnotations;
    }

    public ProcessingEnvironment getProcessingEnvironment() {
        return processingEnv;
    }

    public void error(String message, Object... args) {
        processingEnv.getMessager()
            .printMessage(Diagnostic.Kind.ERROR, String.format(message, args));
    }

    public void error(Element element, String message, Object... args) {
        processingEnv.getMessager()
            .printMessage(Diagnostic.Kind.ERROR, String.format(message, args), element);
    }

    public void info(String message, Object... args) {
        processingEnv.getMessager()
            .printMessage(Diagnostic.Kind.NOTE, String.format(message, args));
    }

    public void info(Element element, String message, Object... args) {
        processingEnv.getMessager()
            .printMessage(Diagnostic.Kind.NOTE, String.format(message, args), element);
    }

    public String getBinaryName(TypeElement element) {
        return processingEnv.getElementUtils().getBinaryName(element).toString();
    }

    public Set<String> getComponents() {
        return this.components;
    }

    public ComponentStorage getStorage() {
        return storage;
    }

    private void processExtensionElement(Element element) {
        // check if @Extension is put on class and not on method or constructor
        if (!(element instanceof TypeElement extensionElement)) {
            error(element, "Put annotation only on classes (no methods, no fields)");
            return;
        }

        String extension = getBinaryName(extensionElement);
        components.add(extension);
    }

    private void initStorage() {
        // search in processing options
        String storageClassName = processingEnv.getOptions().get(STORAGE_CLASS_NAME);
        if (storageClassName == null) {
            // search in system properties
            storageClassName = System.getProperty(STORAGE_CLASS_NAME);
        }

        if (storageClassName != null) {
            // use reflection to create the storage instance
            try {
                Class<?> storageClass = getClass().getClassLoader().loadClass(storageClassName);
                Constructor<?> constructor =
                    storageClass.getConstructor(PluggableAnnotationProcessor.class);
                storage = (ComponentStorage) constructor.newInstance(this);
            } catch (Exception e) {
                error(e.getMessage());
            }
        }

        if (storage == null) {
            // default storage
            storage = new SpringComponentStorage(this);
        }
    }
}
