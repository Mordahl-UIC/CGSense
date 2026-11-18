package cgsense.mining;

import cgsense.app.DataDir;
import cgsense.app.Log;
import cgsense.exec.BuildContainer;
import cgsense.io.JsonlWriter;
import cgsense.model.ChangedFile;
import cgsense.model.CommitPair;
import cgsense.model.RepoRecord;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.lib.AbbreviatedObjectId;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.util.io.DisabledOutputStream;

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
  private static final Logger LOG = Log.get(CommitMiner.class);
  private static final int PROGRESS_EVERY = 10;

  private final DataDir data;
  private final JsonlWriter writer;
  private static final int MAX_PAIRS_PER_REPO = 100;

  /**
   * @param data where bare clones are kept; the build containers mount them read-only
   * @param writer destination for accepted pairs
   */
  public CommitMiner(DataDir data, JsonlWriter writer) {
    this.data = data;
    this.writer = writer;
  }

  /** Mines one repo and returns how many pairs were written. */
  public int mine(RepoRecord repo) {
    return mine(repo.fullName(), repo.url() + ".git");
  }

  public int mine(String fullRepoName, String cloneUrl) {
    LOG.info(String.format("Mining %s (%s)", fullRepoName, cloneUrl));
    Path gitDir = data.clone(fullRepoName);

    if (Files.exists(gitDir)) {
      LOG.info("Reusing existing clone " + gitDir);
    } else {
      try {
        LOG.info(String.format("Cloning %s into %s", fullRepoName, gitDir));
        long start = System.nanoTime();
        Git.cloneRepository()
            .setURI(cloneUrl)
            .setDirectory(gitDir.toFile())
            .setBare(true)
            .call()
            .close();
        LOG.info(
            String.format("Cloned %s in %s", fullRepoName, seconds(System.nanoTime() - start)));
      } catch (GitAPIException e) {
        LOG.severe(String.format("Failed to clone %s: %s", fullRepoName, e.getMessage()));
        return 0;
      }
    }

    int walked = 0;
    int nonLinear = 0;
    int rejected = 0;
    int candidates = 0;
    int buildFailed = 0;
    int accepted = 0;
    LOG.info("Starting build container for " + fullRepoName);
    try (BuildContainer box = new BuildContainer(DataDir.slug(fullRepoName), gitDir);
        Repository repo = new FileRepositoryBuilder().setGitDir(gitDir.toFile()).build();
        RevWalk walk = new RevWalk(repo);
        DiffFormatter diff = new DiffFormatter(DisabledOutputStream.INSTANCE)) {

      ObjectId head = repo.resolve(Constants.HEAD);
      if (head == null) {
        LOG.warning(String.format("%s is empty (no HEAD); skipping", fullRepoName));
        return 0;
      }

      diff.setRepository(repo);
      walk.markStart(walk.parseCommit(head));

      LOG.info(String.format("Walking commit history of %s from %s", fullRepoName, short7(head)));
      for (RevCommit child : walk) {
        if (++walked % PROGRESS_EVERY == 0) {
          LOG.info(
              String.format(
                  "%s: walked %d commits, %d candidates, %d accepted",
                  fullRepoName, walked, candidates, accepted));
        }
        if (child.getParentCount() == 0 || child.getParentCount() > 1) {
          nonLinear++;
          LOG.fine(
              String.format(
                  "%s skip: %s",
                  short7(child), child.getParentCount() == 0 ? "root commit" : "merge commit"));
          continue;
        }
        RevCommit parent = child.getParent(0);

        Optional<List<ChangedFile>> files = candidateFiles(repo, diff, parent, child);
        if (files.isEmpty()) {
          rejected++;
          continue;
        }

        candidates++;
        LOG.info(
            String.format(
                "%s candidate (%d files, %d main java lines): %s",
                short7(child),
                files.get().size(),
                mainJavaLines(files.get()),
                child.getShortMessage()));
        int javaVersion = box.buildPair(parent.name(), child.name());
        if (javaVersion <= 0) {
          buildFailed++;
          LOG.info(
              String.format("%s rejected: no JDK builds both parent and child", short7(child)));
          continue;
        }

        String buildTool = getRootFiles(repo, child).contains("gradlew") ? "gradle" : "maven";
        CommitPair pair =
            new CommitPair(
                fullRepoName, parent.name(), child.name(), javaVersion, buildTool, files.get());
        writer.write(pair);
        accepted++;
        LOG.info(
            String.format(
                "%s accepted on JDK %d with %s [%d/%d]",
                short7(child), javaVersion, buildTool, accepted, MAX_PAIRS_PER_REPO));
        if (accepted >= MAX_PAIRS_PER_REPO) {
          LOG.info(
              String.format("%s: reached %d pairs, stopping", fullRepoName, MAX_PAIRS_PER_REPO));
          break;
        }
      }
    } catch (IOException e) {
      LOG.log(Level.SEVERE, "Commit miner failed on " + fullRepoName, e);
    }

    LOG.info(
        String.format(
            "Finished %s: walked %d commits (%d root/merge, %d filtered out), %d candidates,"
                + " %d failed to build, %d accepted",
            fullRepoName, walked, nonLinear, rejected, candidates, buildFailed, accepted));
    return accepted;
  }

  private static String short7(ObjectId id) {
    return id.abbreviate(7).name();
  }

  private static String seconds(long nanos) {
    return String.format("%.1fs", nanos / 1e9);
  }

  private static int mainJavaLines(List<ChangedFile> files) {
    return files.stream()
        .filter(f -> isMainJava(f.filePath()))
        .mapToInt(f -> f.numLinesAdded() + f.numLinesDeleted())
        .sum();
  }

  /**
   * Applies the selection criteria to one parent/child pair and returns the changed files when the
   * pair is accepted. Does not build anything.
   */
  static Optional<List<ChangedFile>> candidateFiles(
      Repository repo, DiffFormatter diff, RevCommit parent, RevCommit child) throws IOException {
    Set<String> childRootFiles = getRootFiles(repo, child);
    Set<String> parentRootFiles = getRootFiles(repo, parent);
    boolean childHasBuildSystem = childRootFiles.stream().anyMatch(CommitMiner::isBuildFile);
    boolean parentHasBuildSystem = parentRootFiles.stream().anyMatch(CommitMiner::isBuildFile);
    if (!childHasBuildSystem || !parentHasBuildSystem) {
      LOG.fine(
          String.format(
              "%s skip: no root build file in %s",
              short7(child), childHasBuildSystem ? "parent" : "child"));
      return Optional.empty();
    }
    if (onlyCommentsOrFormatting(repo, diff, parent, child)) {
      LOG.fine(String.format("%s skip: no code change in main java files", short7(child)));
      return Optional.empty();
    }

    List<ChangedFile> files = changedFiles(diff, parent, child);
    String reason = rejectReason(files);
    if (reason != null) {
      LOG.fine(String.format("%s skip: %s", short7(child), reason));
      return Optional.empty();
    }
    return Optional.of(files);
  }

  private static final int MAX_JAVA_LINES = 20;

  /** Returns why the pair is rejected, or null when it is selected. */
  private static String rejectReason(List<ChangedFile> files) {
    int javaLines = 0;
    for (ChangedFile f : files) {
      String p = f.filePath();
      if (isTest(p)) {
        return "touches test file " + p;
      }
      if (isBuildFile(p)) {
        return "touches build file " + p;
      }
      if (isMainJava(p)) {
        javaLines += f.numLinesAdded() + f.numLinesDeleted();
      }
    }

    if (javaLines == 0) {
      return "no main java lines changed";
    }
    if (javaLines >= MAX_JAVA_LINES) {
      return String.format("%d main java lines changed >= max %d", javaLines, MAX_JAVA_LINES);
    }

    return null;
  }

  private static boolean onlyCommentsOrFormatting(
      Repository repo, DiffFormatter diff, RevCommit parent, RevCommit child) throws IOException {
    for (DiffEntry e : diff.scan(parent, child)) {
      String path = e.getNewPath();
      if (!isMainJava(path)) continue;

      String before = read(repo, e.getOldId());
      String after = read(repo, e.getNewId());

      if (!JavaCode.sameCode(before, after)) {
        return false;
      }
    }

    return true;
  }

  private static Set<String> getRootFiles(Repository repo, RevCommit commit) throws IOException {
    TreeWalk treeWalk = new TreeWalk(repo);
    treeWalk.addTree(commit.getTree());

    Set<String> out = new HashSet<>();
    while (treeWalk.next()) {
      out.add(treeWalk.getPathString());
    }

    treeWalk.close();

    return out;
  }

  private static String read(Repository repo, AbbreviatedObjectId id) throws IOException {
    ObjectId oid = id.toObjectId();
    if (oid.equals(ObjectId.zeroId())) return "";
    return new String(repo.open(oid, Constants.OBJ_BLOB).getBytes(), StandardCharsets.UTF_8);
  }

  private static List<ChangedFile> changedFiles(
      DiffFormatter diff, RevCommit parent, RevCommit child) {
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
      LOG.warning(
          String.format(
              "Could not diff %s..%s: %s", short7(parent), short7(child), e.getMessage()));
    }

    return files;
  }

  private static boolean isMainJava(String p) {
    return p.endsWith(".java") && !isTest(p) && !isGenerated(p);
  }

  private static boolean isTest(String p) {
    String s = "/" + p;
    return s.contains("/test/") || s.contains("/tests/");
  }

  private static boolean isGenerated(String p) {
    return ("/" + p).contains("/generated/");
  }

  private static boolean isBuildFile(String p) {
    String fileName = p.substring(p.lastIndexOf('/') + 1);
    return fileName.equals("pom.xml")
        || fileName.startsWith("build.gradle")
        || fileName.startsWith("settings.gradle")
        || fileName.equals("gradle.properties");
  }
}
