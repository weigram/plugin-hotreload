package com.hotreload.agent;

import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Re-parses one MyBatis mapper XML into live {@code Configuration} instances.
 *
 * <p>Everything MyBatis-specific is reflective: the agent jar is built without
 * a compile-time MyBatis dependency, and it also works when MyBatis lives in a
 * child class loader (the class is looked up through the configuration's own
 * loader).</p>
 *
 * <p>The statements, result maps, parameter maps, key generators, SQL
 * fragments and cache of the mapper's namespace are removed first, then the
 * file is parsed with MyBatis' own {@code XMLMapperBuilder}, so what runs
 * afterwards is exactly the XML from disk. If the new XML is invalid the old
 * entries are restored and the application keeps working.</p>
 *
 * <p>The maps are read reflectively from the runtime class: MyBatis-Plus
 * redeclares all of them in {@code MybatisConfiguration} and its getters use
 * the shadow copies, so reading only {@code Configuration}'s fields would
 * return empty maps for MyBatis-Plus projects.</p>
 */
public final class MyBatisXmlReloader {

    private static final Pattern NAMESPACE = Pattern.compile(
            "namespace\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final int MAX_XML_BYTES = 4 * 1024 * 1024;
    private static final String[] NAMESPACE_MAPS = {
            "mappedStatements", "resultMaps", "parameterMaps", "keyGenerators", "sqlFragments"
    };

    private MyBatisXmlReloader() {
    }

    /** Called reflectively by {@link XmlReloaders}. */
    public static void reload(Path xml, List<Object> configurations) {
        String namespace = namespace(xml);
        if (namespace == null) {
            Log.debug("not a mapper XML, skip " + xml);
            return;
        }
        for (Object configuration : configurations) {
            if (!isMyBatisConfiguration(configuration)) {
                continue;
            }
            if (!hasNamespace(configuration, namespace)) {
                continue;
            }
            synchronized (configuration) {
                reload(configuration, xml, namespace);
            }
        }
    }

    private static void reload(Object configuration, Path xml, String namespace) {
        String resource = xml.toAbsolutePath().toString();
        Map<String, Map<String, Object>> backup = new HashMap<String, Map<String, Object>>();
        Object cacheBackup = null;

        Map<String, Object> caches = map(configuration, "caches");
        if (caches != null && caches.containsKey(namespace)) {
            cacheBackup = caches.remove(namespace);
        }
        for (String field : NAMESPACE_MAPS) {
            Map<String, Object> values = map(configuration, field);
            if (values == null) {
                continue;
            }
            Map<String, Object> removed = new LinkedHashMap<String, Object>();
            String prefix = namespace + ".";
            Iterator<String> keys = values.keySet().iterator();
            while (keys.hasNext()) {
                String key = keys.next();
                if (key.startsWith(prefix)) {
                    removed.put(key, values.get(key));
                    keys.remove();
                }
            }
            backup.put(field, removed);
        }
        unloadResource(configuration, resource);

        try (InputStream input = Files.newInputStream(xml)) {
            parse(configuration, input, resource);
            int statements = count(configuration, namespace);
            if (statements == 0) {
                Log.warn("mapper XML parsed but no statement was found: " + namespace + " (" + xml + ")");
            } else {
                Log.info("reloaded mapper XML " + display(xml) + " -> " + namespace
                        + " (" + statements + " statements)");
            }
        } catch (Throwable failure) {
            restore(configuration, namespace, resource, backup, caches, cacheBackup);
            Log.error("mapper XML reload failed, previous statements kept: " + xml, cause(failure));
        }
    }

    private static void parse(Object configuration, InputStream input, String resource) throws Exception {
        ClassLoader loader = configuration.getClass().getClassLoader();
        Class<?> builderType = Class.forName(
                "org.apache.ibatis.builder.xml.XMLMapperBuilder", true, loader);
        Class<?> configurationType = Class.forName(
                "org.apache.ibatis.session.Configuration", false, loader);
        Constructor<?> constructor = builderType.getConstructor(
                InputStream.class, configurationType, String.class, Map.class);
        Object builder = constructor.newInstance(input, configuration, resource, fragments(configuration));
        builderType.getMethod("parse").invoke(builder);
    }

    private static void restore(Object configuration, String namespace, String resource,
                                Map<String, Map<String, Object>> backup,
                                Map<String, Object> caches, Object cacheBackup) {
        unloadResource(configuration, resource);
        // Drop whatever the failed parse managed to add.
        for (String field : NAMESPACE_MAPS) {
            Map<String, Object> values = map(configuration, field);
            if (values == null) {
                continue;
            }
            String prefix = namespace + ".";
            Iterator<String> keys = values.keySet().iterator();
            while (keys.hasNext()) {
                if (keys.next().startsWith(prefix)) {
                    keys.remove();
                }
            }
            Map<String, Object> removed = backup.get(field);
            if (removed != null) {
                values.putAll(removed);
            }
        }
        if (caches != null) {
            caches.remove(namespace);
            if (cacheBackup != null) {
                caches.put(namespace, cacheBackup);
            }
        }
    }

    private static boolean isMyBatisConfiguration(Object configuration) {
        try {
            ClassLoader loader = configuration.getClass().getClassLoader();
            Class<?> type = Class.forName("org.apache.ibatis.session.Configuration", false, loader);
            return type.isInstance(configuration);
        } catch (Throwable unavailable) {
            return false;
        }
    }

    private static boolean hasNamespace(Object configuration, String namespace) {
        Map<String, Object> statements = map(configuration, "mappedStatements");
        if (statements == null) {
            return false;
        }
        String prefix = namespace + ".";
        for (String key : statements.keySet()) {
            if (key.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static int count(Object configuration, String namespace) {
        Map<String, Object> statements = map(configuration, "mappedStatements");
        if (statements == null) {
            return 0;
        }
        int count = 0;
        String prefix = namespace + ".";
        for (String key : statements.keySet()) {
            if (key.startsWith(prefix)) {
                count++;
            }
        }
        return count;
    }

    private static void unloadResource(Object configuration, String resource) {
        Set<String> loaded = set(configuration, "loadedResources");
        if (loaded != null) {
            loaded.remove(resource);
            loaded.remove("file:" + resource);
        }
    }

    private static String namespace(Path xml) {
        try {
            long size = Files.size(xml);
            if (size <= 0 || size > MAX_XML_BYTES) {
                return null;
            }
            byte[] bytes = Files.readAllBytes(xml);
            String text = new String(bytes, StandardCharsets.ISO_8859_1);
            if (text.indexOf("<mapper") < 0) {
                return null;
            }
            Matcher matcher = NAMESPACE.matcher(text);
            if (!matcher.find()) {
                return null;
            }
            String namespace = matcher.group(1).trim();
            return namespace.isEmpty() ? null : namespace;
        } catch (Throwable failure) {
            Log.debug("cannot read XML " + xml + ": " + failure);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> fragments(Object configuration) {
        Map<String, Object> map = map(configuration, "sqlFragments");
        if (map != null) {
            return map;
        }
        try {
            Method getter = configuration.getClass().getMethod("getSqlFragments");
            Object value = getter.invoke(configuration);
            return value instanceof Map ? (Map<String, Object>) value : null;
        } catch (Throwable failure) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object target, String name) {
        try {
            Field field = field(target.getClass(), name);
            if (field == null) {
                return null;
            }
            field.setAccessible(true);
            Object value = field.get(target);
            return value instanceof Map ? (Map<String, Object>) value : null;
        } catch (Throwable failure) {
            Log.debug("cannot read " + name + " from " + target.getClass().getName() + ": " + failure);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<String> set(Object target, String name) {
        try {
            Field field = field(target.getClass(), name);
            if (field == null) {
                return null;
            }
            field.setAccessible(true);
            Object value = field.get(target);
            return value instanceof Set ? (Set<String>) value : null;
        } catch (Throwable failure) {
            return null;
        }
    }

    private static Field field(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException missing) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private static Throwable cause(Throwable failure) {
        if (failure instanceof InvocationTargetException && failure.getCause() != null) {
            return failure.getCause();
        }
        return failure;
    }

    private static String display(Path xml) {
        Path name = xml.getFileName();
        return name == null ? xml.toString() : name.toString();
    }
}
