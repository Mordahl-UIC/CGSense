package dyncg.agent;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.AdviceAdapter;

/**
 * Adds {@code Recorder.enter} at method entry (after {@code super()} in constructors),
 * {@code Recorder.exit} before every return, and a catch-all handler that calls
 * {@code Recorder.exit} and rethrows, so that the shadow stack stays balanced when exceptions
 * propagate. Test and fixture methods use the {@code enterTest}/{@code exitTest} variants, which
 * also switch the thread's test context.
 */
final class MethodInstrumenter extends AdviceAdapter {
    private static final String RECORDER = "dyncg/agent/Recorder";

    private final String owner;
    private final TestAnnotations annotations;
    private final ClassLoader loader;
    private final int id;
    private final boolean isStatic;
    private int testKind;
    private final Label bodyStart = new Label();
    private boolean entered;

    MethodInstrumenter(MethodVisitor next, int access, String name, String descriptor, String owner,
            int id, boolean junit3TestCase, TestAnnotations annotations, ClassLoader loader) {
        super(Opcodes.ASM9, next, access, name, descriptor);
        this.owner = owner;
        this.annotations = annotations;
        this.loader = loader;
        this.id = id;
        this.isStatic = (access & Opcodes.ACC_STATIC) != 0;
        if (junit3TestCase && !isStatic && (access & Opcodes.ACC_PUBLIC) != 0 && descriptor.equals("()V")) {
            if (name.startsWith("test")) {
                testKind = Recorder.KIND_TEST;
            } else if (name.equals("setUp") || name.equals("tearDown")) {
                testKind = Recorder.KIND_FIXTURE;
            }
        }
    }

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
            // Use the runtime class so inherited test methods are named after the class that ran them.
            if (isStatic) {
                mv.visitLdcInsn(owner.replace('/', '.'));
            } else {
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "getClass",
                        "()Ljava/lang/Class;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getName",
                        "()Ljava/lang/String;", false);
            }
            pushInt(id);
            pushInt(testKind);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, RECORDER, "enterTest", "(Ljava/lang/String;II)V", false);
        } else {
            pushInt(id);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, RECORDER, "enter", "(I)V", false);
        }
        mv.visitLabel(bodyStart);
        entered = true;
    }

    @Override
    protected void onMethodExit(int opcode) {
        // Thrown exceptions are handled by the catch-all handler added in visitMaxs.
        if (opcode != Opcodes.ATHROW && entered) {
            emitExit();
        }
    }

    @Override
    public void visitMaxs(int maxStack, int maxLocals) {
        if (entered) {
            // Added last, so the method's own handlers keep precedence in the exception table.
            Label handler = new Label();
            mv.visitTryCatchBlock(bodyStart, handler, handler, null);
            mv.visitLabel(handler);
            emitExit();
            mv.visitInsn(Opcodes.ATHROW);
        }
        super.visitMaxs(maxStack, maxLocals);
    }

    private void emitExit() {
        String method = testKind != 0 ? "exitTest" : "exit";
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, RECORDER, method, "()V", false);
    }

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
