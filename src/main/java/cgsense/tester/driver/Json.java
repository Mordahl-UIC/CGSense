package cgsense.tester.driver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/** The shared JSON mapper. Maps are written in insertion order and nulls are kept. */
public final class Json {
    static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {
    }

    public static String compact(Object value) throws JsonProcessingException {
        return MAPPER.writeValueAsString(value);
    }

    public static String pretty(Object value) throws JsonProcessingException {
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
    }

    static void writePretty(Path path, Object value) throws IOException {
        Files.writeString(path, pretty(value) + "\n");
    }
}
