package cgsense.io;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class JsonlReader {
  private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

  public static <T> Stream<T> read(Path file, Class<T> type) throws IOException {
    BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8);
    AtomicLong lineNo = new AtomicLong();
    return reader.lines()
        .peek(line -> lineNo.incrementAndGet())
        .filter(line -> !line.isBlank())
        .map(line -> parse(line, type, file, lineNo.get()))
        .onClose(() -> {
          try {
            reader.close();
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        });
  }

  public static <T> T parse(String line, Class<T> type, Path file, long LineNo) {
    try {
      return MAPPER.readValue(line, type);
    } catch (JsonProcessingException e) {
      throw new UncheckedIOException(file + ":" + LineNo + " is not valid " + type.getSimpleName(), e);
    }
  }

}
