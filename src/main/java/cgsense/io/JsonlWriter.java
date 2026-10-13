package cgsense.io;

import java.io.Closeable;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;

public class JsonlWriter implements Closeable {
  private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
  private final Writer out;

  private JsonlWriter(Writer out) {
    this.out = out;
  }

  public static JsonlWriter open(Path file, OpenOption... options) throws IOException {
    return new JsonlWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8, options));
  }

  public <T> void write(T item) throws IOException {
    out.write(MAPPER.writeValueAsString(item));
    out.write('\n');
  }

  @Override
  public void close() throws IOException {
    out.close();
  }

}
