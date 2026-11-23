package com.hotreload.agent;

import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
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
    private static final Pattern STATEMENT = Pattern.compile(
            "<\\s*(?:select|insert|update|delete)\\b[^>]*?\\bid\\s*=\\s*[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final int MAX_XML_BYTES = 4 * 1024 * 1024;
    private static final String[] NAMESPACE_MAPS = {
            "mappedStatements", "resultMaps", "parameterMaps", "keyGenerators", "sqlFragments"
    };
    private static final String[] INCOMPLETE_COLLECTIONS = {
            "incompleteStatements", "incompleteResultMaps", "incompleteCacheRefs", "incompleteMethods"
    };
    /** Configurations are weak keys; the values only keep resource paths. */
    private static final Map<Object, Set<String>> OWNED_RESOURCES =
            Collections.synchronizedMap(new WeakHashMap<Object, Set<String>>());

    private MyBatisXmlReloader() {
    }

    /** Called reflectively by {@link XmlReloaders}. */
    public static void reload(Path xml, List<Object> configurations) {
        String namespace = namespace(xml);
        if (namespace == null) {
            Log.debug("not a mapper XML, skip " + xml);
            return;
        }
        String resource = xml.toAbsolutePath().toString();
        List<Object> candidates = new ArrayList<Object>();
        List<Object> owners = new ArrayList<Object>();
        for (Object configuration : configurations) {
            if (!isMyBatisConfiguration(configuration)) {
                continue;
            }
            boolean owner = ownsResource(configuration, resource);
            if (!owner && !hasNamespace(configuration, namespace)) {
                continue;
            }
            candidates.add(configuration);
            if (owner) {
                owners.add(configuration);
            }
        }

        List<Object> targets;
        if (!owners.isEmpty()) {
            targets = owners;
        } else if (candidates.size() == 1) {
            // Some applications load mapper XML through a custom resource
            // loader that does not populate loadedResources; with one
            // candidate there is no ambiguity.
            targets = candidates;
        } else {
            targets = Collections.emptyList();
        }
        if (targets.isEmpty()) {
            Log.warn("cannot determine which Configuration owns " + xml
                    + " (namespace " + namespace + ", " + candidates.size() + " candidates); skipping");
            return;
        }
        for (Object configuration : targets) {
            trackResource(configuration, resource);
            synchronized (configuration) {
                reload(configuration, xml, namespace);
            }
        }
    }

    private static void reload(Object configuration, Path xml, String namespace) {
        String resource = xml.toAbsolutePath().toString();
        Set<String> xmlStatements = statementIds(xml);
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
            Map<String, Object> snapshot = new LinkedHashMap<String, Object>();
            String prefix = namespace + ".";
            Iterator<String> keys = values.keySet().iterator();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!key.startsWith(prefix)) {
                    continue;
                }
                Object value = values.get(key);
                snapshot.put(key, value);
                // MyBatis-Plus injects BaseMapper methods (selectList,
                // selectById, ...) into the same namespace from a different
                // resource. Removing every namespace entry would delete those
                // generated statements when the XML only contains resultMaps.
                if ("mappedStatements".equals(field)
                        && !xmlStatements.contains(key.substring(prefix.length()))
                        && !statementFromResource(value, resource)) {
                    continue;
                }
                keys.remove();
            }
            backup.put(field, snapshot);
        }
        unloadResource(configuration, resource);

        try (InputStream input = Files.newInputStream(xml)) {
            parse(configuration, input, resource);
            validateDeferred(configuration, namespace);
            int statements = countStatements(configuration, namespace, xmlStatements);
            if (statements == 0) {
                Log.debug("mapper XML has no SQL statements (result maps only): "
                        + display(xml) + " -> " + namespace);
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
        dropPending(configuration, namespace);
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

    private static void validateDeferred(Object configuration, String namespace) throws Exception {
        for (String methodName : new String[]{
                "parsePendingResultMaps", "parsePendingCacheRefs",
                "parsePendingStatements", "parsePendingMethods"}) {
            try {
                Method method = configuration.getClass().getMethod(methodName, boolean.class);
                method.invoke(configuration, Boolean.FALSE);
            } catch (NoSuchMethodException olderMyBatis) {
                // MyBatis before 3.5.1 has no explicit pending API; the
                // namespace check below still catches unresolved entries.
            }
        }
        if (pendingForNamespace(configuration, namespace)) {
            throw new IllegalStateException(
                    "mapper XML has unresolved deferred elements for " + namespace);
        }
    }

    private static boolean pendingForNamespace(Object configuration, String namespace) {
        for (String fieldName : INCOMPLETE_COLLECTIONS) {
            Collection<Object> pending = collection(configuration, fieldName);
            if (pending == null) {
                continue;
            }
            for (Object item : pending) {
                if (namespace.equals(pendingNamespace(item))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void dropPending(Object configuration, String namespace) {
        for (String fieldName : INCOMPLETE_COLLECTIONS) {
            Collection<Object> pending = collection(configuration, fieldName);
            if (pending == null) {
                continue;
            }
            Iterator<Object> iterator = pending.iterator();
            while (iterator.hasNext()) {
                if (namespace.equals(pendingNamespace(iterator.next()))) {
                    iterator.remove();
                }
            }
        }
    }

    private static String pendingNamespace(Object pending) {
        for (String fieldName : new String[]{"builderAssistant", "assistant"}) {
            Object assistant = fieldValue(pending, fieldName);
            if (assistant != null) {
                String namespace = invokeString(assistant, "getCurrentNamespace");
                if (namespace != null && !namespace.isEmpty()) {
                    return namespace;
                }
            }
        }
        Object context = fieldValue(pending, "context");
        if (context != null) {
            String id = invokeString(context, "getStringAttribute", "id");
            String namespace = namespaceOfId(id);
            if (namespace != null) {
                return namespace;
            }
        }
        String id = fieldValue(pending, "id") instanceof String
                ? (String) fieldValue(pending, "id") : null;
        return namespaceOfId(id);
    }

    private static String namespaceOfId(String id) {
        if (id == null) {
            return null;
        }
        int dot = id.lastIndexOf('.');
        return dot > 0 ? id.substring(0, dot) : null;
    }

    private static String invokeString(Object target, String methodName, Object... args) {
        try {
            Method method;
            if (args.length == 0) {
                method = target.getClass().getMethod(methodName);
            } else {
                method = target.getClass().getMethod(methodName, String.class);
            }
            Object value = method.invoke(target, args);
            return value instanceof String ? (String) value : null;
        } catch (Throwable unavailable) {
            return null;
        }
    }

    private static Object fieldValue(Object target, String name) {
        try {
            Field field = field(target.getClass(), name);
            if (field == null) {
                return null;
            }
            field.setAccessible(true);
            return field.get(target);
        } catch (Throwable unavailable) {
            return null;
        }
    }

    private static boolean ownsResource(Object configuration, String resource) {
        String normalized = normalizeResource(resource);
        synchronized (OWNED_RESOURCES) {
            Set<String> owned = OWNED_RESOURCES.get(configuration);
            if (owned != null && owned.contains(normalized)) {
                return true;
            }
        }
        Set<String> loaded = set(configuration, "loadedResources");
        if (loaded != null) {
            for (String item : loaded) {
                if (sameResource(item, resource)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void trackResource(Object configuration, String resource) {
        String normalized = normalizeResource(resource);
        synchronized (OWNED_RESOURCES) {
            Set<String> owned = OWNED_RESOURCES.get(configuration);
            if (owned == null) {
                owned = new HashSet<String>();
                OWNED_RESOURCES.put(configuration, owned);
            }
            owned.add(normalized);
        }
    }

    private static boolean sameResource(String loaded, String target) {
        String left = normalizeResource(loaded);
        String right = normalizeResource(target);
        if (left.isEmpty() || right.isEmpty() || left.startsWith("namespace:")) {
            return false;
        }
        if (left.equals(right)) {
            return true;
        }
        // Spring/MyBatis often stores the classpath-relative resource name
        // ("mapper/UserMapper.xml") while the watcher sees an absolute path.
        if (left.endsWith("/" + right) || right.endsWith("/" + left)) {
            return true;
        }
        return left.indexOf('/') < 0 && left.equals(fileName(right));
    }

    private static String normalizeResource(String resource) {
        String value = unwrapDescription(resource).replace('\\', '/');
        if (value.regionMatches(true, 0, "file:", 0, "file:".length())) {
            value = value.substring("file:".length());
            if (value.startsWith("//")) {
                value = value.substring(2);
            }
            // A file URL escapes spaces and non-ASCII characters. The watcher
            // path is a decoded filesystem path, so compare them in that form.
            value = decodePercent(value);
        }
        return value;
    }

    private static String decodePercent(String value) {
        if (value.indexOf('%') < 0) {
            return value;
        }
        try {
            // URLDecoder treats '+' as a space; URI paths can legitimately
            // contain a literal '+', so protect it first.
            return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name());
        } catch (Throwable ignored) {
            return value;
        }
    }

    /**
     * MyBatis-Spring passes {@code mapperLocation.toString()} as the resource.
     * Spring resources stringify as {@code file [/path]}, {@code URL
     * [file:/path]}, {@code class path resource [mapper/x.xml]}, and similar
     * bracketed forms; compare the path inside the brackets, not the label.
     */
    private static String unwrapDescription(String resource) {
        String value = resource == null ? "" : resource.trim();
        int open = value.indexOf('[');
        if (open <= 0 || !value.endsWith("]")) {
            return value;
        }
        String label = value.substring(0, open).trim();
        if ("file".equalsIgnoreCase(label)
                || "url".equalsIgnoreCase(label)
                || label.toLowerCase().endsWith(" resource")) {
            return value.substring(open + 1, value.length() - 1).trim();
        }
        return value;
    }

    private static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
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

    private static int countStatements(Object configuration, String namespace, Set<String> xmlStatements) {
        Map<String, Object> statements = map(configuration, "mappedStatements");
        if (statements == null) {
            return 0;
        }
        int count = 0;
        String prefix = namespace + ".";
        for (String key : statements.keySet()) {
            if (key.startsWith(prefix) && xmlStatements.contains(key.substring(prefix.length()))) {
                count++;
            }
        }
        return count;
    }

    private static boolean statementFromResource(Object statement, String resource) {
        String statementResource = invokeString(statement, "getResource");
        return statementResource != null && sameResource(statementResource, resource);
    }

    private static Set<String> statementIds(Path xml) {
        Set<String> ids = new HashSet<String>();
        try {
            long size = Files.size(xml);
            if (size <= 0 || size > MAX_XML_BYTES) {
                return ids;
            }
            String text = new String(Files.readAllBytes(xml), StandardCharsets.ISO_8859_1);
            Matcher matcher = STATEMENT.matcher(text);
            while (matcher.find()) {
                String id = matcher.group(1).trim();
                if (!id.isEmpty()) {
                    ids.add(id);
                }
            }
        } catch (Throwable failure) {
            Log.debug("cannot read XML statements " + xml + ": " + failure);
        }
        return ids;
    }

    private static void unloadResource(Object configuration, String resource) {
        Set<String> loaded = set(configuration, "loadedResources");
        if (loaded != null) {
            Iterator<String> iterator = loaded.iterator();
            while (iterator.hasNext()) {
                if (sameResource(iterator.next(), resource)) {
                    iterator.remove();
                }
            }
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
    private static Collection<Object> collection(Object target, String name) {
        try {
            Field field = field(target.getClass(), name);
            if (field == null) {
                return null;
            }
            field.setAccessible(true);
            Object value = field.get(target);
            return value instanceof Collection ? (Collection<Object>) value : null;
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
