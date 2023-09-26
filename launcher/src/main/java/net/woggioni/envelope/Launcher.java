package net.woggioni.envelope;

import lombok.SneakyThrows;
import net.woggioni.envelope.loader.JarFile;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static net.woggioni.envelope.Common.or;
import static net.woggioni.envelope.Constants.EXTRA_CLASSPATH_ENTRY_SEPARATOR;

public class Launcher {

    @SneakyThrows
    static URL getURL(JarFile jarFile) {
        return jarFile.getUrl();
    }

    @SneakyThrows
    private static JarFile findCurrentJar() {
        String launcherClassName = Launcher.class.getName();
        URL url = Launcher.class.getClassLoader().getResource(launcherClassName.replace('.', '/') + ".class");
        if (url == null || !"jar".equals(url.getProtocol()))
            throw new IllegalStateException(String.format("The class %s must be used inside a JAR file", launcherClassName));
        String path = Paths.get(new URI(url.getPath())).toString();
        return new JarFile(new File(path.substring(0, path.indexOf('!'))));
    }

    @SneakyThrows
    private static void run(Class<?> mainClass, String[] args) {
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

    private static Map<String, Map<String, Object>> createContextMap() {
        Map<String, Map<String, Object>> dictMap = new TreeMap<>();
        dictMap.put("env", Collections.unmodifiableMap(System.getenv()
            .entrySet()
            .stream()
            .map(entry -> new AbstractMap.SimpleEntry<>(entry.getKey(), (Object) entry.getValue()))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))));
        dictMap.put("sys", Collections.unmodifiableMap(System.getProperties().entrySet().stream()
            .map((Map.Entry<? super String, Object> entry) -> (Map.Entry<String, Object>) entry)
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))));
        return Collections.unmodifiableMap(dictMap);
    }

    private static Stream<JarFile> getExtraClasspath(Attributes mainAttributes) {
        return Common.opt2Stream(or(
                    () -> System.getProperty(Constants.JvmProperties.EXTRA_CLASSPATH),
                    () -> mainAttributes.getValue(Constants.ManifestAttributes.EXTRA_CLASSPATH)
                )
                .map(manifestAttribute -> {
                    Map<String, Map<String, Object>> dictMap = createContextMap();
                    return Common.renderTemplate(manifestAttribute, Collections.emptyMap(), dictMap);
                }).map(extraClasspathString -> {
                    List<String> paths = new ArrayList<>();
                    int cursor = 0;
                    while(true) {
                        int sep = Common.indexOfWithEscape(
                            extraClasspathString,
                            EXTRA_CLASSPATH_ENTRY_SEPARATOR,
                            EXTRA_CLASSPATH_ENTRY_SEPARATOR,
                            cursor,
                            extraClasspathString.length()
                        );
                        String classpathEntry = extraClasspathString.substring(cursor, sep < 0 ? extraClasspathString.length() : sep);
                        paths.add(classpathEntry);
                        if(sep < 0) break;
                        cursor = sep + 1;
                    }
                    return paths;
                }))
            .flatMap(List::stream)
            .map(Paths::get)
            .flatMap(new Function<Path, Stream<Path>>() {
                @Override
                @SneakyThrows
                public Stream<Path> apply(Path path) {
                    if(Files.isDirectory(path)) {
                        return Files.list(path).filter(childPath -> !Files.isDirectory(childPath));
                    } else {
                        return Stream.of(path);
                    }
                }
            })
            .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".jar"))
            .map(Path::toFile)
            .map(new Function<File, JarFile>() {
                @Override
                @SneakyThrows
                public JarFile apply(File file) {
                    return new JarFile(file);
                }
            });
    }

    @SneakyThrows
    public static void main(String[] args) {
        Enumeration<URL> it = Launcher.class.getClassLoader().getResources(Constants.SYSTEM_PROPERTIES_FILE);
        JarFile.registerUrlProtocolHandler();
        while (it.hasMoreElements()) {
            URL url = it.nextElement();
            Properties properties = new Properties();
            try (InputStream is = url.openStream()) {
                properties.load(is);
            }
            for (Map.Entry<Object, Object> entry : properties.entrySet()) {
                String key = (String) entry.getKey();
                String value = (String) entry.getValue();
                if (System.getProperty(key) == null) System.setProperty(key, value);
            }
        }
        JarFile currentJar = findCurrentJar();
        Manifest mf = currentJar.getManifest();
        Attributes mainAttributes = mf.getMainAttributes();

        String mainClassName = or(
            () -> System.getProperty(Constants.JvmProperties.MAIN_CLASS),
            () -> mainAttributes.getValue(Constants.ManifestAttributes.MAIN_CLASS)
        ).orElse(null);
        String mainModuleName = or(
            () -> System.getProperty(Constants.JvmProperties.MAIN_MODULE),
            () -> mainAttributes.getValue(Constants.ManifestAttributes.MAIN_MODULE)
        ).orElse(null);
        StringBuilder sb = new StringBuilder();
        List<JarFile> classpath = new ArrayList<>();
        URL libraryTocResource = Launcher.class.getClassLoader().getResource(Constants.LIBRARIES_TOC);
        if(libraryTocResource == null) throw new RuntimeException(
                Constants.LIBRARIES_TOC + " not found");
        try(Reader reader = new InputStreamReader(libraryTocResource.openStream())) {
            while(true) {
                int c = reader.read();
                boolean entryEnd = c == '/' || c < 0;
                if(entryEnd) {
                    String entryName = Constants.LIBRARIES_FOLDER + '/' + sb;
                    JarEntry entry = currentJar.getJarEntry(entryName);
                    classpath.add(currentJar.getNestedJarFile(entry));
                    sb.setLength(0);
                    if(c < 0) break;
                }
                else sb.append((char) c);
            }
        }

        getExtraClasspath(mainAttributes).forEach(classpath::add);
        Consumer<Class<?>> runner = new Consumer<Class<?>>() {
            @Override
            @SneakyThrows
            public void accept(Class<?> mainClass) {
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
        };
        MainRunner.run(
                currentJar,
                mainModuleName,
                mainClassName,
                classpath,
                runner);

    }
}
