package cgsense.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ContainerTest {
  @Test
  void quoteSurvivesBash() throws Exception {
    String value = "it's a\nline $HOME";
    Process p =
        new ProcessBuilder("bash", "-c", "printf %s " + Container.quote(value))
            .redirectErrorStream(true)
            .start();
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(0, p.waitFor());
    assertEquals(value, out);
  }
}
