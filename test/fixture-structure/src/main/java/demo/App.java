package demo;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * Prints observable state once a second. The running instance is created once,
 * so every later change exercises redefinition of an already-loaded class.
 */
public final class App {

    public static void main(String[] args) throws Exception {
        Target target = new Target();
        Path stateFile = Paths.get(System.getProperty("stateFile", "target/ide-state.txt"));
        System.out.println("### STARTED pid=" + ManagementFactory.getRuntimeMXBean().getName());
        while (true) {
            String state = state(target);
            System.out.println(state);
            try {
                Files.write(stateFile, (state + "\n").getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } catch (Exception ignored) {
                // Console output is the fallback when the state file cannot be written.
            }
            Thread.sleep(500L);
        }
    }

    private static String state(Target target) {
        String value;
        try {
            value = String.valueOf(target.getClass().getMethod("value").invoke(target));
        } catch (Throwable failure) {
            value = "ERR:" + failure.getClass().getSimpleName();
        }
        return "STATE value=" + value
                + " extra=" + hasMethod(target, "extra")
                + " field=" + hasField(target, "extraField")
                + " marker=" + hasMethod(target, "marker")
                + " marker2=" + hasMethod(target, "marker2")
                + " deprecated=" + target.getClass().isAnnotationPresent(Deprecated.class);
    }

    private static String hasMethod(Object target, String name) {
        try {
            Method method = target.getClass().getMethod(name);
            method.invoke(target);
            return "YES";
        } catch (Throwable failure) {
            return "NO";
        }
    }

    private static String hasField(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.get(target);
            return "YES";
        } catch (Throwable failure) {
            return "NO";
        }
    }
}
