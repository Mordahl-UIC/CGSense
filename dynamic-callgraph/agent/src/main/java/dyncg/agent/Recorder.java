package dyncg.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Runtime side of the agent, called from instrumented code.
 *
 * <p>Each thread keeps a shadow stack of method ids. On entry the edge (context, caller, callee)
 * is counted, where the caller is the top of the stack (0 = called from uninstrumented code) and
 * the context is the test or fixture method currently running on the thread (0 = none). Threads
 * inherit the context of the thread that created them.
 *
 * <p>None of these methods may throw: a failure here would change the behaviour of the tests.
 */
public final class Recorder {
    public static final int KIND_TEST = 1;
    public static final int KIND_FIXTURE = 2;

    private static final int METHOD_BITS = 22;
    private static final int CONTEXT_BITS = 20;
    private static final long METHOD_MASK = (1L << METHOD_BITS) - 1;

    /** Method table; index 0 is the synthetic root ("called from uninstrumented code"). */
    private static final List<String[]> METHODS = new ArrayList<String[]>();
    private static final List<Integer> METHOD_ACCESS = new ArrayList<Integer>();

    /** Context table; index 0 means "no test or fixture is running". */
    private static final List<String[]> CONTEXTS = new ArrayList<String[]>();
    private static final ConcurrentHashMap<String, Integer> CONTEXT_IDS = new ConcurrentHashMap<String, Integer>();

    private static final ConcurrentHashMap<Long, LongAdder> EDGES = new ConcurrentHashMap<Long, LongAdder>();
    private static final AtomicLong OVERFLOWED_EDGES = new AtomicLong();
    private static final AtomicInteger CLASSES_INSTRUMENTED = new AtomicInteger();
    private static final List<String[]> TRANSFORM_FAILURES = new ArrayList<String[]>();
    private static final int MAX_REPORTED_FAILURES = 200;
    private static final AtomicInteger TRANSFORM_FAILURE_COUNT = new AtomicInteger();

    private static final InheritableThreadLocal<ThreadState> STATE = new InheritableThreadLocal<ThreadState>() {
        @Override
        protected ThreadState initialValue() {
            return new ThreadState(0);
        }

        @Override
        protected ThreadState childValue(ThreadState parent) {
            return new ThreadState(parent == null ? 0 : parent.context);
        }
    };

    static {
        METHODS.add(new String[] {"<root>", "<root>", ""});
        METHOD_ACCESS.add(0);
        CONTEXTS.add(new String[] {"", "", "0"});
    }

    private Recorder() {
    }

    static void start(AgentConfig config) {
        Dumper.start(config);
    }

    // ---- called from instrumented code -------------------------------------------------------

    public static void enter(int methodId) {
        try {
            ThreadState s = STATE.get();
            count(s, s.context, s.top(), methodId);
            s.push(methodId, s.context);
        } catch (Throwable ignored) {
            // never disturb the program under test
        }
    }

    public static void exit() {
        try {
            STATE.get().pop();
        } catch (Throwable ignored) {
            // never disturb the program under test
        }
    }

    public static void enterTest(String runtimeClass, int methodId, int kind) {
        try {
            ThreadState s = STATE.get();
            int context = contextId(runtimeClass, methodId, kind);
            count(s, context, s.top(), methodId);
            s.pushTest(methodId, context);
        } catch (Throwable ignored) {
            // never disturb the program under test
        }
    }

    public static void exitTest() {
        try {
            STATE.get().popTest();
        } catch (Throwable ignored) {
            // never disturb the program under test
        }
    }

    // ---- called by the transformer -----------------------------------------------------------

    static int registerMethod(String owner, String name, String descriptor, int access) {
        synchronized (METHODS) {
            METHODS.add(new String[] {owner, name, descriptor});
            METHOD_ACCESS.add(access);
            return METHODS.size() - 1;
        }
    }

    static void classInstrumented() {
        CLASSES_INSTRUMENTED.incrementAndGet();
    }

    static void transformFailed(String className, Throwable error) {
        TRANSFORM_FAILURE_COUNT.incrementAndGet();
        synchronized (TRANSFORM_FAILURES) {
            if (TRANSFORM_FAILURES.size() < MAX_REPORTED_FAILURES) {
                TRANSFORM_FAILURES.add(new String[] {className, String.valueOf(error)});
            }
        }
    }

    // ---- internals ---------------------------------------------------------------------------

    private static void count(ThreadState s, int context, int caller, int callee) {
        if (context >= (1 << CONTEXT_BITS) || caller > METHOD_MASK || callee > METHOD_MASK) {
            OVERFLOWED_EDGES.incrementAndGet();
            return;
        }
        long key = ((long) context << (2 * METHOD_BITS)) | ((long) caller << METHOD_BITS) | callee;
        LongAdder counter = EDGES.get(key);
        if (counter == null) {
            counter = new LongAdder();
            LongAdder previous = EDGES.putIfAbsent(key, counter);
            if (previous != null) {
                counter = previous;
            }
        }
        counter.increment();
        if (EventLog.enabled()) {
            if (s.events == null) {
                s.events = EventLog.newBuffer();
            }
            EventLog.record(s.events, context, caller, callee);
        }
    }

    private static int contextId(String runtimeClass, int methodId, int kind) {
        String methodName;
        synchronized (METHODS) {
            methodName = METHODS.get(methodId)[1];
        }
        String key = kind + ":" + runtimeClass + "#" + methodName;
        Integer id = CONTEXT_IDS.get(key);
        if (id != null) {
            return id;
        }
        synchronized (CONTEXTS) {
            id = CONTEXT_IDS.get(key);
            if (id == null) {
                CONTEXTS.add(new String[] {runtimeClass, methodName, Integer.toString(kind)});
                id = CONTEXTS.size() - 1;
                CONTEXT_IDS.put(key, id);
            }
            return id;
        }
    }

    // ---- snapshot for the dumper -------------------------------------------------------------

    static final class Snapshot {
        String[][] methods;
        int[] methodAccess;
        String[][] contexts;
        long[] edgeKeys;
        long[] edgeCounts;
        int classesInstrumented;
        int transformFailureCount;
        String[][] transformFailures;
        long overflowedEdges;
    }

    static Snapshot snapshot() {
        Snapshot snap = new Snapshot();
        synchronized (METHODS) {
            snap.methods = METHODS.toArray(new String[0][]);
            snap.methodAccess = new int[METHOD_ACCESS.size()];
            for (int i = 0; i < snap.methodAccess.length; i++) {
                snap.methodAccess[i] = METHOD_ACCESS.get(i);
            }
        }
        synchronized (CONTEXTS) {
            snap.contexts = CONTEXTS.toArray(new String[0][]);
        }
        List<Long> keys = new ArrayList<Long>(EDGES.keySet());
        snap.edgeKeys = new long[keys.size()];
        snap.edgeCounts = new long[keys.size()];
        for (int i = 0; i < keys.size(); i++) {
            long key = keys.get(i);
            snap.edgeKeys[i] = key;
            snap.edgeCounts[i] = EDGES.get(key).sum();
        }
        snap.classesInstrumented = CLASSES_INSTRUMENTED.get();
        snap.transformFailureCount = TRANSFORM_FAILURE_COUNT.get();
        synchronized (TRANSFORM_FAILURES) {
            snap.transformFailures = TRANSFORM_FAILURES.toArray(new String[0][]);
        }
        snap.overflowedEdges = OVERFLOWED_EDGES.get();
        return snap;
    }

    static int edgeContext(long key) {
        return (int) (key >>> (2 * METHOD_BITS));
    }

    static int edgeCaller(long key) {
        return (int) ((key >>> METHOD_BITS) & METHOD_MASK);
    }

    static int edgeCallee(long key) {
        return (int) (key & METHOD_MASK);
    }

    /** Per-thread shadow stack. Only its own thread touches it. */
    private static final class ThreadState {
        int[] frames = new int[64];
        /** Context to restore when the frame at the same index is popped. */
        int[] savedContext = new int[64];
        int depth;
        int context;
        /** Stack indices of the test/fixture frames currently open on this thread. */
        int[] testFrames = new int[4];
        int openTests;
        /** Created on first use when the event log is enabled. */
        EventLog.Buffer events;

        ThreadState(int context) {
            this.context = context;
        }

        int top() {
            return depth == 0 ? 0 : frames[depth - 1];
        }

        void push(int methodId, int restoreContext) {
            if (depth == frames.length) {
                frames = grow(frames);
                savedContext = grow(savedContext);
            }
            frames[depth] = methodId;
            savedContext[depth] = restoreContext;
            depth++;
        }

        void pop() {
            if (depth > 0) {
                depth--;
                context = savedContext[depth];
            }
        }

        void pushTest(int methodId, int testContext) {
            if (openTests == testFrames.length) {
                testFrames = grow(testFrames);
            }
            testFrames[openTests++] = depth;
            push(methodId, context);
            context = testContext;
        }

        /**
         * Pops back to the test frame itself, discarding any frames left above it. This bounds
         * the damage of an unbalanced stack (for example after a StackOverflowError inside the
         * recorder) to the test in which it happened.
         */
        void popTest() {
            if (openTests == 0) {
                pop();
                return;
            }
            int index = testFrames[--openTests];
            if (index < depth) {
                depth = index;
            }
            context = savedContext[index];
        }

        private static int[] grow(int[] array) {
            int[] bigger = new int[array.length * 2];
            System.arraycopy(array, 0, bigger, 0, array.length);
            return bigger;
        }
    }
}
