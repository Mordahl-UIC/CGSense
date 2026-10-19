package cgsense.exec;

import java.util.List;

public record CommandResult(
    List<String> argv,
    int exitCode,
    long elapsedTime,
    String output) {

  public boolean ok() {
    return exitCode == 0;
  }
}
