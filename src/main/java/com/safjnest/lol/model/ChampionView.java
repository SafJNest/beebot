package com.safjnest.lol.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

public record ChampionView(
    Champion champion,
    ChampionStatistics stats,
    @JsonInclude(JsonInclude.Include.NON_NULL) Build build,
    @JsonInclude(JsonInclude.Include.NON_NULL) List<Build> builds,
    @JsonInclude(JsonInclude.Include.NON_NULL) String defaultBuildId,
    @JsonInclude(JsonInclude.Include.NON_NULL) String bestAdjustedWinrateBuildId,
    ResponseMetadata metadata
) {

    public ChampionView(Champion champion, ChampionStatistics stats, ChampionBuildSnapshot snapshot, Build arenaBuild) {
        this(champion, stats, arenaBuild, snapshot == null ? null : snapshot.builds(),
            snapshot == null ? null : snapshot.defaultBuildId(),
            snapshot == null ? null : snapshot.bestAdjustedWinrateBuildId(), null);
    }

    public ChampionView withStatistics(ChampionStatistics statistics, ResponseMetadata responseMetadata) {
        return new ChampionView(champion, statistics, build, builds, defaultBuildId,
            bestAdjustedWinrateBuildId, responseMetadata);
    }

    public record Champion(
        int id,
        String name,
        String image
    ) {}
}
