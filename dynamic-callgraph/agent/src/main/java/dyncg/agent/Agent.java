package dyncg.agent;

import java.lang.instrument.Instrumentation;

/**
 * Agent is the agent's entry point. Its premain method runs when the test JVM starts, before the program's own main.
 * It reads the agent option and checks for validity. if it's valid it starts the recorder and dump writer and registers CallGraphTransformer. The JVM then passes every class it loads through the transformer, which rewrites the matching ones to record their calls.
 * No Java file calls Agent. The JVM calls it, and two places set that up:
 *TestRunner.java builds the flag → Maven or Gradle starts the test JVM with it → the JVM reads Premain-Class from the jar's manifest → the JVM calls Agent.premain(args, inst). When Maven builds dyncg-agent.jar, this line "<Premain-Class>dyncg.agent.Agent</Premain-Class> goes into the jar's manifest. It tells the JVM to run dyncg.agent.Agent.premain when the jar is used as an agent.
*the -javaagent flag loads an agent.
 */
public final class Agent {
    private Agent() {//The class is only a holder for the static premain method, and you never need an object of it
    }
  //premain is a special name. When a test JVM starts with -javaagent:dyncg-agent.jar=..., the JVM calls this method before the program's normal main runs.
    public static void premain(String args, Instrumentation inst) {//String args is the text after the = in the -javaagent flag, for example include=demo/,out=/path/dir,flush=60. The driver's TestRunner builds this string.
                                                                    //Instrumentation inst is an object the JVM hands over. It gives the agent the power to change classes as they are loaded.
        AgentConfig config;
        try {
            config = AgentConfig.parse(args); //This turns the raw string args into a structured config object: which packages to include or exclude, the output folder, the flush interval, and so on. AgentConfig.java has the details.
        } catch (IllegalArgumentException e) {//If the arguments are malformed, parse throws IllegalArgumentException, and this block catches it.
            System.err.println("[dyncg] invalid agent arguments, instrumentation disabled: " + e.getMessage());
            return; //return then exits premain early, so no instrumentation happens. 
        }
        //This starts the runtime side of the agent. Recorder holds the call stacks and edge counts while the tests run. Inside start, it also calls Dumper.start(config). The Dumper writes the JSON dump every few seconds and once more at JVM shutdown.
        //
        Recorder.start(config);//This starts the runtime side of the agent. Recorder holds the call stacks and edge counts while the tests run. Inside start, it also calls Dumper.start(config). The Dumper writes the JSON dump every few seconds and once more at JVM shutdown.
        inst.addTransformer(new CallGraphTransformer(config), false);//This creates a CallGraphTransformer and registers it with the JVM.From now on, every class the JVM loads is passed to this transformer first. 
                                                                    //If the class matches the include list, the transformer rewrites its bytecode so each method calls Recorder on entry and exit. The chain is CallGraphTransformer → ClassInstrumenter → MethodInstrumenter.
    }
}
