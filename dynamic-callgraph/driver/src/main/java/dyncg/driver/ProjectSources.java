package dyncg.driver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Static facts about a checked-out project: its packages and which classes are test code. */
final class ProjectSources {
    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
    private static final int HEADER_CHARS = 20000;
    private static final Set<String> SKIPPED_DIRS = Set.of("target", "build", ".git", "node_modules", ".idea", ".mvn");
    /** A common prefix made only of one of these is too broad to identify a project. */
    private static final Set<String> GENERIC_ROOTS = Set.of(
            "org", "com", "net", "io", "edu", "gov", "de", "fr", "uk", "nl", "cn", "jp", "ru",
            "me", "dev", "info", "app", "co", "ch", "at", "it", "es", "be", "se", "no", "pl");

    /** Top-level classes found in the project's Java sources: dotted name → "main" | "test". */
    final Map<String, String> origins = new HashMap<>();

    String originOf(String className) {
        String topLevel = className.split("\\$", 2)[0];
        return origins.getOrDefault(topLevel, "unknown");
    }

    static boolean isTestSource(Path relative) {
        for (Path part : relative) {
            String name = part.toString();
            if (name.equals("test") || name.equals("tests") || name.endsWith("-tests")) {
                return true;
            }
        }
        return false;
    }

    static ProjectSources scan(Path projectDir) throws IOException {
        ProjectSources sources = new ProjectSources();
        Files.walkFileTree(projectDir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                if (!dir.equals(projectDir) && (SKIPPED_DIRS.contains(name) || name.startsWith("."))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String name = file.getFileName().toString();
                if (name.endsWith(".java") && !name.equals("package-info.java") && !name.equals("module-info.java")) {
                    sources.add(projectDir, file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException error) {
                return FileVisitResult.CONTINUE;
            }
        });
        return sources;
    }

    private void add(Path projectDir, Path file) {
        String head;
        try {
            head = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return;
        }
        if (head.length() > HEADER_CHARS) {
            head = head.substring(0, HEADER_CHARS);
        }
        Matcher match = PACKAGE.matcher(head);
        String packageName = match.find() ? match.group(1) : "";
        String stem = file.getFileName().toString();
        stem = stem.substring(0, stem.length() - ".java".length());
        String className = packageName.isEmpty() ? stem : packageName + "." + stem;
        String origin = isTestSource(projectDir.relativize(file)) ? "test" : "main";
        // A class that exists in main sources stays "main" even if a test copy exists.
        if (!"main".equals(origins.get(className))) {
            origins.put(className, origin);
        }
    }

    /** Package prefixes (internal form, ending in '/') that cover the project's own classes. */
    List<String> detectIncludePrefixes() {
        TreeSet<String> packages = new TreeSet<>();
        TreeSet<String> defaultPackage = new TreeSet<>();
        for (String className : origins.keySet()) {
            int dot = className.lastIndexOf('.');
            if (dot >= 0) {
                packages.add(className.substring(0, dot));
            } else {
                defaultPackage.add(className);
            }
        }
        List<String> prefixes = new ArrayList<>();
        if (!packages.isEmpty()) {
            List<String[]> split = new ArrayList<>();
            int shortest = Integer.MAX_VALUE;
            for (String p : packages) {
                String[] parts = p.split("\\.");
                split.add(parts);
                shortest = Math.min(shortest, parts.length);
            }
            List<String> common = new ArrayList<>();
            for (int i = 0; i < shortest; i++) {
                String segment = split.get(0)[i];
                final int index = i;
                if (!split.stream().allMatch(parts -> parts[index].equals(segment))) {
                    break;
                }
                common.add(segment);
            }
            if (!common.isEmpty() && !(common.size() == 1 && GENERIC_ROOTS.contains(common.get(0)))) {
                prefixes.add(String.join("/", common) + "/");
            } else {
                // Several unrelated roots: use the first two segments of each package.
                TreeSet<String> roots = new TreeSet<>();
                for (String[] parts : split) {
                    roots.add(String.join("/", Arrays.asList(parts).subList(0, Math.min(2, parts.length))) + "/");
                }
                prefixes.addAll(roots);
            }
        }
        // Classes in the default package can only be matched by their exact names.
        prefixes.addAll(defaultPackage);
        return prefixes;
    }
}
