package cgsense.model;

public record ChangedFile(
    String filePath,
    int numLinesAdded,
    int numLinesDeleted) {
}
