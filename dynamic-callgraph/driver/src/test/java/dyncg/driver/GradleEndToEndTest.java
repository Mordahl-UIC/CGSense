package dyncg.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * The end-to-end checks with the fixture built by Gradle (src/test/fixture/gradle replaces pom.xml).
 * Needs a Gradle installation: DYNCG_GRADLE=/path/to/bin/gradle, or gradle on the PATH; the
 * tests are skipped otherwise.
 */
class GradleEndToEndTest extends EndToEndChecks {
    private static final String GRADLE = findGradle();

    @Override
    void useBuild(Path repo) throws IOException {
        if (GRADLE == null) {
            // Surefire reports a class skipped in @BeforeAll as "Tests run: 0", so say why.
            System.out.println("[dyncg-test] SKIPPING GradleEndToEndTest: Gradle not found "
                    + "(set DYNCG_GRADLE=/path/to/bin/gradle or put gradle on the PATH)");
        }
        Assumptions.assumeTrue(GRADLE != null, "Gradle not found: set DYNCG_GRADLE or put gradle on the PATH");
        Files.delete(repo.resolve("pom.xml"));
        FileUtil.copyTree(FIXTURE.resolve("gradle"), repo);
    }

    @Override
    List<String> buildArgs() {
        return List.of("--build-tool", "gradle", "--gradle", GRADLE);
    }

    @Test
    void outcomesComeFromTheListener() throws IOException {
        for (String label : List.of("before", "after")) {
            for (String run : List.of("0", "1")) {
                Path log = out.resolve(label).resolve("runs").resolve(run).resolve(TestRunner.GRADLE_TEST_LOG);
                assertEquals(6, Files.readAllLines(log).stream().filter(l -> !l.isBlank()).count(), log.toString());
            }
        }
    }

    private static String findGradle() {
        String configured = System.getenv("DYNCG_GRADLE");
        if (configured != null && !configured.isEmpty()) {
            return configured;
        }
        for (String dir : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
            Path candidate = Paths.get(dir, "gradle");
            if (Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }
        return null;
    }
}
