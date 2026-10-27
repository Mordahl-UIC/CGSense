package dyncg.driver;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Running a project's tests under the agent. */
final class TestRunner {
    /** Plugins that are irrelevant to running tests and often break on historical commits. */
    static final List<String> SKIP_PROPERTIES = List.of(
            "-Djacoco.skip=true", "-Dcheckstyle.skip=true", "-Denforcer.skip=true",
            "-Danimal.sniffer.skip=true", "-Drat.skip=true", "-Dspotbugs.skip=true", "-Dpmd.skip=true",
            "-Dlicense.skip=true", "-Dmaven.javadoc.skip=true", "-Djapicmp.skip=true", "-Dgpg.skip=true",
            "-Dspotless.check.skip=true", "-Dmaven.source.skip=true", "-Dbuildnumber.skip=true");

    private TestRunner() {
    }

    static String agentOption(TestRunOptions options, Path dumpsDir) {
        List<String> parts = new ArrayList<>();
        parts.add("include=" + String.join(":", options.includes));
        parts.add("out=" + dumpsDir);
        parts.add("flush=" + options.flushSeconds);
        if (options.maxEvents > 0) {
            parts.add("events=" + options.maxEvents);
        }
        if (!options.excludes.isEmpty()) {
            parts.add("exclude=" + String.join(":", options.excludes));
        }
        String value = "-javaagent:" + options.agentJar + "=" + String.join(",", parts);
        if (value.chars().anyMatch(Character::isWhitespace)) {
            throw new UsageException("agent option must not contain whitespace: " + value);
        }
        return value;
    }

    static List<String> mavenCommand(TestRunOptions options) {
        List<String> command = new ArrayList<>(List.of(
                options.mvn, "-B", "test",
                "-Dmaven.test.failure.ignore=true", // keep running after failing tests
                "-DfailIfNoTests=false", "-Dsurefire.failIfNoSpecifiedTests=false",
                "-DtrimStackTrace=false"));
        command.addAll(SKIP_PROPERTIES);
        if (options.tests != null) {
            command.add("-Dtest=" + options.tests);
        }
        if (options.testTimeoutSeconds != null) {
            command.add("-Dsurefire.timeout=" + options.testTimeoutSeconds);
        }
        if (options.offline) {
            command.add("-o");
        }
        command.addAll(options.mavenArgs);
        return command;
    }

    /** Gradle init script that records each test's class, method and outcome (see test-listener.gradle). */
    static final String GRADLE_LISTENER = "dyncg-test-listener.gradle";
    /** Where that listener writes, one JSON object per test case. */
    static final String GRADLE_TEST_LOG = "gradle-tests.jsonl";

    static List<String> gradleCommand(Path projectDir, TestRunOptions options, Path initScript) {
        List<String> command = new ArrayList<>();
        Path wrapper = projectDir.resolve("gradlew");
        if (Files.isRegularFile(wrapper)) {
            if (Files.isExecutable(wrapper)) {
                command.add(wrapper.toString());
            } else {
                command.add("sh");
                command.add(wrapper.toString());
            }
        } else {
            command.add(options.gradle);
        }
        command.add("--no-daemon");
        if (initScript != null) {
            command.add("--init-script");
            command.add(initScript.toString());
            // Listeners added by init scripts are not compatible with the configuration cache.
            command.add("-Dorg.gradle.configuration-cache=false");
            command.add("-Dorg.gradle.unsafe.configuration-cache=false");
        }
        // Keep going after a project's tests fail (like maven.test.failure.ignore), and never take
        // test results from the build cache or skip the task as up to date: the tests must run.
        command.add("--continue");
        command.add("--no-build-cache");
        command.add("cleanTest");
        command.add("test");
        if (options.tests != null) {
            for (String test : options.tests.split(",")) {
                if (!test.isBlank()) {
                    command.add("--tests");
                    command.add(test.strip());
                }
            }
        }
        if (options.testTimeoutSeconds != null) {
            throw new UsageException("--test-timeout is a Maven Surefire option and is not supported for Gradle; "
                    + "use --timeout for the whole Gradle invocation");
        }
        if (options.offline) {
            command.add("--offline");
        }
        command.addAll(options.gradleArgs);
        return command;
    }

    static List<String> buildCommand(Path projectDir, TestRunOptions options, Path initScript) {
        return options.buildTool == BuildTool.GRADLE ? gradleCommand(projectDir, options, initScript)
                : mavenCommand(options);
    }

    /** Runs the test suite once with the agent attached and collects its raw outputs in runDir. */
    static RunResult runTests(Path projectDir, Path runDir, int index, TestRunOptions options)
            throws IOException, InterruptedException {
        Path dumpsDir = runDir.resolve("dumps");
        Path reportsCopy = runDir.resolve("surefire-reports");
        FileUtil.deleteRecursively(dumpsDir);
        FileUtil.deleteRecursively(reportsCopy);
        Files.createDirectories(dumpsDir);
        for (Path stale : SurefireReports.reportDirs(projectDir, options.buildTool)) {
            FileUtil.deleteRecursively(stale);
        }

        Path testLog = null;
        Path initScript = null;
        if (options.buildTool == BuildTool.GRADLE) {
            testLog = runDir.resolve(GRADLE_TEST_LOG);
            Files.deleteIfExists(testLog);
            initScript = runDir.resolve(GRADLE_LISTENER);
            try (InputStream in = TestRunner.class.getResourceAsStream("/dyncg/test-listener.gradle")) {
                Files.copy(in, initScript, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        List<String> command = buildCommand(projectDir, options, initScript);
        ProcessBuilder builder = new ProcessBuilder(command).directory(projectDir.toFile()).redirectErrorStream(true);
        Map<String, String> env = builder.environment();
        if (testLog != null) {
            env.put("DYNCG_GRADLE_TEST_LOG", testLog.toString());
        }
        // JAVA_TOOL_OPTIONS reaches every JVM, including forked test JVMs whose <argLine> is fixed
        // in the pom (where -DargLine would be ignored). Non-test JVMs are filtered out later.
        String existing = env.getOrDefault("JAVA_TOOL_OPTIONS", "");
        String toolOptions = existing.isEmpty() ? agentOption(options, dumpsDir)
                : existing + " " + agentOption(options, dumpsDir);
        env.put("JAVA_TOOL_OPTIONS", toolOptions);
        if (options.javaHome != null) {
            env.put("JAVA_HOME", options.javaHome.toString());
            env.put("PATH", options.javaHome.resolve("bin") + File.pathSeparator + env.getOrDefault("PATH", ""));
        }

        Path log = runDir.resolve("build.log");
        Files.writeString(log, "$ cd " + projectDir + "\n$ JAVA_TOOL_OPTIONS='" + toolOptions + "' "
                + String.join(" ", command) + "\n\n");
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));

        long started = System.nanoTime();
        Process process = builder.start();
        Integer exitCode = null;
        boolean timedOut = false;
        if (options.timeoutSeconds == null) {
            exitCode = process.waitFor();
        } else if (process.waitFor(options.timeoutSeconds, TimeUnit.SECONDS)) {
            exitCode = process.exitValue();
        } else {
            timedOut = true;
            // Maven and its forked test JVMs.
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            process.waitFor();
        }
        double duration = round((System.nanoTime() - started) / 1e9, 1);

        List<Path> copied = new ArrayList<>();
        for (Path directory : SurefireReports.reportDirs(projectDir, options.buildTool)) {
            Path relative = projectDir.relativize(directory);
            Path destination = reportsCopy.resolve(relative.toString());
            FileUtil.copyTree(directory, destination);
            copied.add(destination);
        }
        return new RunResult(index, runDir, command, exitCode, timedOut, duration, dumpFiles(dumpsDir), copied,
                testLog);
    }

    /** Runs saved earlier under runsDir/&lt;index&gt;/{dumps,surefire-reports}, for {@code rebuild}. */
    static List<RunResult> existingRuns(Path runsDir) throws IOException {
        List<Path> dirs;
        try (Stream<Path> stream = Files.list(runsDir)) {
            dirs = stream.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().matches("\\d+"))
                    .sorted(Comparator.comparingInt(p -> Integer.parseInt(p.getFileName().toString())))
                    .collect(Collectors.toList());
        }
        if (dirs.isEmpty()) {
            throw new UsageException("no run directories (0, 1, ...) in " + runsDir);
        }
        List<RunResult> runs = new ArrayList<>();
        for (Path dir : dirs) {
            Path testLog = dir.resolve(GRADLE_TEST_LOG);
            runs.add(new RunResult(Integer.parseInt(dir.getFileName().toString()), dir, null, null, false, 0,
                    dumpFiles(dir.resolve("dumps")),
                    SurefireReports.copiedReportDirs(dir.resolve("surefire-reports")),
                    Files.isRegularFile(testLog) ? testLog : null));
        }
        return runs;
    }

    private static List<Path> dumpFiles(Path dumpsDir) throws IOException {
        List<Path> files = new ArrayList<>();
        if (Files.isDirectory(dumpsDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dumpsDir, "dyncg-*.json")) {
                stream.forEach(files::add);
            }
        }
        Collections.sort(files);
        return files;
    }

    /** Rounds half-to-even on the exact binary value, like Python's round(). */
    static double round(double value, int decimals) {
        return new BigDecimal(value).setScale(decimals, RoundingMode.HALF_EVEN).doubleValue();
    }
}
