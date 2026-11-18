package cgsense.discovery;

import io.github.cdimascio.dotenv.Dotenv;
import java.io.IOException;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;

/*
 quota: 5000 req/hr on fine-grained token
 analyzing: 100 repos @ 100 commits/repo (100 * 100) = 10000 req per run
 or 50 repos @ 100 commits/repo (50 * 100) = 5000 req per run
 Only needs to be done once to find the commits/repos we want to analyze?
* */

public final class GithubClientFactory {
  public static GitHub create() throws IOException {
    try {
    Dotenv dotenv = Dotenv.load();
    String token = dotenv.get("GITHUB_TOKEN");
      System.out.println("Found GitHub Token");
    return new GitHubBuilder().withOAuthToken(token).build();
    } catch (Exception e) {
      System.out.println("No GitHub Token. Limited requests.");
      return new GitHubBuilder().build();
    }
  }
}
