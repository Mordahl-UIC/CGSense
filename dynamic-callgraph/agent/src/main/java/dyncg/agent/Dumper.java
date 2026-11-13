package dyncg.agent;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
Dumper picks a unique file per JVM and saves Recorder's data as JSON: periodically as complete: false, so a killed JVM still leaves data, 
and once at exit as complete: true. It always writes to a temp file and renames it, so the dump on disk is never half-written, and it never 
lets a write failure affect the tests.
 */

/*
 * Who calls Dumper, and when:
 *
 *   Caller                                     Method                When
 *   -----------------------------------------  --------------------  --------------------------------------
 *   Recorder.start(config)                     Dumper.start(config)  Once, at JVM startup
 *   Thread "dyncg-periodic-dump" (own thread)  write(false)          Every flushSeconds while the JVM runs
 *   Thread "dyncg-shutdown-dump" (shutdown     write(true)           Once, when the JVM exits
 *     hook, registered in start())
 */

final class Dumper {
    private static final Object LOCK = new Object();
    private static AgentConfig config;
    private static File target;
    private static String pid;
    private static long startMillis;

    private Dumper() {
    }

    static void start(AgentConfig agentConfig) {
        config = agentConfig;
        startMillis = System.currentTimeMillis();
        pid = processId();//One test run can start several JVMs that all write to the same dumps/ folder: forked test JVMs, Gradle workers, sometimes the build tool's own JVM. Process id plus start time gives each one its own file, so they don't overwrite each other.
        String baseName = "dyncg-" + pid + "-" + startMillis;
        target = new File(config.outDir, baseName + ".json");


        /*A shutdown hook is a thread the JVM runs just before it exits. It runs when main finishes, on System.exit(), or on Ctrl-C / a normal kill signal.
        It calls write(true). The true means complete: this is the final, full data.*/
        Thread hook = new Thread(new Runnable() {
            @Override
            public void run() {
                write(true);
            }
        }, "dyncg-shutdown-dump");
        Runtime.getRuntime().addShutdownHook(hook);

        /*Every flushSeconds (default 60), it saves a snapshot with complete = false. If the test JVM is later killed (for example by a test timeout), the last snapshot is still on disk. You lose at most a minute of data instead of all of it.
        setDaemon(true): a daemon thread doesn't keep the JVM alive. Without this, the endless while (true) loop would stop the test JVM from ever exiting.
        InterruptedException → return: if the thread is interrupted, it stops quietly.
        With --flush 0, there's no periodic writer, only the final write.*/
        if (config.flushSeconds > 0) {
            Thread flusher = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (true) {
                        try {
                            Thread.sleep(config.flushSeconds * 1000L);
                        } catch (InterruptedException e) {
                            return;
                        }
                        write(false);
                    }
                }
            }, "dyncg-periodic-dump");
            flusher.setDaemon(true);
            flusher.start();
        }
    }

    static void write(boolean complete) {
        synchronized (LOCK) {           //This makes sure only one write happens at a time. Without it, the periodic writer and the shutdown hook could both write at the moment of exit.
            try {
                Recorder.Snapshot snap = Recorder.snapshot();  //This copies Recorder's current tables (methods, contexts, edge counts, failure counts) into plain arrays. 
                                                                    // Writing then works on a frozen copy, while the tests keep running and changing the live data.
                if (snap.methods.length <= 1 && snap.transformFailureCount == 0) {
                    return; // no project class was loaded in this JVM
                }
                // Project classes that all failed to transform still produce a dump, so the failures are reported.
                if (!config.outDir.isDirectory() && !config.outDir.mkdirs() && !config.outDir.isDirectory()) {
                    System.err.println("[dyncg] cannot create " + config.outDir);
                    return;
                }
                //The JSON is first written to dyncg-....json.tmp, through a 64 KB buffer (1 << 16 = 65536) for speed. Then the temp file is renamed over the real file in one atomic step: it either fully happens or doesn't happen at all.
                File tmp = new File(target.getPath() + ".tmp");
                Writer out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(tmp),
                        StandardCharsets.UTF_8), 1 << 16);
                try {
                    writeJson(out, snap, complete);
                } finally {
                    out.close();
                }
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (Throwable t) {
                System.err.println("[dyncg] failed to write call graph: " + t);
            }
        }
    }

    private static void writeJson(Writer out, Recorder.Snapshot snap, boolean complete) throws IOException {
        out.write("{\"format\":\"dyncg-dump\",\"version\":1");
        out.write(",\"complete\":" + complete);
        out.write(",\"pid\":");
        string(out, pid);
        out.write(",\"command\":");
        string(out, System.getProperty("sun.java.command", ""));
        out.write(",\"java_version\":");
        string(out, System.getProperty("java.version", ""));
        out.write(",\"start_time_ms\":" + startMillis);
        out.write(",\"write_time_ms\":" + System.currentTimeMillis());
        out.write(",\"classes_instrumented\":" + snap.classesInstrumented);
        out.write(",\"transform_failure_count\":" + snap.transformFailureCount);
        out.write(",\"overflowed_edges\":" + snap.overflowedEdges);

        out.write(",\"transform_failures\":[");
        for (int i = 0; i < snap.transformFailures.length; i++) {
            if (i > 0) {
                out.write(',');
            }
            out.write('[');
            string(out, snap.transformFailures[i][0]);
            out.write(',');
            string(out, snap.transformFailures[i][1]);
            out.write(']');
        }
        out.write(']');

        out.write(",\"method_columns\":[\"id\",\"owner\",\"name\",\"descriptor\",\"access\"]");
        out.write(",\"methods\":[");
        for (int i = 0; i < snap.methods.length; i++) {
            if (i > 0) {
                out.write(',');
            }
            String[] m = snap.methods[i];
            out.write("\n[" + i + ',');
            string(out, m[0]);
            out.write(',');
            string(out, m[1]);
            out.write(',');
            string(out, m[2]);
            out.write("," + snap.methodAccess[i] + ']');
        }
        out.write(']');

        out.write(",\"context_columns\":[\"id\",\"class\",\"method\",\"kind\"]");
        out.write(",\"contexts\":[");
        for (int i = 0; i < snap.contexts.length; i++) {
            if (i > 0) {
                out.write(',');
            }
            String[] c = snap.contexts[i];
            out.write("\n[" + i + ',');
            string(out, c[0]);
            out.write(',');
            string(out, c[1]);
            out.write("," + c[2] + ']');
        }
        out.write(']');

        out.write(",\"edge_columns\":[\"context\",\"caller\",\"callee\",\"count\"]");
        out.write(",\"edges\":[");
        for (int i = 0; i < snap.edgeKeys.length; i++) {
            if (i > 0) {
                out.write(',');
            }
            long key = snap.edgeKeys[i];
            out.write("\n[" + Recorder.edgeContext(key) + ',' + Recorder.edgeCaller(key) + ','
                    + Recorder.edgeCallee(key) + ',' + snap.edgeCounts[i] + ']');
        }
        out.write("]}\n");
    }

    private static void string(Writer out, String value) throws IOException {
        out.write('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    out.write("\\\"");
                    break;
                case '\\':
                    out.write("\\\\");
                    break;
                case '\n':
                    out.write("\\n");
                    break;
                case '\r':
                    out.write("\\r");
                    break;
                case '\t':
                    out.write("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        out.write(String.format("\\u%04x", (int) c));
                    } else {
                        out.write(c);
                    }
            }
        }
        out.write('"');
    }

    private static String processId() {
        String name = ManagementFactory.getRuntimeMXBean().getName();
        int at = name.indexOf('@');
        return at > 0 ? name.substring(0, at) : name;
    }
}
