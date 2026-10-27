package dyncg.agent;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

/** Rewrites project classes so that every method reports its entry and exit to {@link Recorder}. */
final class CallGraphTransformer implements ClassFileTransformer {
    private final AgentConfig config;
    private final ClassHierarchy hierarchy = new ClassHierarchy();
    private final TestAnnotations annotations = new TestAnnotations();

    CallGraphTransformer(AgentConfig config) {
        this.config = config;
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (className == null || classBeingRedefined != null || isGenerated(className)
                || !config.matches(className)) {
            return null;
        }
        try {
            ClassReader reader = new ClassReader(classfileBuffer);
            if ((reader.getAccess() & Opcodes.ACC_MODULE) != 0) {
                return null;
            }
            hierarchy.register(reader);
            ClassWriter writer = new HierarchyClassWriter(hierarchy, loader);
            ClassInstrumenter instrumenter = new ClassInstrumenter(writer, hierarchy, annotations, loader);
            // Frames are recomputed by the writer, so the original ones are not needed.
            reader.accept(instrumenter, ClassReader.SKIP_FRAMES);
            if (instrumenter.instrumentedMethods() == 0) {
                return null;
            }
            byte[] result = writer.toByteArray();
            Recorder.classInstrumented();
            return result;
        } catch (Throwable t) {
            // Leave the class unchanged; the test run must not fail because of the agent.
            Recorder.transformFailed(className, t);
            return null;
        }
    }

    /** Runtime-generated classes (proxies, mocks) that live in project packages. */
    private static boolean isGenerated(String className) {
        return className.contains("$$") || className.contains("$MockitoMock$")
                || className.contains("$ByteBuddy$") || className.contains("$HibernateProxy$");
    }

    /** Computes common super classes from class-file bytes instead of loading classes. */
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
