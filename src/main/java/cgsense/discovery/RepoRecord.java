package cgsense.discovery;

public record RepoRecord(
    String fullName,
    String url,
    String description,
    int stars,
    int forks,
    String license) {
}
