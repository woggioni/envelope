package net.woggioni.executable.jar;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.SneakyThrows;
import org.gradle.internal.impldep.org.junit.Ignore;
import org.junit.jupiter.api.Test;

public class FooTest {
    @Test
    @SneakyThrows
    void foo() {
        Path.of(new URI("jar:file:///home/woggioni/code/wson/benchmark/build/libs/benchmark-executable-1.0.jar"));
    }

    @Test
    @SneakyThrows
    void foo2() {
        Path p = Path.of(new URI("file:///home/woggioni/code/wson/benchmark/build/libs/benchmark-executable-1.0.jar"));
        FileSystem fs = FileSystems.newFileSystem(p, null);
        StreamSupport.stream(fs.getRootDirectories().spliterator(), false).flatMap(new Function<Path, Stream<?>>() {
            @Override
            @SneakyThrows
            public Stream<?> apply(Path path) {
                return Files.list(path);
            }
        }).forEach(r -> {
            System.out.println(r);
        });
    }

    @Test
    @SneakyThrows
    void test() {
        Path fatJar = Path.of("/home/woggioni/code/wson/benchmark/build/libs/benchmark-executable-1.0.jar");
        List<Path> jars = StreamSupport.stream(FileSystems.newFileSystem(fatJar, null).getRootDirectories().spliterator(), false)
                .flatMap(new Function<Path, Stream<? extends Path>>() {
            @Override
            @SneakyThrows
            public Stream<? extends Path> apply(Path root) {
                Path libDir = root.resolve("/LIB-INF");
                if (Files.exists(libDir) && Files.isDirectory(libDir)) {
                    return Files.list(libDir);
                } else {
                    return Stream.empty();
                }
            }
        }).flatMap(new Function<Path, Stream<Path>>() {
            @Override
            @SneakyThrows
            public Stream<Path> apply(Path path) {
                return StreamSupport.stream(FileSystems.newFileSystem(path, null).getRootDirectories().spliterator(), false);
            }
        }).collect(Collectors.toList());
        PathClassLoader p = new PathClassLoader(jars.toArray(new Path[jars.size()]));
        Class<?> cl = p.loadClass("net.woggioni.wson.serialization.binary.JBONParser");
        System.out.println(cl);
        URL resource = p.findResource("citylots.json.xz");
        resource.openStream();
    }

    @Test
    @Ignore
    @SneakyThrows
    void test2() {
        FileSystem fs = FileSystems.newFileSystem(new URI("jar:file:/home/woggioni/code/wson/benchmark/build/libs/benchmark-executable-1.0.jar"), new HashMap<>());
        String s = "jar:jar:file:///home/woggioni/code/wson/benchmark/build/libs/benchmark-executable-1.0.jar!/LIB-INF/wson-test-utils-1.0.jar!/citylots.json.xz";
        URI uri = new URI(s);
        Files.list(Path.of(uri)).forEach(System.out::println);
    }

    private static final class PathURLConnection extends URLConnection {

        private final Path path;

        PathURLConnection(URL url, Path path) {
            super(url);
            this.path = path;
        }

        @Override
        public void connect() {}

        @Override
        public long getContentLengthLong() {
            try {
                return Files.size(this.path);
            } catch (IOException e) {
                throw new RuntimeException("could not get size of: " + this.path, e);
            }
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return Files.newInputStream(this.path);
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            return Files.newOutputStream(this.path);
        }

        @Override
        @SneakyThrows
        public String getContentType() {
            return Files.probeContentType(this.path);
        }

        @Override
        @SneakyThrows
        public long getLastModified() {
            BasicFileAttributes attributes = Files.readAttributes(this.path, BasicFileAttributes.class);
            return attributes.lastModifiedTime().toMillis();
        }
    }

    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    private static final class PathURLStreamHandler extends URLStreamHandler {

        static final URLStreamHandler INSTANCE = new PathURLStreamHandler();

        @Override
        @SneakyThrows
        protected URLConnection openConnection(URL url) {
            URI uri = url.toURI();
            Path path = Paths.get(uri);
            return new PathURLConnection(url, path);
        }
    }
}
