package dyncg.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
//to validate the pipelines mechanics
/// End-to-end test for the `dyncg version` command, which generates a dynamic call graph for an already checked-out project version.

final class VersionEndToEndTest {

    private static final String BEFORE_SHA =
            "1111111111111111111111111111111111111111";

    private static final String AFTER_SHA =
            "2222222222222222222222222222222222222222";

    @Test
    void generatesAndDiffsVersionCallGraphs(
            @TempDir Path tempDir)
            throws Exception {

        Path projectRoot = Path.of(
                System.getProperty("dyncg.project.root"))
                .toAbsolutePath()
                .normalize();

        Path agentJar = Path.of(
                System.getProperty("dyncg.agent.jar"))
                .toAbsolutePath()
                .normalize();

        Path beforeProject = projectRoot.resolve(
                "examples/version-pair/before");

        Path afterProject = projectRoot.resolve(
                "examples/version-pair/after");

        Path beforeOutput = tempDir.resolve("before");
        Path afterOutput = tempDir.resolve("after");
        Path diffOutput = tempDir.resolve("diff.json");

        assertTrue(
                Files.isRegularFile(agentJar),
                "Agent JAR does not exist: " + agentJar);

        assertTrue(
                Files.isRegularFile(
                        beforeProject.resolve("pom.xml")),
                "Before dummy project does not exist");

        assertTrue(
                Files.isRegularFile(
                        afterProject.resolve("pom.xml")),
                "After dummy project does not exist");

        int beforeExit = Main.execute(
                "version",
                "--project-dir", beforeProject.toString(),
                "--out", beforeOutput.toString(),
                "--label", "before",
                "--commit", BEFORE_SHA,
                "--include", "demo/",
                "--repeats", "1",
                "--java-home", System.getProperty("java.home"),
                "--agent-jar", agentJar.toString());

        assertEquals(
                0,
                beforeExit,
                "Before-version analysis failed");

        int afterExit = Main.execute(
                "version",
                "--project-dir", afterProject.toString(),
                "--out", afterOutput.toString(),
                "--label", "after",
                "--commit", AFTER_SHA,
                "--include", "demo/",
                "--repeats", "1",
                "--java-home", System.getProperty("java.home"),
                "--agent-jar", agentJar.toString());

        assertEquals(
                0,
                afterExit,
                "After-version analysis failed");

        Path beforeGraph =
                beforeOutput.resolve("callgraph.json");

        Path afterGraph =
                afterOutput.resolve("callgraph.json");

        assertTrue(Files.isRegularFile(beforeGraph));
        assertTrue(Files.isRegularFile(afterGraph));

        JsonNode beforeJson =
                Json.MAPPER.readTree(beforeGraph.toFile());

        JsonNode afterJson =
                Json.MAPPER.readTree(afterGraph.toFile());

        assertEquals(
                "before",
                beforeJson.path("version_label").asText());

        assertEquals(
                BEFORE_SHA,
                beforeJson.path("commit").asText());

        assertEquals(
                "after",
                afterJson.path("version_label").asText());

        assertEquals(
                AFTER_SHA,
                afterJson.path("commit").asText());

        assertEquals(
                1,
                beforeJson.path("stats")
                        .path("tests")
                        .asInt());

        assertEquals(
                1,
                afterJson.path("stats")
                        .path("tests")
                        .asInt());

        assertEquals(
                0,
                beforeJson.path("stats")
                        .path("runs_without_tests")
                        .asInt());

        assertEquals(
                0,
                afterJson.path("stats")
                        .path("runs_without_tests")
                        .asInt());

        Map<String, Object> diff =
                CallGraphDiffMain.buildDiff(
                        beforeGraph,
                        afterGraph);

        Json.writePretty(diffOutput, diff);

        assertTrue(Files.isRegularFile(diffOutput));

        JsonNode diffJson =
                Json.MAPPER.valueToTree(diff);

        assertEquals(
                2,
                diffJson.path("stats")
                        .path("added_edges")
                        .asInt());

        assertEquals(
                1,
                diffJson.path("stats")
                        .path("removed_edges")
                        .asInt());

        assertTrue(
                containsEdge(
                        diffJson.path("added_edges"),
                        "demo.Calculator#calculate()I",
                        "demo.Calculator#afterCalculation()I"));

        assertTrue(
                containsEdge(
                        diffJson.path("added_edges"),
                        "demo.Calculator#afterCalculation()I",
                        "demo.Calculator#normalize(I)I"));

        assertTrue(
                containsEdge(
                        diffJson.path("removed_edges"),
                        "demo.Calculator#calculate()I",
                        "demo.Calculator#beforeCalculation()I"));
    }

    private static boolean containsEdge(
            JsonNode edges,
            String caller,
            String callee) {

        for (JsonNode edge : edges) {
            if (caller.equals(edge.path("caller").asText())
                    && callee.equals(
                            edge.path("callee").asText())) {
                return true;
            }
        }

        return false;
    }
}
