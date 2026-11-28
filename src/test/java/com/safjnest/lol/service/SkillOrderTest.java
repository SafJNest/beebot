package com.safjnest.lol.service;

import com.safjnest.lol.model.Build;
import com.safjnest.lol.utils.ChampionBuildTimelineUtils;
import com.safjnest.lol.utils.ChampionBuildTimelineUtils.SkillEvents;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SkillOrderTest {

    @Test
    public void khazixShortGamesSupportTheCommonPathThroughRankTwoOfR() {
        List<Integer> actual = List.of(1, 2, 3, 1, 1, 4, 1, 2, 1, 2, 4, 2, 2, 3, 3, 4, 3, 3);
        List<Integer> alternate1 = List.of(1, 2, 3, 1, 1, 4, 1, 2, 1, 2, 4, 2, 3, 2, 3, 4, 3, 3);
        List<Integer> alternate2 = List.of(1, 2, 3, 1, 1, 4, 1, 2, 1, 2, 4, 3, 2, 2, 3, 4, 3, 3);
        List<Integer> delayed = List.of(1, 2, 3, 1, 1, 4, 1, 2, 1, 2, 2, 4, 2, 3, 3, 3, 4, 3);
        SkillOrder orders = new SkillOrder();
        SkillOrder evolutions = new SkillOrder();
        for (List<Integer> path : List.of(actual.subList(0, 11), actual, alternate1, alternate2)) {
            JSONArray timeline = upgrades(path, 0);
            timeline.put(event("EVOLVE", 1, 241_000));
            timeline.put(event("EVOLVE", 2, 442_000));
            SkillEvents events = events(timeline);
            orders.accept(delayed, events.upgrades());
            evolutions.accept(List.of(), events.evolutions());
        }
        orders.accept(delayed, events(upgrades(actual.subList(0, 10), 0)).upgrades());
        for (int match = 0; match < 3; match++) {
            orders.accept(actual, events(upgrades(delayed, 0)).upgrades());
        }

        Build.SkillOrderOption selected = orders.recommended(SkillOrder.Selection.PREFIX);
        assertEquals(actual, selected.order());
        assertEquals(new Build.SkillTiming(null, 6, 4, 240, 8), selected.timedOrder().get(5));
        assertEquals(new Build.SkillTiming(null, 11, 4, 440, 4), selected.timedOrder().get(10));
        assertEquals(new Build.SkillTiming(null, 16, 4, 640, 1), selected.timedOrder().get(15));
        assertEquals(List.of(1, 2), evolutions.recommended(SkillOrder.Selection.MOST_PLAYED).order());
    }

    @Test
    public void bonusSkillPointKeepsChampionLevelSeparateFromThePositionOfR() {
        List<Integer> path = List.of(1, 2, 3, 1, 1, 4, 1, 2, 1, 2, 2, 4, 2, 3, 3, 3, 4, 3);
        JSONArray timeline = upgrades(path, 0);
        timeline.put(event("EVOLVE", 1, 241_000)).put(event("EVOLVE", 2, 481_000));
        JSONArray levels = new JSONArray();
        for (int point = 2; point <= path.size(); point++) {
            if (point == 10) {
                continue;
            }
            int level = point > 10 ? point - 1 : point;
            levels.put(new JSONObject().put("participant", 1).put("level", level)
                .put("timestamp", point * 40_000L));
        }
        JSONObject events = new JSONObject().put("participants", new JSONObject().put("1", "test-player"))
            .put("skill_events", timeline).put("level_events", levels);
        SkillEvents skills = ChampionBuildTimelineUtils.skillEvents(events, "test-player");
        SkillOrder orders = new SkillOrder();
        orders.accept(List.of(), skills.upgrades());

        Build.SkillOrderOption selected = orders.recommended(SkillOrder.Selection.PREFIX);
        assertEquals(path, selected.order());
        assertEquals(new Build.SkillTiming(9, 9, 1, 360, 1), selected.timedOrder().get(8));
        assertEquals(new Build.SkillTiming(9, 10, 2, 400, 1), selected.timedOrder().get(9));
        assertEquals(new Build.SkillTiming(6, 6, 4, 240, 1), selected.timedOrder().get(5));
        assertEquals(new Build.SkillTiming(11, 12, 4, 480, 1), selected.timedOrder().get(11));
        assertEquals(new Build.SkillTiming(16, 17, 4, 680, 1), selected.timedOrder().get(16));

        SkillOrder evolutions = new SkillOrder();
        evolutions.accept(List.of(), skills.evolutions());
        Build.SkillOrderOption selectedEvolutions = evolutions.recommended(SkillOrder.Selection.MOST_PLAYED);
        assertEquals(List.of(new Build.SkillTiming(6, 1, 1, 241, 1), new Build.SkillTiming(11, 2, 2, 481, 1)),
            selectedEvolutions.timedOrder());
    }

    @Test
    public void kaisaUsesMoreGamesForEarlierIntervalsWithoutInvertingTheTimes() {
        List<Integer> path = List.of(1, 2, 3, 1, 1, 4, 1, 3, 1);
        SkillOrder orders = new SkillOrder();
        for (int offset : List.of(0, 20_000)) {
            JSONArray timeline = upgrades(path, offset);
            timeline.put(event("EVOLVE", 1, 285_000 + offset));
            timeline.put(event("EVOLVE", 3, 314_000 + offset));
            SkillEvents extracted = events(timeline);
            assertEquals(2, extracted.evolutions().size());
            orders.accept(List.of(), extracted.upgrades());
        }
        for (int match = 0; match < 10; match++) {
            orders.accept(List.of(), events(upgrades(path.subList(0, 8), 120_000)).upgrades());
        }

        Build.SkillOrderOption selected = orders.recommended(SkillOrder.Selection.PREFIX);
        assertEquals(path, selected.order());
        Build.SkillTiming eighth = selected.timedOrder().get(7);
        Build.SkillTiming ninth = selected.timedOrder().get(8);
        assertEquals(12, eighth.timedMatches());
        assertEquals(2, ninth.timedMatches());
        assertEquals(421.666667, eighth.averageUpgradeTimeSeconds(), 1e-6);
        assertEquals(40, ninth.averageUpgradeTimeSeconds() - eighth.averageUpgradeTimeSeconds(), 1e-6);
    }

    @Test
    public void udyrKeepsSlotFourAtTheFirstPointAndUsesFallbackOnlyWithoutUsableNormalEvents() {
        JSONArray timeline = new JSONArray()
            .put(event("NORMAL", 4, 0))
            .put(event(null, 1, 90_000))
            .put(event("NORMAL", 4, 170_000))
            .put(event("NORMAL", 2, 240_000))
            .put(event("UNKNOWN", 3, 10_000))
            .put(event("NORMAL", 3, 20_000).put("participant", 2));
        List<Integer> guide = List.of(4, 1, 4, 2, 3);
        SkillOrder orders = new SkillOrder();
        orders.accept(guide, events(timeline).upgrades());

        Build.SkillOrderOption observed = orders.recommended(SkillOrder.Selection.PREFIX);
        assertEquals(guide.subList(0, 4), observed.order());
        assertEquals(new Build.SkillTiming(null, 1, 4, 0, 1), observed.timedOrder().getFirst());
        assertEquals(new Build.SkillTiming(null, 3, 4, 170, 1), observed.timedOrder().get(2));

        SkillEvents unavailable = events(new JSONArray().put(event("EVOLVE", 4, 80_000)));
        orders.accept(guide, unavailable.upgrades());
        Build.SkillOrderOption withFallback = orders.recommended(SkillOrder.Selection.PREFIX);
        assertEquals(guide, withFallback.order());
        assertEquals(observed.timedOrder(), withFallback.timedOrder());
    }

    @Test
    public void viktorMatchKeepsTheUpgradeAndEvolutionOfSlotFourSeparate() {
        // Skill events for Viktor in KR_8467032072, with participant references removed.
        int[][] upgrades = {{1, 3832}, {3, 112680}, {2, 168946}, {3, 238562}, {3, 322633}, {4, 440427},
            {3, 526725}, {1, 699906}, {3, 851580}, {1, 955252}, {4, 1075858}, {1, 1205743}, {2, 1368416}};
        int[][] evolves = {{3, 438857}, {1, 785598}, {2, 985214}, {4, 1155942}};
        JSONArray timeline = new JSONArray();
        for (int[] point : upgrades) {
            timeline.put(event("NORMAL", point[0], point[1]));
        }
        for (int[] point : evolves) {
            timeline.put(event("EVOLVE", point[0], point[1]));
        }

        SkillEvents extracted = events(timeline);
        SkillOrder normals = new SkillOrder();
        SkillOrder evolutions = new SkillOrder();
        normals.accept(List.of(), extracted.upgrades());
        evolutions.accept(List.of(), extracted.evolutions());

        Build.SkillOrderOption normal = normals.recommended(SkillOrder.Selection.PREFIX);
        Build.SkillOrderOption evolve = evolutions.recommended(SkillOrder.Selection.MOST_PLAYED);
        assertEquals(13, normal.order().size());
        assertEquals(new Build.SkillTiming(null, 6, 4, 440.427, 1), normal.timedOrder().get(5));
        assertEquals(new Build.SkillTiming(null, 11, 4, 1075.858, 1), normal.timedOrder().get(10));
        assertEquals(List.of(3, 1, 2, 4), evolve.order());
        assertEquals(new Build.SkillTiming(null, 4, 4, 1155.942, 1), evolve.timedOrder().get(3));
    }

    private static JSONArray upgrades(List<Integer> order, int offsetMillis) {
        JSONArray timeline = new JSONArray();
        for (int point = 0; point < order.size(); point++) {
            timeline.put(event("NORMAL", order.get(point), 40_000L * (point + 1) + offsetMillis));
        }
        return timeline;
    }

    private static JSONObject event(String type, int slot, long time) {
        return new JSONObject().put("participant", 1).put("skill_slot", slot)
            .put("level_up_type", type).put("timestamp", time);
    }

    private static SkillEvents events(JSONArray timeline) {
        JSONObject participants = new JSONObject().put("1", "test-player").put("2", "other-player");
        return ChampionBuildTimelineUtils.skillEvents(
            new JSONObject().put("participants", participants).put("skill_events", timeline), "test-player");
    }
}
