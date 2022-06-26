package net.woggioni.gradle.envelope.test;

import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import org.apache.groovy.util.Arrays;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.Buffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Assertions;

import static java.util.concurrent.TimeUnit.MINUTES;

public class EnvelopePluginTest {

    private static URL getResource(String resourceName) {
        return getResource(resourceName, null, null);
    }

    private static URL getResource(String resourceName, ClassLoader cl) {
        return getResource(resourceName, null, cl);
    }

    private static URL getResource(String resourceName, Class<?> cls) {
        return getResource(resourceName, cls, null);
    }

    private static URL getResource(String resourceName, Class<?> cls, ClassLoader cl) {
        URL result = null;
        if (cl != null) {
            result = cl.getResource(resourceName);
        }
        if (result == null && cls != null) {
            result = cls.getClassLoader().getResource(resourceName);
        }
        if (result == null) {
            result = EnvelopePluginTest.class.getClassLoader().getResource(resourceName);
        }
        return result;
    }

//    @SneakyThrows
//    private static void installResource(String root, String resourceName, Path destination) {
//        Path outputFile = with {
//            Path realDestination;
//            if (Files.isSymbolicLink(destination)) {
//                realDestination = destination.toRealPath();
//            } else {
//                realDestination = destination;
//            }
//            realDestination = realDestination.resolve(resourceName);
//            if(!Files.exists(realDestination)) {
//                Files.createDirectories(realDestination.getParent());
//                realDestination;
//            } else if(Files.isDirectory(realDestination)) {
//                realDestination.resolve(resourceName.substring(1 + resourceName.lastIndexOf('/')));
//            }
//            else if(Files.isRegularFile(realDestination)) {
//                realDestination;
//            } else throw new IllegalStateException("Path '${realDestination}' is neither a file nor a directory");
//        }
//        String resourcePath = root + '/' + resourceName;
//        URL res = getResource(resourcePath);
//        if(res == null) throw new FileNotFoundException(resourceName);
//        try(InputStream inputStream = res.openStream()) {
//            Files.copy(inputStream, outputFile, StandardCopyOption.REPLACE_EXISTING);
//        }
//    }

    void invokeGradle(Path rootProjectDir, String... taskName) {
        GradleRunner runner = GradleRunner.create()
                .withDebug(true)
                .withProjectDir(rootProjectDir.toFile())
                .withArguments(Arrays.concat(new String[]{"-s", "--info", "-g", testGradleHomeDir.toString()}, taskName))
                .withPluginClasspath();
        System.out.println(runner.build().getOutput());
    }

    private Path testDir;
    private static final Path testGradleHomeDir = Paths.get(System.getProperty("test.gradle.user.home"));

    @BeforeEach
    @SneakyThrows
    public void setup(@TempDir Path testDir) {
        this.testDir = testDir;
        String resourceFile = "test-resources.txt";
        try (BufferedReader reader =
                     Optional.ofNullable(getResource(resourceFile).openStream())
                             .map(InputStreamReader::new)
                             .map(BufferedReader::new)
                             .orElseThrow(() -> new FileNotFoundException(resourceFile))) {
            while (true) {
                String line = reader.readLine();
                if (line == null) break;
                Path destination = testDir.resolve(line);
                Files.createDirectories(destination.getParent());
                Files.copy(getResource(line).openStream(), destination);
            }
        }
    }

    @Test
    void legacyTest() {
        invokeGradle(testDir.resolve("test-project"), ":legacy-executable:envelopeRun");
    }

    @Test
    void jpmsTest() {
        invokeGradle(testDir.resolve("test-project"), ":jpms-executable:envelopeRun");
    }

}
