package net.woggioni.executable.jar;

import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.Enumeration;
import java.util.Map;
import java.util.Properties;
import lombok.SneakyThrows;

class JavaAgentLauncher {

    @SneakyThrows
    static void premain(String agentArguments, Instrumentation instrumentation) {
        ClassLoader cl = JavaAgentLauncher.class.getClassLoader();
        Enumeration<URL> it = cl.getResources(Constants.JAVA_AGENTS_FILE);
        while (it.hasMoreElements()) {
            URL url = it.nextElement();
            Properties properties = new Properties();
            try (InputStream is = url.openStream()) {
                properties.load(is);
            }
            for (Map.Entry<Object, Object> entry : properties.entrySet()) {
                String agentClassName = (String) entry.getKey();
                String agentArgs = (String) entry.getValue();
                Class<?> agentClass = cl.loadClass(agentClassName);
                Method premainMethod = agentClass.getMethod("premain", String.class, Instrumentation.class);
                premainMethod.invoke(null, agentArgs, instrumentation);
            }
        }
    }

    static void agentmain(String agentArguments, Instrumentation instrumentation) {
        premain(agentArguments, instrumentation);
    }
}