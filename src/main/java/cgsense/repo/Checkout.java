package cgsense.repo;

import cgsense.model.BuildTool;
import cgsense.model.Commit;
import cgsense.tester.driver.RunResult;
import cgsense.tester.driver.TestRunOptions;
import java.io.IOException;
import java.nio.file.Path;

/**
 * One commit checked out, made by {@link Repo#checkout}. It knows where it is and how to build and
 * test itself, so callers never pass its SHA or directory around.
 */
public interface Checkout extends AutoCloseable {
  /** The build tool the commit pair recorded */
  Commit commit();

  boolean build(int jdk) throws IOException;

  /** Copies to host on this machine*/
  Path copyTo(Path host) throws IOException;

  /** The checkout on this machine; what the driver scans. */
  Path dir();

  /** Runs the test suite once under the agent; its outputs end up in {@code runDir}. */
  RunResult runTests(Path runDir, int index, TestRunOptions options)
      throws IOException, InterruptedException;

  /** Removes the checkout. */
  @Override
  void close() throws IOException;

  /** A project already checked out at {@code dir} on this machine; closing it leaves it there. */
  static Checkout local(Commit commit, Path dir) {
    return new LocalCheckout(commit, dir);
  }
}
