package cgsense.mining;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import cgsense.model.RepoRecord;

public class CommitMiner {

  private final Path clonesDir;
  private final Path outputFile;
  private final boolean overwrite;

  public CommitMiner(Path clonesDir, Path outputFile, boolean overwrite) {
    this.clonesDir = clonesDir;
    this.outputFile = outputFile;
    this.overwrite = overwrite;
  }

  public void mine(RepoRecord repo) {
    mine(repo.fullName(), repo.url() + ".git");
  }

  public void mine(String fullRepoName, String cloneUrl) {
    System.out.printf("Cloning and Mining: %s --- %s\n", fullRepoName, cloneUrl);
    String slug = fullRepoName.replace("/", "_");

    try (Repository repo = openRepo(cloneUrl, clonesDir.resolve(slug))) {
      return;
    } catch (Exception e) {
      e.printStackTrace();
      return;
    }
  }

  /*
   * Clone if repo doesnt exist in memory.
   */
  private Repository openRepo(String cloneUrl, Path gitDir) throws IOException, GitAPIException {
    if (!Files.exists(gitDir)) {

      Files.createDirectories(gitDir);

      Git.cloneRepository()
          .setURI(cloneUrl)
          .setDirectory(gitDir.toFile())
          .setBare(true)
          .call()
          .close();
    }
    return new FileRepositoryBuilder().setGitDir(gitDir.toFile()).setMustExist(true).build();
  }

}
