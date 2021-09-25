package net.woggioni.gradle.executable.jar;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.BasePluginExtension;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.tasks.bundling.Jar;

public class ExecutableJarPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        BasePluginExtension basePluginExtension = project.getExtensions().getByType(BasePluginExtension.class);
        project.getTasks().register("executable-jar", ExecutableJarTask.class, t -> {
            t.includeLibraries(project.getConfigurations().named(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME));
            t.includeLibraries(project.getTasks().named(JavaPlugin.JAR_TASK_NAME, Jar.class));
        });
    }
}
