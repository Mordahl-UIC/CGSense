package dyncg.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

/** The end-to-end checks with the fixture built by Maven (its own pom.xml). */
class MavenEndToEndTest extends EndToEndChecks {
    @Override
    void useBuild(Path repo) {
        // the fixture is a Maven project already
    }

    @Override
    List<String> buildArgs() {
        return List.of("--build-tool", "maven");
    }

    /**
     * Compiles the fixture for the newest class-file version the test JDK supports (its own feature
     * version) and checks that the agent can still read and instrument it.
     */
    @Test
    void instrumentsClassesCompiledForTheTestJdk() throws Exception {
        String javaHome = System.getenv().getOrDefault("DYNCG_JAVA_HOME", System.getProperty("java.home"));
        int feature = featureVersion(Paths.get(javaHome));
        Path project = Files.createTempDirectory("dyncg-classversion-");
        try {
            FileUtil.copyTree(FIXTURE.resolve("v1"), project);
            Path out = project.resolve("dyncg-out");
            List<String> argv = new ArrayList<>(List.of("version", "--project-dir", project.toString(),
                    "--out", out.toString(), "--repeats", "1", "--java-home", javaHome,
                    "--maven-arg=-Dmaven.compiler.source=" + feature, "--maven-arg=-Dmaven.compiler.target=" + feature));
            argv.addAll(buildArgs());
            assertEquals(0, Main.execute(argv.toArray(new String[0])));
            JsonNode stats = Json.MAPPER.readTree(out.resolve("callgraph.json").toFile()).get("stats");
            assertEquals(0, stats.get("transform_failures").asLong(), "classes for Java " + feature + " not instrumented");
            assertTrue(stats.get("aggregate_edges").asLong() > 20, "no calls recorded for Java " + feature + " classes");
        } finally {
            FileUtil.deleteQuietly(project);
        }
    }

    /** The feature version (8, 11, 25, ...) of a JDK, from its {@code release} file. */
    private static int featureVersion(Path javaHome) throws IOException {
        Matcher m = Pattern.compile("JAVA_VERSION=\"(?:1\\.)?(\\d+)").matcher(Files.readString(javaHome.resolve("release")));
        if (!m.find()) {
            throw new IOException("no JAVA_VERSION in " + javaHome.resolve("release"));
        }
        return Integer.parseInt(m.group(1));
    }
}
