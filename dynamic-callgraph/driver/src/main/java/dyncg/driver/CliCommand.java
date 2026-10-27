package dyncg.driver;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Options;

/**
 * One dyncg command. Same shape as CGSense's {@code cgsense.app.cmd.CliCommand}, so the commands
 * can be registered in CGSense's {@code Main} when dyncg is integrated.
 */
interface CliCommand {
    String name();

    Options options();

    int run(CommandLine cl) throws Exception;
}
