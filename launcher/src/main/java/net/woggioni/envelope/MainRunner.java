package net.woggioni.envelope;

import lombok.SneakyThrows;
import net.woggioni.envelope.loader.JarFile;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

class MainRunner {
    @SneakyThrows
    static void run(JarFile currentJarFile,
                    String mainModuleName,
                    String mainClassName,
                    List<JarFile> classpath,
                    Consumer<Class<?>> runner) {
        URL[] urls = classpath.stream().map(Launcher::getURL).toArray(URL[]::new);
        try (URLClassLoader cl = new URLClassLoader(urls, ClassLoader.getSystemClassLoader().getParent())) {
            Thread.currentThread().setContextClassLoader(cl);
            runner.accept(cl.loadClass(mainClassName));
        }
    }
}
