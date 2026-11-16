package cgsense.tester.driver;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/** One JVM's dump as written by the agent's Dumper. */
/** AgentDump is the Java shape of one agent dump file. Jackson fills it from JSON: matching names directly, snake_case keys through @JsonProperty, unknown keys ignored, missing keys left at empty defaults. GraphBuilder reads its methods, contexts and edges to build the call graph. */

/** The following is a description of the flow of data through the system:
test JVM:  Recorder (in memory) ─► Dumper.write ─► runs/0/dumps/dyncg-209482-....json
                                                            │
driver:    GraphBuilder.loadRun ─► Json.MAPPER.readValue(file, AgentDump.class)
                                                            ▼
                                   AgentDump { complete, pid, methods, contexts, edges, ... }
                                                            │
           GraphBuilder.addDump ─► translate ids → names → merge into the version's graph
*/


@JsonIgnoreProperties(ignoreUnknown = true)         //If the agent writes new fields in the future, we don't want to break older dyncg versions. So we ignore unknown fields instead of throwing an exception.
final class AgentDump {
    public Boolean complete;  //true if this is the final dump at JVM exit, false if it's a periodic snapshot while the JVM runs
    public String pid;          //the process id of the JVM that wrote this dump
    public String command = ""; //the command that was used to start the JVM

    @JsonProperty("java_version")   //the Java version of the JVM that wrote this dump
    public String javaVersion;      //the Java version of the JVM that wrote this dump

    @JsonProperty("classes_instrumented")   //the number of classes that were instrumented by the agent in this JVM
    public Integer classesInstrumented;     //

    @JsonProperty("transform_failure_count")
    public long transformFailureCount;      //the number of classes that failed to be instrumented by the agent in this JVM

    @JsonProperty("overflowed_edges")   
    public long overflowedEdges;        //the number of edges that were dropped because the agent's internal buffer was full and could not record all method calls

    /** Rows of [class, error]. */
    @JsonProperty("transform_failures")
    public List<List<String>> transformFailures = List.of();    //the list of classes that failed to be instrumented by the agent, along with the error message for each failure

    /** Rows of [id, owner, name, descriptor, access]. */
    public List<JsonNode> methods = List.of();  //the list of methods that were recorded by the agent, with each method represented as a JSON array containing its id, owner class, name, descriptor, and access flags

    /** Rows of [id, class, method, kind]. */
    public List<JsonNode> contexts = List.of(); //the list of contexts that were recorded by the agent, with each context represented as a JSON array containing its id, class, method, and kind (e.g., static or instance)

    /** Rows of [context, caller, callee, count]. */
    public long[][] edges = new long[0][];      //the list of edges that were recorded by the agent, with each edge represented as a JSON array containing the context id, caller method id, callee method id, and the number of times this call was made
}
