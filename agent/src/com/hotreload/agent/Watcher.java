package com.hotreload.agent;

import java.io.File;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Polls the compiled output directories and reloads what changed.
 *
 * <p>Polling instead of a {@code WatchService}: an IDE or Maven build writes a
 * file and immediately renames it over the old one, and a recursive
 * {@code WatchService} silently stops delivering events when a watched
 * directory is deleted and recreated by {@code clean}. A scan of the few
 * output directories is cheap (only {@code .class} and {@code .xml} entries
 * are stat'ed) and does not lose events.</p>
 *
 * <p>A change is only processed after the file was unchanged for one poll
 * interval, so a class file that is still being written is not read half
 * way.</p>
 */
final class Watcher implements Runnable {

    private final Instrumentation instrumentation;
    private final AgentArgs args;
    private final Map<Path, FileState> known = new HashMap<Path, FileState>();
    private final Map<Path, FileState> pending = new HashMap<Path, FileState>();
    private List<Path> directories = Collections.emptyList();
    private boolean initializing = true;
    private boolean emptyWarningShown;
    private long lastDiscovery;

    Watcher(Instrumentation instrumentation, AgentArgs args) {
        this.instrumentation = instrumentation;
        this.args = args;
    }

    @Override
    public void run() {
        discover();
        scan();
        initializing = false;
        while (true) {
            try {
                Thread.sleep(args.pollMs);
            } catch (InterruptedException interrupted) {
                return;
            }
            if (directories.isEmpty() || System.currentTimeMillis() - lastDiscovery > 30_000L) {
                discover();
            }
            scan();
        }
    }

    private void discover() {
        lastDiscovery = System.currentTimeMillis();
        List<Path> found = ClassDirs.discover(args);
        if (!found.equals(directories)) {
            directories = found;
            if (!found.isEmpty()) {
                StringBuilder line = new StringBuilder();
                for (int i = 0; i < found.size() && i < 6; i++) {
                    if (i > 0) {
                        line.append(", ");
                    }
                    line.append(found.get(i));
                }
                if (found.size() > 6) {
                    line.append(", … (").append(found.size() - 6).append(" more)");
                }
                Log.info("watching " + found.size() + " class directories: " + line);
            }
        }
        if (found.isEmpty() && !emptyWarningShown) {
            emptyWarningShown = true;
            Log.warn("没有找到 class 输出目录。热更需要 exploded classpath：");
            Log.warn("  IDE 的 Application / Spring Boot / JAR Application 运行配置，或 mvn spring-boot:run。");
            Log.warn("  Spring Boot fat jar、Maven 的 spring-boot:repackage 结果不能热更。");
        }
    }

    private void scan() {
        final Set<Path> seen = new HashSet<Path>();
        for (final Path root : new ArrayList<Path>(directories)) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try {
                Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                        if (".git".equals(name) || ".svn".equals(name) || ".hg".equals(name)
                                || "node_modules".equals(name) || ".idea".equals(name)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        String name = file.getFileName() == null ? "" : file.getFileName().toString();
                        boolean watched = (args.classes && name.endsWith(".class"))
                                || (args.xml && name.endsWith(".xml"));
                        if (!watched) {
                            return FileVisitResult.CONTINUE;
                        }
                        seen.add(file);
                        consider(root, file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException failure) {
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException failure) {
                Log.debug("cannot scan " + root + ": " + failure);
            }
        }
        known.keySet().retainAll(seen);
        pending.keySet().retainAll(seen);
    }

    private void consider(Path root, Path file) {
        FileState now = FileState.read(file);
        if (now == null) {
            return;
        }
        FileState previous = known.get(file);
        if (previous == null) {
            known.put(file, now);
            if (!initializing) {
                // A newly created class file is usually loaded on first use.
                // Queue it anyway: it may be a new nested class of a class
                // that is already running.
                pending.put(file, now);
            }
            return;
        }
        if (previous.equals(now)) {
            FileState waiting = pending.get(file);
            if (waiting != null && waiting.equals(now)) {
                pending.remove(file);
                process(root, file);
            }
            return;
        }
        known.put(file, now);
        pending.put(file, now);
    }

    private void process(Path root, Path file) {
        String name = file.getFileName() == null ? "" : file.getFileName().toString();
        if (args.classes && name.endsWith(".class")) {
            String className = className(root, file);
            if (className == null) {
                return;
            }
            try {
                ClassRedefiner.reload(instrumentation, file, className);
            } catch (Throwable failure) {
                Log.error("reload failed: " + className, failure);
            }
            return;
        }
        if (args.xml && name.endsWith(".xml")) {
            XmlReloaders.reload(file);
        }
    }

    private static String className(Path root, Path file) {
        try {
            String relative = root.relativize(file).toString().replace(File.separatorChar, '/');
            if (!relative.endsWith(".class")) {
                return null;
            }
            if (relative.startsWith("META-INF/") || relative.startsWith("BOOT-INF/")
                    || relative.startsWith("WEB-INF/")) {
                return null;
            }
            String name = relative.substring(0, relative.length() - ".class".length()).replace('/', '.');
            if (name.endsWith("module-info") || name.endsWith("package-info")) {
                return null;
            }
            return name;
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private static final class FileState {
        final long modified;
        final long size;

        private FileState(long modified, long size) {
            this.modified = modified;
            this.size = size;
        }

        static FileState read(Path file) {
            try {
                return new FileState(Files.getLastModifiedTime(file).toMillis(), Files.size(file));
            } catch (IOException failure) {
                return null;
            }
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof FileState)) {
                return false;
            }
            FileState state = (FileState) other;
            return modified == state.modified && size == state.size;
        }

        @Override
        public int hashCode() {
            return (int) (modified * 31 + size);
        }
    }
}
