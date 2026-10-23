package cgsense.exec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

public class CommandRunner {

  public static CommandResult run(Path dir, String... argv) {
    return run(List.of(argv), dir);
  }

  public static CommandResult run(List<String> argv, Path dir) {
    long start = System.nanoTime();
    argv = List.copyOf(argv);

    ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(true);

    if (dir != null)
      pb.directory(dir.toFile());

    Process p;
    try {
      p = pb.start();
      String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).stripTrailing();
      int exit = p.waitFor();
      return new CommandResult(argv, exit, System.nanoTime() - start, output);
    } catch (IOException e) {
      return new CommandResult(argv, -1, System.nanoTime() - start, e.getMessage());
    } catch (InterruptedException e) {
      return new CommandResult(argv, -1, System.nanoTime() - start, e.getMessage());
    }
  }
}
