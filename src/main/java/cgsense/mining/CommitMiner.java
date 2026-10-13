package cgsense.mining;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.util.io.DisabledOutputStream;

import cgsense.io.JsonlWriter;
import cgsense.model.CommitPair;
import cgsense.model.RepoRecord;

public class CommitMiner {

  private final Path clonesDir;
  private final Path outputFile;

  public CommitMiner(Path clonesDir, Path outputFile, boolean overwrite) {
    this.clonesDir = clonesDir;
    this.outputFile = outputFile;
    try {
      if (overwrite)
        Files.deleteIfExists(outputFile);
    } catch (Exception e) {
      System.err.println("Failed to delete existing output file.");
    }

  }

  public void mine(RepoRecord repo) {
    mine(repo.fullName(), repo.url() + ".git");
  }

  public void mine(String fullRepoName, String cloneUrl) {
    System.out.printf("Cloning and Mining: %s --- %s\n", fullRepoName, cloneUrl);
    String slug = fullRepoName.replace("/", "_");

    try (
        Repository repo = openRepo(cloneUrl, clonesDir.resolve(slug));
        RevWalk walk = new RevWalk(repo);
        DiffFormatter diff = new DiffFormatter(DisabledOutputStream.INSTANCE);
        JsonlWriter writer = JsonlWriter.open(this.outputFile, StandardOpenOption.APPEND, StandardOpenOption.CREATE)) {

      ObjectId head = repo.resolve(Constants.HEAD);
      if (head == null) {
        System.err.println("Empty repo.");
        return;
      }

      walk.markStart(walk.parseCommit(head));

      for (RevCommit child : walk) {
        if (child.getParentCount() == 0 || child.getParentCount() > 1) {
          continue;
        }
        RevCommit parent = child.getParent(0);

        writer.write(new CommitPair(fullRepoName, parent.name(), child.name()));

        break;
      }
    } catch (GitAPIException | IOException e) {
      System.out.println("Commit miner failed on " + fullRepoName + " with exception: " + e);
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
