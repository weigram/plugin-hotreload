package com.hotreload.agent;

import java.io.IOException;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Redefines one loaded class from its new {@code .class} file.
 *
 * <p>Standard JVM redefinition replaces method bodies (and constant pool
 * values) and refuses changes to the class schema. JetBrains Runtime accepts
 * schema changes when it is started with
 * {@code -XX:+AllowEnhancedClassRedefinition}; the agent cannot turn that on
 * by itself, so the failure is reported with the exact fix.</p>
 */
final class ClassRedefiner {

    private static final Map<String, Integer> LAST_BYTES = new HashMap<String, Integer>();
    private static boolean schemaHintShown;

    private ClassRedefiner() {
    }

    static void reload(Instrumentation instrumentation, Path file, String className) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            Log.error("cannot read " + file, e);
            return;
        }
        if (bytes.length < 8) {
            return;
        }

        int fingerprint = Arrays.hashCode(bytes);
        boolean found = false;
        for (Class<?> candidate : instrumentation.getAllLoadedClasses()) {
            if (!className.equals(candidate.getName())) {
                continue;
            }
            found = true;
            String key = key(className, candidate.getClassLoader());
            Integer previous = LAST_BYTES.get(key);
            if (previous != null && previous == fingerprint) {
                Log.debug("unchanged bytes, skip " + className);
                continue;
            }
            if (!instrumentation.isModifiableClass(candidate)) {
                Log.debug("not modifiable: " + className + " (" + candidate.getClassLoader() + ")");
                continue;
            }
            long started = System.currentTimeMillis();
            try {
                instrumentation.redefineClasses(new ClassDefinition(candidate, bytes));
                LAST_BYTES.put(key, fingerprint);
                Log.info("reloaded " + className + " (" + (System.currentTimeMillis() - started) + " ms)");
            } catch (Throwable failure) {
                LAST_BYTES.put(key, fingerprint);
                report(className, failure);
            }
        }

        if (!found) {
            Log.debug("not loaded yet, new bytecode will be used on first load: " + className);
        }
    }

    private static String key(String className, ClassLoader loader) {
        return className + "@" + System.identityHashCode(loader);
    }

    private static void report(String className, Throwable failure) {
        String message = failure.getMessage() == null ? "" : failure.getMessage();
        boolean schema = failure instanceof UnsupportedOperationException
                || message.contains("schema")
                || message.contains("add method")
                || message.contains("delete method")
                || message.contains("add field")
                || message.contains("delete field")
                || message.contains("change field")
                || message.contains("is not supported");
        if (schema) {
            if (!schemaHintShown) {
                schemaHintShown = true;
                Log.warn("结构变更没有生效：" + className + "（" + message + "）");
                Log.warn("标准 JVM 只允许替换方法体。要增删方法/字段、改父类或接口：");
                Log.warn("  1) 用 JetBrains Runtime（JBR）启动应用");
                Log.warn("  2) 在运行配置的 VM options 里加 -XX:+AllowEnhancedClassRedefinition");
                Log.warn("  3) 重启应用后保存 Java 文件");
                Log.warn("如果 JBR 已经带上该参数，则这次改动影响了正在执行的调用点，重启一次即可。");
            } else {
                Log.warn("结构变更没有生效：" + className + " → " + message);
            }
            return;
        }
        Log.error("redefine failed: " + className, failure);
    }
}
