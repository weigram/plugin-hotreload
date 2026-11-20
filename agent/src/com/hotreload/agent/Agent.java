package com.hotreload.agent;

import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent bootstrap.
 *
 * <p>Two engines share one jar:</p>
 *
 * <ul>
 *   <li><b>HotSwapAgent 2.0.5</b> reloads changed classes and lets its Spring
 *       plugin reprocess beans, controllers and AOP metadata. It is started by
 *       calling {@code HotswapAgent.premain} directly, so no
 *       {@code -XX:HotswapAgent=external} is needed (that option is what broke
 *       JDK 21 in the old plugin).</li>
 *   <li><b>This agent's XML reloader</b> owns MyBatis mapper XML. HA's MyBatis
 *       and MyBatis-Plus plugins are disabled so the same file is not parsed
 *       twice and MyBatis-Plus / Flowable proxies are left alone.</li>
 * </ul>
 *
 * <p>If HotSwapAgent cannot be started, the fallback is this agent's own
 * {@link ClassRedefiner}, which covers method bodies and JBR-enhanced schema
 * changes but has no Spring plugin.</p>
 */
final class Agent {

    private static final AtomicBoolean STARTED = new AtomicBoolean();

    /**
     * HA premain arguments. One {@code disablePlugin} per plugin: HA splits on
     * commas, so a single argument with a comma-separated value would only
     * disable the first name.
     */
    private static final String HOTSWAP_AGENT_ARGS =
            "autoHotswap=true"
            + ",disablePlugin=MyBatis"
            + ",disablePlugin=MyBatisPlus"
            + ",disablePlugin=WatchResources"
            + ",LOGGER=info";

    private Agent() {
    }

    static void start(String argument, Instrumentation instrumentation) {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }

        AgentArgs args = AgentArgs.parse(argument);
        Log.setVerbose(args.verbose);

        String vm = System.getProperty("java.vm.name", "JVM") + " "
                + System.getProperty("java.vm.version", "");
        Log.info("Hot Reload agent " + version() + " (pid " + pid() + ", " + vm.trim() + ")");
        if (isJetBrainsRuntime()) {
            Log.info("JetBrains Runtime 检测到：方法体热更开箱可用；增删方法/字段还需在运行配置的 VM options 加 "
                    + "-XX:+AllowEnhancedClassRedefinition（已经加了可忽略）。");
        }

        // HotSwapAgent reflects into java.base; open the packages at runtime so
        // the IDE's -javaagent injection (which cannot add VM options) is enough.
        boolean modulesOpen = JdkModuleOpens.open(instrumentation);

        // Register our transformer before HA starts, so MyBatis Configuration
        // classes (loaded later by the application) are seen by it.
        try {
            instrumentation.addTransformer(new MyBatisConfigTransformer(), false);
            // Stop HA's Spring plugin from parsing MyBatis mapper XML as a
            // Spring bean-definition file; our XML reloader handles it.
            instrumentation.addTransformer(new HotswapSpringPatch(), false);
        } catch (Throwable failure) {
            Log.error("cannot register the agent transformers", failure);
        }

        boolean hotswapAgent = modulesOpen && startHotswapAgent(instrumentation);
        if (hotswapAgent) {
            // HA owns class reloading (and therefore Spring plugin events);
            // this watcher now only refreshes mapper XML.
            args.classes = false;
            Log.info("class/Spring engine: HotSwapAgent 2.0.5 (autoHotswap, Spring plugin on, MyBatis plugins off)");
        } else {
            args.classes = true;
            Log.warn("HotSwapAgent 未加载：退回自研类重定义，方法体和 XML 可用，但没有 Spring bean/controller reprocess");
        }
        Log.info("Mapper XML reload: " + (args.xml ? "on (custom namespace reloader)" : "off"));

        if (args.classes || args.xml) {
            Thread watcher = new Thread(new Watcher(instrumentation, args), "hotreload-watcher");
            watcher.setDaemon(true);
            watcher.setPriority(Thread.MIN_PRIORITY);
            watcher.start();
        }
    }

    private static boolean startHotswapAgent(Instrumentation instrumentation) {
        try {
            ClassLoader loader = Agent.class.getClassLoader();
            Class<?> agent = Class.forName("org.hotswap.agent.HotswapAgent", true, loader);
            Method premain = agent.getMethod("premain", String.class, Instrumentation.class);
            installHotswapLogger();
            premain.invoke(null, HOTSWAP_AGENT_ARGS, instrumentation);
            installHotswapLogger();
            return true;
        } catch (InvocationTargetException failure) {
            Log.warn("HotSwapAgent premain failed: " + failure.getCause());
            return false;
        } catch (Throwable failure) {
            Log.warn("HotSwapAgent is not available: " + failure);
            return false;
        }
    }

    private static void installHotswapLogger() {
        try {
            ClassLoader loader = Agent.class.getClassLoader();
            Class<?> logger = Class.forName("org.hotswap.agent.logging.AgentLogger", true, loader);
            Class<?> handler = Class.forName("org.hotswap.agent.logging.AgentLoggerHandler", true, loader);
            Object custom = Class.forName("com.hotreload.agent.HotswapLogHandler", true, loader)
                    .getDeclaredConstructor().newInstance();
            logger.getMethod("setHandler", handler).invoke(null, custom);
            Log.debug("HotSwapAgent log handler installed: " + custom.getClass().getName());
        } catch (Throwable unavailable) {
            Log.warn("HotSwapAgent logger handler is unavailable: " + unavailable);
        }
    }

    private static String version() {
        try {
            Package info = Agent.class.getPackage();
            if (info != null && info.getImplementationVersion() != null) {
                return info.getImplementationVersion();
            }
        } catch (Throwable ignored) {
            // fall through
        }
        return "1.2.0";
    }

    private static boolean isJetBrainsRuntime() {
        String vendor = System.getProperty("java.vm.vendor", "") + " "
                + System.getProperty("java.vm.name", "");
        return vendor.toLowerCase().contains("jetbrains");
    }

    private static String pid() {
        try {
            String name = ManagementFactory.getRuntimeMXBean().getName();
            int at = name.indexOf('@');
            return at > 0 ? name.substring(0, at) : name;
        } catch (Throwable unavailable) {
            return "unknown";
        }
    }
}
