package cgsense.tester.driver;

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

/*ProjectSources collects facts about the project's source code before any test runs. It reads the project's .java files and records:

every top-level class name (for example demo.Calculator), and
whether it's main code or test code.
The driver uses this for two things: detecting --include when you don't give one, and the origin field of each node in callgraph.json.*/

/* bin/dyncg version --project-dir X --out Y [--include ...]
   │
   ▼
Main.main → execute → VersionCommand.run(cl)
   │
   ├─ ① ProjectSources.scan(projectDir)                         [VersionCommand.java:56]
   │      │  creates new ProjectSources()  (origins = {})
   │      │  Files.walkFileTree(projectDir, visitor)
   │      │
   │      ├─ preVisitDirectory(dir)        for every folder
   │      │     target/, build/, .git/, hidden → SKIP_SUBTREE
   │      │     everything else              → CONTINUE
   │      │
   │      ├─ visitFile(file)               for every file
   │      │     *.java (not package-info / module-info)
   │      │        └─ ② add(projectDir, file)
   │      │              ├─ read file, find "package x.y;" → class name "x.y.Class"
   │      │              ├─ ③ isTestSource(relative path)  → "test" or "main"
   │      │              └─ origins.put(className, origin)  (main wins)
   │      │
   │      └─ visitFileFailed(file)         unreadable → ignore, CONTINUE
   │
   │      returns sources  (origins = {"demo.Calculator": "main", "demo.CalculatorTest": "test"})
   │
   ├─ no --include given?
   │      └─ ④ sources.detectIncludePrefixes()                  [VersionCommand.java:57]
   │            uses origins → ["demo/"]
   │            (empty → UsageException "could not detect project packages; pass --include")
   │
   ├─ run.toOptions(includes) → TestRunOptions
   │
   └─ VersionRunner.run(..., sources)                           [VersionCommand.java:62]
          │  runs the tests N times (TestRunner)
          └─ GraphBuilder.build(..., sources)
                 └─ for every node (method) in the graph:
                       ⑤ sources.originOf(className)            [GraphBuilder.java:297]
                            "demo.Calculator$Inner" → "demo.Calculator" → "main"
                            not found → "unknown"
                       → node "origin" in callgraph.json
*/

/* It does not compile or run anything. It only reads text files. */

/** Static facts about a checked-out project: its packages and which classes are test code. */
public final class ProjectSources {
    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
    private static final int HEADER_CHARS = 20000;
    private static final Set<String> SKIPPED_DIRS = Set.of("target", "build", ".git", "node_modules", ".idea", ".mvn");
    /** A common prefix made only of one of these is too broad to identify a project. */
    /*
 * GENERIC_ROOTS: package roots that are too common to identify one project.
 *
 * dyncg builds the include from the part all the project's packages share. Sometimes that
 * shared part is only a word like "org" or "com". Thousands of unrelated libraries start with
 * those, so the include "org/" would also instrument JUnit, Apache, SLF4J and so on.
 *
 * If the shared part is only one of these words, dyncg does not use it. Instead it takes the
 * first two parts of each package, which are specific to the project.
 *
 *   Project packages:  org.myshop.cart, org.myshop.payment, org.myutils.text
 *   Shared part:       org                  -> in GENERIC_ROOTS -> too broad
 *   Instead:           org/myshop/, org/myutils/
 *   Effect:            org/junit/..., org/apache/... are not instrumented
 */

/*
 * Limitation: only a shared part made of ONE generic word (org, com, ...) is rejected.
 * A two-part prefix is always accepted, even if libraries share it too.
 *
 *   Project packages:  org.apache.foo, org.apache.bar
 *   Shared part:       org.apache  -> 2 parts, accepted -> include "org/apache/"
 *   Problem:           also instruments other Apache libraries (org/apache/commons/io/...)
 *
 * The same happens with com/google/, org/eclipse/, org/springframework/, and with the
 * two-part fallback. Fix: pass --include explicitly (e.g. --include org/apache/foo/)
 * or exclude the libraries (e.g. --exclude org/apache/commons/).
 */

    private static final Set<String> GENERIC_ROOTS = Set.of(                         
            "org", "com", "net", "io", "edu", "gov", "de", "fr", "uk", "nl", "cn", "jp", "ru",
            "me", "dev", "info", "app", "co", "ch", "at", "it", "es", "be", "se", "no", "pl");



// A map of class names to their origin (main or test).
//origins remembers main vs. test for each source file's class, and originOf answers that question for any class name. 
// It maps inner classes to their outer class and gives "unknown" for classes that have no source file.
    final Map<String, String> origins = new HashMap<>();

    String originOf(String className) {
        String topLevel = className.split("\\$", 2)[0];
        return origins.getOrDefault(topLevel, "unknown");
    }

// if any folder in the file's path is test, tests or *-tests, the class is counted as test code; otherwise it's main code. It's a simple rule that covers the standard src/test/java layout.
    static boolean isTestSource(Path relative) {
        for (Path part : relative) {
            String name = part.toString();
            if (name.equals("test") || name.equals("tests") || name.endsWith("-tests")) {
                return true;
            }
        }
        return false;
    }

/*scan walks through every folder and file of the project, finds the .java source files, and records each class as main or test. 
It returns a ProjectSources object holding that information in origins.*/
    public static ProjectSources scan(Path projectDir) throws IOException {
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

  /*/*
 * detectIncludePrefixes(): finds the packages to instrument when no --include is given.
 *
 * Think of package names as addresses: dyncg looks for the "neighborhood" the project lives in.
 *
 * Step 1: collect the package of every class in the project:
 *     org.jsoup.nodes
 *     org.jsoup.parser
 *     org.jsoup
 *
 * Step 2: compare them word by word from the left, and stop at the first word that differs:
 *     org . jsoup . nodes
 *     org . jsoup . parser
 *     org . jsoup
 *      ok    ok
 *   All of them start with org.jsoup, so the include is "org/jsoup/".
 *
 * Step 3: if the shared part is too vague, look one word deeper:
 *     org . myshop  . cart
 *     org . myutils . text
 *      ok    differs
 *   "org" alone is too general (JUnit, Apache and many other libraries start with it too),
 *   so dyncg takes the first two words of each package instead: "org/myshop/", "org/myutils/".
 *
 * Step 4: a class without a package (e.g. "Main") is added by its name.
 *
 * Example: in the demo all classes are in "demo", so the result is "demo/".
 *
 * In one line: use the part of the package names that all classes share; if that is too
 * general (like "org"), go one word deeper.
 */
    public List<String> detectIncludePrefixes() {
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
