package net.woggioni.envelope;

import lombok.SneakyThrows;
import net.woggioni.xclassloader.PathURLStreamHandler;
import net.woggioni.xclassloader.URLManager;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Function;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Collector;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;


public class Launcher {

    @SneakyThrows
    private static URI findCurrentJar() {
        String launcherClassName = Launcher.class.getName();
        URL url = Launcher.class.getClassLoader().getResource(launcherClassName.replace('.', '/') + ".class");
        if (url == null || !"jar".equals(url.getProtocol()))
            throw new IllegalStateException(String.format("The class %s must be used inside a JAR file", launcherClassName));
        String path = url.getPath();
        return new URI(path.substring(0, path.indexOf('!')));
    }

    @SneakyThrows
    public static void main(String[] args) {
        URLManager urlManager = URLManager.getInstance();
        urlManager.registerProtocol(PathURLStreamHandler.SCHEME, PathURLStreamHandler.INSTANCE);
        URL.setURLStreamHandlerFactory(urlManager);
        Enumeration<URL> it = Launcher.class.getClassLoader().getResources(Constants.SYSTEM_PROPERTIES_FILE);
        while (it.hasMoreElements()) {
            URL url = it.nextElement();
            Properties properties = new Properties();
            try (InputStream is = url.openStream()) {
                properties.load(is);
            }
            for (Map.Entry<Object, Object> entry : properties.entrySet()) {
                String key = (String) entry.getKey();
                String value = (String) entry.getValue();
                if(System.getProperty(key) == null) System.setProperty(key, value);
            }
        }
        URI currentJar = findCurrentJar();
        String currentJarPath = currentJar.getPath();
        URL manifestResource = null;
        Enumeration<URL> enumeration = Launcher.class.getClassLoader().getResources(JarFile.MANIFEST_NAME);
        while(enumeration.hasMoreElements()) {
            URL candidate = enumeration.nextElement();
            URL subUrl = new URL(candidate.getFile());
            String candidatePath = subUrl.getPath();
            int i = candidatePath.indexOf("!/");
            candidatePath = candidatePath.substring(0, i);
            if(Objects.equals(currentJarPath, candidatePath)) {
                manifestResource = candidate;
                break;
            }
        }
        if(Objects.isNull(manifestResource)) {
            throw new RuntimeException("Launcher manifest not found");
        }
        Manifest mf = new Manifest();
        try(InputStream is = manifestResource.openStream()) {
            mf.read(is);
        }
        try(FileSystem fs = FileSystems.newFileSystem(Paths.get(currentJar), null)) {
            Attributes mainAttributes = mf.getMainAttributes();

            Collector<Path, ArrayList<Path>, List<Path>> immutableListCollector = Collector.of(
                    ArrayList::new,
                    List::add,
                    (l1, l2) -> { l1.addAll(l2); return l1; },
                    Collections::unmodifiableList);
            List<Path> jarList = StreamSupport.stream(fs.getRootDirectories().spliterator(), false).flatMap(new Function<Path, Stream<Path>>() {
                @Override
                @SneakyThrows
                public Stream<Path> apply(Path path) {
                    return Files.list(path.resolve(Constants.LIBRARIES_FOLDER))
                            .filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().endsWith(".jar"));
                }
            }).flatMap(new Function<Path, Stream<Path>>() {
                @Override
                @SneakyThrows
                public Stream<Path> apply(Path path) {
                    return StreamSupport.stream(FileSystems.newFileSystem(path, null).getRootDirectories().spliterator(), false);
                }
            }).collect(immutableListCollector);

            String mainClassName = mainAttributes.getValue(Constants.ManifestAttributes.MAIN_CLASS);
            String mainModuleName = mainAttributes.getValue(Constants.ManifestAttributes.MAIN_MODULE);
            Class<?> mainClass = MainClassLoader.loadMainClass(jarList, mainModuleName, mainClassName);
            try {
                Method mainMethod = mainClass.getMethod("main", String[].class);
                Class<?> returnType = mainMethod.getReturnType();
                if (mainMethod.getReturnType() != Void.TYPE) {
                    throw new IllegalArgumentException(String.format("Main method in class '%s' " +
                            "has wrong return type, expected '%s', found '%s' instead", mainClass, Void.class.getName(), returnType));
                }
                mainMethod.invoke(null, (Object) args);
            } catch (NoSuchMethodException nsme) {
                throw new IllegalArgumentException(String.format("No valid main method found in class '%s'", mainClass), nsme);
            }
        }
    }
}
