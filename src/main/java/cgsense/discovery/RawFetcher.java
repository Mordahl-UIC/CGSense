package cgsense.discovery;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.stream.Collectors;

public class RawFetcher {
  private final HttpClient http = HttpClient.newBuilder()
      .followRedirects(HttpClient.Redirect.NORMAL)
      .connectTimeout(Duration.ofSeconds(10))
      .build();

  private final String owner, name;

  public RawFetcher(String owner, String name) {
    this.owner = owner;
    this.name = name;
  }

  String url(String sha, String path) {
    String encoded = Arrays.stream(path.split("/"))
        .map(seg -> URLEncoder.encode(seg, StandardCharsets.UTF_8).replace("+", "%20"))
        .collect(Collectors.joining("/"));
    return "https://raw.githubusercontent.com/%s/%s/%s/%s".formatted(owner, name, sha, encoded);
  }

  public String get(String sha, String path) throws IOException, InterruptedException {
    HttpRequest req = HttpRequest.newBuilder(URI.create(url(sha, path)))
        .timeout(Duration.ofSeconds(30))
        .GET().build();

    HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

    if (resp.statusCode() != 200) {
      throw new IOException(String.format("HTTP %d: %s", resp.statusCode(), resp.body()));
    }
    return resp.body();
  }
}
