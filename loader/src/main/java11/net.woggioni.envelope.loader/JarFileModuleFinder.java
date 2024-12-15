package net.woggioni.envelope.loader;

import lombok.SneakyThrows;
import net.woggioni.envelope.loader.JarFile;
import net.woggioni.envelope.loader.jar.Handler;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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
import java.util.ArrayList;
import java.util.List;
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
import java.util.jar.Attributes;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.lang.module.FindException;
import java.nio.charset.StandardCharsets;
import java.lang.module.InvalidModuleDescriptorException;

public class JarFileModuleFinder implements ModuleFinder {

    private static final String MODULE_DESCRIPTOR_ENTRY = "module-info.class";
    private static final Name AUTOMATIC_MODULE_NAME_MANIFEST_ENTRY = new Name("Automatic-Module-Name");
    private static final String SERVICES_PREFIX = "META-INF/services/";
    private static class Patterns {
        static final Pattern DASH_VERSION = Pattern.compile("-(\\d+(\\.|$))");
        static final Pattern NON_ALPHANUM = Pattern.compile("[^A-Za-z0-9]");
        static final Pattern REPEATING_DOTS = Pattern.compile("(\\.)(\\1)+");
        static final Pattern LEADING_DOTS = Pattern.compile("^\\.");
        static final Pattern TRAILING_DOTS = Pattern.compile("\\.$");
    }


    // keywords, boolean and null literals, not allowed in identifiers
    private static final Set<String> RESERVED = Set.of(
        "abstract",
        "assert",
        "boolean",
        "break",
        "byte",
        "case",
        "catch",
        "char",
        "class",
        "const",
        "continue",
        "default",
        "do",
        "double",
        "else",
        "enum",
        "extends",
        "final",
        "finally",
        "float",
        "for",
        "goto",
        "if",
        "implements",
        "import",
        "instanceof",
        "int",
        "interface",
        "long",
        "native",
        "new",
        "package",
        "private",
        "protected",
        "public",
        "return",
        "short",
        "static",
        "strictfp",
        "super",
        "switch",
        "synchronized",
        "this",
        "throw",
        "throws",
        "transient",
        "try",
        "void",
        "volatile",
        "while",
        "true",
        "false",
        "null",
        "_"
    );

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
                  moduleDescriptor = deriveModuleDescriptor(jarFile);
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

    private ModuleDescriptor deriveModuleDescriptor(JarFile jf)
        throws IOException
    {
        // Read Automatic-Module-Name attribute if present
        Manifest man = jf.getManifest();
        Attributes attrs = null;
        String moduleName = null;
        if (man != null) {
            attrs = man.getMainAttributes();
            if (attrs != null) {
                moduleName = attrs.getValue(AUTOMATIC_MODULE_NAME_MANIFEST_ENTRY);
            }
        }

        // Derive the version, and the module name if needed, from JAR file name
        String fn = jf.getName();
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

        // Create builder, using the name derived from file name when
        // Automatic-Module-Name not present
        ModuleDescriptor.Builder builder;
        if (moduleName != null) {
            try {
                builder = ModuleDescriptor.newAutomaticModule(moduleName);
            } catch (IllegalArgumentException e) {
                throw new FindException(AUTOMATIC_MODULE_NAME_MANIFEST_ENTRY + ": " + e.getMessage());
            }
        } else {
            builder = ModuleDescriptor.newAutomaticModule(cleanModuleName(name));
        }

        // module version if present
        if (vs != null)
            builder.version(vs);

        // scan the names of the entries in the JAR file
        Map<Boolean, Set<String>> map = jf.versionedStream()
            .filter(e -> !e.isDirectory())
            .map(JarEntry::getName)
            .filter(e -> (e.endsWith(".class") ^ e.startsWith(SERVICES_PREFIX)))
            .collect(Collectors.partitioningBy(e -> e.startsWith(SERVICES_PREFIX),
                Collectors.toSet()));

        Set<String> classFiles = map.get(Boolean.FALSE);
        Set<String> configFiles = map.get(Boolean.TRUE);

        // the packages containing class files
        Set<String> packages = classFiles.stream()
            .map(this::toPackageName)
            .flatMap(Optional::stream)
            .distinct()
            .collect(Collectors.toSet());

        // all packages are exported and open
        builder.packages(packages);

        // map names of service configuration files to service names
        Set<String> serviceNames = configFiles.stream()
            .map(this::toServiceName)
            .flatMap(Optional::stream)
            .collect(Collectors.toSet());

        // parse each service configuration file
        for (String sn : serviceNames) {
            JarEntry entry = jf.getJarEntry(SERVICES_PREFIX + sn);
            List<String> providerClasses = new ArrayList<>();
            try (InputStream in = jf.getInputStream(entry)) {
                BufferedReader reader
                    = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                String cn;
                while ((cn = nextLine(reader)) != null) {
                    if (!cn.isEmpty()) {
                        String pn = packageName(cn);
                        if (!packages.contains(pn)) {
                            String msg = "Provider class " + cn + " not in module";
                            throw new InvalidModuleDescriptorException(msg);
                        }
                        providerClasses.add(cn);
                    }
                }
            }
            if (!providerClasses.isEmpty())
                builder.provides(sn, providerClasses);
        }

        // Main-Class attribute if it exists
        if (attrs != null) {
            String mainClass = attrs.getValue(Attributes.Name.MAIN_CLASS);
            if (mainClass != null) {
                mainClass = mainClass.replace('/', '.');
                if (isClassName(mainClass)) {
                    String pn = packageName(mainClass);
                    if (packages.contains(pn)) {
                        builder.mainClass(mainClass);
                    }
                }
            }
        }

        return builder.build();
    }

    private Optional<String> toServiceName(String cf) {
        assert cf.startsWith(SERVICES_PREFIX);
        int index = cf.lastIndexOf("/") + 1;
        if (index < cf.length()) {
            String prefix = cf.substring(0, index);
            if (prefix.equals(SERVICES_PREFIX)) {
                String sn = cf.substring(index);
                if (isClassName(sn))
                    return Optional.of(sn);
            }
        }
        return Optional.empty();
    }

    private static String packageName(String cn) {
        int index = cn.lastIndexOf('.');
        return (index == -1) ? "" : cn.substring(0, index);
    }

    /**
     * Maps the name of an entry in a JAR or ZIP file to a package name.
     *
     * @throws InvalidModuleDescriptorException if the name is a class file in
     *         the top-level directory of the JAR/ZIP file (and it's not
     *         module-info.class)
     */
    private Optional<String> toPackageName(String name) {
        assert !name.endsWith("/");
        int index = name.lastIndexOf("/");
        if (index == -1) {
            if (name.endsWith(".class") && !name.equals(MODULE_DESCRIPTOR_ENTRY)) {
                String msg = name + " found in top-level directory"
                    + " (unnamed package not allowed in module)";
                throw new InvalidModuleDescriptorException(msg);
            }
            return Optional.empty();
        }

        String pn = name.substring(0, index).replace('/', '.');
        if (isPackageName(pn)) {
            return Optional.of(pn);
        } else {
            // not a valid package name
            return Optional.empty();
        }
    }

    /**
     * Reads the next line from the given reader and trims it of comments and
     * leading/trailing white space.
     *
     * Returns null if the reader is at EOF.
     */
    private String nextLine(BufferedReader reader) throws IOException {
        String ln = reader.readLine();
        if (ln != null) {
            int ci = ln.indexOf('#');
            if (ci >= 0)
                ln = ln.substring(0, ci);
            ln = ln.trim();
        }
        return ln;
    }

    private static boolean isClassName(String name) {
        return isTypeName(name);
    }

    /**
     * Returns {@code true} if the given name is a legal type name.
     */
    private static boolean isPackageName(String name) {
        return isTypeName(name);
    }

    private static boolean isTypeName(String name) {
        int next;
        int off = 0;
        while ((next = name.indexOf('.', off)) != -1) {
            String id = name.substring(off, next);
            if (!isJavaIdentifier(id))
                return false;
            off = next+1;
        }
        String last = name.substring(off);
        return isJavaIdentifier(last);
    }

    private static boolean isJavaIdentifier(String str) {
        if (str.isEmpty() || RESERVED.contains(str))
            return false;

        int first = Character.codePointAt(str, 0);
        if (!Character.isJavaIdentifierStart(first))
            return false;

        int i = Character.charCount(first);
        while (i < str.length()) {
            int cp = Character.codePointAt(str, i);
            if (!Character.isJavaIdentifierPart(cp))
                return false;
            i += Character.charCount(cp);
        }

        return true;
    }
}
