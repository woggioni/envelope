package net.woggioni.envelope;

import lombok.SneakyThrows;
import net.woggioni.xclassloader.jar.JarFile;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import static java.util.jar.JarFile.MANIFEST_NAME;

public class Launcher {

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

    @SneakyThrows
    public static void main(String[] args) {
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
                if (System.getProperty(key) == null) System.setProperty(key, value);
            }
        }
        JarFile currentJar = findCurrentJar();
        URL manifestResource = null;
        Enumeration<URL> enumeration = Launcher.class.getClassLoader().getResources(MANIFEST_NAME);
        while (enumeration.hasMoreElements()) {
            URL candidate = enumeration.nextElement();
            URL subUrl = new URL(candidate.getFile());
            String candidatePath = subUrl.getPath();
            int i = candidatePath.indexOf("!/");
            candidatePath = candidatePath.substring(0, i);
            if (Objects.equals(currentJar.getName(), candidatePath)) {
                manifestResource = candidate;
                break;
            }
        }
        if (Objects.isNull(manifestResource)) {
            throw new RuntimeException("Launcher manifest not found");
        }
        Manifest mf = new Manifest();
        try (InputStream is = manifestResource.openStream()) {
            mf.read(is);
        }
        Attributes mainAttributes = mf.getMainAttributes();

        String mainClassName = mainAttributes.getValue(Constants.ManifestAttributes.MAIN_CLASS);
        String mainModuleName = mainAttributes.getValue(Constants.ManifestAttributes.MAIN_MODULE);

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
                Constants.LIBRARIES_FOLDER,
                runner);

    }
}
