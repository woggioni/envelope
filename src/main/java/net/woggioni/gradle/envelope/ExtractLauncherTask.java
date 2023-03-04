package net.woggioni.gradle.envelope;

import lombok.Getter;
import lombok.SneakyThrows;
import net.woggioni.envelope.Common;
import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public class ExtractLauncherTask extends DefaultTask {

    @Getter(onMethod_ = @OutputFile)
    private final Path launcherTar;

    @Input
    public byte[] getInputHash() {
        return LauncherResource.instance.getHash();
    }
    public ExtractLauncherTask() {
        Path tmpDir = getTemporaryDir().toPath();
        launcherTar = tmpDir.resolve("launcher.tar");
    }

    @TaskAction
    @SneakyThrows
    public void run() {
        try(InputStream inputStream = LauncherResource.instance.read();
            OutputStream outputStream = Files.newOutputStream(launcherTar)) {
            Common.write2Stream(inputStream, outputStream);
        }
    }
}
