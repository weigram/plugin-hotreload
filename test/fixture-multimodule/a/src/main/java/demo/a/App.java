package demo.a;

import demo.b.Provider;

import java.lang.management.ManagementFactory;

/**
 * Module A depends on B. The test recompiles B only and expects A's next call
 * to see B's new method body without restarting or rebuilding A.
 */
public final class App {

    public static void main(String[] args) throws Exception {
        System.out.println("### STARTED pid=" + ManagementFactory.getRuntimeMXBean().getName());
        while (true) {
            System.out.println("MULTI A=" + banner() + " B=" + new Provider().value());
            System.out.flush();
            Thread.sleep(300L);
        }
    }

    static String banner() {
        return "A1";
    }
}
