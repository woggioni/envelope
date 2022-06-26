package net.woggioni.envelope;

import lombok.SneakyThrows;
import net.woggioni.xclassloader.jar.JarFile;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.function.Consumer;
import java.util.jar.JarEntry;

class MainRunner {
    @SneakyThrows
    static void run(JarFile currentJarFile,
                                  String mainModuleName,
                                  String mainClassName,
                                  String librariesFolder,
                                  Consumer<Class<?>> runner) {
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
    }
}
