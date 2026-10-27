package dyncg.driver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;

/** Test-execution options shared by the {@code pair} and {@code version} commands. */
final class RunOptions {
    List<String> includes;
    List<String> excludes;
    String tests;
    int repeats;
    Path javaHome;
    Integer testTimeout;
    Integer timeout;
    boolean offline;
    String buildTool;
    List<String> mavenArgs;
    List<String> gradleArgs;
    int flush;
    boolean recordEvents;
    long maxEvents;
    Path agentJar;
    String mvn;
    String gradle;

    /** Adds the shared options to a command's options. */
    static Options addTo(Options options) {
        return options
                .addOption(value("include", "prefix",
                        "package prefix to instrument, e.g. org/jsoup/ (repeatable; default: detected)"))
                .addOption(value("exclude", "prefix", "package prefix to skip (repeatable)"))
                .addOption(value("tests", "patterns",
                        "test class/pattern selection, comma-separated, applied to both versions"))
                .addOption(value("repeats", "n", "test runs per version (default 2)"))
                .addOption(value("java-home", "dir", "JDK used to run the build and tests"))
                .addOption(value("test-timeout", "seconds", "seconds per forked test JVM (surefire.timeout)"))
                .addOption(value("timeout", "seconds", "seconds per build invocation"))
                .addOption(flag("offline", "run the build tool offline"))
                .addOption(value("build-tool", "tool", "auto, maven or gradle (default: auto)"))
                .addOption(value("maven-arg", "arg",
                        "extra Maven argument (repeatable; use --maven-arg=-X for dashes)"))
                .addOption(value("gradle-arg", "arg",
                        "extra Gradle argument (repeatable; use --gradle-arg=-X for dashes)"))
                .addOption(value("flush", "seconds", "agent snapshot interval in seconds (0 = only at exit)"))
                .addOption(flag("record-events", "also log individual invocations in order (see --max-events)"))
                .addOption(value("max-events", "n",
                        "with --record-events: invocations logged per test JVM before logging stops (default 5000000)"))
                .addOption(value("agent-jar", "file", "default: <project root>/agent/target/dyncg-agent.jar"))
                .addOption(value("mvn", "command", "Maven executable (default: mvn)"))
                .addOption(value("gradle", "command", "Gradle executable when no gradlew exists (default: gradle)"));
    }

    static RunOptions from(CommandLine cl) {
        RunOptions run = new RunOptions();
        run.includes = values(cl, "include");
        run.excludes = values(cl, "exclude");
        run.tests = cl.getOptionValue("tests");
        run.repeats = intValue(cl, "repeats", 2);
        run.javaHome = path(cl, "java-home");
        run.testTimeout = cl.hasOption("test-timeout") ? intValue(cl, "test-timeout", 0) : null;
        run.timeout = cl.hasOption("timeout") ? intValue(cl, "timeout", 0) : null;
        run.offline = cl.hasOption("offline");
        run.buildTool = cl.getOptionValue("build-tool", "auto");
        run.mavenArgs = values(cl, "maven-arg");
        run.gradleArgs = values(cl, "gradle-arg");
        run.flush = intValue(cl, "flush", 60);
        run.recordEvents = cl.hasOption("record-events");
        run.maxEvents = longValue(cl, "max-events", 5000000L);
        run.agentJar = path(cl, "agent-jar");
        run.mvn = cl.getOptionValue("mvn", "mvn");
        run.gradle = cl.getOptionValue("gradle", "gradle");
        return run;
    }

    TestRunOptions toOptions(List<String> includeList) {
        Path agent = agentJar != null ? Main.abs(agentJar) : TestRunOptions.defaultAgentJar();
        if (!Files.isRegularFile(agent)) {
            throw new UsageException("agent jar not found: " + agent + " (build it with: mvn -B package)");
        }
        if (repeats < 1) {
            throw new UsageException("--repeats must be at least 1");
        }
        if (maxEvents < 1 || maxEvents > Integer.MAX_VALUE) {
            throw new UsageException("--max-events must be between 1 and " + Integer.MAX_VALUE);
        }
        return new TestRunOptions(agent, includeList, excludes, Main.abs(javaHome), tests, testTimeout, timeout,
                offline, mavenArgs, gradleArgs, flush, recordEvents ? maxEvents : 0, mvn, gradle, BuildTool.parse(buildTool));
    }

    // ---- option definitions and typed values, shared by all commands --------------------------

    /** An option that takes a value, given as {@code --name value} or {@code --name=value}. */
    static Option value(String name, String argName, String description) {
        return Option.builder().longOpt(name).hasArg().argName(argName).desc(description).get();
    }

    static Option required(String name, String argName, String description) {
        return Option.builder().longOpt(name).hasArg().argName(argName).desc(description).required().get();
    }

    static Option flag(String name, String description) {
        return Option.builder().longOpt(name).desc(description).get();
    }

    /** All values of a repeatable option, in order; empty when it was not given. */
    static List<String> values(CommandLine cl, String name) {
        String[] values = cl.getOptionValues(name);
        return values == null ? List.of() : Arrays.asList(values);
    }

    static Path path(CommandLine cl, String name) {
        String value = cl.getOptionValue(name);
        return value == null ? null : Paths.get(value);
    }

    static int intValue(CommandLine cl, String name, int defaultValue) {
        long value = longValue(cl, name, defaultValue);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new UsageException("--" + name + ": number out of range: " + value);
        }
        return (int) value;
    }

    static long longValue(CommandLine cl, String name, long defaultValue) {
        String value = cl.getOptionValue(name);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.strip());
        } catch (NumberFormatException error) {
            throw new UsageException("--" + name + ": not a number: '" + value + "'");
        }
    }
}
