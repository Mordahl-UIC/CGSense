package cgsense.tester.agent;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

/**The JVM gives it the raw bytecode of every class it loads. For project classes, it returns rewritten bytecode in which each method reports to Recorder on entry and exit. */

//Only one transformer object exists. It's created in Agent.premain, and these helpers are shared across all classes.
/**
 * JVM starts
 * └─ Agent.premain() ← once
 * ├─ AgentConfig.parse(args)
 * ├─ Recorder.start(config)
 * └─ new CallGraphTransformer(config) + inst.addTransformer(...)
 * JUnit / tests run
 * └─ each class loaded for the first time → JVM calls
 * CallGraphTransformer.transform(...) ← many times
 * └─ project class? → ClassInstrumenter → MethodInstrumenter → rewritten bytes
 */

final class CallGraphTransformer implements ClassFileTransformer {
  private final AgentConfig config; // // include/exclude settings
  private final ClassHierarchy hierarchy = new ClassHierarchy(); // remembers each class's superclass/interfaces
  private final TestAnnotations annotations = new TestAnnotations(); // recognises @Test, @BeforeEach, ...

  CallGraphTransformer(AgentConfig config) {
    this.config = config;
  }

  // Only your project's own classes get past this point.
  @Override
  public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
      ProtectionDomain protectionDomain, byte[] classfileBuffer) {
    if (className == null || classBeingRedefined != null || isGenerated(className) // classBeingRedefined != null The
                                                                                   // class is already loaded and
                                                                                   // something (e.g. a debugger's
                                                                                   // hot-swap) is redefining it.
                                                                                   // The agent only handles a class's
                                                                                   // first load. isGenerated(className)
                                                                                   // A proxy or mock created at runtime
                                                                                   // !config.matches(className) Not
                                                                                   // project code: JDK, libraries,
                                                                                   // excluded packages, or the agent
                                                                                   // itself
        || !config.matches(className)) {
      return null;
    }
    try {
      // read the class
      ClassReader reader = new ClassReader(classfileBuffer);
      if ((reader.getAccess() & Opcodes.ACC_MODULE) != 0) {
        return null;
      }
      hierarchy.register(reader); // This stores the class's superclass name and whether it is an interface (not
                                  // the list of interfaces it implements).

      // ClassReader → ClassInstrumenter → (MethodInstrumenter, per method) →
      // HierarchyClassWriter → new bytes
      // reads registers each method inserts Recorder.enter/exit writes the class
      ClassWriter writer = new HierarchyClassWriter(hierarchy, loader);
      ClassInstrumenter instrumenter = new ClassInstrumenter(writer, hierarchy, annotations, loader);
      // Frames are recomputed by the writer, so the original ones are not needed.
      reader.accept(instrumenter, ClassReader.SKIP_FRAMES);
      if (instrumenter.instrumentedMethods() == 0) { // If the class has no methods to instrument, don't rewrite it.
                                                     // This avoids creating a new class object and messing up identity
                                                     // comparisons.
        return null;
      }
      byte[] result = writer.toByteArray();
      Recorder.classInstrumented(); // count it: shows up as classes_instrumented in the dump
      return result; // the JVM loads this modified version
    } catch (Throwable t) {
      // on any error, keep the original class and log the failure. This avoids
      // breaking the test run if the agent has a bug or the class is weird.
      Recorder.transformFailed(className, t);
      return null;
    }
  }

  /**
   * Frameworks like Mockito, ByteBuddy, Hibernate and Spring (CGLIB, with $$ in
   * the name) create classes at runtime inside your project's packages, for
   * example demo.Calculator$MockitoMock$123.
   * //They match your include prefix, but they aren't your code, so they're
   * skipped by name. This keeps fake nodes out of the call graph.
   */
  private static boolean isGenerated(String className) {
    return className.contains("$$") || className.contains("$MockitoMock$")
        || className.contains("$ByteBuddy$") || className.contains("$HibernateProxy$");
  }

  /**
   * Computes common super classes from class-file bytes instead of loading
   * classes.
   */
  // After inserting new instructions, ASM must rebuild the type notes the JVM
  // uses for verification,
  // and that needs "common parent class" answers. ASM's default way of answering
  // is to load those classes with Class.forName. Inside a transformer, that's
  // dangerous:
  // loading a class here would call transform again, in the middle of a
  // transformation
  // it can run static initializers too early, or use the wrong class loader
  // So this subclass overrides the method to answer from ClassHierarchy instead.
  // It reads superclass information from the raw class-file bytes, without
  // loading any classes. That's why step 3 registers each class.
  private static final class HierarchyClassWriter extends ClassWriter {
    private final ClassHierarchy hierarchy;
    private final ClassLoader loader;

    HierarchyClassWriter(ClassHierarchy hierarchy, ClassLoader loader) {
      super(ClassWriter.COMPUTE_FRAMES);
      this.hierarchy = hierarchy;
      this.loader = loader;
    }

    @Override
    protected String getCommonSuperClass(String type1, String type2) {
      return hierarchy.commonSuperClass(type1, type2, loader);
    }
  }
}
