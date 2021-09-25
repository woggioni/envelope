package net.woggioni.executable.jar;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.SneakyThrows;

/**
 * A classloader that loads classes from a {@link Path} instance
 */
public final class PathClassLoader extends ClassLoader {

    private final Iterable<Path> paths;

    static {
        registerAsParallelCapable();
    }

    public PathClassLoader(Path ...path) {
        this(Arrays.asList(path), null);
    }

    public PathClassLoader(Iterable<Path> paths) {
        this(paths, null);
    }

    public PathClassLoader(Iterable<Path> paths, ClassLoader parent) {
        super(parent);
        this.paths = paths;
    }

    @Override
    @SneakyThrows
    protected Class<?> findClass(String name) {
        String resource = name.replace('.', '/').concat(".class");
        for(Path path : paths) {
            Path classPath = path.resolve(resource);
            if (Files.exists(classPath)) {
                byte[] byteCode = Files.readAllBytes(classPath);
                return defineClass(name, byteCode, 0, byteCode.length);
            }
        }
        throw new ClassNotFoundException(name);
    }

    @Override
    @SneakyThrows
    protected URL findResource(String name) {
        for(Path path : paths) {
            Path resolved = path.resolve(name);
            if (Files.exists(resolved)) {
                return toURL(resolved);
            }
        }
        return null;
    }

    @Override
    protected Enumeration<URL> findResources(final String name) throws IOException {
        final List<URL> resources = new ArrayList<>(1);
        for(Path path : paths) {
            Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (!name.isEmpty()) {
                        this.addIfMatches(resources, file);
                    }
                    return super.visitFile(file, attrs);
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (!name.isEmpty() || path.equals(dir)) {
                        this.addIfMatches(resources, dir);
                    }
                    return super.preVisitDirectory(dir, attrs);
                }

                void addIfMatches(List<URL> resources, Path file) throws IOException {
                    if (path.relativize(file).toString().equals(name)) {
                        resources.add(toURL(file));
                    }
                }
            });
        }
        return Collections.enumeration(resources);
    }

    private static URL toURL(Path path) throws IOException {
        return new URL(null, path.toUri().toString(), PathURLStreamHandler.INSTANCE);
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
            List<String> stack = new ArrayList<>();
            URL currentURL = url;
            while(true) {
                String file = currentURL.getFile();
                int exclamationMark = file.lastIndexOf('!');
                if(exclamationMark != -1) {
                    stack.add(file.substring(exclamationMark + 1));
                    currentURL = new URL(file.substring(0, exclamationMark));
                } else {
                    stack.add(file);
                    break;
                }
            }

            Path path;
            FileSystem fs = FileSystems.getDefault();
            while(true) {
                String pathString = stack.remove(stack.size() - 1);
                path = fs.getPath(pathString);
                if(stack.isEmpty()) break;
                else {
                    fs = FileSystems.newFileSystem(path,  null);
                }
            }
            return new PathURLConnection(url, path);
        }
    }
}