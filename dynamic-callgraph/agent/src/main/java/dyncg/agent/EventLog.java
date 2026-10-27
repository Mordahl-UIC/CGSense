package dyncg.agent;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Optional log of individual invocations, streamed to a binary file instead of being kept in memory.
 *
 * <p>Each thread fills its own small buffer; full buffers are appended to the file. At most
 * {@code max} events are recorded per JVM; later invocations are only counted as edges. Every
 * record is {@link #RECORD_BYTES} bytes, big-endian: sequence (long), thread id (long),
 * context (int), caller (int), callee (int). Sequence numbers are 1, 2, ... in the order the
 * invocations started, so a reader can restore the global order.
 */
final class EventLog {
    static final int RECORD_BYTES = 28;
    private static final int BUFFER_EVENTS = 1024;

    private static volatile boolean enabled;
    private static volatile boolean full;
    private static long max;
    private static File file;
    private static DataOutputStream out;
    private static final Object WRITE_LOCK = new Object();
    private static final AtomicLong SEQUENCE = new AtomicLong();
    /** Buffers of all threads that recorded events, so they can be flushed from other threads. */
    private static final ConcurrentLinkedQueue<Buffer> BUFFERS = new ConcurrentLinkedQueue<Buffer>();
    private static volatile String error;

    private EventLog() {
    }

    static void start(AgentConfig config, String baseName) {
        if (config.maxEvents <= 0) {
            return;
        }
        max = config.maxEvents;
        file = new File(config.outDir, baseName + ".events");
        enabled = true;
    }

    static boolean enabled() {
        return enabled;
    }

    /** Called on every invocation while enabled; must stay cheap once the limit is reached. */
    static void record(Buffer buffer, int context, int caller, int callee) {
        if (full) {
            return;
        }
        long sequence = SEQUENCE.incrementAndGet();
        if (sequence > max) {
            full = true;
            return;
        }
        buffer.add(sequence, context, caller, callee);
    }

    /** A new buffer for the calling thread, registered for flushing. */
    static Buffer newBuffer() {
        Buffer buffer = new Buffer(Thread.currentThread().getId());
        BUFFERS.add(buffer);
        return buffer;
    }

    /** Writes all buffered events and flushes the file (periodic snapshots and shutdown). */
    static void flushAll() {
        if (!enabled) {
            return;
        }
        for (Buffer buffer : BUFFERS) {
            buffer.drain();
        }
        synchronized (WRITE_LOCK) {
            try {
                if (out != null) {
                    out.flush();
                }
            } catch (IOException e) {
                fail(e);
            }
        }
    }

    static String fileName() {
        return enabled ? file.getName() : null;
    }

    static long max() {
        return enabled ? max : 0;
    }

    static boolean limitReached() {
        return full || SEQUENCE.get() > max;
    }

    /** Highest sequence number handed out, i.e. the number of events that should be in the file. */
    static long recorded() {
        return Math.min(SEQUENCE.get(), max);
    }

    static String error() {
        return error;
    }

    private static void write(long[] sequences, long thread, int[] contexts, int[] callers, int[] callees, int n) {
        synchronized (WRITE_LOCK) {
            if (error != null) {
                return;
            }
            try {
                if (out == null) {
                    File dir = file.getParentFile();
                    if (dir != null && !dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
                        throw new IOException("cannot create " + dir);
                    }
                    out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file), 1 << 16));
                }
                for (int i = 0; i < n; i++) {
                    out.writeLong(sequences[i]);
                    out.writeLong(thread);
                    out.writeInt(contexts[i]);
                    out.writeInt(callers[i]);
                    out.writeInt(callees[i]);
                }
            } catch (IOException e) {
                fail(e);
            }
        }
    }

    private static void fail(IOException e) {
        if (error == null) {
            error = String.valueOf(e);
            full = true; // stop recording; the edges are unaffected
            System.err.println("[dyncg] event log disabled: " + e);
        }
    }

    /** Per-thread event buffer. Filled by its thread; drained by it or by the flusher. */
    static final class Buffer {
        private final long thread;
        private final long[] sequences = new long[BUFFER_EVENTS];
        private final int[] contexts = new int[BUFFER_EVENTS];
        private final int[] callers = new int[BUFFER_EVENTS];
        private final int[] callees = new int[BUFFER_EVENTS];
        private int size;

        Buffer(long thread) {
            this.thread = thread;
        }

        synchronized void add(long sequence, int context, int caller, int callee) {
            sequences[size] = sequence;
            contexts[size] = context;
            callers[size] = caller;
            callees[size] = callee;
            size++;
            if (size == BUFFER_EVENTS) {
                drainLocked();
            }
        }

        synchronized void drain() {
            drainLocked();
        }

        private void drainLocked() {
            if (size > 0) {
                write(sequences, thread, contexts, callers, callees, size);
                size = 0;
            }
        }
    }
}
