package cgsense.model;

import java.util.List;

public record CommitPair(
    String fullName,
    String parentSha,
    String childSha,
    int javaVersion,
    String buildTool,
    List<ChangedFile> files) {

  private String shortSha(String sha) {
    return sha.length() > 7 ? sha.substring(0, 7) : sha;
  }

  public Commit parent() {
    return new Commit(parentSha, BuildTool.parse(buildTool));
  }

  public Commit child() {
    return new Commit(childSha, BuildTool.parse(buildTool));
  }

  public String ID() {
    return shortSha(this.parentSha()) + "-" + shortSha(this.childSha());
  }
}
