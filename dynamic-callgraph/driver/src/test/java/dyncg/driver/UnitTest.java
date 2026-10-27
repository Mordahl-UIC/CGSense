package dyncg.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.ParseException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dyncg.driver.SurefireReports.TestInvocation;

class UnitTest {
    @Test
    void methodKeyStripsParameters() {
        assertEquals("C#parse", SurefireReports.methodKey("C", "parse(String)[2]"));
        assertEquals("C#parse", SurefireReports.methodKey("C", "parse[1]"));
        assertEquals("C#plain", SurefireReports.methodKey("C", "plain"));
        assertEquals("C#[1] display", SurefireReports.methodKey("C", "[1] display"));
    }

    @Test
    void worstStatusWins() {
        assertEquals("error", SurefireReports.worst(List.of("passed", "error", "failed")));
        assertEquals("failed", SurefireReports.worst(List.of("skipped", "failed")));
        assertEquals("passed", SurefireReports.worst(List.of("skipped", "passed")));
    }

    @Test
    void parsesReports(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("TEST-a.Foo.xml"), "<?xml version=\"1.0\"?>\n"
                + "<testsuite name=\"a.Foo\">\n"
                + "  <testcase classname=\"a.Foo\" name=\"ok\" time=\"0.1\"/>\n"
                + "  <testcase classname=\"a.Foo\" name=\"bad\"><failure type=\"java.lang.AssertionError\""
                + " message=\"boom\">java.lang.AssertionError: boom\n\tat a.Foo.bad(Foo.java:3)</failure></testcase>\n"
                + "  <testcase classname=\"a.Foo\" name=\"crash\"><error type=\"java.lang.NullPointerException\"/></testcase>\n"
                + "  <testcase classname=\"a.Foo\" name=\"later\"><skipped/></testcase>\n"
                + "  <testcase classname=\"a.Foo\" name=\"param(int)[1]\"/>\n"
                + "</testsuite>\n");
        Files.writeString(dir.resolve("TEST-broken.xml"), "<testsuite");
        List<TestInvocation> parsed = SurefireReports.parse(List.of(dir));
        Map<String, String> statuses = parsed.stream()
                .collect(Collectors.toMap(i -> i.reportedName, i -> i.status));
        assertEquals(Map.of("ok", "passed", "bad", "failed", "crash", "error", "later", "skipped",
                "param(int)[1]", "passed"), statuses);
        TestInvocation bad = parsed.stream().filter(i -> i.reportedName.equals("bad")).findFirst().orElseThrow();
        assertEquals("a.Foo#bad", bad.testId);
        assertEquals("boom", bad.failure.get("message"));
        assertEquals(List.of("java.lang.AssertionError: boom", "\tat a.Foo.bad(Foo.java:3)"), bad.failure.get("trace"));
        TestInvocation crash = parsed.stream().filter(i -> i.reportedName.equals("crash")).findFirst().orElseThrow();
        assertNull(crash.failure.get("message"));
        assertEquals(List.of(), crash.failure.get("trace"));
        assertEquals("a.Foo#param", parsed.get(4).testId);
    }

    private static ProjectSources sources(String... classes) {
        ProjectSources sources = new ProjectSources();
        for (String c : classes) {
            sources.origins.put(c, "main");
        }
        return sources;
    }

    @Test
    void detectsIncludePrefixes() {
        assertEquals(List.of("org/jsoup/"), sources("org.jsoup.A", "org.jsoup.nodes.B").detectIncludePrefixes());
        assertEquals(List.of("org/a/", "org/b/"), sources("org.a.X", "org.b.Y").detectIncludePrefixes());
        assertEquals(List.of("sample/"), sources("sample.A", "sample.B").detectIncludePrefixes());
        assertEquals(List.of("com/x/y/", "Foo"), sources("Foo", "com.x.y.Z").detectIncludePrefixes());
        assertEquals(List.of(), sources().detectIncludePrefixes());
    }

    @Test
    void scansSourcesAndOrigins(@TempDir Path dir) throws IOException {
        Path main = Files.createDirectories(dir.resolve("src/main/java/p"));
        Path test = Files.createDirectories(dir.resolve("src/test/java/p"));
        Path ignored = Files.createDirectories(dir.resolve("target/generated/p"));
        Files.writeString(main.resolve("A.java"), "// header\npackage p;\nclass A {}\n");
        Files.writeString(test.resolve("ATest.java"), "package p;\nclass ATest {}\n");
        Files.writeString(ignored.resolve("Gen.java"), "package p;\nclass Gen {}\n");
        ProjectSources sources = ProjectSources.scan(dir);
        assertEquals(Map.of("p.A", "main", "p.ATest", "test"), sources.origins);
        assertEquals("main", sources.originOf("p.A$Inner"));
        assertEquals("unknown", sources.originOf("q.B"));
    }

    @Test
    void classifiesOutcomes() {
        assertEquals("pass_pass", PairRunner.classify("passed", "passed"));
        assertEquals("fail_pass", PairRunner.classify("error", "passed"));
        assertEquals("pass_fail", PairRunner.classify("passed", "failed"));
        assertEquals("fail_fail", PairRunner.classify("failed", "error"));
        assertEquals("unstable", PairRunner.classify("flaky", "passed"));
        assertEquals("skipped", PairRunner.classify("skipped", "passed"));
        assertEquals("after_only", PairRunner.classify(null, "passed"));
        assertEquals("before_only", PairRunner.classify("passed", null));
        assertEquals("not_reported", PairRunner.classify("not_reported", "passed"));
    }

    @Test
    void roundsLikePython() {
        assertEquals(0.12, TestRunner.round(0.125, 2));
        assertEquals(2.5, TestRunner.round(2.5, 2));
        assertEquals(0.33, TestRunner.round(1.0 / 3, 2));
    }

    @Test
    void jvmRoles() {
        assertEquals("test-jvm", GraphBuilder.jvmRole("/x/target/surefire/surefirebooter123.jar /x/target/surefire 2026"));
        assertEquals("maven", GraphBuilder.jvmRole("org.codehaus.plexus.classworlds.launcher.Launcher -B test"));
        assertEquals("test-jvm", GraphBuilder.jvmRole("worker.org.gradle.process.internal.worker.GradleWorkerMain 'Gradle Test Executor 1'"));
        assertEquals("gradle", GraphBuilder.jvmRole("org.gradle.launcher.daemon.bootstrap.GradleDaemon 8.10"));
        assertEquals("other", GraphBuilder.jvmRole("com.example.Server"));
    }

    private static TestRunOptions options(BuildTool tool, String tests) {
        return new TestRunOptions(Path.of("agent.jar"), List.of("sample/"), List.of(), null, tests,
                null, null, false, List.of(), List.of("--stacktrace"), 60, 0, "mvn", "gradle", tool);
    }

    @Test
    void detectsBuildTools(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("pom.xml"), "<project/>");
        assertEquals(BuildTool.MAVEN, BuildTool.AUTO.resolve(dir));
        Files.delete(dir.resolve("pom.xml"));
        Files.writeString(dir.resolve("build.gradle.kts"), "plugins { java }");
        assertEquals(BuildTool.GRADLE, BuildTool.AUTO.resolve(dir));
        Files.writeString(dir.resolve("pom.xml"), "<project/>");
        assertThrows(UsageException.class, () -> BuildTool.AUTO.resolve(dir));
    }

    @Test
    void buildsGradleCommandAndPrefersWrapper(@TempDir Path dir) throws IOException {
        Path wrapper = dir.resolve("gradlew");
        Files.writeString(wrapper, "#!/bin/sh\n");
        Path init = dir.resolve("init.gradle");
        List<String> command = TestRunner.gradleCommand(dir, options(BuildTool.GRADLE, "FooTest,Bar*"), init);
        assertEquals(List.of("sh", wrapper.toString(), "--no-daemon", "--init-script", init.toString(),
                "-Dorg.gradle.configuration-cache=false", "-Dorg.gradle.unsafe.configuration-cache=false",
                "--continue", "--no-build-cache",
                "cleanTest", "test", "--tests", "FooTest",
                "--tests", "Bar*", "--stacktrace"), command);
    }

    @Test
    void parsesGradleListenerLog(@TempDir Path dir) throws IOException {
        Path log = dir.resolve("gradle-tests.jsonl");
        Files.writeString(log, String.join("\n",
                "{\"task\":\":test\",\"class\":\"a.FooTest\",\"method\":\"batches(Factory)\","
                        + "\"name\":\"[1] List DataLoader\",\"status\":\"passed\",\"failure\":null}",
                "{\"task\":\":test\",\"class\":\"a.FooTest\",\"method\":\"plain()\",\"name\":\"plain()\","
                        + "\"status\":\"failed\",\"failure\":{\"kind\":\"failed\",\"type\":\"org.opentest4j.AssertionFailedError\","
                        + "\"message\":\"boom\",\"trace\":[\"x\"]}}",
                ""));
        List<TestInvocation> parsed = SurefireReports.parseGradleTestLog(log);
        assertEquals(2, parsed.size());
        assertEquals("a.FooTest#batches", parsed.get(0).testId);
        assertEquals("[1] List DataLoader", parsed.get(0).reportedName);
        assertEquals("a.FooTest#plain", parsed.get(1).testId);
        assertEquals("failed", parsed.get(1).status);
        assertEquals("org.opentest4j.AssertionFailedError", parsed.get(1).failure.get("type"));
    }

    @Test
    void findsGradleReports(@TempDir Path dir) throws IOException {
        Path unit = Files.createDirectories(dir.resolve("build/test-results/test"));
        Path integration = Files.createDirectories(dir.resolve("sub/build/test-results/integrationTest"));
        Files.writeString(unit.resolve("TEST-a.Unit.xml"), "<testsuite/>");
        Files.writeString(integration.resolve("TEST-a.Integration.xml"), "<testsuite/>");
        assertEquals(List.of(unit, integration).stream().sorted().collect(Collectors.toList()),
                SurefireReports.reportDirs(dir, BuildTool.GRADLE));
    }

    private static CommandLine parse(CliCommand command, String... args) throws ParseException {
        return DefaultParser.builder().setAllowPartialMatching(false).get().parse(command.options(), args);
    }

    @Test
    void parsesRepeatableOptionsAndDefaults() throws ParseException {
        CommandLine cl = parse(new PairCommand(), "--repo", "r", "--before", "a", "--after", "b", "--out", "o",
                "--include", "org/x/", "--include=org/y/", "--maven-arg=-Dfoo=bar", "--maven-arg", "-o",
                "--record-events", "--repeats", "5");
        RunOptions run = RunOptions.from(cl);
        assertEquals(List.of("org/x/", "org/y/"), run.includes);
        assertEquals(List.of("-Dfoo=bar", "-o"), run.mavenArgs);
        assertEquals(5, run.repeats);
        assertTrue(run.recordEvents);
        // defaults
        assertEquals(List.of(), run.excludes);
        assertEquals(60, run.flush);
        assertEquals(5000000L, run.maxEvents);
        assertEquals("auto", run.buildTool);
        assertEquals("mvn", run.mvn);
        assertNull(run.timeout);
        assertNull(run.javaHome);
    }

    @Test
    void rejectsBadNumbers() throws ParseException {
        CommandLine cl = parse(new VersionCommand(), "--project-dir", "p", "--out", "o", "--repeats", "two");
        UsageException error = assertThrows(UsageException.class, () -> RunOptions.from(cl));
        assertEquals("--repeats: not a number: 'two'", error.getMessage());
    }

    @Test
    void commandLineExitCodes() {
        assertEquals(0, Main.execute("--help"));
        assertEquals(0, Main.execute("--version"));
        assertEquals(0, Main.execute("pair", "--help"));
        assertEquals(2, Main.execute());
        assertEquals(2, Main.execute("nope"));
        assertEquals(2, Main.execute("version", "--out", "o")); // --project-dir missing
        assertEquals(2, Main.execute("version", "--project-dir", "p", "--out", "o", "--no-such-option"));
        assertEquals(2, Main.execute("version", "--project-dir", "p", "--out", "o", "--max", "3")); // no abbreviations
        assertEquals(2, Main.execute("version", "--project-dir", "p", "--out", "o", "extra"));
    }
}
