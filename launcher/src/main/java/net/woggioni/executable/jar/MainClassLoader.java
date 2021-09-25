package net.woggioni.executable.jar;

import java.nio.file.Path;
import lombok.SneakyThrows;


class MainClassLoader {
    @SneakyThrows
    static Class<?> loadMainClass(Iterable<Path> roots, String mainModuleName, String mainClassName) {
        ClassLoader pathClassLoader = new net.woggioni.xclassloader.PathClassLoader(roots);
        return pathClassLoader.loadClass(mainClassName);
    }
}
