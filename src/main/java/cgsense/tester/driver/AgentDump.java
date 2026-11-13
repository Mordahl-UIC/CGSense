package cgsense.tester.driver;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/** One JVM's dump as written by the agent's Dumper. */
@JsonIgnoreProperties(ignoreUnknown = true)
final class AgentDump {
    public Boolean complete;
    public String pid;
    public String command = "";

    @JsonProperty("java_version")
    public String javaVersion;

    @JsonProperty("classes_instrumented")
    public Integer classesInstrumented;

    @JsonProperty("transform_failure_count")
    public long transformFailureCount;

    @JsonProperty("overflowed_edges")
    public long overflowedEdges;

    /** Rows of [class, error]. */
    @JsonProperty("transform_failures")
    public List<List<String>> transformFailures = List.of();

    /** Rows of [id, owner, name, descriptor, access]. */
    public List<JsonNode> methods = List.of();

    /** Rows of [id, class, method, kind]. */
    public List<JsonNode> contexts = List.of();

    /** Rows of [context, caller, callee, count]. */
    public long[][] edges = new long[0][];
}
