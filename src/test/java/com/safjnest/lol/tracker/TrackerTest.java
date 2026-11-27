package com.safjnest.lol.tracker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import com.safjnest.lol.model.match.RankProgress;
import com.safjnest.lol.utils.ChampionBuildTimelineUtils;
import com.safjnest.lol.utils.ChampionBuildTimelineUtils.SkillUpgrade;
import com.safjnest.lol.utils.RankProgressUtils;
import com.safjnest.utils.JsonCodec;

import no.stelar7.api.r4j.basic.constants.types.lol.GameQueueType;
import no.stelar7.api.r4j.basic.constants.types.lol.TierDivisionType;
import no.stelar7.api.r4j.pojo.lol.match.v5.LOLTimeline;

public class TrackerTest {

    @Test
    public void rebuildKeepsEvolutionEventsOutOfSkillOrdersAndRetainsUltimateRanks() {
        List<Integer> order = List.of(1, 2, 3, 1, 1, 4, 1, 3, 1, 3, 4, 3, 3, 2, 2, 4, 2, 2);
        JSONArray events = new JSONArray();
        for (int level = 1; level <= order.size(); level++) {
            int timestamp = level * 10_000;
            if (level > 1) {
                events.put(new JSONObject().put("type", "LEVEL_UP").put("participantId", 1)
                    .put("timestamp", timestamp).put("level", level));
            }
            events.put(new JSONObject().put("type", "SKILL_LEVEL_UP").put("participantId", 1)
                .put("timestamp", timestamp + 100).put("skillSlot", order.get(level - 1)).put("levelUpType", "NORMAL"));
            if (level == 11) {
                events.put(new JSONObject().put("type", "SKILL_LEVEL_UP").put("participantId", 1)
                    .put("timestamp", timestamp + 500).put("skillSlot", 1).put("levelUpType", "EVOLVE"));
            }
        }
        JSONObject raw = new JSONObject().put("participants", new JSONArray()
            .put(new JSONObject().put("participantId", 1).put("puuid", "kaisa")))
            .put("frames", new JSONArray().put(new JSONObject().put("timestamp", 180_500).put("events", events)));
        LOLTimeline timeline = JsonCodec.fromJson(raw.toString(), LOLTimeline.class);
        assertNotNull(timeline);

        Tracker.RebuiltTimeline rebuilt = Tracker.rebuildTimeline(timeline);
        assertNotNull(rebuilt);
        assertEquals(order, rebuilt.skillOrders().get("kaisa"));
        JSONArray skills = new JSONObject(rebuilt.events()).getJSONArray("skill_events");
        assertEquals(19, skills.length());
        assertEquals("EVOLVE", skills.getJSONObject(11).getString("level_up_type"));

        List<SkillUpgrade> upgrades = ChampionBuildTimelineUtils.skillUpgrades(rebuilt.events(), "kaisa");
        assertEquals(order, ChampionBuildTimelineUtils.skillOrder(List.of(), upgrades));
        assertEquals(List.of(6, 11, 16), upgrades.stream().filter(upgrade -> upgrade.skillSlot() == 4)
            .map(SkillUpgrade::championLevel).toList());
    }

    @Test
    public void missingPreviousMatchKeepsGainAtZero() {
        assertEquals(0, RankProgressUtils.calculateGain(
                GameQueueType.TEAM_BUILDER_RANKED_SOLO,
                progress(TierDivisionType.GOLD_II, 70),
                null));
    }

    @Test
    public void sameRankUsesLpDelta() {
        assertEquals(20, RankProgressUtils.calculateGain(
                GameQueueType.TEAM_BUILDER_RANKED_SOLO,
                progress(TierDivisionType.GOLD_II, 70),
                progress(TierDivisionType.GOLD_II, 50)));
    }

    @Test
    public void divisionChangeUsesBoundaryCalculation() {
        assertEquals(-30, RankProgressUtils.calculateGain(
                GameQueueType.TEAM_BUILDER_RANKED_SOLO,
                progress(TierDivisionType.PLATINUM_I, 0),
                progress(TierDivisionType.EMERALD_IV, 70)));
    }

    @Test
    public void diamondPromotionToMasterUsesPromotionCalculation() {
        assertEquals(20, RankProgressUtils.calculateGain(
                GameQueueType.TEAM_BUILDER_RANKED_SOLO,
                progress(TierDivisionType.MASTER_I, 80),
                progress(TierDivisionType.DIAMOND_I, 0)));
    }

    @Test
    public void masterPlusUsesLpDeltaAcrossTierChanges() {
        assertEquals(10, RankProgressUtils.calculateGain(
                GameQueueType.TEAM_BUILDER_RANKED_SOLO,
                progress(TierDivisionType.MASTER_I, 110),
                progress(TierDivisionType.MASTER_I, 100)));
        assertEquals(15, RankProgressUtils.calculateGain(
                GameQueueType.TEAM_BUILDER_RANKED_SOLO,
                progress(TierDivisionType.GRANDMASTER_I, 115),
                progress(TierDivisionType.MASTER_I, 100)));
        assertEquals(-10, RankProgressUtils.calculateGain(
                GameQueueType.TEAM_BUILDER_RANKED_SOLO,
                progress(TierDivisionType.CHALLENGER_I, 90),
                progress(TierDivisionType.GRANDMASTER_I, 100)));
    }

    @Test
    public void unrankedTransitionsHaveDefinedGain() {
        assertEquals(42, RankProgressUtils.calculateGain(GameQueueType.TEAM_BUILDER_RANKED_SOLO,
                progress(TierDivisionType.IRON_IV, 42), progress(TierDivisionType.UNRANKED, 0)));
        assertEquals(0, RankProgressUtils.calculateGain(GameQueueType.TEAM_BUILDER_RANKED_SOLO,
                progress(TierDivisionType.UNRANKED, 0), progress(TierDivisionType.IRON_IV, 42)));
    }

    private static RankProgress progress(TierDivisionType rank, int lp) {
        return new RankProgress(rank, lp, null, null, null);
    }
}
