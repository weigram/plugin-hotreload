package com.hotreload.agent;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;

/**
 * Keeps MyBatis out of the agent's startup path.
 *
 * <p>{@link MyBatisXmlReloader} links against MyBatis classes. Loading it in an
 * application without MyBatis would fail, so it is loaded reflectively only
 * after a {@code Configuration} has actually been registered.</p>
 */
final class XmlReloaders {

    private XmlReloaders() {
    }

    static void reload(Path xml) {
        List<Object> configurations = MyBatisConfigRegistry.live();
        if (configurations.isEmpty()) {
            Log.debug("no MyBatis configuration yet, skip " + xml);
            return;
        }
        try {
            Class<?> reloader = Class.forName("com.hotreload.agent.MyBatisXmlReloader", true,
                    XmlReloaders.class.getClassLoader());
            Method reload = reloader.getMethod("reload", Path.class, List.class);
            reload.invoke(null, xml, configurations);
        } catch (ClassNotFoundException missing) {
            Log.warn("MyBatis XML reload is unavailable: MyBatis is not visible from the application class loader");
        } catch (Throwable failure) {
            Log.error("MyBatis XML reload failed for " + xml, failure);
        }
    }
}
