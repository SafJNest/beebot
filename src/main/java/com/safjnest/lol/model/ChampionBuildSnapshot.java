package com.safjnest.lol.model;

import java.util.List;
import java.util.Objects;

public record ChampionBuildSnapshot(Filter filter, List<Build> builds, String defaultBuildId,
                                    String bestAdjustedWinrateBuildId) {

    public ChampionBuildSnapshot {
        Objects.requireNonNull(filter);
        builds = List.copyOf(builds);

        String firstId = null;
        boolean bestFound = bestAdjustedWinrateBuildId == null;
        for (Build build : builds) {
            if (build.coreBuilds() == null || build.coreBuilds().size() != 1) {
                throw new IllegalArgumentException("A build variant must contain one core");
            }
            String id = Objects.requireNonNull(build.coreBuilds().getFirst().id());
            if (firstId == null) {
                firstId = id;
            }
            bestFound |= id.equals(bestAdjustedWinrateBuildId);
        }
        if (!Objects.equals(defaultBuildId, firstId) || !bestFound) {
            throw new IllegalArgumentException("Champion build selection does not match the variants");
        }
    }
}
