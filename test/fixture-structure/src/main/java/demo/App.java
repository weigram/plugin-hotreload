package demo;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Prints observable state once a second. The running instance is created once,
 * so every later change exercises redefinition of an already-loaded class.
 */
public final class App {

    public static void main(String[] args) throws Exception {
        Target target = new Target();
        System.out.println("### STARTED pid=" + ManagementFactory.getRuntimeMXBean().getName());
        while (true) {
            System.out.println(state(target));
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
