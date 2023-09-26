package net.woggioni.gradle.envelope;

import org.gradle.api.DefaultTask;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.BasePlugin;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.bundling.Jar;

public class EnvelopePlugin implements Plugin<Project> {

    public static final String ENVELOPE_GROUP_NAME = " envelope";

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply(JavaPlugin.class);
        Provider<EnvelopeJarTask> envelopeJarTaskProvider = project.getTasks().register("envelopeJar", EnvelopeJarTask.class, t -> {
            t.setGroup(ENVELOPE_GROUP_NAME);
            t.setDescription("Package the application in a single executable jar file");
            t.includeLibraries(project.getConfigurations().named(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME));
            t.includeLibraries(project.getTasks().named(JavaPlugin.JAR_TASK_NAME, Jar.class));
        });
        project.getTasks().named(BasePlugin.ASSEMBLE_TASK_NAME, DefaultTask.class, assembleTask -> {
            assembleTask.dependsOn(envelopeJarTaskProvider);
        });
        Provider<JavaExec> envelopeRunTaskProvider = project.getTasks().register("envelopeRun", JavaExec.class, t -> {
            t.getInputs().files(envelopeJarTaskProvider);
            t.setGroup(ENVELOPE_GROUP_NAME);
            t.setDescription("Run the application in the envelope jar");
            t.classpath(envelopeJarTaskProvider);
        });
    }
}
