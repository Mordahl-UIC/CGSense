package dyncg.driver;

import java.nio.file.Path;
import java.util.List;

/** Raw outputs of one test run: agent dumps and copied test reports. */
final class RunResult {
    final int index;
    final Path runDir;
    /** Build command line; null for runs loaded by {@code rebuild}. */
    final List<String> command;
    /** Null when the run timed out or was loaded by {@code rebuild}. */
    final Integer exitCode;
    final boolean timedOut;
    final double durationSeconds;
    final List<Path> dumpFiles;
    final List<Path> reportDirs;
    /** Per-test outcomes written by dyncg's Gradle test listener; null for Maven runs. */
    final Path testLog;

    RunResult(int index, Path runDir, List<String> command, Integer exitCode, boolean timedOut,
            double durationSeconds, List<Path> dumpFiles, List<Path> reportDirs, Path testLog) {
        this.index = index;
        this.runDir = runDir;
        this.command = command;
        this.exitCode = exitCode;
        this.timedOut = timedOut;
        this.durationSeconds = durationSeconds;
        this.dumpFiles = dumpFiles;
        this.reportDirs = reportDirs;
        this.testLog = testLog;
    }
}
