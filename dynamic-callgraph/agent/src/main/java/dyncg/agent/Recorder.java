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
/**Recorder is the agent's runtime memory. While the tests run, every instrumented method calls it on entry and exit. It keeps a per-thread "shadow stack" of the methods 
 * currently running, knows which test is running, and counts every (test, caller, callee) edge. Dumper reads a snapshot of all this to write the JSON file.
The file has five parts: the data tables, the methods called from instrumented code, the methods called by the transformer, the internals (counting and contexts), 
and the snapshot for Dumper. */

/*
 * Who calls Recorder, and when:
 *
 *   Caller                          Methods                              When
 *   ------------------------------  -----------------------------------  ------------------------------------------
 *   Agent.java                      start                                Once, at JVM startup
 *   ClassInstrumenter.java          registerMethod                       Once per instrumented method, when its
 *                                                                          class loads
 *   CallGraphTransformer.java       classInstrumented, transformFailed   After each class is rewritten, or when
 *                                                                          rewriting fails
 *   Project code (bytecode          enter, exit, enterTest, exitTest     On every method call during the tests
 *     inserted by MethodInstrumenter)
 *   Dumper.java                     snapshot, edgeContext/Caller/Callee  Every flushSeconds, and at JVM exit
 */


public final class Recorder {
    public static final int KIND_TEST = 1;
    public static final int KIND_FIXTURE = 2;

    private static final int METHOD_BITS = 22;
    private static final int CONTEXT_BITS = 20;
    private static final long METHOD_MASK = (1L << METHOD_BITS) - 1;

    /** Method table; index 0 is the synthetic root ("called from uninstrumented code"). */
    private static final List<String[]> METHODS = new ArrayList<String[]>();  // each entry is {owner, name, descriptor}
    private static final List<Integer> METHOD_ACCESS = new ArrayList<Integer>();// the access flags for each method, same index as METHODS

    /** Context table; index 0 means "no test or fixture is running". */
    private static final List<String[]> CONTEXTS = new ArrayList<String[]>(); // each entry is {runtimeClass, methodName, kind}, where kind is one of the Recorder.KIND_* constants
    private static final ConcurrentHashMap<String, Integer> CONTEXT_IDS = new ConcurrentHashMap<String, Integer>();// maps "kind:runtimeClass#methodName" to the context id, same index as CONTEXTS

    private static final ConcurrentHashMap<Long, LongAdder> EDGES = new ConcurrentHashMap<Long, LongAdder>();// maps (context, caller, callee) to the number of times that edge was taken
    private static final AtomicLong OVERFLOWED_EDGES = new AtomicLong(); // the number of times an edge was skipped because context, caller or callee was too large to fit in the edge key
    private static final AtomicInteger CLASSES_INSTRUMENTED = new AtomicInteger(); // the number of classes that were successfully instrumented
    private static final List<String[]> TRANSFORM_FAILURES = new ArrayList<String[]>(); // each entry is {className, error}, for the first few classes that failed to transform
    private static final int MAX_REPORTED_FAILURES = 200; // don't waste too much memory on failures, the rest are just counted
    private static final AtomicInteger TRANSFORM_FAILURE_COUNT = new AtomicInteger(); // the total number of classes that failed to transform, including those not reported in TRANSFORM_FAILURES

    // Each thread has its own shadow stack, and inherits the context of the thread that created it. The context is the id of the test or fixture method currently running on this thread, or 0 if none.
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
 
 //This runs once, when the class is first used. It reserves id 0 in both tables, so real methods and tests start at 1.
    static {
        METHODS.add(new String[] {"<root>", "<root>", ""});
        METHOD_ACCESS.add(0);
        CONTEXTS.add(new String[] {"", "", "0"});
    }

    private Recorder() {
    }
    //Called once, from Agent.premain, to start the dumper thread that writes the call graph to disk at the end of the test run.
    static void start(AgentConfig config) {
        Dumper.start(config);
    }

    // ---- called from instrumented code -------------------------------------------------------
    /*caller = s.top(), the method currently on top of the stack, or 0 (<root>) if the stack is empty, meaning the call came from code that isn't instrumented, like JUnit.
callee = the method being entered.
context = the test currently running on this thread.*/
    public static void enter(int methodId) {
        try {
            ThreadState s = STATE.get();
            count(s.context, s.top(), methodId);
            s.push(methodId, s.context);
        } catch (Throwable ignored) {
            // never disturb the program under test
        }
    }
//It removes the method from the top of the stack, so its caller is on top again.
    public static void exit() {
        try {
            STATE.get().pop();
        } catch (Throwable ignored) {
            // never disturb the program under test
        }
    }

    // Enters a test method.
    public static void enterTest(String runtimeClass, int methodId, int kind) {
        try {
            ThreadState s = STATE.get();
            int context = contextId(runtimeClass, methodId, kind);
            count(context, s.top(), methodId);
            s.pushTest(methodId, context);
        } catch (Throwable ignored) {
            // never disturb the program under test
        }
    }
// exits a test method, popping back to the test frame itself and discarding any frames left above it. This bounds the damage of an unbalanced stack (for example after a StackOverflowError inside the recorder) to the test in which it happened.
    public static void exitTest() {
        try {
            STATE.get().popTest();
        } catch (Throwable ignored) {
            // never disturb the program under test
        }
    }

    // ---- called by the transformer -----------------------------------------------------------
    //ClassInstrumenter calls this for each method it instruments, and gets the id that MethodInstrumenter then bakes into the bytecode. synchronized makes sure two threads loading classes at the same time don't get the same id.
    static int registerMethod(String owner, String name, String descriptor, int access) {
        synchronized (METHODS) {
            METHODS.add(new String[] {owner, name, descriptor});
            METHOD_ACCESS.add(access);
            return METHODS.size() - 1;
        }
    }

//Called by ClassInstrumenter when it successfully instruments a class, to count it for the dumper.
    static void classInstrumented() {
        CLASSES_INSTRUMENTED.incrementAndGet();
    }
//Called by CallGraphTransformer when it fails to transform a class, to count it for the dumper and log the failure. It only keeps the first few failures in memory, to avoid using too much memory if many classes fail.
    static void transformFailed(String className, Throwable error) {
        TRANSFORM_FAILURE_COUNT.incrementAndGet();
        synchronized (TRANSFORM_FAILURES) {
            if (TRANSFORM_FAILURES.size() < MAX_REPORTED_FAILURES) {
                TRANSFORM_FAILURES.add(new String[] {className, String.valueOf(error)});
            }
        }
    }

    // ---- internals ---------------------------------------------------------------------------

    private static void count(int context, int caller, int callee) {

        //check that the ids fit: At most about 1 million contexts (2^20) and about 4.2 million methods (2^22) fit. Anything larger can't be packed, so it's counted as overflowed (stats.overflowed_edges) instead of being stored wrong. In practice this almost never happens.
        if (context >= (1 << CONTEXT_BITS) || caller > METHOD_MASK || callee > METHOD_MASK) {
            OVERFLOWED_EDGES.incrementAndGet();
            return;
        }
        //pack the three ids into one long. << shifts a number left into its slot, and | combines the slots. A single long key is much smaller and faster in a map than an object holding three numbers, and this runs on every method call.
        long key = ((long) context << (2 * METHOD_BITS)) | ((long) caller << METHOD_BITS) | callee;

        //add 1 to that edge's counter. LongAdder is a thread-safe counter that is faster than AtomicLong when many threads increment it at the same time. The first thread to see a new edge creates the LongAdder and puts it in the map, and later threads find it and increment it.
        LongAdder counter = EDGES.get(key);
        if (counter == null) {
            counter = new LongAdder();
            LongAdder previous = EDGES.putIfAbsent(key, counter);
            if (previous != null) {
                counter = previous;
            }
        }
        counter.increment();
    }

    // Returns the ID of the context for the given class, method, and kind.
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

    /**
     * Extracts the context from an edge key.
     *
     * @param key the edge key
     * @return the context
     */
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
