package com.safjnest.lol.service;

import java.util.List;

import org.junit.Test;

import com.safjnest.lol.model.ChampionIndexable;
import com.safjnest.lol.model.Filter;
import com.safjnest.lol.model.statistics.shared.ChampionStatsScope;

import no.stelar7.api.r4j.basic.constants.api.regions.LeagueShard;
import no.stelar7.api.r4j.basic.constants.types.lol.GameQueueType;
import no.stelar7.api.r4j.basic.constants.types.lol.LaneType;
import no.stelar7.api.r4j.basic.constants.types.lol.TierType;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

public class ChampionFilterTest {

    private static final int KAISA = 145;
    private static final String PATCH = "16.23";

    @Test
    public void omittedRoleUsesSameBuildKeyAsExplicitAdc() {
        ChampionService service = new ChampionService() {
            @Override
            public List<ChampionIndexable> getIndexables() {
                return List.of(new ChampionIndexable(99, LaneType.MID, 5000, true, 0),
                    new ChampionIndexable(KAISA, LaneType.BOT, 4000, true, 0),
                    new ChampionIndexable(KAISA, LaneType.MID, 1000, true, 0));
            }
        };

        Filter api = service.resolveFilter(KAISA, PATCH, null, null, null, null);
        Filter discord = service.resolveFilter(KAISA, PATCH, null, null,
            GameQueueType.TEAM_BUILDER_RANKED_SOLO, LaneType.BOT).setOpponent(99).setDuo(412);
        Filter explicitMid = service.resolveFilter(KAISA, PATCH, null, null, null, LaneType.MID);
        Filter discordData = ChampionService.dataFilter(discord);

        assertEquals(LaneType.BOT, api.lane());
        assertEquals(GameQueueType.TEAM_BUILDER_RANKED_SOLO, api.queue());
        assertNull(api.rank());
        assertNull(api.region());
        assertEquals(api.toKey(), discordData.toKey());
        assertEquals(api.pageKey(), discordData.pageKey());
        assertNotEquals(api.toKey(), explicitMid.toKey());
        assertEquals(ChampionStatsScope.from(api).toKey(), ChampionStatsScope.from(discordData).toKey());
        assertEquals(ChampionStatsScope.from(api).toKey(), ChampionStatsScope.from(explicitMid).toKey());
    }

    @Test
    public void explicitScopeAndQueuesWithoutRolesBypassIndexables() {
        ChampionService service = new ChampionService() {
            @Override
            public List<ChampionIndexable> getIndexables() {
                throw new AssertionError("Indexables are only needed to resolve a missing role");
            }
        };

        Filter explicit = service.resolveFilter(KAISA, PATCH + ".1", TierType.EMERALD, LeagueShard.EUW1,
            GameQueueType.RANKED_FLEX_SR, LaneType.MID);
        Filter aram = service.resolveFilter(KAISA, PATCH, null, null, GameQueueType.ARAM, null);
        Filter arena = service.resolveFilter(KAISA, PATCH, null, null, GameQueueType.CHERRY, LaneType.BOT);

        assertEquals(PATCH, explicit.patch());
        assertEquals(TierType.EMERALD, explicit.rank());
        assertEquals(LeagueShard.EUW1, explicit.region());
        assertEquals(GameQueueType.RANKED_FLEX_SR, explicit.queue());
        assertEquals(LaneType.MID, explicit.lane());
        assertNull(aram.lane());
        assertNull(arena.lane());
    }
}
