package net.woggioni.envelope.loader;

import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;

import java.net.URI;
import java.net.URL;
import java.net.URLStreamHandler;
import java.nio.ByteBuffer;
import java.lang.module.ResolvedModule;
import java.lang.module.Configuration;
import java.security.CodeSource;
import java.security.SecureClassLoader;
import java.security.CodeSigner;

@RequiredArgsConstructor
public final class ModuleClassLoader extends SecureClassLoader {

    static {
        registerAsParallelCapable();
    }

    private static String className2ResourceName(String className) {
        return className.replace('.', '/') + ".class";
    }

    private static String packageName(String cn) {
        int pos = cn.lastIndexOf('.');
        return (pos < 0) ? "" : cn.substring(0, pos);
    }

    private final Map<String, ClassLoader> packageMap;
    private final ModuleReference moduleReference;

    private final Function<URI, URL> urlConverter;

    @Override
    protected Class<?> loadClass(String className, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(className)) {
            Class<?> result = findLoadedClass(className);
            if (result == null) {
                result = findClass(className);
                if(result == null) {
                    ClassLoader classLoader = packageMap.get(packageName(className));
                    if (classLoader != null && classLoader != this) {
                        result = classLoader.loadClass(className);
                    }
                    if(result == null) {
                        result = super.loadClass(className, resolve);
                    }    
                } else if(resolve) {
                    resolveClass(result);
                }
            }
            return result;
        }
    }

    @Override
    protected URL findResource(String moduleName, String name) throws IOException {
        if (Objects.equals(moduleReference.descriptor().name(), moduleName)) {
            return findResource(name);
        } else {
            return null;
        }
    }

    @Override
    @SneakyThrows
    protected URL findResource(String resource) {
        try(ModuleReader reader = moduleReference.open()) {
            Optional<ByteBuffer> byteBufferOptional = reader.read(resource);
            if (byteBufferOptional.isPresent()) {
                ByteBuffer byteBuffer = byteBufferOptional.get();
                try {
                    return moduleReference.location()
                        .map(new Function<URI, URI>() {
                            @SneakyThrows
                            public URI apply(URI uri) {
                                return new URI(uri.toString() + resource);
                            }
                        }).map(urlConverter).orElse(null);
                } finally {
                    reader.release(byteBuffer);
                }
            } else {
                return null;
            }
        }
    }

    @Override
    protected Enumeration<URL> findResources(final String resource) throws IOException {
        return new Enumeration<URL>() {
            private URL url = findResource(resource);

            public boolean hasMoreElements() {
                return url != null;
            }

            public URL nextElement() {
                URL result = url;
                url = null;
                return result;
            }
        };
    }


    @Override
    @SneakyThrows
    protected Class<?> findClass(String className) throws ClassNotFoundException {
        Class<?> result = findClass(moduleReference.descriptor().name(), className);
        return result;
    }

    @Override
    @SneakyThrows
    protected Class<?> findClass(String moduleName, String className) {
        if (Objects.equals(moduleReference.descriptor().name(), moduleName)) {
            String resource = className.replace('.', '/').concat(".class");
            Optional<ByteBuffer> byteBufferOptional;
            try(ModuleReader reader = moduleReference.open()) {
                byteBufferOptional = reader.read(resource);
                if (byteBufferOptional.isPresent()) {
                    ByteBuffer byteBuffer = byteBufferOptional.get();
                    try {
                        URL location = moduleReference
                                .location()
                                .map(urlConverter)
                                .orElse(null);
                        CodeSource codeSource = new CodeSource(location, (CodeSigner[]) null);
                        return defineClass(className, byteBuffer, codeSource);
                    } finally {
                        reader.release(byteBuffer);
                    }
                } else {
                    return null;
                }
            }
        } else {
            return null;
        }
    }
}