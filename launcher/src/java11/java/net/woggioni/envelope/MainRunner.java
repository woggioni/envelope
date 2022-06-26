package net.woggioni.envelope;

import java.util.Map;
import java.util.TreeMap;
import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.lang.module.ResolvedModule;
import java.lang.module.ModuleReference;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.Optional;
import java.util.Enumeration;
import java.util.function.Consumer;
import java.net.URL;
import java.net.URLClassLoader;


import lombok.SneakyThrows;

import net.woggioni.xclassloader.ModuleClassLoader;
import net.woggioni.xclassloader.JarFileModuleFinder;
import net.woggioni.xclassloader.jar.JarFile;
import java.util.jar.JarEntry;

class MainRunner {

    @SneakyThrows
    static void run(JarFile currentJarFile,
                    String mainModuleName,
                    String mainClassName,
                    String librariesFolder,
                    Consumer<Class<?>> runner) {
        if(mainModuleName == null) {
            List<URL> jarList = new ArrayList<>();
            Enumeration<JarEntry> entries = currentJarFile.entries();
            while(entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if(!entry.isDirectory() && name.startsWith(librariesFolder) && name.endsWith(".jar")) {
                    jarList.add(currentJarFile.getNestedJarFile(entry).getUrl());
                }
            }
            try (URLClassLoader cl = new URLClassLoader(jarList.toArray(new URL[0]), ClassLoader.getSystemClassLoader().getParent())) {
                Thread.currentThread().setContextClassLoader(cl);
                runner.accept(cl.loadClass(mainClassName));
            }
        } else {
            List<JarFile> jarList = new ArrayList<>();
            Enumeration<JarEntry> entries = currentJarFile.entries();
            while(entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if(!entry.isDirectory() && name.startsWith("LIB-INF") && name.endsWith(".jar")) {
                    jarList.add(currentJarFile.getNestedJarFile(entry));
                }
            }
            ModuleLayer bootLayer = ModuleLayer.boot();
            Configuration bootConfiguration = bootLayer.configuration();
            Configuration cfg = bootConfiguration.resolve(new JarFileModuleFinder(jarList), ModuleFinder.of(), Collections.singletonList(mainModuleName));
            Map<String, ClassLoader> packageMap = new TreeMap<>();
            ModuleLayer.Controller controller =
                    ModuleLayer.defineModules(cfg, Collections.singletonList(ModuleLayer.boot()), moduleName -> {
                        ModuleReference modRef = cfg.findModule(moduleName)
                                .map(ResolvedModule::reference)
                                .orElseThrow();
                        ClassLoader cl =  new ModuleClassLoader(
                                Collections.unmodifiableMap(packageMap),
                                modRef
                        );
                        for(String packageName : modRef.descriptor().packages()) {
                            packageMap.put(packageName, cl);
                        }
                        return cl;
                    });
            ModuleLayer layer = controller.layer();
            Module mainModule = layer.findModule(mainModuleName).orElseThrow(
                    () -> new IllegalStateException(String.format("Main module '%s' not found", mainModuleName)));
            runner.accept(Optional.ofNullable(mainClassName)
                    .or(() -> mainModule.getDescriptor().mainClass())
                    .map(className -> Class.forName(mainModule, className))
                    .orElseThrow(() -> new IllegalStateException(
                        String.format("Unable to determine main class name for module '%s'", mainModule.getName()))));
        }
    }
}
