## Overview
Envelope is a simple Gradle plugin that allows you to create an executable jar file
that includes all runtime dependencies and can be executed with a simple

```bash
java -jar my-app.jar
```
It supports JPMS, embedded system properties, Java agents, extra folders to be added to classpath. 

### Usage

Declare the plugin in your build's `settings.gradle` like this
```groovy

pluginManagement {
    repositories {
        maven {
            url = 'https://gitea.woggioni.net/api/packages/woggioni/maven'
        }
    }

    plugins {
        id "net.woggioni.gradle.envelope" version "2025.01.21"
    }
}
```

Then add it to a project's `build.gradle`

```groovy
plugins {
    id 'net.woggioni.gradle.envelope'
}

envelopeJar {
    mainClass = 'your.main.Class'
}
```

The plugin adds 2 tasks to your project:

- `envelopeJar` of type `net.woggioni.gradle.envelope.EnvelopeJarTask` that creates the executable jar in the project's libraries folder
- `envelopeRun` of type `org.gradle.api.tasks.JavaExec` which launches the jar created by the `envelopeJar` task

### Configuration

`EnvelopeJarTask` has several properties useful for configuration purposes:

###### mainClass 

This string property sets the class that will be searched for the `main` method to start the application

###### mainModule

When this string property is set, the jar file will be started in JPMS mode (if running on Java 9+) and 
this module will be searched for the main class, if the `mainClass` is not set the main class specified 
in the module descriptor will be loaded instead

###### systemProperties

This is a map that contains Java system properties that will be set before your application starts

###### extraClasspath

This is a list of strings representing filesystem paths that will be added to the classpath (if running in classpath mode) 
or to the module path (if running in JPMS mode) when the application starts. 

Relative paths and interpolation with Java System properties and environmental variables are supported:

e.g.

This looks for a `plugin` folder in the user's home directory
```
${env:HOME}/plugins
```

Same using Java system properties instead
```
${sys:user.home}/plugins
```

###### javaAgent
This is a method accepting 2 strings, the first is the Java agent class name and the second one is the java agent arguments.
It can be invoked multiple times to setup multiple java agents for the same JAR file. 
All the java agents will be invoked before the application startup.

Java agents configured in this way will always run together with the application and cannot be disabled.

### Example

```groovy
plugins {
    id 'net.woggioni.gradle.envelope'
}

envelopeJar {
    mainClass = 'your.main.Class'
    mainModule = 'your.main.module'

    systemProperties = [
        'some.property' : 'Some value'
    ]

    extraClasspath = ["plugins"]
    
    javaAgent('your.java.agent.Class', 'optional agent arguments')
}
```

### Limitations

- This plugin requires Gradle >= 6.0 and Java >=0 8 to build the executable jar file.
The assembled envelope jar requires and Java >= 8 to run, if only `mainClass` is specified,
if both `mainModule` and `mainClass` are specified the generated jar file will (try to) run in classpath mode on Java 8
and in JPMS mode on Java > 8.

- When running in JPMS mode (when the `mainModule` property is set), command line arguments like
`--add-opens`, `--add-exports`, `--add-reads` won't work as JPMS is initialized after application startup

- When running in JPMS mode custom stream handler need to added installed using `URL.setURLStreamHandlerFactory`,
  setting the `java.protocol.handler.pkgs` system property does not work as it tries to load
  the respective handler using the system classloader which, in an envelope application, can only load envelope own classes
