package dyncg.driver;

import java.nio.file.Files;
import java.nio.file.Path;

/** Build system used to compile and run a checked-out project. */
enum BuildTool {
    AUTO, MAVEN, GRADLE;

    static BuildTool parse(String value) {
        try {
            return valueOf(value.toUpperCase());
        } catch (IllegalArgumentException error) {
            throw new UsageException("unknown build tool '" + value + "' (expected auto, maven or gradle)");
        }
    }

    BuildTool resolve(Path projectDir) {
        if (this != AUTO) {
            return this;
        }
        boolean maven = Files.isRegularFile(projectDir.resolve("pom.xml"));
        boolean gradle = Files.isRegularFile(projectDir.resolve("build.gradle"))
                || Files.isRegularFile(projectDir.resolve("build.gradle.kts"))
                || Files.isRegularFile(projectDir.resolve("settings.gradle"))
                || Files.isRegularFile(projectDir.resolve("settings.gradle.kts"));
        if (maven && gradle) {
            throw new UsageException("both Maven and Gradle files found in " + projectDir
                    + "; pass --build-tool maven or --build-tool gradle");
        }
        if (maven) {
            return MAVEN;
        }
        if (gradle) {
            return GRADLE;
        }
        throw new UsageException("no Maven or Gradle build found in " + projectDir
                + " (expected pom.xml or build.gradle[.kts]); pass --build-tool explicitly if needed");
    }
}
