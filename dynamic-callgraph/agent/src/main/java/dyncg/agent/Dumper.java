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
 * Writes the recorded call graph of this JVM as JSON: periodically (so a crashed or killed JVM
 * still leaves data, marked {@code "complete": false}) and once more from a shutdown hook.
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
        pid = processId();
        String baseName = "dyncg-" + pid + "-" + startMillis;
        target = new File(config.outDir, baseName + ".json");
        EventLog.start(config, baseName);

        Thread hook = new Thread(new Runnable() {
            @Override
            public void run() {
                write(true);
            }
        }, "dyncg-shutdown-dump");
        Runtime.getRuntime().addShutdownHook(hook);

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
        synchronized (LOCK) {
            try {
                // Flush events first, so every logged event's edge is in the snapshot taken next.
                EventLog.flushAll();
                Recorder.Snapshot snap = Recorder.snapshot();
                if (snap.methods.length <= 1 && snap.transformFailureCount == 0) {
                    return; // no project class was loaded in this JVM
                }
                // Project classes that all failed to transform still produce a dump, so the failures are reported.
                if (!config.outDir.isDirectory() && !config.outDir.mkdirs() && !config.outDir.isDirectory()) {
                    System.err.println("[dyncg] cannot create " + config.outDir);
                    return;
                }
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
        out.write(",\"events_file\":");
        if (EventLog.fileName() == null) {
            out.write("null");
        } else {
            string(out, EventLog.fileName());
        }
        out.write(",\"events_max\":" + EventLog.max());
        out.write(",\"events_recorded\":" + EventLog.recorded());
        out.write(",\"events_limit_reached\":" + (EventLog.enabled() && EventLog.limitReached()));
        out.write(",\"events_error\":");
        if (EventLog.error() == null) {
            out.write("null");
        } else {
            string(out, EventLog.error());
        }

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
