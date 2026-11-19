package com.hotreload.agent;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * The live MyBatis {@code org.apache.ibatis.session.Configuration} instances.
 *
 * <p>The class is referenced from instrumented bytecode, so it must stay
 * {@code public} and its {@code register} method {@code public static}. A
 * {@link WeakReference} is enough: the configuration is owned by the
 * application's {@code SqlSessionFactory}, and a collected one cannot be
 * reloaded any more.</p>
 *
 * <p>Discovered this way instead of through Spring so the same code works for
 * plain MyBatis, MyBatis-Plus, MyBatis-Spring and Spring Boot, without
 * touching their proxies.</p>
 */
public final class MyBatisConfigRegistry {

    private static final List<WeakReference<Object>> CONFIGS = new ArrayList<WeakReference<Object>>();

    private MyBatisConfigRegistry() {
    }

    /** Called from the instrumented {@code addMappedStatement}. */
    public static void register(Object configuration) {
        if (configuration == null) {
            return;
        }
        synchronized (CONFIGS) {
            for (int i = CONFIGS.size() - 1; i >= 0; i--) {
                Object live = CONFIGS.get(i).get();
                if (live == null) {
                    CONFIGS.remove(i);
                } else if (live == configuration) {
                    return;
                }
            }
            CONFIGS.add(new WeakReference<Object>(configuration));
        }
    }

    static int size() {
        synchronized (CONFIGS) {
            return CONFIGS.size();
        }
    }

    static List<Object> live() {
        List<Object> live = new ArrayList<Object>();
        synchronized (CONFIGS) {
            for (int i = CONFIGS.size() - 1; i >= 0; i--) {
                Object configuration = CONFIGS.get(i).get();
                if (configuration == null) {
                    CONFIGS.remove(i);
                } else {
                    live.add(configuration);
                }
            }
        }
        return live;
    }
}
