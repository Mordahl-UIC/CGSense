package cgsense.io;

import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

public class JsonlWriter {
  private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

  public static <T> void writeMultiple(List<T> itemList, Path file) throws Exception {
    try (Writer w = Files.newBufferedWriter(file)) {
      for (T item : itemList) {
        w.write(MAPPER.writeValueAsString(item));
        w.write('\n');
      }
    }
  }

  public static <T> void write(T item, Path path) throws Exception {
    try (Writer w = Files.newBufferedWriter(path)) {
      w.write(MAPPER.writeValueAsString(item));
      w.write('\n');
    }
  }
}
