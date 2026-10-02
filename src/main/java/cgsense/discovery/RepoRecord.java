package cgsense.discovery;

public record RepoRecord(
    String fullName,
    String url,
    int stars,
    String license,
    String buildSystem,
    int javaFileCount,
    String entryPointKind) {
}
