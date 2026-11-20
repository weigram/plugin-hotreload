package com.hotreload.agent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * One red, English warning per process for a change the JVM refused.
 *
 * <p>The same text is also written to {@code .hotreload-unsupported} in the
 * application working directory. The IDE plugin watches that marker and can
 * show a popup; the marker contains only a timestamp and the class name /
 * failure reason, never a file path from the developer machine.</p>
 */
final class UnsupportedWarning {

    private static final String MARKER = ".hotreload-unsupported";
    private static volatile boolean shown;

    private UnsupportedWarning() {
    }

    static boolean isUnsupported(String message, Throwable failure, Object... arguments) {
        StringBuilder textBuilder = new StringBuilder(message == null ? "" : message);
        if (arguments != null) {
            for (Object argument : arguments) {
                textBuilder.append(' ').append(argument);
            }
        }
        String text = textBuilder + " " + chain(failure);
        String lower = text.toLowerCase();
        return lower.contains("class redefinition failed")
                || lower.contains("unsupportedoperationexception")
                || lower.contains("attempted to change")
                || lower.contains("attempted to add")
                || lower.contains("attempted to delete")
                || lower.contains("schema")
                || lower.contains("not supported");
    }

    static void warn(String detail) {
        if (shown || Boolean.getBoolean("hotreload.suppressUnsupportedWarning")) {
            return;
        }
        shown = true;
        String line = clean(detail);
        System.out.println("\u001B[31m[hotreload] ERROR  Unsupported class change was not applied: "
                + line + "\u001B[0m");
        System.out.println("\u001B[31m[hotreload] ERROR  Restart the application. For supported structural "
                + "changes use JetBrains Runtime (JBR) or DCEVM with "
                + "-XX:+AllowEnhancedClassRedefinition.\u001B[0m");
        System.out.flush();
        writeMarker(line);
    }

    private static String chain(Throwable failure) {
        StringBuilder text = new StringBuilder();
        Throwable current = failure;
        while (current != null) {
            if (current.getMessage() != null) {
                text.append(' ').append(current.getMessage());
            }
            current = current.getCause();
        }
        return text.toString();
    }

    private static String clean(String detail) {
        String value = detail == null ? "" : detail.replace('\r', ' ').replace('\n', ' ');
        return value.length() > 500 ? value.substring(0, 500) : value;
    }

    private static void writeMarker(String detail) {
        try {
            Path marker = Paths.get(System.getProperty("user.dir", "."))
                    .toAbsolutePath().resolve(MARKER);
            String text = System.currentTimeMillis() + "\t" + detail + "\n";
            Files.write(marker, text.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Throwable ignored) {
            // The console warning is the primary signal; the marker is optional.
        }
    }
}
