package net.woggioni.envelope;

import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.lang.module.Configuration;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ResolvedModule;
import java.lang.module.ModuleReference;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.Optional;
import java.util.Enumeration;
import java.util.function.Consumer;
import java.net.URI;
import java.net.URL;
import java.net.URLStreamHandler;
import java.net.URLClassLoader;

import lombok.SneakyThrows;

import net.woggioni.envelope.Constants;
import net.woggioni.envelope.loader.ModuleClassLoader;
import net.woggioni.envelope.loader.JarFileModuleFinder;
import net.woggioni.envelope.loader.JarFile;
import java.util.jar.JarEntry;

class MainRunner {
    @SneakyThrows
    private static final URL uri2url(URI uri, URLStreamHandler streamHandler) {
        return new URL(null, uri.toString(), streamHandler);
    }

    @SneakyThrows
    static void run(JarFile currentJarFile,
                    String mainModuleName,
                    String mainClassName,
                    List<JarFile> classpath,
                    Consumer<Class<?>> runner) {
        if(mainModuleName == null) {
            if(mainClassName == null) {
                throw new RuntimeException(
                        String.format(
                                "Missing main attribute '%s' from manifest",
                                Constants.ManifestAttributes.MAIN_CLASS
                        )
                );
            }
            URL[] urls = classpath.stream().map(Launcher::getURL).toArray(URL[]::new);
            try (URLClassLoader cl = new URLClassLoader(urls, ClassLoader.getSystemClassLoader().getParent())) {
                Thread.currentThread().setContextClassLoader(cl);
                runner.accept(cl.loadClass(mainClassName));
            }
        } else {
            ModuleLayer bootLayer = ModuleLayer.boot();
            Configuration bootConfiguration = bootLayer.configuration();
            JarFileModuleFinder jarFileModuleFinder = new JarFileModuleFinder(classpath);
            List<String> moduleNames = Collections.unmodifiableList(
                jarFileModuleFinder.findAll()
                    .stream()
                    .map(ModuleReference::descriptor)
                    .map(ModuleDescriptor::name)
                    .collect(Collectors.toList())
            );
            Configuration cfg = bootConfiguration.resolve(jarFileModuleFinder, ModuleFinder.of(), moduleNames);
            Map<String, ClassLoader> packageMap = new TreeMap<>();
            ModuleLayer.Controller controller =
                ModuleLayer.defineModules(cfg, Collections.singletonList(ModuleLayer.boot()), moduleName -> {
                    ModuleReference modRef = cfg.findModule(moduleName)
                        .map(ResolvedModule::reference)
                        .orElseThrow();
                    URLStreamHandler streamHandler = jarFileModuleFinder.getStreamHandlerForModule(moduleName);
                    ClassLoader cl =  new ModuleClassLoader(
                        Collections.unmodifiableMap(packageMap),
                        modRef,
                        (URI uri) -> uri2url(uri, streamHandler)
                    );
                    for(String packageName : modRef.descriptor().packages()) {
                        packageMap.put(packageName, cl);
                    }
                    return cl;
                });
            ModuleLayer layer = controller.layer();
            Module mainModule = layer.findModule(mainModuleName).orElseThrow(
                    () -> new IllegalStateException(String.format("Main module '%s' not found", mainModuleName)));
            Thread.currentThread().setContextClassLoader(mainModule.getClassLoader());
            Optional<String> mainClassOpt = Optional.ofNullable(mainClassName);
            runner.accept(Optional.ofNullable(mainClassName)
                .or(() -> mainModule.getDescriptor().mainClass())
                .map(className -> Class.forName(mainModule, className))
                .orElseThrow(() -> new IllegalStateException(
                    String.format("Unable to determine main class name for module '%s'", mainModule.getName()))));
        }
    }
}
