package net.woggioni.executable.jar;

import java.util.Calendar;
import java.util.GregorianCalendar;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class Constants {
    public static final String LIBRARIES_FOLDER = "LIB-INF";
    public static final String METADATA_FOLDER = "META-INF";
    public static final int BUFFER_SIZE = 0x10000;
    public static final String DEFAULT_LAUNCHER = "net.woggioni.executable.jar.Launcher";
    public static final String AGENT_LAUNCHER = "net.woggioni.executable.jar.JavaAgentLauncher";
    public static final String JAVA_AGENTS_FILE = METADATA_FOLDER + "/javaAgents.properties";

    public static class ManifestAttributes {
        public static final String MAIN_MODULE = "Executable-Jar-Main-Module";
        public static final String MAIN_CLASS = "Executable-Jar-Main-Class";
        public static final String ENTRY_HASH = "SHA-256-Digest";
    }

    /**
     * This value is used as a default file timestamp for all the zip entries when
     * <a href="https://docs.gradle.org/current/javadoc/org/gradle/api/tasks/bundling/AbstractArchiveTask.html#isPreserveFileTimestamps--">AbstractArchiveTask.isPreserveFileTimestamps</a>
     * is true; its value is taken from Gradle's <a href="https://github.com/gradle/gradle/blob/master/subprojects/core/src/main/java/org/gradle/api/internal/file/archive/ZipCopyAction.java#L42-L57">ZipCopyAction<a/>
     * for the reasons outlined there.
     */
    public static final long ZIP_ENTRIES_DEFAULT_TIMESTAMP =
            new GregorianCalendar(1980, Calendar.FEBRUARY, 1, 0, 0, 0).getTimeInMillis();
}
