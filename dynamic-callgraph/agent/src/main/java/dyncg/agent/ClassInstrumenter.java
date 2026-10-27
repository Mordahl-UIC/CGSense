package dyncg.agent;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.JSRInlinerAdapter;

/** Registers each method with {@link Recorder} and wraps it with a {@link MethodInstrumenter}. */
final class ClassInstrumenter extends ClassVisitor {
    private static final int API = Opcodes.ASM9;
    private static final int FIRST_VERSION_WITHOUT_JSR = 50; // Java 6

    private final ClassHierarchy hierarchy;
    private final TestAnnotations annotations;
    private final ClassLoader loader;
    private String owner;
    private int majorVersion;
    private boolean junit3TestCase;
    private int instrumented;

    ClassInstrumenter(ClassVisitor next, ClassHierarchy hierarchy, TestAnnotations annotations,
            ClassLoader loader) {
        super(API, next);
        this.hierarchy = hierarchy;
        this.annotations = annotations;
        this.loader = loader;
    }

    int instrumentedMethods() {
        return instrumented;
    }

    @Override
    public void visit(int version, int access, String name, String signature, String superName,
            String[] interfaces) {
        super.visit(version, access, name, signature, superName, interfaces);
        owner = name;
        majorVersion = version & 0xFFFF;
        junit3TestCase = superName != null
                && hierarchy.isSubclassOf(superName, "junit/framework/TestCase", loader);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
            String[] exceptions) {
        MethodVisitor next = super.visitMethod(access, name, descriptor, signature, exceptions);
        if (next == null || (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
            return next;
        }
        // Compiler-generated forwarders: skipping them keeps edges pointing at the real target.
        boolean bridge = (access & Opcodes.ACC_BRIDGE) != 0;
        boolean accessor = (access & Opcodes.ACC_SYNTHETIC) != 0 && name.startsWith("access$");
        if (bridge || accessor) {
            return next;
        }
        int id = Recorder.registerMethod(owner, name, descriptor, access);
        instrumented++;
        MethodVisitor instrumenter = new MethodInstrumenter(next, access, name, descriptor, owner, id,
                junit3TestCase, annotations, loader);
        if (majorVersion < FIRST_VERSION_WITHOUT_JSR) {
            // Old class files may contain JSR/RET, which frame computation cannot handle.
            return new JSRInlinerAdapter(instrumenter, access, name, descriptor, signature, exceptions);
        }
        return instrumenter;
    }
}
