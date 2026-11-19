package com.hotreload.agent;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Finds the directories that hold the compiled application.
 *
 * <p>The IDE starts an application with every module output directory on
 * {@code java.class.path}, so that is the primary source. Maven and Gradle
 * started from a terminal are covered by the usual {@code target/classes},
 * {@code build/classes/java/main} and {@code out/production/classes} guesses
 * below {@code user.dir}, including multi-module projects.</p>
 *
 * <p>Only directories that actually contain {@code .class} files (or a
 * {@code mapper} folder) are watched, so a classpath entry such as the project
 * root or a configuration folder does not make the agent walk the whole
 * project.</p>
 */
final class ClassDirs {

    /** Upper bound keeps a huge multi-module repository from being watched entirely. */
    private static final int MAX_DIRS = 64;

    private ClassDirs() {
    }

    static List<Path> discover(AgentArgs args) {
        LinkedHashSet<Path> found = new LinkedHashSet<Path>();

        for (Path configured : args.dirs) {
            add(found, configured);
        }

        String classpath = System.getProperty("java.class.path", "");
        boolean classpathHadDirectories = false;
        for (String entry : classpath.split(Pattern.quote(File.pathSeparator))) {
            if (entry.trim().isEmpty()) {
                continue;
            }
            Path path = Paths.get(entry.trim());
            if (Files.isDirectory(path)) {
                classpathHadDirectories = true;
            }
            add(found, path);
        }

        // Usual output locations below the working directory. These guesses
        // are only needed when the JVM was started with a jar or with a
        // classpath that does not name directories (for example
        // `java -jar app.jar`); otherwise the classpath already listed the
        // output directories and adding unrelated sibling projects would
        // watch code that is not part of this application.
        if (!classpathHadDirectories) {
            Path base = Paths.get(System.getProperty("user.dir", "."));
            add(found, base.resolve("target/classes"));
            add(found, base.resolve("build/classes/java/main"));
            add(found, base.resolve("build/classes/kotlin/main"));
            add(found, base.resolve("out/production/classes"));
            add(found, base.resolve("out/production/resources"));
            add(found, base.resolve("bin"));
            scanModules(base, found);
        }

        return new ArrayList<Path>(found);
    }

    private static void add(Set<Path> found, Path candidate) {
        if (found.size() >= MAX_DIRS) {
            return;
        }
        try {
            Path dir = candidate.toAbsolutePath().normalize();
            if (!Files.isDirectory(dir)) {
                return;
            }
            if (!looksLikeOutput(dir) && !isKnownOutput(dir)) {
                return;
            }
            found.add(dir);
        } catch (RuntimeException ignored) {
            // an inaccessible path is simply not watched
        }
    }

    /** Common build output directories, even when their classes are deep. */
    private static boolean isKnownOutput(Path dir) {
        String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
        if ("classes".equals(name) || "test-classes".equals(name)) {
            return true;
        }
        String path = dir.toString().replace('\\', '/');
        return path.endsWith("/target/classes")
                || path.endsWith("/build/classes/java/main")
                || path.endsWith("/build/classes/kotlin/main")
                || path.endsWith("/out/production/classes");
    }

    /**
     * True when the directory contains a compiled class or a MyBatis mapper
     * folder near its root. The walk stops at the first hit and at a small
     * depth, so checking a classpath entry is cheap.
     */
    private static boolean looksLikeOutput(final Path dir) {
        if (Files.isDirectory(dir.resolve("mapper"))) {
            return true;
        }
        final boolean[] useful = new boolean[1];
        try {
            Files.walkFileTree(dir, java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class), 4,
                    new SimpleFileVisitor<Path>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            String name = file.getFileName().toString();
                            if (name.endsWith(".class") || name.endsWith(".xml")) {
                                useful[0] = true;
                                return FileVisitResult.TERMINATE;
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult preVisitDirectory(Path sub, BasicFileAttributes attrs) {
                            String name = sub.getFileName() == null ? "" : sub.getFileName().toString();
                            if ("META-INF".equals(name) || "BOOT-INF".equals(name) || "WEB-INF".equals(name)) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException ignored) {
            return false;
        }
        return useful[0];
    }

    /**
     * Multi-module projects started from the repository root: add every child
     * module's Maven / Gradle output directory. Depth is limited (the module
     * folder, then {@code target/classes}), not the whole tree.
     */
    private static void scanModules(Path base, final Set<Path> found) {
        try {
            Files.walkFileTree(base, java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class), 3,
                    new SimpleFileVisitor<Path>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                            if (".git".equals(name) || ".idea".equals(name) || ".gradle".equals(name)
                                    || "node_modules".equals(name) || "target".equals(name)
                                    || "build".equals(name) || "out".equals(name)) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            add(found, dir.resolve("target/classes"));
                            add(found, dir.resolve("build/classes/java/main"));
                            add(found, dir.resolve("out/production/classes"));
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException ignored) {
            // no module scan then
        }
    }
}
