package dyncg.agent;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;

/**
 * Super-class and interface information read from class-file resources.
 *
 * <p>Loading classes from inside a transformer can deadlock or trigger circular loading, so the
 * hierarchy is resolved from the bytes only. Unknown types are treated as direct subclasses of
 * {@code java/lang/Object}, which is the conservative answer for frame computation.
 */
final class ClassHierarchy {
    private static final String OBJECT = "java/lang/Object";
    private static final ClassInfo MISSING = new ClassInfo(null, false);

    private final ConcurrentHashMap<String, ClassInfo> cache = new ConcurrentHashMap<String, ClassInfo>();

    void register(ClassReader reader) {
        cache.put(reader.getClassName(), infoOf(reader));
    }

    String commonSuperClass(String type1, String type2, ClassLoader loader) {
        if (type1.equals(type2)) {
            return type1;
        }
        ClassInfo info1 = info(type1, loader);
        ClassInfo info2 = info(type2, loader);
        if (info1 == MISSING || info2 == MISSING || info1.isInterface || info2.isInterface) {
            return OBJECT;
        }
        Set<String> ancestors = new HashSet<String>();
        for (String t = type1; t != null; t = info(t, loader).superName) {
            ancestors.add(t);
        }
        for (String t = type2; t != null; t = info(t, loader).superName) {
            if (ancestors.contains(t)) {
                return t;
            }
        }
        return OBJECT;
    }

    boolean isSubclassOf(String type, String ancestor, ClassLoader loader) {
        for (String t = type; t != null; t = info(t, loader).superName) {
            if (t.equals(ancestor)) {
                return true;
            }
        }
        return false;
    }

    private ClassInfo info(String type, ClassLoader loader) {
        ClassInfo info = cache.get(type);
        if (info == null) {
            info = read(type, loader);
            cache.put(type, info);
        }
        return info;
    }

    private static ClassInfo read(String type, ClassLoader loader) {
        String resource = type + ".class";
        InputStream in = null;
        try {
            if (loader != null) {
                in = loader.getResourceAsStream(resource);
            }
            if (in == null) {
                in = ClassLoader.getSystemResourceAsStream(resource);
            }
            if (in == null) {
                return MISSING;
            }
            return infoOf(new ClassReader(in));
        } catch (IOException e) {
            return MISSING;
        } catch (RuntimeException e) {
            return MISSING;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // nothing to do
                }
            }
        }
    }

    private static ClassInfo infoOf(ClassReader reader) {
        return new ClassInfo(reader.getSuperName(), (reader.getAccess() & Opcodes.ACC_INTERFACE) != 0);
    }

    private static final class ClassInfo {
        final String superName;
        final boolean isInterface;

        ClassInfo(String superName, boolean isInterface) {
            this.superName = superName;
            this.isInterface = isInterface;
        }
    }
}
