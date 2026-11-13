package cgsense.tester.agent;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Decides whether a method annotation marks a test or a fixture, including
 * composed annotations
 * such as {@code @MultiLocaleTest}, which are themselves annotated with
 * {@code @ParameterizedTest}.
 * Annotation types are read from class-file resources, never loaded.
 *
 */
// An annotation is a label you attach to a piece of Java code (a class, method,
// field or parameter). It starts with @. On its own it doesn't change what the
// code does. It's extra information that other tools, or Java itself, can read
// and act on.
// A fixture method is a method that prepares things before tests or cleans up
// after them. It isn't a test itself; it sets the stage so tests can run.

/*
 * TestAnnotations decides whether a method annotation means test, fixture or
 * neither. It first checks a list of known JUnit and TestNG annotations.
 * For custom annotations, it reads their class files (without loading them) and
 * follows their meta-annotations up to 4 levels deep, guarding against cycles,
 * preferring "test" over "fixture", and caching each answer.
 */

/*
 * Who uses TestAnnotations, and when:
 *
 * Caller What it uses When
 * ---------------------------------------- ------------------------------------
 * -----------------------------------------
 * CallGraphTransformer.java new TestAnnotations() Once, when the transformer is
 * created at
 * JVM startup
 * MethodInstrumenter.java (visitAnnotation) annotations.kind(descriptor,
 * loader), For each annotation on each instrumented
 * TestAnnotations.NONE method, while its class is being rewritten
 */

/*
 * Why three kinds of methods (testKind)?
 *
 * 0 = normal -> enter/exit : records WHO called WHOM (the edges)
 * 1 = test -> enterTest/exitTest : opens a test context, so every call is
 * linked
 * to the test that caused it (and its pass/fail)
 * 2 = fixture -> enterTest/exitTest : opens a fixture context for
 * set-up/clean-up
 * (@BeforeEach, setUp, ...), so those calls are
 * not "unattributed" and not counted as tests
 *
 * If all were treated the same, the edges and the diff would stay the same,
 * but we would lose which test made each call.
 */

final class TestAnnotations {
  static final int NONE = 0;

  private static final Set<String> TEST = new HashSet<String>(Arrays.asList(
      "Lorg/junit/Test;",
      "Lorg/junit/jupiter/api/Test;",
      "Lorg/junit/jupiter/api/RepeatedTest;",
      "Lorg/junit/jupiter/api/TestFactory;",
      "Lorg/junit/jupiter/api/TestTemplate;",
      "Lorg/junit/jupiter/params/ParameterizedTest;",
      "Lorg/testng/annotations/Test;"));

  private static final Set<String> FIXTURE = new HashSet<String>(Arrays.asList(
      "Lorg/junit/Before;", "Lorg/junit/After;",
      "Lorg/junit/BeforeClass;", "Lorg/junit/AfterClass;",
      "Lorg/junit/jupiter/api/BeforeEach;", "Lorg/junit/jupiter/api/AfterEach;",
      "Lorg/junit/jupiter/api/BeforeAll;", "Lorg/junit/jupiter/api/AfterAll;",
      "Lorg/testng/annotations/BeforeMethod;", "Lorg/testng/annotations/AfterMethod;",
      "Lorg/testng/annotations/BeforeClass;", "Lorg/testng/annotations/AfterClass;"));

  /** Prefixes of annotation types that are never composed test annotations. */
  private static final String[] TERMINAL = {
      "Ljava/", "Ljavax/", "Ljdk/", "Lkotlin/", "Lorg/apiguardian/", "Lorg/junit/", "Lorg/testng/",
  };
  private static final int MAX_DEPTH = 4;

  private final ConcurrentHashMap<String, Integer> resolved = new ConcurrentHashMap<String, Integer>();

  /**
   * {@link Recorder#KIND_TEST}, {@link Recorder#KIND_FIXTURE} or {@link #NONE}.
   */
  int kind(String descriptor, ClassLoader loader) {
    return kind(descriptor, loader, 0, new HashSet<String>());
  }

  private int kind(String descriptor, ClassLoader loader, int depth, Set<String> visiting) {
    if (TEST.contains(descriptor)) {
      return Recorder.KIND_TEST;
    }
    if (FIXTURE.contains(descriptor)) {
      return Recorder.KIND_FIXTURE;
    }
    for (String prefix : TERMINAL) {
      if (descriptor.startsWith(prefix)) {
        return NONE;
      }
    }
    Integer cached = resolved.get(descriptor);
    if (cached != null) {
      return cached;
    }
    if (depth >= MAX_DEPTH || !visiting.add(descriptor)) {
      return NONE;
    }
    int result = NONE;
    for (String meta : metaAnnotations(descriptor, loader)) {
      int k = kind(meta, loader, depth + 1, visiting);
      if (k == Recorder.KIND_TEST) {
        result = k;
        break;
      }
      if (k == Recorder.KIND_FIXTURE) {
        result = k;
      }
    }
    resolved.put(descriptor, result);
    return result;
  }

  private static List<String> metaAnnotations(String descriptor, ClassLoader loader) {
    final List<String> found = new ArrayList<String>();
    if (!descriptor.startsWith("L") || !descriptor.endsWith(";")) {
      return found;
    }
    String resource = descriptor.substring(1, descriptor.length() - 1) + ".class";
    InputStream in = null;
    try {
      if (loader != null) {
        in = loader.getResourceAsStream(resource);
      }
      if (in == null) {
        in = ClassLoader.getSystemResourceAsStream(resource);
      }
      if (in == null) {
        return found;
      }
      new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
        @Override
        public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
          found.add(desc);
          return null;
        }
      }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    } catch (IOException e) {
      // treat as a plain annotation
    } catch (RuntimeException e) {
      // treat as a plain annotation
    } finally {
      if (in != null) {
        try {
          in.close();
        } catch (IOException ignored) {
          // nothing to do
        }
      }
    }
    return found;
  }
}
