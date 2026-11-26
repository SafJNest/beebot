package com.safjnest.lol.champion;

import java.util.List;

import org.bson.Document;
import org.json.JSONObject;
import org.junit.Test;

import com.safjnest.database.nosql.MongoDB.ChampionRawMatch;
import com.safjnest.lol.model.Filter;
import com.safjnest.lol.service.ChampionService;

import no.stelar7.api.r4j.basic.constants.api.regions.LeagueShard;
import no.stelar7.api.r4j.basic.constants.types.lol.GameQueueType;
import no.stelar7.api.r4j.basic.constants.types.lol.LaneType;
import no.stelar7.api.r4j.basic.constants.types.lol.TierType;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ChampionInputTest {

    @Test
    public void selectionUsesTheMatchingParticipantAndTheMatchScope() {
        Document jinx = new Document("champion", 222).append("lane", "BOT");
        Document ahri = new Document("champion", 103).append("lane", "MID");
        Document match = new Document("_id", "EUW1_1").append("region", "EUW1").append("rank", "DIAMOND")
            .append("participants", List.of(jinx, ahri));
        ChampionRawMatch raw = new ChampionRawMatch(match, null, false);
        Filter filter = Filter.championBuild(222, "16.12", GameQueueType.TEAM_BUILDER_RANKED_SOLO)
            .setLane(LaneType.BOT).setRegion(LeagueShard.EUW1).setRank(TierType.EMERALD);

        assertTrue(ChampionBuildProvider.matches(filter, raw, jinx));
        assertFalse(ChampionBuildProvider.matches(filter, raw, ahri));
        assertFalse(ChampionBuildProvider.matches(filter.setRankBehavior(Filter.RankBehavior.EXACT), raw, jinx));
        assertTrue(ChampionBuildProvider.matches(filter.setRank(TierType.DIAMOND), raw, jinx));
    }

    @Test
    public void championKeyDropsDiscordContextAndSplitPeriod() {
        Filter filter = Filter.championBuild(222, "16.12", GameQueueType.TEAM_BUILDER_RANKED_SOLO)
            .setLane(LaneType.BOT).setRank(TierType.EMERALD);
        Filter contextual = Filter.championBuild(222, "16.12", GameQueueType.TEAM_BUILDER_RANKED_SOLO)
            .setLane(LaneType.BOT).setRank(TierType.EMERALD).setDuo(412).setOpponent(51).setPeriod(10, 20);

        assertEquals(filter.toKey(), ChampionService.dataFilter(contextual).toKey());
        assertEquals(filter.pageKey(), ChampionService.dataFilter(contextual).pageKey());
    }

    @Test
    public void statsBorrowTheReaderTimelineOnlyWhenItIsUsable() {
        Document match = new Document("_id", "EUW1_1");
        JSONObject timeline = new JSONObject();
        assertSame(timeline, ChampionStatsProvider.read(new ChampionRawMatch(match, timeline, true)).metadata().events());
        assertNull(ChampionStatsProvider.read(new ChampionRawMatch(match, timeline, false)).metadata().events());
    }
}
