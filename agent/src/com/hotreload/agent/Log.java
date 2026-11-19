package com.hotreload.agent;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Console log of the hot reload agent.
 *
 * <p>The application's own logging framework is not initialized when the agent
 * starts, so everything goes to {@code System.out} in the same stream the IDE
 * shows in the Run console. The format is intentionally close to JRebel's
 * so it is obvious in a busy log.</p>
 */
final class Log {

    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss.SSS");

    private static volatile boolean verbose;

    private Log() {
    }

    static void setVerbose(boolean value) {
        verbose = value;
    }

    static void debug(String message) {
        if (verbose) {
            print("DEBUG", message);
        }
    }

    static void info(String message) {
        print("INFO", message);
    }

    static void warn(String message) {
        print("WARN", message);
    }

    static void error(String message) {
        print("ERROR", message);
    }

    static void error(String message, Throwable error) {
        String detail = error == null ? "" : ": " + error.getClass().getSimpleName()
                + (error.getMessage() == null ? "" : " " + error.getMessage());
        print("ERROR", message + detail);
        if (error != null && verbose) {
            error.printStackTrace(System.out);
        }
    }

    private static void print(String level, String message) {
        String time;
        synchronized (TIME) {
            time = TIME.format(new Date());
        }
        System.out.println(time + " [hotreload] " + level + "  " + message);
        System.out.flush();
    }
}
