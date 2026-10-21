package cgsense.app.cmd;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Options;

public interface CliCommand {
  String name();

  Options options();

  int run(CommandLine cl) throws Exception;
}
