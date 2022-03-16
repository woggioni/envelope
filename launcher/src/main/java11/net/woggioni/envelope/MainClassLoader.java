package net.woggioni.envelope;

import java.util.Map;
import java.util.TreeMap;
import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.lang.module.ResolvedModule;
import java.lang.module.ModuleReference;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Optional;

import lombok.SneakyThrows;

import net.woggioni.xclassloader.PathClassLoader;
import net.woggioni.xclassloader.ModuleClassLoader;
import net.woggioni.xclassloader.PathModuleFinder;

class MainClassLoader {

    @SneakyThrows
    static Class<?> loadMainClass(Iterable<Path> roots, String mainModuleName, String mainClassName) {
        if (mainModuleName == null) {
            ClassLoader pathClassLoader = new net.woggioni.xclassloader.PathClassLoader(roots);
            return pathClassLoader.loadClass(mainClassName);
        } else {
            ModuleLayer bootLayer = ModuleLayer.boot();
            Configuration bootConfiguration = bootLayer.configuration();
            Configuration cfg = bootConfiguration.resolve(new PathModuleFinder(roots), ModuleFinder.of(), Collections.singletonList(mainModuleName));
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
            return Optional.ofNullable(mainClassName)
                    .or(() -> mainModule.getDescriptor().mainClass())
                    .map(className -> Class.forName(mainModule, className))
                    .orElseThrow(() -> new IllegalStateException(String.format("Unable to determine main class name for module '%s'", mainModule.getName())));
        }
    }
}
