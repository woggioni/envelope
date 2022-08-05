package net.woggioni.envelope.loader;

import lombok.SneakyThrows;
import net.woggioni.envelope.loader.JarFile;
import net.woggioni.envelope.loader.jar.Handler;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.net.URI;
import java.net.URL;
import java.net.URLStreamHandler;
import java.nio.file.Paths;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.Attributes.Name;
import java.util.jar.JarEntry;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class JarFileModuleFinder implements ModuleFinder {

    private static final String MODULE_DESCRIPTOR_ENTRY = "module-info.class";
    private static final Name AUTOMATIC_MODULE_NAME_MANIFEST_ENTRY = new Name("Automatic-Module-Name");

    private static class Patterns {
        static final Pattern DASH_VERSION = Pattern.compile("-(\\d+(\\.|$))");
        static final Pattern NON_ALPHANUM = Pattern.compile("[^A-Za-z0-9]");
        static final Pattern REPEATING_DOTS = Pattern.compile("(\\.)(\\1)+");
        static final Pattern LEADING_DOTS = Pattern.compile("^\\.");
        static final Pattern TRAILING_DOTS = Pattern.compile("\\.$");
    }
    private final Map<String, Map.Entry<ModuleReference, Handler>> modules;

    @SneakyThrows
    private static final URI toURI(URL url) {
        return url.toURI();
    }

    public final URLStreamHandler getStreamHandlerForModule(String moduleName) {
        return modules.get(moduleName).getValue();
    }

    private static String cleanModuleName(String mn) {
        // replace non-alphanumeric
        mn = Patterns.NON_ALPHANUM.matcher(mn).replaceAll(".");

        // collapse repeating dots
        mn = Patterns.REPEATING_DOTS.matcher(mn).replaceAll(".");

        // drop leading dots
        if (!mn.isEmpty() && mn.charAt(0) == '.')
            mn = Patterns.LEADING_DOTS.matcher(mn).replaceAll("");

        // drop trailing dots
        int len = mn.length();
        if (len > 0 && mn.charAt(len-1) == '.')
            mn = Patterns.TRAILING_DOTS.matcher(mn).replaceAll("");

        return mn;
    }

    @SneakyThrows
    private static String moduleNameFromURI(URI uri) {
        String fn = null;
        URI tmp = uri;
        while(true) {
            String schemeSpecificPart = tmp.getSchemeSpecificPart();
            if(tmp.getPath() != null) {
                String path = tmp.getPath();
                int end = path.lastIndexOf("!");
                if(end == -1) end = path.length();
                int start = path.lastIndexOf("!", end - 1);
                if(start == -1) start = 0;
                fn = Paths.get(path.substring(start, end)).getFileName().toString();
                break;
            } else {
                tmp = new URI(schemeSpecificPart);
            }
        }
        // Derive the version, and the module name if needed, from JAR file name
        int i = fn.lastIndexOf(File.separator);
        if (i != -1)
            fn = fn.substring(i + 1);

        // drop ".jar"
        String name = fn.substring(0, fn.length() - 4);
        String vs = null;

        // find first occurrence of -${NUMBER}. or -${NUMBER}$
        Matcher matcher = Patterns.DASH_VERSION.matcher(name);
        if (matcher.find()) {
            int start = matcher.start();

            // attempt to parse the tail as a version string
            try {
                String tail = name.substring(start + 1);
                ModuleDescriptor.Version.parse(tail);
                vs = tail;
            } catch (IllegalArgumentException ignore) { }

            name = name.substring(0, start);
        }
        return cleanModuleName(name);
    }

    public JarFileModuleFinder(JarFile ...jarFiles) {
        this(Arrays.asList(jarFiles));
    }
    private static Set<String> collectPackageNames(JarFile jarFile) {
        Set<String> result = jarFile
            .versionedStream()
            .filter(entry -> entry.getName().endsWith(".class"))
            .map(entry -> {
                String entryName = entry.getName();
                int lastSlash = entryName.lastIndexOf('/');
                if(lastSlash < 0) return null;
                else return entryName.substring(0, lastSlash).replace('/', '.');
            })
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        return Collections.unmodifiableSet(result);
    }
    @SneakyThrows
    public JarFileModuleFinder(Iterable<JarFile> jarFiles) {

        TreeMap<String, Map.Entry<ModuleReference, Handler>> modules = new TreeMap<>();

        for(JarFile jarFile : jarFiles) {
            URI uri = jarFile.getUrl().toURI();
            String moduleName = null;
            ModuleDescriptor moduleDescriptor;
            JarEntry moduleDescriptorEntry = jarFile.getJarEntry(MODULE_DESCRIPTOR_ENTRY);
            if (moduleDescriptorEntry != null) {
                try(InputStream is = jarFile.getInputStream(moduleDescriptorEntry)) {
                    moduleDescriptor = ModuleDescriptor.read(is, () -> collectPackageNames(jarFile));
                }
            } else {
                Manifest mf = jarFile.getManifest();
                moduleName = mf.getMainAttributes().getValue(AUTOMATIC_MODULE_NAME_MANIFEST_ENTRY);
                if(moduleName == null) {
                    moduleName = moduleNameFromURI(uri);
                }
                ModuleDescriptor.Builder mdb = ModuleDescriptor.newAutomaticModule(moduleName);
                mdb.packages(collectPackageNames(jarFile));

                // Main-Class attribute if it exists
                String mainClass = mf.getMainAttributes().getValue(Name.MAIN_CLASS);
                if (mainClass != null) {
                    mdb.mainClass(mainClass);
                }
                moduleDescriptor = mdb.build();
            }
        
            modules.put(moduleDescriptor.name(),
                new AbstractMap.SimpleEntry<>(new ModuleReference(moduleDescriptor, uri) {
                    @Override
                    public ModuleReader open() throws IOException {
                        return new ModuleReader() {
                            @Override
                            public Optional<URI> find(String name) throws IOException {
                                JarEntry jarEntry = jarFile.getJarEntry(name);
                                if(jarEntry == null) return Optional.empty();
                                return Optional.of(uri.resolve('!' + name));
                            }

                            @Override
                            public Optional<InputStream> open(String name) throws IOException {
                                JarEntry jarEntry = jarFile.getJarEntry(name);
                                if(jarEntry == null) return Optional.empty();
                                return Optional.of(jarFile.getInputStream(jarEntry));
                            }

                            @Override
                            public Stream<String> list() throws IOException {
                                return jarFile.stream().map(JarEntry::getName);
                            }

                            @Override
                            public void close() throws IOException {}
                        };
                    }
            }, new Handler(jarFile)));
        }
        this.modules = Collections.unmodifiableMap(modules);
    }

    @Override
    public Optional<ModuleReference> find(String name) {
        return Optional.ofNullable(modules.get(name)).map(Map.Entry::getKey);
    }

    @Override
    public Set<ModuleReference> findAll() {
        return Collections.unmodifiableSet(modules.values()
            .stream().map(Map.Entry::getKey)
            .collect(Collectors.toSet()));
    }
}
