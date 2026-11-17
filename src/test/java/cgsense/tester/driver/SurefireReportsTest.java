package cgsense.tester.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import cgsense.tester.driver.SurefireReports.TestInvocation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SurefireReportsTest {
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
    Files.writeString(
        dir.resolve("TEST-a.Foo.xml"),
        "<?xml version=\"1.0\"?>\n"
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
    Map<String, String> statuses =
        parsed.stream().collect(Collectors.toMap(i -> i.reportedName, i -> i.status));
    assertEquals(
        Map.of(
            "ok",
            "passed",
            "bad",
            "failed",
            "crash",
            "error",
            "later",
            "skipped",
            "param(int)[1]",
            "passed"),
        statuses);
    TestInvocation bad =
        parsed.stream().filter(i -> i.reportedName.equals("bad")).findFirst().orElseThrow();
    assertEquals("a.Foo#bad", bad.testId);
    assertEquals("boom", bad.failure.get("message"));
    assertEquals(
        List.of("java.lang.AssertionError: boom", "\tat a.Foo.bad(Foo.java:3)"),
        bad.failure.get("trace"));
    TestInvocation crash =
        parsed.stream().filter(i -> i.reportedName.equals("crash")).findFirst().orElseThrow();
    assertNull(crash.failure.get("message"));
    assertEquals(List.of(), crash.failure.get("trace"));
    assertEquals("a.Foo#param", parsed.get(4).testId);
  }

  @Test
  void parsesGradleListenerLog(@TempDir Path dir) throws IOException {
    Path log = dir.resolve("gradle-tests.jsonl");
    Files.writeString(
        log,
        String.join(
            "\n",
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
    Path integration =
        Files.createDirectories(dir.resolve("sub/build/test-results/integrationTest"));
    Files.writeString(unit.resolve("TEST-a.Unit.xml"), "<testsuite/>");
    Files.writeString(integration.resolve("TEST-a.Integration.xml"), "<testsuite/>");
    assertEquals(
        List.of(unit, integration).stream().sorted().collect(Collectors.toList()),
        SurefireReports.reportDirs(dir, BuildTool.GRADLE));
  }
}
