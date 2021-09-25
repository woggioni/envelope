package net.woggioni.gradle.executable.jar;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.security.MessageDigest;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.function.Supplier;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.annotation.Nonnull;
import javax.inject.Inject;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import net.woggioni.executable.jar.Common;
import net.woggioni.executable.jar.Constants;
import org.gradle.api.GradleException;
import org.gradle.api.internal.file.CopyActionProcessingStreamAction;
import org.gradle.api.internal.file.copy.CopyAction;
import org.gradle.api.internal.file.copy.CopyActionProcessingStream;
import org.gradle.api.internal.file.copy.FileCopyDetailsInternal;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.plugins.BasePluginExtension;
import org.gradle.api.plugins.JavaApplication;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.WorkResult;
import org.gradle.api.tasks.bundling.AbstractArchiveTask;
import org.gradle.util.GradleVersion;


import static java.util.zip.Deflater.BEST_COMPRESSION;
import static java.util.zip.Deflater.NO_COMPRESSION;
import static net.woggioni.executable.jar.Constants.*;

@SuppressWarnings({"unused" })
public class ExecutableJarTask extends AbstractArchiveTask {

    private static final String MINIMUM_GRADLE_VERSION = "6.0";

    static {
        if (GradleVersion.current().compareTo(GradleVersion.version(MINIMUM_GRADLE_VERSION)) < 0) {
            throw new GradleException(ExecutableJarTask.class.getName() +
                    " requires Gradle " + MINIMUM_GRADLE_VERSION + " or newer.");
        }
    }

    @Getter(onMethod_ = {@Input})
    private final Property<String> mainClass;

    @Getter(onMethod_ = {@Input, @Optional})
    private final Property<String> mainModule;

    private final Properties javaAgents = new Properties();

    @Input
    public Set<Map.Entry<Object, Object>> getJavaAgents() {
        return Collections.unmodifiableSet(javaAgents.entrySet());
    }

    public void javaAgent(String className, String args) {
        javaAgents.put(className, args);
    }

    public void includeLibraries(Object... files) {
        into(LIBRARIES_FOLDER, (copySpec) -> copySpec.from(files));
    }


    @Inject
    public ExecutableJarTask(ObjectFactory objects) {
        setGroup("build");
        setDescription("Creates an executable jar file, embedding all of its runtime dependencies");
        BasePluginExtension basePluginExtension = getProject().getExtensions().getByType(BasePluginExtension.class);
        getDestinationDirectory().set(basePluginExtension.getDistsDirectory());
        getArchiveBaseName().convention(getProject().getName());
        getArchiveExtension().convention("jar");
        getArchiveVersion().convention(getProject().getVersion().toString());
        getArchiveAppendix().convention("executable");
        exclude("**/module-info.class");

        mainClass = objects.property(String.class);
        mainModule = objects.property(String.class);
        JavaApplication javaApplication = getProject().getExtensions().findByType(JavaApplication.class);
        if(!Objects.isNull(javaApplication)) {
            mainClass.convention(javaApplication.getMainClass());
            mainModule.convention(javaApplication.getMainModule());
        }
        from(getProject().tarTree(LauncherResource.instance), copySpec -> exclude(JarFile.MANIFEST_NAME));
    }

    @Input
    public String getLauncherArchiveHash() {
        return Common.bytesToHex(Common.computeSHA256Digest(LauncherResource.instance::read));
    }

    @RequiredArgsConstructor
    private static class StreamAction implements CopyActionProcessingStreamAction {

        private final ZipOutputStream zoos;
        private final Manifest manifest;
        private final MessageDigest md;
        private final ZipEntryFactory zipEntryFactory;
        private final byte[] buffer;

        @Override
        @SneakyThrows
        public void processFile(FileCopyDetailsInternal fileCopyDetails) {
            String entryName = fileCopyDetails.getRelativePath().toString();
            if (!fileCopyDetails.isDirectory() && entryName.startsWith(LIBRARIES_FOLDER)) {
                Supplier<InputStream> streamSupplier = () -> Common.read(fileCopyDetails.getFile(), false);
                Attributes attr = manifest.getEntries().computeIfAbsent(entryName, it -> new Attributes());
                md.reset();
                attr.putValue(Constants.ManifestAttributes.ENTRY_HASH,
                        Base64.getEncoder().encodeToString(Common.computeDigest(streamSupplier, md, buffer)));
            }
            if (METADATA_FOLDER.equals(entryName)) return;
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
            zipEntry.setTime(isPreserveFileTimestamps ? lastModifiedTime : ZIP_ENTRIES_DEFAULT_TIMESTAMP);
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
                Manifest manifest = new Manifest();
                Attributes mainAttributes = manifest.getMainAttributes();
                mainAttributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
                mainAttributes.put(Attributes.Name.MAIN_CLASS, DEFAULT_LAUNCHER);
                mainAttributes.put(Attributes.Name.MULTI_RELEASE, "true");
                mainAttributes.put(new Attributes.Name("Launcher-Agent-Class"), AGENT_LAUNCHER);
                mainAttributes.put(new Attributes.Name("Can-Redefine-Classes"), "true");
                mainAttributes.put(new Attributes.Name("Can-Retransform-Classes"), "true");
                mainAttributes.putValue(Constants.ManifestAttributes.MAIN_CLASS, mainClass.get());
                if(mainModule.isPresent()) {
                    mainAttributes.putValue(Constants.ManifestAttributes.MAIN_MODULE, mainModule.get());
                }

                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] buffer = new byte[Constants.BUFFER_SIZE];

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
                    StreamAction streamAction = new StreamAction(zipOutputStream, manifest, md, zipEntryFactory, buffer);
                    copyActionProcessingStream.process(streamAction);
                }

                try (ZipOutputStream zipOutputStream = new ZipOutputStream(Common.write(destination, true));
                    ZipInputStream zipInputStream = new ZipInputStream(Common.read(temporaryJar, true))) {
                    zipOutputStream.setLevel(BEST_COMPRESSION);
                    ZipEntry zipEntry = zipEntryFactory.createDirectoryEntry(METADATA_FOLDER);
                    zipOutputStream.putNextEntry(zipEntry);
                    zipEntry = zipEntryFactory.createZipEntry(JarFile.MANIFEST_NAME);
                    zipEntry.setMethod(ZipEntry.DEFLATED);
                    zipOutputStream.putNextEntry(zipEntry);
                    manifest.write(zipOutputStream);
                    zipEntry = zipEntryFactory.createZipEntry(JAVA_AGENTS_FILE);
                    zipEntry.setMethod(ZipEntry.DEFLATED);
                    zipOutputStream.putNextEntry(zipEntry);
                    javaAgents.store(zipOutputStream, null);

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