package cgsense.tester.driver;

import java.nio.file.Files;
import java.nio.file.Path;
/*BuildTool.java says which build system the analyzed project uses: Maven or Gradle. 
It can turn the --build-tool text into a value, and it can detect the build system from the files in the project folder.*/
/** Build system used to compile and run a checked-out project. */
/*parse turns the user's text into AUTO, MAVEN or GRADLE (with a clear error for typos),
 and resolve turns AUTO into the real build tool by checking for pom.xml or Gradle files in the project folder.*/


/*--build-tool auto  (default)
   → RunOptions.toOptions:  BuildTool.parse("auto")        → AUTO
   → VersionRunner:         AUTO.resolve(projectDir)        → MAVEN (pom.xml found)
                            options.withBuildTool(MAVEN)
   → TestRunner:            MAVEN → mvn -B test ...
   → SurefireReports:       MAVEN → target/surefire-reports
   */

// The build tool can be AUTO, MAVEN, or GRADLE.
enum BuildTool {
  AUTO,
  MAVEN,
  GRADLE;

//parse(String value) converts the string from the command line into a BuildTool enum value.Called from: RunOptions.toOptions() (line 106) when building TestRunOptions.
    static BuildTool parse(String value) { 
        try {
            return valueOf(value.toUpperCase());
        } catch (IllegalArgumentException error) {
            throw new UsageException("unknown build tool '" + value + "' (expected auto, maven or gradle)");
        }
    }

//resolve(Path projectDir) detects the build system from the files in the project folder.Called from: VersionRunner.java:43, once, before the first test run:
    BuildTool resolve(Path projectDir) {
        if (this != AUTO) {     //If the build tool is not AUTO, we just return the specified build tool (MAVEN or GRADLE). We don't need to detect anything.
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
