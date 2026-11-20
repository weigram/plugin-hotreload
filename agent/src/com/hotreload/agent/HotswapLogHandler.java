package com.hotreload.agent;

import org.hotswap.agent.logging.AgentLogger;
import org.hotswap.agent.logging.AgentLoggerHandler;

/** Turns HA's structural-redefinition failures into one red English warning. */
public final class HotswapLogHandler extends AgentLoggerHandler {

    @Override
    @SuppressWarnings("rawtypes")
    public void print(Class source, AgentLogger.Level level, String message,
                      Throwable throwable, Object... arguments) {
        super.print(source, level, message, throwable, arguments);
        if (level == AgentLogger.Level.ERROR
                && UnsupportedWarning.isUnsupported(message, throwable, arguments)) {
            UnsupportedWarning.warn(details(message, arguments));
        }
    }

    private static String details(String message, Object[] arguments) {
        StringBuilder text = new StringBuilder(message == null ? "" : message);
        if (arguments != null) {
            for (Object argument : arguments) {
                int placeholder = text.indexOf("{}");
                if (placeholder >= 0) {
                    text.replace(placeholder, placeholder + 2, String.valueOf(argument));
                } else {
                    text.append(' ').append(argument);
                }
            }
        }
        return text.toString();
    }
}
