package cgsense.discovery;

import java.util.List;

public record Criteria(
    int minStars, List<String> buildSystems) {
}
