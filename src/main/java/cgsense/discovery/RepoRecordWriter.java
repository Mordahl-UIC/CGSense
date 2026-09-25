package cgsense.discovery;

import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

public class RepoRecordWriter {

  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

  public void writeJsonLines(List<RepoRecord> records, Path path) throws Exception {
    try (Writer w = Files.newBufferedWriter(path)) {
      for (RepoRecord r : records) {
        w.write(mapper.writeValueAsString(r));
        w.write('\n');
      }
    }
  }
}
