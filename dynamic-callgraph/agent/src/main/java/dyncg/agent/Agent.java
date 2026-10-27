package dyncg.agent;

import java.lang.instrument.Instrumentation;

/**
 * Java agent entry point.
 *
 * <p>Usage: {@code -javaagent:dyncg-agent.jar=include=org/example/,out=/abs/dir[,flush=60][,exclude=a/b/]}.
 */
public final class Agent {
    private Agent() {
    }

    public static void premain(String args, Instrumentation inst) {
        AgentConfig config;
        try {
            config = AgentConfig.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("[dyncg] invalid agent arguments, instrumentation disabled: " + e.getMessage());
            return;
        }
        Recorder.start(config);
        inst.addTransformer(new CallGraphTransformer(config), false);
    }
}
