package cgsense.tester.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class GraphBuilderTest {
  @Test
  void jvmRoles() {
    assertEquals(
        "test-jvm",
        GraphBuilder.jvmRole("/x/target/surefire/surefirebooter123.jar /x/target/surefire 2026"));
    assertEquals(
        "maven", GraphBuilder.jvmRole("org.codehaus.plexus.classworlds.launcher.Launcher -B test"));
    assertEquals(
        "test-jvm",
        GraphBuilder.jvmRole(
            "worker.org.gradle.process.internal.worker.GradleWorkerMain 'Gradle Test Executor 1'"));
    assertEquals(
        "gradle", GraphBuilder.jvmRole("org.gradle.launcher.daemon.bootstrap.GradleDaemon 8.10"));
    assertEquals("other", GraphBuilder.jvmRole("com.example.Server"));
  }
}
