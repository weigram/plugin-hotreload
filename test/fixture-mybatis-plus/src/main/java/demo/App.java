package demo;

import java.lang.management.ManagementFactory;

/**
 * Prints the injected BaseMapper result and the XML statement result.
 *
 * <p>{@code mp=...} comes from MyBatis-Plus' injected {@code selectList};
 * {@code xml=...} comes from the mapper XML. After a resultMap-only reload the
 * injected statement must survive and the XML statement must disappear.</p>
 */
public final class App {

    public static void main(String[] args) throws Exception {
        MyBatisPlusFixture fixture = new MyBatisPlusFixture();
        System.out.println("### STARTED pid=" + ManagementFactory.getRuntimeMXBean().getName());
        while (true) {
            System.out.println("STATE " + fixture.state());
            System.out.flush();
            Thread.sleep(300L);
        }
    }
}
