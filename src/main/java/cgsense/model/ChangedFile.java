package cgsense.model;

public record ChangedFile(
    String fileName,
    int numLinesAdded,
    int numLinesDeleted) {
}
