package cgsense.model;

/** A commit to check out: its SHA and the build tool it builds with. */
public record Commit(String sha, BuildTool buildTool) {}
