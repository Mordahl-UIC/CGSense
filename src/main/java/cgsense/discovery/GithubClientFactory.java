package cgsense.discovery;

import cgsense.app.Log;
import io.github.cdimascio.dotenv.Dotenv;
import java.io.IOException;
import java.util.logging.Logger;
import org.kohsuke.github.GHRateLimit;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;

/*
 quota: 5000 req/hr on fine-grained token
 analyzing: 100 repos @ 100 commits/repo (100 * 100) = 10000 req per run
 or 50 repos @ 100 commits/repo (50 * 100) = 5000 req per run
 Only needs to be done once to find the commits/repos we want to analyze?
* */

public final class GithubClientFactory {
  private static final Logger LOG = Log.get(GithubClientFactory.class);

  public static GitHub create() throws IOException {
    String token = null;
    try {
      token = Dotenv.configure().ignoreIfMissing().load().get("GITHUB_TOKEN");
    } catch (Exception e) {
      LOG.warning("Could not read .env: " + e.getMessage());
    }

    GitHub gh;
    if (token != null && !token.isBlank()) {
      LOG.info("Using GITHUB_TOKEN");
      gh = new GitHubBuilder().withOAuthToken(token).build();
    } else {
      LOG.warning("No GITHUB_TOKEN in .env or environment; unauthenticated, requests are limited");
      gh = new GitHubBuilder().build();
    }

    try {
      GHRateLimit.Record core = gh.getRateLimit().getCore();
      LOG.info(
          String.format(
              "GitHub rate limit: %d/%d remaining, resets %s",
              core.getRemaining(), core.getLimit(), core.getResetDate()));
    } catch (IOException e) {
      LOG.warning("Could not query GitHub rate limit: " + e.getMessage());
    }
    return gh;
  }
}
