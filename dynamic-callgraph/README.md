# dynamic-callgraph (dyncg)

Generates **dynamic call graphs** for the before and after versions of a commit pair by running
the project's tests under an ASM-based Java agent.

This module covers running the tests and building the call graphs. It assumes another
module has already checked that both commits build. Comparing the two graphs is left to a
later module; this one writes the per-version graphs and a test-outcome comparison.

```
repo + (before, after)
  ├─ git worktree add (each commit)
  ├─ Maven or Gradle tests with -javaagent (N repeats per version)
  │     agent: one JSON dump per JVM (method table, test contexts, edge counts)
  │     surefire: per-test outcomes (XML)
  ├─ merge dumps + reports → before/callgraph.json, after/callgraph.json
  └─ classify test outcomes  → tests.json (pass_pass, fail_pass, pass_fail, ...)
```

## Layout

| Path | Content |
| --- | --- |
| `pom.xml` | Parent build: `agent` first, then `driver` |
| `agent/` | Java agent (Java 8 bytecode, ASM 9.10.1 shaded into `dyncg.shaded.asm`) → `agent/target/dyncg-agent.jar` |
| `driver/` | Driver and CLI (Java 21, Jackson, commons-cli) → `driver/target/dyncg.jar` |
| `driver/src/test/fixture/` | Two-commit sample project used by the end-to-end test |
| `bin/dyncg` | Wrapper for `java -jar driver/target/dyncg.jar` |
| `outputs/` | Local runs (ignored by git) |

## Build and test

```bash
mvn -B package                 # builds both jars and runs the driver's tests
mvn -B package -DskipTests     # build only
```

The driver's tests include an end-to-end run of `dyncg pair` on the fixture project, which needs
`git`, `mvn` and network access or a warm `~/.m2`. Set `DYNCG_JAVA_HOME` to run the fixture's
tests on another JDK and `DYNCG_KEEP=1` to keep its output in `/tmp/dyncg-e2e-*`.
The same checks run once with Maven and once with Gradle. The Gradle run needs Gradle:
`DYNCG_GRADLE=/path/to/bin/gradle`, or `gradle` on the `PATH`; without it the Gradle tests are
skipped and the build prints `SKIPPING GradleEndToEndTest`.

Building dyncg and running the driver need Java 21 or newer, the same level as CGSense (if `java`
on the `PATH` is older, set `JAVA_HOME`, or `DYNCG_JAVA` for `bin/dyncg`); the projects under
test can use any JDK from 8 on, selected
with `--java-home`. The agent reads class files up to Java 27 (ASM 9.10.1). The end-to-end checks
pass with the tested project on JDK 8, 17, 21, 25, 26 and 27 (Maven) and 17 and 21 (Gradle 8.10,
which cannot itself run on Java 25 or newer; projects bring their own Gradle through `gradlew`).
Classes the agent cannot read are loaded unchanged and counted in `stats.transform_failures`;
if tests ran but no calls were recorded, the driver prints a warning.

Maven and Gradle projects are supported. The driver detects `pom.xml` or Gradle build/settings
files in each checkout. Gradle builds prefer the checkout's `gradlew`; otherwise they use `gradle`
(`--gradle` sets the executable). Gradle runs `--no-daemon --continue --no-build-cache cleanTest test`,
so every repeat really executes the tests (never `UP-TO-DATE` or `FROM-CACHE`) and a project's
failing tests do not stop the other projects of a multi-project build.

Gradle's XML reports name a parameterized test case only by its display name (for example
`[1] List DataLoader`), so the method it belongs to is lost. dyncg therefore passes Gradle an init
script (`--init-script`) with a test listener that writes each case's class, method and outcome to
`runs/<i>/gradle-tests.jsonl`, and reads test outcomes from there; the XML reports are still copied.
The configuration cache is turned off for these runs, because it does not allow such listeners.

## Usage

Commit pair (creates and removes git worktrees; the repository may be bare):

```bash
bin/dyncg pair --repo /path/to/repo.git --before <parent-sha> --after <fix-sha> \
    --out outputs/<name> --java-home ~/.sdkman/candidates/java/17.0.10-tem
```

One version that is already checked out:

```bash
bin/dyncg version --project-dir /path/to/checkout --out outputs/<name> --label before
```

Rebuild `callgraph.json` from the raw outputs of earlier runs without running the tests again,
e.g. after changing the graph builder (`--project-dir` is a checkout of the same commit, used
for node origins):

```bash
bin/dyncg rebuild --runs-dir outputs/<name>/before/runs --project-dir /path/to/checkout \
    --include org/x/ --label before --commit <sha> --out outputs/<name>/before-rebuilt
```

`bin/dyncg <command> --help` lists all options.

Main options:

| Option | Meaning |
| --- | --- |
| `--build-tool auto|maven|gradle` | Build tool selection (default: detect it independently in each checkout) |
| `--include org/x/` | Package prefixes to instrument (repeatable). Detected from the sources by default; the same list is used for both versions |
| `--exclude org/x/gen/` | Prefixes to skip |
| `--tests 'FooTest,Bar*'` | Test patterns, applied as Maven `-Dtest` or repeated Gradle `--tests` selections |
| `--repeats N` | Test runs per version (default 2), used to detect unstable edges and flaky tests |
| `--record-events` | Also log individual invocations in order, with sequence number and thread (see [Call events](#call-events)) |
| `--max-events N` | With `--record-events`: invocations logged per test JVM before logging stops (default 5,000,000) |
| `--java-home`, `--before-java-home`, `--after-java-home` | JDK used to run the build and tests |
| `--test-timeout S` | Surefire forked-JVM timeout (Maven only) |
| `--timeout S` | Limit for one build invocation; the process and descendants are killed after it |
| `--offline` | Use Maven or Gradle offline mode |
| `--maven-arg=X`, `--gradle-arg=X` | Build-tool-specific extra arguments (repeatable; use `=` for values starting with `-`) |
| `--keep-worktrees` | Keep the checkouts for inspection |

## How the agent works

`-javaagent:dyncg-agent.jar=include=org/x/:org/y/,out=/abs/dir,flush=60[,exclude=...]`

* `premain` registers a `ClassFileTransformer`. Only classes matching `include` are rewritten
  (`java/`, `javax/`, `jdk/`, `sun/`, `com/sun/` and runtime-generated proxies or mocks never are).
* Every method gets `Recorder.enter(id)` at entry (after `super()`/`this()` in constructors),
  `Recorder.exit()` before each return, and a catch-all handler that calls `Recorder.exit()` and
  rethrows. The handler is added last to the exception table, so the method's own handlers
  still take precedence.
* `Recorder` keeps a shadow stack per thread. On entry it counts the edge
  `(context, caller = top of stack, callee)`; `<root>` is the caller when the method is called
  from code that is not instrumented (JUnit, the JDK, a new thread). Because the edge is recorded
  inside the called method, virtual and interface calls are resolved to the method that actually ran.
* **Test contexts.** Methods annotated with JUnit 4/5 or TestNG test annotations (including
  composed annotations such as a custom `@MultiLocaleTest` that is itself annotated with
  `@ParameterizedTest`, which are resolved from the annotation's class file), and JUnit 3 `test*`
  methods, call `enterTest`, which makes them the thread's current context
  until they return. Fixture methods (`@Before`, `@BeforeEach`, `setUp`, …) get their own
  fixture contexts. The context is named after the runtime class, so inherited test methods
  are attributed to the class that ran them. New threads inherit their creator's context.
* Bridge methods and `access$NNN` accessors are not instrumented, so edges point at the real
  target. Lambda bodies (`lambda$m$0`) are instrumented and appear as normal nodes.
* Frames are recomputed (`COMPUTE_FRAMES`) using a class hierarchy read from class-file
  resources, never by loading classes. Pre-Java-6 class files go through `JSRInlinerAdapter` first.
  A class that fails to transform is loaded unchanged and reported in the dump.
* The agent jar is placed on the bootstrap class path (`Boot-Class-Path`), so
  instrumented classes in any class loader can call `Recorder`.
* Each JVM writes `dyncg-<pid>-<start>.json`: periodically (`"complete": false`), so a crashed
  or killed JVM still leaves data, and finally from a shutdown hook (`"complete": true`).

### Attaching the agent

The driver passes the agent through `JAVA_TOOL_OPTIONS`, not Maven `-DargLine`, because many
projects (jsoup among them) set `<argLine>` in the pom, which makes `-DargLine` ineffective, and
overriding it would drop the project's own JVM flags. This also reaches Gradle test workers. Every
JVM started by the build therefore loads the agent. Dumps are classified by the JVM's main command:
Surefire/Failsafe JVMs and Gradle Test Executors (`test-jvm`) are used; the Maven or Gradle build
JVM's dump is used only when no test JVM produced one (tests run in-process); other JVMs (for
example ones started by tests) are kept as `other`.

## Failing tests

Failing tests are kept. The exception passes through the catch-all handlers, which keeps the
stack balanced, and edges recorded up to the failure are attributed to that test. The
driver runs Maven with `-Dmaven.test.failure.ignore=true` and Gradle with `--continue` so all
tests run, reads the outcome of every test from the Surefire/Failsafe or Gradle XML reports, and stores it on the test's context. A
fail→pass test's before graph is truncated at the point of failure; the failure type, message
and stack trace are stored so the comparison step can account for this.

## Output

```
<out>/
  pair.json                 inputs, resolved commits, include prefixes, stats, outcome counts
  tests.json                per test: before status, after status, category
  before/  after/
    callgraph.json          the graph (format below)
    summary.json            stats only
    runs/<i>/build.log      full Maven or Gradle output, with the exact command
    call_events.tsv.gz      individual invocations (only with --record-events)
    runs/<i>/dumps/         raw agent dumps (*.json; *.events binary event logs)
    runs/<i>/surefire-reports/  Surefire/Failsafe or Gradle XML reports
    runs/<i>/gradle-tests.jsonl Gradle only: per-test outcomes from dyncg's listener
```

`callgraph.json` (schema version 1):

* `nodes`: `{id, signature, class, method, descriptor, static, synthetic, origin}`; id 0 is
  `<root>`. Signatures look like `org.jsoup.nodes.Element#text()Ljava/lang/String;`.
  `origin` is `main`, `test` or `unknown`, from the location of the source file.
* `contexts`: id 0 is `<unattributed>` (calls outside any test or fixture, e.g. JUnit creating
  the test instance). Tests have `status` (`passed`, `failed`, `error`, `skipped`, `flaky`,
  `inconsistent`, `not_reported`, `not_run`), `run_statuses`, `invocations` (parameterized
  cases), `failure` and `recorded_by_agent`. Fixtures have `kind: "fixture"`.
* `edges`: rows `[context, caller, callee, runs_seen, mean_count, counts_per_run]`.
* `aggregate_edges`: the same over all contexts: `[caller, callee, runs_seen, mean_count, contexts]`.
  An edge is stable when `runs_seen` equals the number of runs.
* `call_events`: `null` without `--record-events`; otherwise a summary of `call_events.tsv.gz`:
  `rows`, `max_per_jvm`, `jvms`, `jvms_at_limit`, `unmapped` (events whose ids were not in the
  JVM's last snapshot, possible when a JVM was killed), `missing` (events the agent counted but
  never wrote, e.g. buffers lost in a crash) and `errors`.
* `runs`, `stats`, `transform_failures`: provenance and diagnostics. `stats.runs_without_tests`
  counts runs that produced no test results at all (usually a build failure; the driver also
  prints a warning that points to the run's `build.log`). In a graph made by `rebuild`,
  `runs[].command`, `exit_code` and `duration_s` are not known and are `null`/`0`.

### Call events

`edges` count invocations: two identical calls are one edge with count 2. With
`--record-events`, each invocation is also logged as its own row of `call_events.tsv.gz`
(gzip-compressed, tab-separated, with a header line):

| Column | Meaning |
| --- | --- |
| `run`, `jvm` | Run index and the JVM's index in `runs[run].dumps` |
| `sequence` | 1, 2, ... per JVM, in the order the invocations started (rows are sorted by it) |
| `thread` | Java thread id |
| `context`, `caller`, `callee` | Ids from `contexts` and `nodes` in `callgraph.json` |

A test suite can make billions of calls (jsoup: about 1.7 billion per run), so logging stops after
`--max-events` invocations per test JVM; `call_events.jvms_at_limit` says when that happened. The
edges and counts are always complete. The agent streams events to a binary file through small
per-thread buffers (28 bytes per event, so the default limit is at most 140 MB per JVM) and keeps
nothing else in memory. To log the calls of particular tests completely, combine the option
with `--tests`.

Test outcome categories in `tests.json`: `pass_pass`, `fail_pass`, `pass_fail`, `fail_fail`,
`unstable` (flaky in either version), `skipped`, `before_only`, `after_only`, `not_reported`.
