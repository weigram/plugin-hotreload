package com.hotreload.agent;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Options of the agent.
 *
 * <p>All options have a working default, so the IDE can inject the agent with
 * no arguments at all. They can be given either in the {@code -javaagent}
 * argument list ({@code key=value,key=value}) or as system properties
 * ({@code -Dhotreload.…}), which is handy while developing the agent.</p>
 */
final class AgentArgs {

    /** Additional directories to watch, besides the ones found on the classpath. */
    final Set<Path> dirs = new LinkedHashSet<Path>();
    /** Reload changed {@code .class} files. */
    boolean classes = true;
    /** Reload changed MyBatis mapper XML files. */
    boolean xml = true;
    /** Poll interval in milliseconds. */
    long pollMs = 500L;
    /** Print one line per scanned file (very noisy; for debugging the agent). */
    boolean verbose = false;

    private AgentArgs() {
    }

    static AgentArgs parse(String argument) {
        AgentArgs args = new AgentArgs();

        if (argument != null && !argument.trim().isEmpty()) {
            for (String part : argument.split(",")) {
                int equals = part.indexOf('=');
                if (equals <= 0) {
                    continue;
                }
                String key = part.substring(0, equals).trim();
                String value = part.substring(equals + 1).trim();
                args.apply(key, value);
            }
        }

        applySystemProperty(args, "hotreload.classes");
        applySystemProperty(args, "hotreload.xml");
        applySystemProperty(args, "hotreload.poll");
        applySystemProperty(args, "hotreload.verbose");
        applySystemProperty(args, "hotreload.dirs");
        return args;
    }

    private static void applySystemProperty(AgentArgs args, String name) {
        String value = System.getProperty(name);
        if (value != null && !value.trim().isEmpty()) {
            args.apply(name.substring("hotreload.".length()), value.trim());
        }
    }

    private void apply(String key, String value) {
        if ("classes".equalsIgnoreCase(key)) {
            classes = truthy(value);
        } else if ("xml".equalsIgnoreCase(key)) {
            xml = truthy(value);
        } else if ("poll".equalsIgnoreCase(key) || "pollMs".equalsIgnoreCase(key)) {
            try {
                pollMs = Math.max(150L, Math.min(5000L, Long.parseLong(value)));
            } catch (NumberFormatException ignored) {
                // keep the default
            }
        } else if ("verbose".equalsIgnoreCase(key)) {
            verbose = truthy(value);
        } else if ("dirs".equalsIgnoreCase(key) || "dir".equalsIgnoreCase(key)) {
            for (String dir : value.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                if (!dir.trim().isEmpty()) {
                    dirs.add(Paths.get(dir.trim()));
                }
            }
        }
    }

    private static boolean truthy(String value) {
        return "true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value);
    }
}
