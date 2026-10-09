package cgsense.io;

import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

public class JsonlWriter<T> {

  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

  public void writeJsonLines(List<T> itemList, Path path) throws Exception {
    try (Writer w = Files.newBufferedWriter(path)) {
      for (T item : itemList) {
        w.write(mapper.writeValueAsString(item));
        w.write('\n');
      }
    }
  }
}
