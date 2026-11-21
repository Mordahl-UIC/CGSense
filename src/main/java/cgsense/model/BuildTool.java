package cgsense.model;

/**
 * Build system used to compile and run a checked-out project. CommitMiner decides it and records
 * it in the pair, so nothing downstream detects it again.
 */
public enum BuildTool {
  MAVEN,
  GRADLE;

  /** Parses a pair's "maven" or "gradle", in any case. */
  public static BuildTool parse(String value) {
    try {
      return valueOf(value.toUpperCase());
    } catch (IllegalArgumentException | NullPointerException error) {
      throw new IllegalArgumentException(
          "unknown build tool '" + value + "' (expected maven or gradle)");
    }
  }
}
