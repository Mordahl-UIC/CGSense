package cgsense.tester.agent;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.AdviceAdapter;

/**
 * MethodInstrumenter wraps each method's code in "Recorder.enter(id) …
 * Recorder.exit()", with a catch-all that calls exit() and rethrows.
 * That keeps Recorder's shadow stack correct even when exceptions occur. For
 * test and fixture methods, detected from JUnit 3 naming or from annotations,
 * it uses enterTest/exitTest with the runtime class name, so each call can be
 * attributed to the test that was running.
 */
/*
 * Created in ClassInstrumenter.java:58, once for each method that gets
 * instrumented.
 * Its methods are called by ASM while reader.accept(...) processes the method:
 * visitAnnotation for each annotation, onMethodEnter at the start of the code,
 * onMethodExit before each return or throw, and visitMaxs at the end.
 * The calls it inserts (Recorder.enter, exit, enterTest, exitTest) run later,
 * every time the rewritten method runs during the tests.
 */

/*
 * AdviceAdapter is an ASM helper that provides two hooks:
 * 
 * onMethodEnter() runs at the start of the method's code. In constructors, it
 * runs after the super(...) call, because before super() the object isn't
 * initialized yet, and the JVM forbids using this or doing much else at that
 * point.
 * onMethodExit(opcode) runs just before every exit instruction: each return and
 * each throw.
 */
final class MethodInstrumenter extends AdviceAdapter {
  private static final String RECORDER = "cgsense/tester/agent/Recorder"; // class whose methods we call

  private final String owner; // the class we're instrumenting, e.g. "demo/CalculatorTest"
  private final TestAnnotations annotations; // helper that recognises @Test, @BeforeEach, ...
  private final ClassLoader loader; // the loader that loaded the class we're instrumenting, or null for the
                                    // bootstrap loader
  private final int id; // the method's id, assigned by Recorder.registerMethod
  private final boolean isStatic; // whether the method is static (no this parameter)
  private int testKind; // 0 = not a test method, or one of the Recorder.KIND_* constants for a test or
                        // fixture method
  private final Label bodyStart = new Label(); // marks the start of the method's body, after any super() call in a
                                               // constructor
  private boolean entered; // whether onMethodEnter() has run, so we know to call exit on every return

  MethodInstrumenter(MethodVisitor next, int access, String name, String descriptor, String owner,
      int id, boolean junit3TestCase, TestAnnotations annotations, ClassLoader loader) {
    super(Opcodes.ASM9, next, access, name, descriptor);
    this.owner = owner;
    this.annotations = annotations;
    this.loader = loader;
    this.id = id;
    this.isStatic = (access & Opcodes.ACC_STATIC) != 0;
    /*
     * JUnit 3 has no annotations. A method is a test if its class extends TestCase
     * (the junit3TestCase flag from ClassInstrumenter)
     * and the method is public, non-static, takes no arguments and returns nothing
     * (()V), and its name starts with test. setUp and tearDown are the fixtures.
     */
    if (junit3TestCase && !isStatic && (access & Opcodes.ACC_PUBLIC) != 0 && descriptor.equals("()V")) {
      if (name.startsWith("test")) {
        testKind = Recorder.KIND_TEST;
      } else if (name.equals("setUp") || name.equals("tearDown")) {
        testKind = Recorder.KIND_FIXTURE;
      }
    }
  }

  /*
   * ASM calls this for each annotation on the method, before the method's code.
   * So by the time onMethodEnter runs, testKind is already decided.
   * TestAnnotations.kind
   * recognises @Test, @ParameterizedTest, @BeforeEach, @AfterAll and others,
   * including custom annotations built from them.
   * if (testKind != KIND_TEST): once a method is known to be a test, that's
   * final. A later fixture-type annotation can't downgrade it.
   */
  @Override
  public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
    if (testKind != Recorder.KIND_TEST) {
      int kind = annotations.kind(descriptor, loader);
      if (kind != TestAnnotations.NONE) {
        testKind = kind;
      }
    }
    return super.visitAnnotation(descriptor, visible);
  }

  @Override
  protected void onMethodEnter() {
    if (testKind != 0) {
      // Use the runtime class so inherited test methods are named after the class
      // that ran them.
      if (isStatic) {
        mv.visitLdcInsn(owner.replace('/', '.')); // static methods: use the class that owns the method, e.g.
                                                  // demo/CalculatorTest
      } else {
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "getClass",
            "()Ljava/lang/Class;", false); // instance methods: use the runtime class, e.g. demo/CalculatorTest$1 for an
                                           // anonymous subclass
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getName",
            "()Ljava/lang/String;", false); // getClass().getName() returns the fully qualified name with dots, e.g.
                                            // demo.CalculatorTest$1
      }
      pushInt(id); // the method's id, assigned by Recorder.registerMethod
      pushInt(testKind); // the kind of test or fixture method, one of the Recorder.KIND_* constants
      mv.visitMethodInsn(Opcodes.INVOKESTATIC, RECORDER, "enterTest", "(Ljava/lang/String;II)V", false);
    } else {
      pushInt(id); // the method's id, assigned by Recorder.registerMethod
      mv.visitMethodInsn(Opcodes.INVOKESTATIC, RECORDER, "enter", "(I)V", false);
    }
    mv.visitLabel(bodyStart); // marks the start of the method's body, after any super() call in a constructor
    entered = true; // onMethodExit will call exit on every return, so the shadow stack stays
                    // balanced even if an exception propagates
  }

  @Override
  protected void onMethodExit(int opcode) {
    // Before every return instruction, it inserts Recorder.exit() (or exitTest()).
    // entered is a safety check: in unusual constructors, a return or throw can be
    // reached before onMethodEnter has run. Calling exit without a matching enter
    // would unbalance the stack.

    if (opcode != Opcodes.ATHROW && entered) {
      emitExit();
    }
  }

  // ASM calls visitMaxs once, after all of the method's instructions. It's the
  // last chance to add code.
  // without it, a test that throws (for example a failing assertEquals) would
  // leave its method on the shadow stack forever, and every later call would get
  // the wrong caller.
  @Override
  public void visitMaxs(int maxStack, int maxLocals) {
    if (entered) {
      // Added last, so the method's own handlers keep precedence in the exception
      // table.
      Label handler = new Label();
      mv.visitTryCatchBlock(bodyStart, handler, handler, null);
      mv.visitLabel(handler);
      emitExit();
      mv.visitInsn(Opcodes.ATHROW);
    }
    super.visitMaxs(maxStack, maxLocals);
  }

  // This inserts a call to Recorder.exit() or exitTest() at the current point in
  // the method.
  private void emitExit() {
    String method = testKind != 0 ? "exitTest" : "exit";//
    mv.visitMethodInsn(Opcodes.INVOKESTATIC, RECORDER, method, "()V", false);
  }

  // ASM's visitIntInsn and visitLdcInsn have different limits on the size of the
  // integer they can push. This helper picks the right one for the value.
  private void pushInt(int value) {
    if (value >= -1 && value <= 5) {
      mv.visitInsn(Opcodes.ICONST_0 + value);
    } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
      mv.visitIntInsn(Opcodes.BIPUSH, value);
    } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
      mv.visitIntInsn(Opcodes.SIPUSH, value);
    } else {
      mv.visitLdcInsn(value);
    }
  }
}
