# dynamic-callgraph (dyncg)

`dyncg` generates a dynamic call graph for an already checked-out Java project by running its tests under an ASM-based Java agent. A separate tool, `dyncg-diff`, compares two generated graphs and reports added and removed runtime call edges.

Repository discovery, cloning, commit selection, and checkout creation are intentionally outside this module. The input to `dyncg version` may be a normal Git checkout, a Git worktree, a copied directory, or an extracted archive.

```text
checked-out project
  → Maven or Gradle tests with -javaagent
  → agent dumps + test reports
  → callgraph.json

before/callgraph.json + after/callgraph.json
  → dyncg-diff
  → diff.json
```

## Layout

Part of the CGSense build (root `pom.xml`); there is no separate build.

| Path | Content |
| --- | --- |
| `agent/` | Java agent, built as `agent/target/dyncg-agent.jar` |
| `driver/` | Version runner, graph builder, and diff implementation, built as `driver/target/dyncg.jar` |
| `bin/dyncg` | Runs the version-analysis CLI |
| `bin/dyncg-diff` | Compares two `callgraph.json` files |
| `examples/version-pair/` | Git-free before/after Maven projects used by the end-to-end test |

## Requirements

- Java 21 or newer to build and run the dyncg driver.
- Maven for Maven projects.
- Gradle, or a project-provided `gradlew`, for Gradle projects.
- A JDK compatible with the project being analyzed, selected with `--java-home`.

The driver and the analyzed project may use different JDKs. For example, dyncg can run on Java 21 while testing a project on Java 17.

If the default `java` is older than 21, set `JAVA_HOME` or `DYNCG_JAVA`:

```bash
JAVA_HOME=/path/to/jdk-21 mvn -B package
DYNCG_JAVA=/path/to/jdk-21/bin/java bin/dyncg --help
```

## Build and test

```bash
mvn -B package
```

This builds the agent and driver, runs the unit tests, and runs `VersionEndToEndTest`. The end-to-end test analyzes both projects in `examples/version-pair/` and verifies their resulting edge diff.

Build without running tests:

```bash
mvn -B package -DskipTests
```

Run only the end-to-end test:

```bash
mvn -B -pl driver -am \
  -Dtest=VersionEndToEndTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  test
```

Analyzed projects always run offline using dependencies already present in the local Maven or Gradle cache. A clean environment needs a network-enabled build outside dyncg to populate that cache first.

## Generate one call graph

```bash
bin/dyncg version \
  --project-dir /path/to/checkout \
  --out outputs/example/before \
  --label before \
  --commit <commit-sha> \
  --include com/example/ \
  --repeats 2 \
  --java-home /path/to/project-jdk
```

`--project-dir` is the source input. The command does not invoke Git, create worktrees, or verify that `--commit` matches the directory. The commit value is output metadata and may be omitted.

Run `bin/dyncg version --help` for the complete option list.

### Main options

| Option | Meaning |
| --- | --- |
| `--project-dir DIR` | Existing Maven or Gradle project directory; required |
| `--out DIR` | Output directory; required |
| `--label NAME` | Graph label, such as `before` or `after`; default `version` |
| `--commit SHA` | Commit identifier recorded as metadata |
| `--build-tool auto\|maven\|gradle` | Build tool; default is automatic detection |
| `--repeats N` | Number of test runs; default `2` |
| `--java-home DIR` | JDK used by the analyzed project's build and tests |

When comparing two versions, use the same `--include`, `--exclude`, `--tests`, `--repeats`, and project JDK settings. Explicit `--include` values are recommended because automatic package detection runs independently for each directory.

## Generate and compare a before/after pair

Run `version` separately on the two directories supplied by the checkout or mining stage:

```bash
bin/dyncg version \
  --project-dir /path/to/parent-checkout \
  --out outputs/example/before \
  --label before \
  --commit <parent-sha> \
  --include com/example/ \
  --repeats 2 \
  --java-home /path/to/project-jdk

bin/dyncg version \
  --project-dir /path/to/child-checkout \
  --out outputs/example/after \
  --label after \
  --commit <child-sha> \
  --include com/example/ \
  --repeats 2 \
  --java-home /path/to/project-jdk
```

Compare the resulting graphs:

```bash
bin/dyncg-diff \
  outputs/example/before/callgraph.json \
  outputs/example/after/callgraph.json \
  --out outputs/example/diff.json
```

The diff compares aggregate caller-to-callee edges by method signature, not by numeric node ID. Node IDs are local to each graph and are not stable across versions.

An edge is:

- **added** when it exists in the after graph but not the before graph;
- **removed** when it exists in the before graph but not the after graph;
- **unchanged** when it exists in both graphs.

The current diff is structural. It does not report invocation-count changes or context-specific changes when an aggregate edge exists in both graphs.

## Build-tool behavior

The driver detects `pom.xml` or Gradle build/settings files in the checkout.

- Maven runs the selected tests under the call-graph agent.
- Gradle prefers the checkout's `gradlew`; otherwise it uses `gradle` or the executable supplied by `--gradle`.
- Gradle runs without the daemon, build cache, or configuration cache so repeated executions actually run the tests.

Buildability filtering is expected to happen upstream. dyncg still invokes the build tool because dynamic calls can only be collected while instrumented tests execute. Build failures and timeouts are recorded in the run output.

## How the agent works

The driver passes the agent through `JAVA_TOOL_OPTIONS`, allowing it to reach Maven Surefire/Failsafe JVMs and Gradle test workers without overwriting a project's own JVM arguments.

The agent:

- registers a `ClassFileTransformer` from `premain`;
- instruments only classes matching the configured include prefixes;
- inserts recorder calls at method entry and exit;
- maintains a shadow call stack per thread;
- records `(test context, caller, callee)` counts;
- attributes annotated JUnit, TestNG, and JUnit 3 test methods to test contexts;
- records fixture contexts separately;
- inherits the current context into newly created threads;
- writes periodic and final `dyncg-*.json` snapshots for each instrumented JVM.

Calls originating outside instrumented code use `<root>` as their caller. Because an edge is recorded inside the called method, virtual and interface calls resolve to the implementation that actually ran.

JDK and runtime-generated classes are excluded. A class that cannot be transformed is loaded unchanged and counted in `stats.transform_failures`.

## Version output

One `version` invocation writes:

```text
<out>/
  callgraph.json
  summary.json
  call_events.tsv.gz              # only with --record-events
  runs/
    0/
      build.log
      dumps/
      surefire-reports/
      gradle-tests.jsonl          # Gradle only
    1/
      ...                         # when --repeats is greater than 1
```

`callgraph.json` uses schema version 1:

- `nodes`: method metadata. Node `0` is `<root>`. A signature resembles `org.jsoup.nodes.Element#text()Ljava/lang/String;`.
- `contexts`: tests, fixtures, and context `0` (`<unattributed>`).
- `edges`: `[context, caller, callee, runs_seen, mean_count, counts_per_run]`.
- `aggregate_edges`: `[caller, callee, runs_seen, mean_count, contexts]`, combined across contexts.
- `runs`: commands, exit status, duration, dump details, and test-report counts.
- `stats`: node, edge, test, instrumentation, and stability summaries.
- `transform_failures`: a bounded sample of classes the agent could not transform.
- `call_events`: event-file metadata when event recording is enabled; otherwise `null`.

An aggregate edge is stable when `runs_seen` equals the number of requested runs.

## Diff output

`dyncg-diff` writes:

```json
{
  "schema_version": 1,
  "before": {
    "file": ".../before/callgraph.json",
    "label": "before",
    "commit": "<parent-sha>"
  },
  "after": {
    "file": ".../after/callgraph.json",
    "label": "after",
    "commit": "<child-sha>"
  },
  "stats": {
    "before_edges": 5,
    "after_edges": 6,
    "added_edges": 2,
    "removed_edges": 1,
    "unchanged_edges": 4
  },
  "added_edges": [
    {
      "caller": "demo.Calculator#calculate()I",
      "callee": "demo.Calculator#afterCalculation()I"
    }
  ],
  "removed_edges": [
    {
      "caller": "demo.Calculator#calculate()I",
      "callee": "demo.Calculator#beforeCalculation()I"
    }
  ]
}
```
