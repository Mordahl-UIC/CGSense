package cgsense.exec;

import cgsense.app.Log;
import cgsense.tester.driver.FileUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/** A long-running Docker container that work is sent into. */
public final class Container implements AutoCloseable {
  private static final Logger LOG = Log.get(Container.class);

  private final String name;

  private Container(String name) {
    this.name = name;
  }

  public static Builder named(String name) {
    return new Builder(name);
  }

  public static final class Builder {
    private final String name;
    private String image;
    private final List<String> volumes = new ArrayList<>();
    private final Map<String, String> env = new LinkedHashMap<>();
    private final List<Step> setup = new ArrayList<>();

    private Builder(String name) {
      this.name = name;
    }

    public Builder image(String image) {
      this.image = image;
      return this;
    }

    /** Mounts a named Docker volume */
    public Builder volume(String volume, String containerPath) {
      volumes.add(volume + ":" + containerPath);
      return this;
    }

    /** An environment variable for everything run in the container. */
    public Builder env(String key, String value) {
      env.put(key, value);
      return this;
    }

    /** Copies a host file or directory to containerPath once the container is up. */
    public Builder copyIn(Path host, String containerPath) {
      setup.add(box -> box.copyIn(host, containerPath));
      return this;
    }

    /** Writes content to the file containerPath once the container is up. */
    public Builder write(String containerPath, byte[] content) {
      setup.add(box -> box.write(containerPath, content));
      return this;
    }

    /** Runs the container and its setup, in order; removes it again if any step fails. */
    public Container start() throws IOException {
      if (image == null) {
        throw new IllegalStateException("no image for container " + name);
      }
      Container box = new Container(name);
      box.docker("rm", "-f", name);
      LOG.info(String.format("Starting container %s from %s", name, image));

      List<String> args = new ArrayList<>(List.of("run", "-d", "--name", name));
      for (String volume : volumes) {
        args.add("-v");
        args.add(volume);
      }
      env.forEach(
          (key, value) -> {
            args.add("-e");
            args.add(key + "=" + value);
          });
      args.addAll(List.of(image, "sleep", "infinity"));
      CommandResult r = box.docker(args.toArray(String[]::new));
      if (!r.ok()) {
        throw new IOException(String.format("docker run failed: %s", r.output()));
      }

      try {
        for (Step step : setup) {
          step.run(box);
        }
      } catch (IOException | RuntimeException e) {
        box.close();
        throw e;
      }
      LOG.info("Container " + name + " is up");
      return box;
    }
  }

  private interface Step {
    void run(Container box) throws IOException;
  }

  public String name() {
    return name;
  }

  /** Runs a command in the container. */
  public CommandResult exec(String... argv) throws IOException {
    List<String> args = new ArrayList<>(List.of("exec", name));
    args.addAll(List.of(argv));
    return docker(args.toArray(String[]::new));
  }

  /** Runs a bash script in the container. */
  public CommandResult sh(String script) throws IOException {
    return exec("bash", "-c", script);
  }

  /** Copies a host file or directory to exactly containerPath, replacing it. */
  public void copyIn(Path host, String containerPath) throws IOException {
    CommandResult prep =
        sh("rm -rf " + quote(containerPath) + " && mkdir -p " + quote(parent(containerPath)));
    if (!prep.ok()) {
      throw new IOException(String.format("preparing %s failed: %s", containerPath, prep.output()));
    }
    CommandResult r = docker("cp", host.toAbsolutePath().toString(), name + ":" + containerPath);
    if (!r.ok()) {
      throw new IOException(
          String.format("docker cp %s into %s failed: %s", host, name, r.output()));
    }
    LOG.fine(String.format("Copied %s to %s:%s (%.1fs)", host, name, containerPath, secs(r)));
  }

  /** Writes {@code content} to the file {@code containerPath}, replacing it. */
  public void write(String containerPath, byte[] content) throws IOException {
    Path tmp = Files.createTempFile("cgsense-", ".tmp");
    try {
      Files.write(tmp, content);
      copyIn(tmp, containerPath);
    } finally {
      Files.deleteIfExists(tmp);
    }
  }

  /** Copies a container file or directory to exactly {@code host}, replacing it. */
  public void copyOut(String containerPath, Path host) throws IOException {
    FileUtil.deleteRecursively(host);
    Path parent = host.toAbsolutePath().getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    CommandResult r = docker("cp", name + ":" + containerPath, host.toAbsolutePath().toString());
    if (!r.ok()) {
      throw new IOException(
          String.format("docker cp %s out of %s failed: %s", containerPath, name, r.output()));
    }
    LOG.fine(String.format("Copied %s:%s to %s (%.1fs)", name, containerPath, host, secs(r)));
  }

  @Override
  public void close() throws IOException {
    LOG.info("Removing container " + name);
    docker("rm", "-f", name);
  }

  /**
   * Quotes {@code s} as a single bash word. Every value put into a script must go through this: a
   * space splits it, and a newline ends the command.
   */
  public static String quote(String s) {
    return "'" + s.replace("'", "'\\''") + "'";
  }

  /** Runs a docker command */
  private CommandResult docker(String... args) {
    List<String> cmd = new ArrayList<>();
    cmd.add("docker");
    cmd.addAll(List.of(args));
    if (LOG.isLoggable(Level.FINER)) {
      LOG.finer(String.join(" ", cmd));
    }
    CommandResult r = CommandRunner.run(cmd, null);
    if (r.exitCode() == -1) {
      LOG.warning(String.format("Could not run docker: %s", r.output()));
    }
    return r;
  }

  private static String parent(String containerPath) {
    int slash = containerPath.lastIndexOf('/');
    return slash <= 0 ? "/" : containerPath.substring(0, slash);
  }

  private static double secs(CommandResult r) {
    return r.elapsedTime() / 1e9;
  }
}
