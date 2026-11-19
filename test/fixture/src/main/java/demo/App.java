package demo;

import java.lang.management.ManagementFactory;

/**
 * Plain-MyBatis fixture for the agent's end-to-end test.
 *
 * <p>Prints one line every 500 ms. The XML select is changed by
 * {@code test/e2e.sh} to prove that a mapper XML reload reaches the live
 * {@code Configuration}; the banner method body is changed to prove class
 * redefinition.</p>
 */
public final class App {

    public static void main(String[] args) throws Exception {
        MyBatisFixture fixture = new MyBatisFixture();
        System.out.println("### STARTED pid=" + ManagementFactory.getRuntimeMXBean().getName());
        while (true) {
            try {
                System.out.println(banner() + " " + fixture.value());
            } catch (Throwable failure) {
                System.out.println("### ERROR " + failure);
            }
            Thread.sleep(500L);
        }
    }

    static String banner() {
        return "VALUE";
    }
}
