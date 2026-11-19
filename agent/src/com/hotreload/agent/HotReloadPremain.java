package com.hotreload.agent;

import java.lang.instrument.Instrumentation;

/**
 * JVM entry point. The IDE adds this jar with
 * {@code -javaagent:<path>} (see {@code [[contributes.javaAgents]]} in the
 * plugin manifest), so no VM option or project change is needed.
 */
public final class HotReloadPremain {

    private HotReloadPremain() {
    }

    public static void premain(String argument, Instrumentation instrumentation) {
        Agent.start(argument, instrumentation);
    }

    /** Attach support, for running {@code jcmd <pid> JVMTI.agent_load}. */
    public static void agentmain(String argument, Instrumentation instrumentation) {
        Agent.start(argument, instrumentation);
    }
}
