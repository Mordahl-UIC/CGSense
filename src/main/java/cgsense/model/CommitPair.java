package cgsense.model;

public record CommitPair(
    String fullName,
    String parentSha,
    String childSha) {
}
