package cgsense.discovery;

import java.util.List;

public record RepoCriteria(
    int minStars,
    List<String> buildSystems,
    boolean excludeForks,
    boolean excludeArchived,
    boolean excludeAndroid,
    int minJavaFiles,
    int maxModules,
    boolean requireMainOrTestEntryPoint) {
}
