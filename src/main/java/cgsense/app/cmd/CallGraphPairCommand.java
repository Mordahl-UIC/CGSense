package cgsense.app.cmd;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Options;

import cgsense.io.JsonlReader;
import cgsense.model.CommitPair;
import cgsense.tester.driver.FileUtil;
import cgsense.tester.driver.PairCallGraphRunner;
import cgsense.tester.driver.RunOptions;
import cgsense.tester.driver.UsageException;

/**
 * Generates call graphs and a diff for an already-checked-out commit pair.
 */

public final class CallGraphPairCommand
        implements CliCommand {

    @Override
    public String name() {
        return "callgraph-pair";
    }

    @Override
    public Options options() {
        return RunOptions.addTo(
            new Options()
                .addOption(
                    RunOptions.required(
                        "pair-file",
                        "file",
                        "JSONL file containing one commit pair"
                    )
                )
                .addOption(
                    RunOptions.required(
                        "before-project",
                        "dir",
                        "checked-out parent project"
                    )
                )
                .addOption(
                    RunOptions.required(
                        "after-project",
                        "dir",
                        "checked-out child project"
                    )
                )
                .addOption(
                    RunOptions.required(
                        "out",
                        "dir",
                        "output directory"
                    )
                )
        );
    }

    @Override
    public int run(CommandLine cl)
            throws Exception {

        if (!cl.hasOption("build-tool")) {
            throw new UsageException(
                "--build-tool is required; "
                    + "use maven or gradle");
        }

        Path pairFile =
            FileUtil.abs(
                RunOptions.path(cl, "pair-file")
            );

        Path beforeProject =
            FileUtil.abs(
                RunOptions.path(
                    cl,
                    "before-project"
                )
            );

        Path afterProject =
            FileUtil.abs(
                RunOptions.path(
                    cl,
                    "after-project"
                )
            );

        Path outputDirectory =
            FileUtil.abs(
                RunOptions.path(cl, "out")
            );

        CommitPair pair =
            readOnePair(pairFile);

        RunOptions runOptions =
            RunOptions.from(cl);

        PairCallGraphRunner.run(
            pair,
            beforeProject,
            afterProject,
            outputDirectory,
            runOptions
        );

        return 0;
    }

    private static CommitPair readOnePair(
            Path pairFile)
            throws Exception {

        List<CommitPair> pairs;

        try (Stream<CommitPair> stream =
                 JsonlReader.read(
                     pairFile,
                     CommitPair.class)) {

            /*
             * Reading at most two entries lets us distinguish between an
             * empty file, exactly one pair, and more than one pair.
             */
            pairs = stream.limit(2).toList();
        }

        if (pairs.isEmpty()) {
            throw new UsageException(
                "pair file contains no commit pair: "
                    + pairFile);
        }

        if (pairs.size() > 1) {
            throw new UsageException(
                "pair file must contain exactly "
                    + "one commit pair: "
                    + pairFile);
        }

        return pairs.get(0);
    }
}