package cgsense.repo;

import cgsense.model.Commit;
import cgsense.tester.driver.FileUtil;
import cgsense.tester.driver.RunResult;
import cgsense.tester.driver.TestRunOptions;
import cgsense.tester.driver.TestRunner;
import java.io.IOException;
import java.nio.file.Path;

/**
 * A project already checked out on this machine, made by {@link Checkout#local}. Its tests run in
 * place on the host. The directory belongs to the caller, so closing it removes nothing.
 */
final class LocalCheckout implements Checkout {
  private final Commit commit;
  private Path dir;

  LocalCheckout(Commit commit, Path dir) {
    this.commit = commit;
    this.dir = FileUtil.abs(dir);
  }

  @Override
  public Commit commit() {
    return commit;
  }

  /** The test run compiles the project itself, on {@code --java-home}. */
  @Override
  public boolean build(int jdk) {
    throw new UnsupportedOperationException("a local checkout is built by its test run");
  }

  @Override
  public Path copyTo(Path host) throws IOException {
    FileUtil.copyTree(dir, host);
    dir = FileUtil.abs(host);
    return dir;
  }

  @Override
  public Path dir() {
    return dir;
  }

  @Override
  public RunResult runTests(Path runDir, int index, TestRunOptions options)
      throws IOException, InterruptedException {
    return TestRunner.runTests(dir, runDir, index, commit.buildTool(), options);
  }

  @Override
  public void close() {}
}
