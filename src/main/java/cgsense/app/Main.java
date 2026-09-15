package cgsense.app;

import org.kohsuke.github.GHCommit;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

import cgsense.astdiff.GumTreeRunner;
import cgsense.discovery.GithubClientFactory;
import cgsense.discovery.RawFetcher;

public class Main {

  public static void main(String[] args) {
    try {
      GumTreeRunner runner = new GumTreeRunner();

      GitHub client = GithubClientFactory.create();

      GHRepository repo = client.getRepository("jhy/jsoup");
      RawFetcher raw = new RawFetcher("jhy", "jsoup");
      System.out.printf("%s\n", repo.getDescription());

      for (GHCommit commit : repo.listCommits()) {
        if (commit.getParents().size() > 1)
          continue;

        String afterSha = commit.getSHA1();
        String beforeSha = commit.getParents().get(0).getSHA1();

        for (GHCommit.File file : commit.listFiles()) {
          if (file.getFileName().contains(".java")) {
            if (file.getLinesAdded() < 10) {

              String pathAfter = file.getFileName();
              String pathBefore = file.getPreviousFilename() != null
                  ? file.getPreviousFilename()
                  : file.getFileName();

              String before, after;

              try {
                before = raw.get(beforeSha, pathBefore);
                after = raw.get(afterSha, pathAfter);

                var r = runner.diff(before, after);
                r.script().forEach(a -> System.out.printf("%s\n\n", a.toString()));
                return;

              } catch (Exception e) {
                e.printStackTrace();
              }
            }
          }
        }
      }

    } catch (Exception e) {
      e.printStackTrace();
    }
  }
}
