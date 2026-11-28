package com.safjnest.lol.model.summoner;

import java.util.Map;

import no.stelar7.api.r4j.basic.constants.api.regions.LeagueShard;
import no.stelar7.api.r4j.basic.constants.types.lol.GameQueueType;

public record LinkedSummoner(String puuid, String riotId, LeagueShard region, Rank rank, boolean tracking) {
    public static LinkedSummoner from(Summoner summoner) {
        Map<GameQueueType, Rank> ranks = summoner.ranks();
        Rank rank = ranks == null ? null : ranks.get(GameQueueType.RANKED_SOLO_5X5);

        return new LinkedSummoner(summoner.puuid(), summoner.riotId(), summoner.region(), rank, summoner.tracking());
    }
}
