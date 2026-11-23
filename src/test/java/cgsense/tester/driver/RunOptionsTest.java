package cgsense.tester.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cgsense.app.cmd.CliCommand;
import cgsense.app.cmd.VersionCommand;
import java.util.List;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.ParseException;
import org.junit.jupiter.api.Test;

class RunOptionsTest {
  private static CommandLine parse(CliCommand command, String... args) throws ParseException {
    return DefaultParser.builder()
        .setAllowPartialMatching(false)
        .get()
        .parse(command.options(), args);
  }

  @Test
  void parsesRepeatableOptionsAndDefaults() throws ParseException {
    CommandLine cl =
        parse(
            new VersionCommand(),
            "--project-dir",
            "project",
            "--build-tool",
            "maven",
            "--out",
            "output",
            "--label",
            "before",
            "--commit",
            "abc123",
            "--include",
            "org/x/",
            "--include=org/y/",
            "--repeats",
            "5");

    RunOptions run = RunOptions.from(cl);

    assertEquals(List.of("org/x/", "org/y/"), run.includes);
    assertEquals(5, run.repeats);

    // Defaults
    assertEquals(List.of(), run.excludes);
    assertEquals(60, run.flush);
    assertEquals("mvn", run.mvn);
    assertNull(run.timeout);
    assertNull(run.javaHome);
  }

  @Test
  void rejectsBadNumbers() throws ParseException {
    CommandLine cl =
        parse(
            new VersionCommand(),
            "--project-dir",
            "p",
            "--out",
            "o",
            "--build-tool",
            "maven",
            "--repeats",
            "two");
    UsageException error = assertThrows(UsageException.class, () -> RunOptions.from(cl));
    assertEquals("--repeats: not a number: 'two'", error.getMessage());
  }
}
