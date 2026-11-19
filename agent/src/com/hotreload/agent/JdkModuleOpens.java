package com.hotreload.agent;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Opens the JDK packages HotSwapAgent reflects into, without requiring the user
 * to add {@code --add-opens} to the run configuration.
 *
 * <p>OpenIDEA's {@code javaAgents} only adds {@code -javaagent}; it cannot add
 * VM options. HotSwapAgent's {@code ClassLoaderHelper} calls
 * {@code ClassLoader.findLoadedClass} reflectively, so on Java 9+ it fails with
 * {@code InaccessibleObjectException} unless {@code java.base/java.lang} is
 * open. An agent may call {@link Instrumentation#redefineModule} at runtime,
 * which is exactly the same grant {@code --add-opens java.base/java.lang=ALL-UNNAMED}
 * gives.</p>
 *
 * <p>On Java 8 there are no modules and this class does nothing.</p>
 */
final class JdkModuleOpens {

    private static final String[] BASE_PACKAGES = {
            "java.lang",
            "java.lang.reflect",
            "java.lang.invoke",
            "java.io",
            "java.nio",
            "java.util",
            "java.net",
            "sun.nio.ch",
            "sun.security.action",
            "jdk.internal.reflect"
    };

    /** HA's JdkPlugin flushes java.beans.Introspector; java.beans lives in java.desktop. */
    private static final String[] DESKTOP_PACKAGES = {"java.beans"};

    private JdkModuleOpens() {
    }

    static boolean open(Instrumentation instrumentation) {
        try {
            Class<?> moduleClass = Class.forName("java.lang.Module");
            Method getModule = Class.class.getMethod("getModule");
            Object base = getModule.invoke(Object.class);

            Set<Object> targets = new LinkedHashSet<Object>();
            addUnnamed(targets, Agent.class.getClassLoader());
            addUnnamed(targets, Thread.currentThread().getContextClassLoader());
            if (targets.isEmpty()) {
                return false;
            }

            Method redefineModule = Instrumentation.class.getMethod(
                    "redefineModule",
                    moduleClass,
                    Set.class,
                    Map.class,
                    Map.class,
                    Set.class,
                    Map.class);
            open(instrumentation, redefineModule, base, targets, BASE_PACKAGES);
            Object desktop = moduleOf("java.beans.Introspector", getModule);
            if (desktop != null && desktop != base) {
                open(instrumentation, redefineModule, desktop, targets, DESKTOP_PACKAGES);
            }
            Log.debug("opened JDK packages for HotSwapAgent: " + join(BASE_PACKAGES)
                    + ", " + join(DESKTOP_PACKAGES));
            return true;
        } catch (ClassNotFoundException java8) {
            // Java 8: no module system, nothing to open.
            return true;
        } catch (NoSuchMethodException java8) {
            // Java 8 Class.getModule / ClassLoader.getUnnamedModule are absent.
            return true;
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            Log.warn("could not open JDK packages at runtime: " + cause);
            if (cause != failure) {
                cause.printStackTrace(System.out);
            }
            return false;
        } catch (Throwable failure) {
            Log.warn("could not open JDK packages at runtime: " + failure);
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static void open(Instrumentation instrumentation, Method redefineModule,
                             Object module, Set<Object> targets, String[] packages) throws Exception {
        // A package that does not exist in the module (sun.security.action was
        // removed in a later JDK) makes redefineModule throw IllegalArgumentException.
        Set<String> present = (Set<String>) Class.forName("java.lang.Module")
                .getMethod("getPackages")
                .invoke(module);
        Map<String, Set<Object>> opens = new HashMap<String, Set<Object>>();
        for (String pkg : packages) {
            if (present.contains(pkg)) {
                opens.put(pkg, targets);
            }
        }
        if (opens.isEmpty()) {
            return;
        }
        redefineModule.invoke(instrumentation, module,
                Collections.emptySet(),
                Collections.emptyMap(),
                opens,
                Collections.emptySet(),
                Collections.emptyMap());
    }

    private static Object moduleOf(String className, Method getModule) {
        try {
            Class<?> type = Class.forName(className, false, null);
            return getModule.invoke(type);
        } catch (Throwable unavailable) {
            return null;
        }
    }

    private static void addUnnamed(Set<Object> targets, ClassLoader loader) {
        if (loader == null) {
            return;
        }
        try {
            Method unnamed = ClassLoader.class.getMethod("getUnnamedModule");
            Object module = unnamed.invoke(loader);
            if (module != null) {
                targets.add(module);
            }
        } catch (Throwable ignored) {
            // Java 8 or a loader without a module; no target then.
        }
    }

    private static String join(String[] values) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                text.append(", ");
            }
            text.append(values[i]);
        }
        return text.toString();
    }
}
