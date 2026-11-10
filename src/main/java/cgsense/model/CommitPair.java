package cgsense.model;

import java.util.List;

public record CommitPair(
    String fullName,
    String parentSha,
    String childSha,
    int javaVersion,
    List<ChangedFile> files) {
}
