package cgsense.mining;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
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
import cgsense.model.ChangedFile;

/*
 * Criteria for choosing commits
 * Exactly 1 parent commit
 * Atleast one .java file changed
 * No build file changed
 * No test file changed
 * At most 100 changes application lines?
 * Parent and Child both compile including tests on the same JDK
 * */

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
    Path gitDir = clonesDir.resolve(slug);

    if (!Files.exists(gitDir)) {
      try {
        Git.cloneRepository()
            .setURI(cloneUrl)
            .setDirectory(gitDir.toFile())
            .setBare(true)
            .call()
            .close();
      } catch (GitAPIException e) {
        System.err.println("Failed to clone repo: " + fullRepoName);
        return;
      }
    }

    try (
        Repository repo = new FileRepositoryBuilder().setGitDir(gitDir.toFile()).build();
        RevWalk walk = new RevWalk(repo);
        DiffFormatter diff = new DiffFormatter(DisabledOutputStream.INSTANCE);
        JsonlWriter writer = JsonlWriter.open(this.outputFile, StandardOpenOption.APPEND, StandardOpenOption.CREATE)) {

      ObjectId head = repo.resolve(Constants.HEAD);
      if (head == null) {
        System.err.println("Empty repo.");
        return;
      }

      diff.setRepository(repo);
      walk.markStart(walk.parseCommit(head));

      for (RevCommit child : walk) {
        if (child.getParentCount() == 0 || child.getParentCount() > 1) {
          continue;
        }
        RevCommit parent = child.getParent(0);

        List<ChangedFile> files = changedFiles(diff, parent, child);
        CommitPair pair = select(new CommitPair(fullRepoName, parent.name(), child.name(), files));
        if (pair != null) {
          writer.write(pair);
        }
      }
    } catch (IOException e) {
      System.out.println("Commit miner failed on " + fullRepoName + " with exception: " + e);
      return;
    }
  }

  private static final int MAX_JAVA_LINES = 20;

  private CommitPair select(CommitPair pair) {
    int javaLines = 0;
    for (ChangedFile f : pair.files()) {
      String p = f.filePath();
      if (isTest(p) || isBuildFile(p)) {
        return null;
      }
      if (isMainJava(p)) {
        javaLines += f.numLinesAdded() + f.numLinesDeleted();
      }
    }

    if (javaLines == 0 || javaLines >= MAX_JAVA_LINES) {
      return null;
    }

    return pair;
  }

  private List<ChangedFile> changedFiles(DiffFormatter diff, RevCommit parent, RevCommit child) {
    List<ChangedFile> files = new ArrayList<>();

    try {
      for (DiffEntry entry : diff.scan(parent, child)) {
        int added = 0;
        int deleted = 0;
        for (Edit edit : diff.toFileHeader(entry).toEditList()) {
          deleted += edit.getLengthA();
          added += edit.getLengthB();
        }

        files.add(new ChangedFile(entry.getNewPath(), added, deleted));
      }
    } catch (IOException e) {
    }

    return files;
  }

  private boolean isMainJava(String p) {
    return p.endsWith(".java") && !isTest(p) && !isGenerated(p);

  }

  private boolean isTest(String p) {
    String s = "/" + p;
    return s.contains("/test/") || s.contains("/tests/");
  }

  private boolean isGenerated(String p) {
    return ("/" + p).contains("/generated/");
  }

  private boolean isBuildFile(String p) {
    String fileName = p.substring(p.lastIndexOf('/') + 1);
    return fileName.equals("pom.xml")
        || fileName.startsWith("build.gradle")
        || fileName.startsWith("settings.gradle")
        || fileName.equals("gradle.properties");

  }
}
