package net.woggioni.gradle.envelope;

import groovy.lang.Closure;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import net.woggioni.envelope.Common;
import net.woggioni.envelope.Constants;
import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.internal.file.CopyActionProcessingStreamAction;
import org.gradle.api.internal.file.FileResolver;
import org.gradle.api.internal.file.copy.CopyAction;
import org.gradle.api.internal.file.copy.CopyActionProcessingStream;
import org.gradle.api.internal.file.copy.FileCopyDetailsInternal;
import org.gradle.api.java.archives.internal.DefaultManifest;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.plugins.BasePlugin;
import org.gradle.api.plugins.BasePluginExtension;
import org.gradle.api.plugins.JavaApplication;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.WorkResult;
import org.gradle.api.tasks.bundling.AbstractArchiveTask;
import org.gradle.internal.Cast;
import org.gradle.util.GradleVersion;

import javax.annotation.Nonnull;
import javax.inject.Inject;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static java.util.zip.Deflater.BEST_COMPRESSION;
import static java.util.zip.Deflater.NO_COMPRESSION;
import static net.woggioni.gradle.envelope.EnvelopePlugin.ENVELOPE_GROUP_NAME;

@SuppressWarnings({"unused" })
public abstract class EnvelopeJarTask extends AbstractArchiveTask {

    private static final String DEFAULT_ARCHIVE_APPENDIX = ENVELOPE_GROUP_NAME;
    private static final String MINIMUM_GRADLE_VERSION = "6.0";
    private static final String EXTRACT_LAUNCHER_TASK_NAME = "extractEnvelopeLauncher";

    static {
        if (GradleVersion.current().compareTo(GradleVersion.version(MINIMUM_GRADLE_VERSION)) < 0) {
            throw new GradleException(EnvelopeJarTask.class.getName() +
                    " requires Gradle " + MINIMUM_GRADLE_VERSION + " or newer.");
        }
    }

    private final Provider<ExtractLauncherTask> extractLauncherTaskProvider;

    @Input
    @Optional
    public abstract Property<String> getMainClass();

    @Input
    @Optional
    public abstract Property<String> getMainModule();

    private final Properties javaAgents = new Properties();

    @Input
    public abstract MapProperty<String, String> getSystemProperties();

    @Input
    public abstract ListProperty<String> getExtraClasspath();

    private final org.gradle.api.java.archives.Manifest manifest;

    public org.gradle.api.java.archives.Manifest manifest() {
        return manifest;
    }

    @Input
    @SneakyThrows
    public byte[] getManifestHash() {
        MessageDigest md = MessageDigest.getInstance("md5");
        try(OutputStream outputStream = new DigestOutputStream(NullOutputStream.getInstance(), md)) {
            ManifestUtils.generateJavaManifest(manifest).write(outputStream);
        }
        return md.digest();
    }

    public org.gradle.api.java.archives.Manifest manifest(Closure<org.gradle.api.java.archives.Manifest> closure) {
        closure.setDelegate(manifest);
        closure.call();
        return manifest;
    }

    public org.gradle.api.java.archives.Manifest manifest(Action<? super org.gradle.api.java.archives.Manifest> action) {
        action.execute(manifest);
        return manifest;
    }

    @Input
    public Set<String> getJavaAgents() {
        return Collections.unmodifiableSet(javaAgents.entrySet().stream()
                .map(entry -> entry.getKey().toString() + ':' + entry.getValue().toString())
                .collect(Collectors.toSet()));
    }

    public void javaAgent(String className, String args) {
        javaAgents.put(className, args);
    }

    public void includeLibraries(Object... files) {
        into(Constants.LIBRARIES_FOLDER, (copySpec) -> copySpec.from(files));
    }


    @Inject
    public EnvelopeJarTask(ObjectFactory objects, FileResolver fileResolver) {
        Project project = getProject();
        TaskContainer tasks = project.getTasks();
        if(tasks.getNames().contains(EXTRACT_LAUNCHER_TASK_NAME)) {
            extractLauncherTaskProvider = tasks.named(EXTRACT_LAUNCHER_TASK_NAME, ExtractLauncherTask.class);
        } else {
            extractLauncherTaskProvider = tasks.register(EXTRACT_LAUNCHER_TASK_NAME, ExtractLauncherTask.class);
        }
        getInputs().files(extractLauncherTaskProvider);

        setGroup(BasePlugin.BUILD_GROUP);
        setDescription("Creates an executable jar file, embedding all of its runtime dependencies");
        BasePluginExtension basePluginExtension = getProject().getExtensions().getByType(BasePluginExtension.class);
        getDestinationDirectory().set(basePluginExtension.getLibsDirectory());
        getArchiveBaseName().convention(getProject().getName());
        getArchiveExtension().convention("jar");
        getArchiveVersion().convention(getProject().getVersion().toString());
        getArchiveAppendix().convention(DEFAULT_ARCHIVE_APPENDIX);

        manifest = new DefaultManifest(fileResolver);
        getSystemProperties().convention(new TreeMap<>());
        JavaApplication javaApplication = getProject().getExtensions().findByType(JavaApplication.class);
        if(!Objects.isNull(javaApplication)) {
            getMainClass().convention(javaApplication.getMainClass());
            getMainModule().convention(javaApplication.getMainModule());
        }
        from(getProject().tarTree(extractLauncherTaskProvider.map(ExtractLauncherTask::getLauncherTar)), copySpec -> exclude(JarFile.MANIFEST_NAME));
    }

    @RequiredArgsConstructor
    private static class StreamAction implements CopyActionProcessingStreamAction {

        private final ZipOutputStream zoos;
        private final Manifest manifest;
        private final MessageDigest md;
        private final ZipEntryFactory zipEntryFactory;
        private final byte[] buffer;

        private final List<String> libraries;

        private static final String LIBRARY_PREFIX = Constants.LIBRARIES_FOLDER + '/';

        @Override
        @SneakyThrows
        public void processFile(FileCopyDetailsInternal fileCopyDetails) {
            String entryName = fileCopyDetails.getRelativePath().toString();
            int start = LIBRARY_PREFIX.length() + 1;
            if (!fileCopyDetails.isDirectory() &&
                    entryName.startsWith(LIBRARY_PREFIX) &&
                    entryName.indexOf('/', start) < 0) {
                libraries.add(entryName.substring(LIBRARY_PREFIX.length()));
                Supplier<InputStream> streamSupplier = () -> Common.read(fileCopyDetails.getFile(), false);
                Attributes attr = manifest.getEntries().computeIfAbsent(entryName, it -> new Attributes());
                md.reset();
                attr.putValue(Constants.ManifestAttributes.ENTRY_HASH,
                        Base64.getEncoder().encodeToString(Common.computeDigest(streamSupplier, md, buffer)));
            }
            if (Constants.METADATA_FOLDER.equals(entryName)) return;
            if (fileCopyDetails.isDirectory()) {
                ZipEntry zipEntry = zipEntryFactory.createDirectoryEntry(entryName, fileCopyDetails.getLastModified());
                zoos.putNextEntry(zipEntry);
            } else {
                ZipEntry zipEntry = zipEntryFactory.createZipEntry(entryName, fileCopyDetails.getLastModified());
                boolean compressed = Common.splitExtension(fileCopyDetails.getSourceName())
                        .map(entry -> ".jar".equals(entry.getValue()))
                        .orElse(false);
                if (!compressed) {
                    zipEntry.setMethod(ZipEntry.DEFLATED);
                } else {
                    try (InputStream is = Common.read(fileCopyDetails.getFile(), false)) {
                        Common.computeSizeAndCrc32(zipEntry, is, buffer);
                    }
                    zipEntry.setMethod(ZipEntry.STORED);
                }
                zoos.putNextEntry(zipEntry);
                try (InputStream is = Common.read(fileCopyDetails.getFile(), false)) {
                    Common.write2Stream(is, zoos, buffer);
                }
            }
        }
    }

    @SuppressWarnings("SameParameterValue")
    @RequiredArgsConstructor
    private static final class ZipEntryFactory {

        private final boolean isPreserveFileTimestamps;
        private final long defaultLastModifiedTime;

        @Nonnull
        ZipEntry createZipEntry(String entryName, long lastModifiedTime) {
            ZipEntry zipEntry = new ZipEntry(entryName);
            zipEntry.setTime(isPreserveFileTimestamps ? lastModifiedTime : Constants.ZIP_ENTRIES_DEFAULT_TIMESTAMP);
            return zipEntry;
        }

        @Nonnull
        ZipEntry createZipEntry(String entryName) {
            return createZipEntry(entryName, defaultLastModifiedTime);
        }

        @Nonnull
        ZipEntry createDirectoryEntry(@Nonnull String entryName, long lastModifiedTime) {
            ZipEntry zipEntry = createZipEntry(entryName.endsWith("/") ? entryName : entryName + '/', lastModifiedTime);
            zipEntry.setMethod(ZipEntry.STORED);
            zipEntry.setCompressedSize(0);
            zipEntry.setSize(0);
            zipEntry.setCrc(0);
            return zipEntry;
        }

        @Nonnull
        ZipEntry createDirectoryEntry(@Nonnull String entryName) {
            return createDirectoryEntry(entryName, defaultLastModifiedTime);
        }

        @Nonnull
        ZipEntry copyOf(@Nonnull ZipEntry zipEntry) {
            if (zipEntry.getMethod() == ZipEntry.STORED) {
                return new ZipEntry(zipEntry);
            } else {
                ZipEntry newEntry = new ZipEntry(zipEntry.getName());
                newEntry.setMethod(ZipEntry.DEFLATED);
                newEntry.setTime(zipEntry.getTime());
                newEntry.setExtra(zipEntry.getExtra());
                newEntry.setComment(zipEntry.getComment());
                return newEntry;
            }
        }
    }

    @Override
    @Nonnull
    protected CopyAction createCopyAction() {
        File destination = getArchiveFile().get().getAsFile();
        return new CopyAction() {

            private final ZipEntryFactory zipEntryFactory = new ZipEntryFactory(isPreserveFileTimestamps(), System.currentTimeMillis());

            @Override
            @Nonnull
            @SneakyThrows
            public WorkResult execute(@Nonnull CopyActionProcessingStream copyActionProcessingStream) {
                Manifest manifest = ManifestUtils.generateJavaManifest(EnvelopeJarTask.this.manifest);
                Attributes mainAttributes = manifest.getMainAttributes();
                mainAttributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
                mainAttributes.put(Attributes.Name.MAIN_CLASS, Constants.DEFAULT_LAUNCHER);
                mainAttributes.putValue("Multi-Release", "true");
                mainAttributes.put(new Attributes.Name("Launcher-Agent-Class"), Constants.AGENT_LAUNCHER);
                mainAttributes.put(new Attributes.Name("Can-Redefine-Classes"), "true");
                mainAttributes.put(new Attributes.Name("Can-Retransform-Classes"), "true");
                String separator = "" + Constants.EXTRA_CLASSPATH_ENTRY_SEPARATOR;
                ListProperty<String> extraClasspath = EnvelopeJarTask.this.getExtraClasspath();
                if(extraClasspath.isPresent()) {
                    String extraClasspathString = extraClasspath.get().stream()
                        .map(it -> it.replace(separator, separator + separator)
                        ).collect(Collectors.joining(separator));
                    mainAttributes.put(new Attributes.Name(Constants.ManifestAttributes.EXTRA_CLASSPATH), extraClasspathString);
                }
                if(getMainClass().isPresent()) {
                    mainAttributes.putValue(Constants.ManifestAttributes.MAIN_CLASS, getMainClass().get());
                }
                if(getMainModule().isPresent()) {
                    mainAttributes.putValue(Constants.ManifestAttributes.MAIN_MODULE, getMainModule().get());
                }

                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] buffer = new byte[Constants.BUFFER_SIZE];
                List<String> libraries = new ArrayList<>();

                /**
                 * The manifest has to be the first zip entry in a jar archive, as an example,
                 * {@link java.util.jar.JarInputStream} assumes the manifest is the first (or second at most)
                 * entry in the jar and simply returns a null manifest if that is not the case.
                 * In this case the manifest has to contain the hash of all the jar entries, so it cannot
                 * be computed in advance, we write all the entries to a temporary zip archive while computing the manifest,
                 * then we write the manifest to the final zip file as the first entry and, finally,
                 * we copy all the other entries from the temporary archive.
                 *
                 * The {@link org.gradle.api.Task#getTemporaryDir} directory is guaranteed
                 * to be unique per instance of this task.
                 */
                File temporaryJar = new File(getTemporaryDir(), "premature.zip");
                try (ZipOutputStream zipOutputStream = new ZipOutputStream(Common.write(temporaryJar, true))) {
                    zipOutputStream.setLevel(NO_COMPRESSION);
                    StreamAction streamAction = new StreamAction(
                            zipOutputStream, manifest, md, zipEntryFactory, buffer, libraries);
                    copyActionProcessingStream.process(streamAction);
                }

                try (ZipOutputStream zipOutputStream = new ZipOutputStream(Common.write(destination, true));
                    ZipInputStream zipInputStream = new ZipInputStream(Common.read(temporaryJar, true))) {
                    zipOutputStream.setLevel(BEST_COMPRESSION);
                    ZipEntry zipEntry = zipEntryFactory.createDirectoryEntry(Constants.METADATA_FOLDER);
                    zipOutputStream.putNextEntry(zipEntry);
                    zipEntry = zipEntryFactory.createZipEntry(JarFile.MANIFEST_NAME);
                    zipEntry.setMethod(ZipEntry.DEFLATED);
                    zipOutputStream.putNextEntry(zipEntry);
                    manifest.write(zipOutputStream);
                    zipEntry = zipEntryFactory.createZipEntry(Constants.JAVA_AGENTS_FILE);
                    zipEntry.setMethod(ZipEntry.DEFLATED);
                    zipOutputStream.putNextEntry(zipEntry);
                    javaAgents.store(zipOutputStream, null);
                    zipEntry = zipEntryFactory.createZipEntry(Constants.SYSTEM_PROPERTIES_FILE);
                    zipEntry.setMethod(ZipEntry.DEFLATED);
                    zipOutputStream.putNextEntry(zipEntry);
                    Properties props = new Properties();
                    for(Map.Entry<String, String> entry : getSystemProperties().get().entrySet()) {
                        props.setProperty(entry.getKey(), entry.getValue());
                    }
                    props.store(zipOutputStream, null);
                    zipEntry = zipEntryFactory.createZipEntry(Constants.LIBRARIES_TOC);
                    zipEntry.setMethod(ZipEntry.DEFLATED);
                    zipOutputStream.putNextEntry(zipEntry);
                    int i = 0;
                    while(i < libraries.size()) {
                        if(i > 0) zipOutputStream.write('/');
                        zipOutputStream.write(libraries.get(i).getBytes(StandardCharsets.UTF_8));
                        ++i;
                    }

                    while (true) {
                        zipEntry = zipInputStream.getNextEntry();
                        if (zipEntry == null) break;
                        // Create a new ZipEntry explicitly, without relying on
                        // subtle (undocumented?) behaviour of ZipInputStream.
                        zipOutputStream.putNextEntry(zipEntryFactory.copyOf(zipEntry));
                        Common.write2Stream(zipInputStream, zipOutputStream, buffer);
                    }
                    return () -> true;
                }
            }
        };
    }


}

@NoArgsConstructor(access = AccessLevel.PRIVATE)
class ManifestUtils {
    static Manifest generateJavaManifest(org.gradle.api.java.archives.Manifest gradleManifest) {
        Manifest javaManifest = new Manifest();
        addMainAttributesToJavaManifest(gradleManifest, javaManifest);
        addSectionAttributesToJavaManifest(gradleManifest, javaManifest);
        return javaManifest;
    }

    private static void addMainAttributesToJavaManifest(org.gradle.api.java.archives.Manifest gradleManifest, Manifest javaManifest) {
        fillAttributes(gradleManifest.getAttributes(), javaManifest.getMainAttributes());
    }

    private static void addSectionAttributesToJavaManifest(org.gradle.api.java.archives.Manifest gradleManifest, Manifest javaManifest) {
        Iterator<Map.Entry<String, org.gradle.api.java.archives.Attributes>> it = gradleManifest.getSections().entrySet().iterator();

        while(it.hasNext()) {
            Map.Entry<String, org.gradle.api.java.archives.Attributes> entry = it.next();
            String sectionName = entry.getKey();
            java.util.jar.Attributes targetAttributes = new java.util.jar.Attributes();
            fillAttributes(entry.getValue(), targetAttributes);
            javaManifest.getEntries().put(sectionName, targetAttributes);
        }

    }

    private static void fillAttributes(org.gradle.api.java.archives.Attributes attributes, java.util.jar.Attributes targetAttributes) {
        Iterator<Map.Entry<String, Object>> it = attributes.entrySet().iterator();

        while(it.hasNext()) {
            Map.Entry<String, Object> entry = it.next();
            String mainAttributeName = entry.getKey();
            String mainAttributeValue = resolveValueToString(entry.getValue());
            if (mainAttributeValue != null) {
                targetAttributes.putValue(mainAttributeName, mainAttributeValue);
            }
        }

    }

    private static String resolveValueToString(Object value) {
        Object underlyingValue = value;
        if (value instanceof Provider) {
            Provider<?> provider = Cast.uncheckedCast(value);
            if (!provider.isPresent()) {
                return null;
            }

            underlyingValue = provider.get();
        }

        return underlyingValue.toString();
    }
}

@NoArgsConstructor
class NullOutputStream extends OutputStream {
    @Getter
    private static final OutputStream instance = new NullOutputStream();
    public void write(byte[] b, int off, int len) {}

    public void write(int b) {}

    public void write(byte[] b) {}
}