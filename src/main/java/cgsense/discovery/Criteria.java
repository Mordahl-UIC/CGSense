package cgsense.discovery;

import java.util.List;

public record Criteria(
    int minStars,
    List<String> buildSystems,
    boolean excludeForks,
    boolean excludeArchived,
    boolean excludeAndroid,
    int minJavaFiles,
    int maxModules,
    boolean requireMainOrTestEntryPoint) {
}
