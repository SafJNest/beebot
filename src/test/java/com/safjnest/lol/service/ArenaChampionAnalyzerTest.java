package com.safjnest.lol.service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.bson.Document;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import com.safjnest.database.nosql.MongoDB.ChampionRawMatch;
import com.safjnest.lol.arena.ArenaItemCatalog;
import com.safjnest.lol.champion.ChampionStatsData.RawParticipant;
import com.safjnest.lol.champion.ChampionStatsProvider;
import com.safjnest.lol.model.ArenaBuildData;
import com.safjnest.lol.model.ArenaBuildData.Core;
import com.safjnest.lol.model.ArenaBuildData.Option;
import com.safjnest.lol.model.Build;
import com.safjnest.lol.model.ChampionStatistics.ArenaAllySynergy;
import com.safjnest.lol.model.ChampionStatistics.ArenaSynergy;
import com.safjnest.utils.JsonCodec;
import no.stelar7.api.r4j.pojo.lol.staticdata.item.Item;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ArenaChampionAnalyzerTest {
    private static final int SHACO = 35;
    private static final int BOOTS = 223006;
    private static final int OTHER_BOOTS = 223047;
    private static final int PRISMATIC = 440001;
    private static final int OTHER_PRISMATIC = 440002;
    private static final int ITEM_A = 223072;
    private static final int ITEM_B = 223056;
    private static final String PATCH = "16.23";

    @Test
    public void namesArenaCoreByPrismaticAndDisambiguatesWithBoots() {
        int everfrost = 446656;
        int cruelty = 447109;
        int unknown = 449999;
        ArenaItemCatalog catalog = ArenaItemCatalog.fromItems(Map.of(
            BOOTS, arenaItem(BOOTS, "Berserker's Greaves", 500, 250, List.of("Boots")),
            OTHER_BOOTS, arenaItem(OTHER_BOOTS, "Plated Steelcaps", 500, 250, List.of("Boots")),
            everfrost, arenaItem(everfrost, "Everfrost", 2750, 1100, List.of("SpellDamage")),
            cruelty, arenaItem(cruelty, "Cruelty", 2750, 1100, List.of("SpellDamage"))));
        ArenaChampionAnalyzer.Accumulator accumulator = ArenaChampionAnalyzer.accumulator(SHACO, PATCH, catalog);
        accumulator.accept(match(1, null, player(1, false, 5, BOOTS).append("item0", everfrost)));
        accumulator.accept(match(2, null, player(1, false, 6, BOOTS).append("item0", everfrost)));
        accumulator.accept(match(3, null, player(1, true, 2, OTHER_BOOTS).append("item0", everfrost)));
        accumulator.accept(match(4, null, player(1, true, 1, BOOTS).append("item0", cruelty)));
        accumulator.accept(match(5, null, player(1, true, 1, BOOTS).append("item0", unknown)));

        ArenaBuildData original = accumulator.finish();
        ArenaBuildData result = BuildNaming.labelArena(original, catalog);
        Document originalDocument = JsonCodec.toDocument(original);
        Document labeledDocument = JsonCodec.toDocument(result);
        for (Document core : labeledDocument.getList("coreBuilds", Document.class)) {
            core.remove("name");
            core.remove("badges");
        }
        assertEquals(originalDocument, labeledDocument);
        assertEquals(5, result.coverage().firstPrismaticFallbackGames());
        assertEquals(List.of("Berserker's Greaves + Everfrost", "Plated Steelcaps + Everfrost", "Cruelty",
            "Berserker's Greaves + Prismatic 449999"),
            List.of(core(result, BOOTS, everfrost).name(), core(result, OTHER_BOOTS, everfrost).name(),
                core(result, BOOTS, cruelty).name(), core(result, BOOTS, unknown).name()));
        assertEquals(List.of(Build.Badge.MOST_POPULAR), core(result, BOOTS, everfrost).badges());
        assertTrue(core(result, BOOTS, cruelty).badges().isEmpty());
        assertEquals(result, JsonCodec.fromDocument(JsonCodec.toDocument(result), ArenaBuildData.class));
    }

    @Test
    public void countsCanonicalWinsAndLocalPlacementSamplesWithOrWithoutCore() {
        Document observed = player(1, true, 3, BOOTS).append("item0", OTHER_PRISMATIC)
            .append("item1", PRISMATIC).append("item2", ITEM_A).append("item3", ITEM_B)
            .append("augments", Arrays.asList(10, null, 30));
        Document withoutItems = player(2, false, 6, BOOTS).append("item0", PRISMATIC);
        JSONObject events = timeline(Map.of("1", "player-1", "2", "player-2"),
            purchase(1, ITEM_B, 210_000), purchase(1, BOOTS, 100_000), purchase(1, PRISMATIC, 140_000),
            purchase(1, ITEM_A, 300_000), purchase(1, OTHER_PRISMATIC, 350_000), purchase(2, BOOTS, 120_000));
        Document inventoryOnly = player(1, false, 1, BOOTS).append("item0", PRISMATIC)
            .append("item1", ITEM_A).append("item2", ITEM_B).append("augments", List.of(10));
        Document noCore = player(1, true, 0, OTHER_BOOTS).append("item0", ITEM_B).append("augments", List.of(10));

        List<ChampionRawMatch> games = List.of(match(1, events, observed, withoutItems),
            match(2, timeline(Map.of("1", "player-1"), purchase(1, BOOTS, 80_000)), inventoryOnly),
            match(3, null, noCore));
        ArenaChampionAnalyzer.Accumulator accumulator = accumulator();
        long expectedWins = 0;
        for (ChampionRawMatch game : games) {
            assertTrue(accumulator.accept(game));
            expectedWins += ChampionStatsProvider.read(game).participants().stream().filter(RawParticipant::win).count();
        }
        ArenaBuildData build = accumulator.finish();

        assertEquals(4, build.games());
        assertEquals(2, expectedWins);
        assertEquals(expectedWins, build.wins());
        assertEquals(0.5, build.winrate(), 0);
        assertEquals(10 / 3d, build.averagePlacement(), 0);
        assertEquals(1 / 3d, build.firstPlaceRate(), 0);
        assertEquals(new ArenaBuildData.Coverage(3, 3, 1, 2, 3, 1, 1, 1, 1), build.coverage());

        Option global = option(build.items(), ITEM_B);
        assertEquals(3, global.matches());
        assertEquals(2, global.wins());
        assertEquals(2 / 3d, global.winrate(), 0);
        assertEquals(0.75, global.pickrate(), 0);
        assertEquals(2, global.averagePlacement(), 0);
        assertEquals(0.5, global.firstPlaceRate(), 0);
        assertEquals(210, global.averagePurchaseTimeSeconds(), 0);
        assertEquals(Long.valueOf(1), global.timedMatches());
        assertEquals(1, option(build.boots(), OTHER_BOOTS).matches());
        assertNull(option(build.boots(), OTHER_BOOTS).averagePlacement());
        assertEquals(3, build.augments().getFirst().getFirst().matches());
        assertEquals(30, build.augments().get(2).getFirst().id());
        assertEquals(0.5, build.augments().getFirst().getFirst().firstPlaceRate(), 0);
        assertEquals(1 / 3d, option(build.prismatics(), PRISMATIC).firstPlaceRate(), 0);

        Core core = build.coreBuilds().getFirst();
        assertEquals(List.of(BOOTS, PRISMATIC), core.items());
        assertEquals(3, core.matches());
        assertEquals(1, core.wins());
        assertEquals(0.75, core.pickrate(), 0);
        assertEquals(10 / 3d, core.averagePlacement(), 0);
        assertEquals(1 / 3d, core.firstPlaceRate(), 0);
        assertEquals(140, core.averageCompletionTimeSeconds(), 0);
        assertEquals(Long.valueOf(1), core.timedMatches());
        assertEquals(100, core.boots().averagePurchaseTimeSeconds(), 0);
        assertEquals(Long.valueOf(3), core.boots().timedMatches());
        assertEquals(2, option(core.itemOptions(), ITEM_B).matches());
        assertEquals(2 / 3d, option(core.itemOptions(), ITEM_B).pickrate(), 0);
        assertEquals(2, core.augments().getFirst().getFirst().matches());
        assertEquals(1, option(core.prismatics(), OTHER_PRISMATIC).matches());

        assertEquals(2, core.slots().size());
        assertEquals(ITEM_B, core.slots().get(0).getFirst().id());
        assertEquals(ITEM_A, core.slots().get(1).getFirst().id());
        assertEquals(1 / 3d, core.slots().get(0).getFirst().pickrate(), 0);
        assertEquals(300, core.slots().get(1).getFirst().averagePurchaseTimeSeconds(), 0);
        assertEquals(2, core.builds().stream().mapToLong(ArenaBuildData.FullBuild::matches).sum());
        ArenaBuildData.FullBuild ordered = core.builds().getFirst();
        assertFalse(ordered.inventoryFallback());
        assertEquals(List.of(ITEM_B, ITEM_A), ordered.items());
        assertEquals(3, ordered.averagePlacement(), 0);
        assertEquals(0, ordered.firstPlaceRate(), 0);
        ArenaBuildData.FullBuild fallback = core.builds().getLast();
        assertTrue(fallback.inventoryFallback());
        assertEquals(List.of(ITEM_B, ITEM_A), fallback.items());
        assertEquals(1, fallback.firstPlaceRate(), 0);
        assertEquals(build, JsonCodec.fromDocument(JsonCodec.toDocument(build), ArenaBuildData.class));
    }

    @Test
    public void reportsArenaSkillsBeyondEighteenAndTimedEvolutionsIndependently() {
        Document player = player(1, true, 1, BOOTS).append("item0", PRISMATIC);
        JSONObject events = timeline(Map.of("1", "player-1"));
        JSONArray upgrades = new JSONArray();
        for (int point = 1; point <= 19; point++) {
            upgrades.put(skill(point % 4 + 1, point * 1_000, "NORMAL"));
        }
        upgrades.put(skill(2, 18_500, "EVOLVE"));
        events.put("skill_events", upgrades);
        events.put("level_events", new JSONArray()
            .put(new JSONObject().put("participant", 1).put("timestamp", 1_000).put("level", 1))
            .put(new JSONObject().put("participant", 1).put("timestamp", 18_000).put("level", 17)));
        ArenaChampionAnalyzer.Accumulator accumulator = accumulator();
        assertTrue(accumulator.accept(match(1, events, player)));
        ArenaBuildData build = accumulator.finish();
        Core core = build.coreBuilds().getFirst();

        assertEquals(19, core.skillOrders().getFirst().order().size());
        assertEquals(new Build.SkillTiming(17, 19, 4, 19, 1),
            core.skillOrders().getFirst().timedOrder().getLast());
        assertEquals(new Build.SkillTiming(17, 1, 2, 18.5, 1), core.skillEvolutions().timedOrder().getFirst());
        assertEquals(core.skillOrders(), build.skillOrders());
        assertEquals(core.skillEvolutions(), build.skillEvolutions());
        assertEquals(build, JsonCodec.fromDocument(JsonCodec.toDocument(build), ArenaBuildData.class));
    }

    @Test
    public void rollsUpEveryDistinctAllyFromExistingTeamCompositions() {
        List<ArenaSynergy> compositions = List.of(new ArenaSynergy(List.of(1, 2), 2, 1, 0.5),
            new ArenaSynergy(List.of(1, 1), 1, 1, 1), new ArenaSynergy(List.of(1, 3), 2, 1, 0.5));

        assertEquals(List.of(new ArenaAllySynergy(1, 5, 3, 0.6, 1),
            new ArenaAllySynergy(2, 2, 1, 0.5, 0.4), new ArenaAllySynergy(3, 2, 1, 0.5, 0.4)),
            ChampionAnalyzer.arenaAllySynergies(compositions, 5));
    }

    private static Option option(List<Option> options, int id) {
        return options.stream().filter(value -> value.id() == id).findFirst().orElseThrow();
    }

    private static Core core(ArenaBuildData data, int boots, int prismatic) {
        List<Integer> items = List.of(boots, prismatic);
        return data.coreBuilds().stream().filter(value -> value.items().equals(items)).findFirst().orElseThrow();
    }

    private static Item arenaItem(int id, String name, int cost, int sell, List<String> tags) {
        Document item = new Document("id", id).append("name", name).append("tags", tags)
            .append("maps", new Document("30", true)).append("gold", new Document("total", cost).append("sell", sell));
        return JsonCodec.fromDocument(item, Item.class);
    }

    private static ArenaChampionAnalyzer.Accumulator accumulator() {
        ArenaItemCatalog catalog = new ArenaItemCatalog(Map.of(BOOTS, Build.Kind.BOOTS,
            OTHER_BOOTS, Build.Kind.BOOTS, PRISMATIC, Build.Kind.PRISMATIC, OTHER_PRISMATIC, Build.Kind.PRISMATIC,
            ITEM_A, Build.Kind.ITEM, ITEM_B, Build.Kind.ITEM));
        return ArenaChampionAnalyzer.accumulator(SHACO, PATCH, catalog);
    }

    private static Document player(int id, boolean win, int placement, int boots) {
        return new Document("id", id).append("puuid", "player-" + id).append("champion", SHACO)
            .append("boots", boots).append("win", win).append("subTeamPlacement", placement);
    }

    private static ChampionRawMatch match(int id, JSONObject events, Document... players) {
        Document match = new Document("_id", "EUW1_" + id).append("queue", "CHERRY")
            .append("patch", PATCH).append("participants", List.of(players));
        return new ChampionRawMatch(match, events, events != null);
    }

    private static JSONObject timeline(Map<String, String> participants, JSONObject... purchases) {
        return new JSONObject().put("participants", new JSONObject(participants))
            .put("item_events", new JSONArray(List.of(purchases)));
    }

    private static JSONObject purchase(int participant, int item, long millis) {
        return new JSONObject().put("participant", participant).put("event", "ITEM_PURCHASED")
            .put("item", item).put("before", 0).put("after", 0).put("timestamp", millis);
    }

    private static JSONObject skill(int slot, long millis, String type) {
        return new JSONObject().put("participant", 1).put("level_up_type", type).put("skill_slot", slot)
            .put("timestamp", millis);
    }
}
