package com.safjnest.lol.service;

import com.safjnest.database.nosql.ChampionBuildSnapshotStorageTest;
import com.safjnest.database.nosql.MongoDB.ChampionRawMatch;
import com.safjnest.lol.champion.ChampionBuildData.Game;
import com.safjnest.lol.champion.ChampionBuildProvider;
import com.safjnest.lol.champion.RuneSignature;
import com.safjnest.lol.model.Build;
import com.safjnest.lol.model.ChampionBuildSnapshot;
import com.safjnest.lol.model.Filter;
import com.safjnest.lol.utils.ChampionBuildTimelineUtils.SkillUpgrade;
import com.safjnest.lol.utils.MatchupTimelineUtils;
import com.safjnest.utils.JsonCodec;

import no.stelar7.api.r4j.basic.constants.types.lol.GameQueueType;
import no.stelar7.api.r4j.pojo.lol.staticdata.item.Item;
import org.bson.Document;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ChampionBuildEngineTest {

    @Test
    public void reversedCoreKeepsItsOwnOptionsAndPurchaseTimes() throws Exception {
        Fixture fixture = xerath();
        JSONObject realTimeline = new JSONObject(fixture.timeline().toString());
        JSONObject reversedTimeline = new JSONObject(realTimeline.toString());
        int participantId = fixture.participant().getInteger("id");
        realTimeline.getJSONArray("skill_events")
            .put(new JSONObject().put("participant", participantId).put("level_up_type", "EVOLVE")
                .put("skill_slot", 3).put("timestamp", 1_200_000))
            .put(new JSONObject().put("participant", participantId).put("level_up_type", "EVOLVE")
                .put("skill_slot", 1).put("timestamp", 720_000));
        Game real = fixture.parse(fixture.participant(), realTimeline);
        Document reversedParticipant = new Document(fixture.participant())
            .append("win", false).append("item1", 4628).append("item2", 6655).append("item4", 6657)
            .append("summonerSpell2", 14).append("primaryRunes", List.of(8200, 8214, 8226, 8210, 8237));
        JSONArray purchases = reversedTimeline.getJSONArray("item_events");
        for (int index = 0; index < purchases.length(); index++) {
            JSONObject event = purchases.getJSONObject(index);
            if (event.getInt("participant") != fixture.participant().getInteger("id")
                    || !"ITEM_PURCHASED".equals(event.getString("event"))) {
                continue;
            }

            int replacement = switch (event.getInt("item")) {
                case 6655 -> 4628;
                case 4628 -> 6655;
                case 4645 -> 6657;
                default -> 0;
            };
            if (replacement != 0) {
                event.put("item", replacement).put("timestamp", event.getLong("timestamp") + 60000);
            }
        }

        JSONArray upgrades = reversedTimeline.getJSONArray("skill_events");
        for (int index = 0; index < upgrades.length(); index++) {
            JSONObject event = upgrades.getJSONObject(index);
            if (event.getInt("participant") == fixture.participant().getInteger("id")
                    && event.getLong("timestamp") == 30370) {
                event.put("timestamp", 40370);
            }
        }
        upgrades.put(new JSONObject().put("participant", participantId).put("level_up_type", "EVOLVE")
            .put("skill_slot", 2).put("timestamp", 660_000));
        Game reverse = fixture.parse(reversedParticipant, reversedTimeline);

        ChampionBuildEngine engine = new ChampionBuildEngine(fixture.filter());
        Filter arenaFilter = Filter.championBuild(101, fixture.filter().patch(), GameQueueType.CHERRY);
        ChampionBuildEngine arena = new ChampionBuildEngine(arenaFilter);
        int samples = 100;
        for (int i = 0; i < samples; i++) {
            engine.accept(real);
            engine.accept(reverse);
            arena.accept(real);
            arena.accept(reverse);
        }
        ChampionBuildSnapshot ranked = ChampionBuildSnapshotStorageTest.roundTrip(engine.finish());
        List<Build> variants = ranked.builds();

        assertEquals(engine.filter().toKey(), ranked.filter().toKey());
        assertEquals(2, variants.size());
        assertEquals("4628-6655", ranked.defaultBuildId());
        assertEquals("6655-4628", ranked.bestAdjustedWinrateBuildId());
        ChampionBuildSnapshot rankedArena = arena.finish();
        assertEquals(2, rankedArena.builds().size());
        assertEquals("4628-6655", rankedArena.defaultBuildId());
        assertNull(rankedArena.bestAdjustedWinrateBuildId());
        Build actual = variants.get(1);
        Build reversed = variants.get(0);
        assertEquals(List.of(6655, 4628), actual.coreBuilds().getFirst().items());
        assertEquals(List.of(4628, 6655), reversed.coreBuilds().getFirst().items());
        assertEquals(samples, actual.games());
        assertEquals(samples, reversed.games());
        assertEquals(samples, actual.wins());
        assertEquals(0, reversed.wins());

        assertEquals(1d, actual.winrate(), 0d);
        assertEquals(0.5d, actual.coreBuilds().getFirst().pickrate(), 0d);
        checkPurchase(actual.coreItems().get(0), 6655, 814.349, samples);
        checkPurchase(actual.coreItems().get(1), 4628, 1387.473, samples);
        checkPurchase(reversed.coreItems().get(0), 4628, 874.349, samples);
        checkPurchase(reversed.coreItems().get(1), 6655, 1447.473, samples);
        assertEquals(1387.473, actual.coreBuilds().getFirst().averageCompletionTimeSeconds(), 0.000001);
        assertEquals(1447.473, reversed.coreBuilds().getFirst().averageCompletionTimeSeconds(), 0.000001);
        checkPurchase(actual.itemSlots().get(0).getFirst(), 4645, 1922.776, samples);
        checkPurchase(reversed.itemSlots().get(0).getFirst(), 6657, 1982.776, samples);
        checkPurchase(actual.itemSlots().get(1).getFirst(), 3089, 2320.368, samples);
        checkPurchase(actual.bootOptions().getFirst(), 3020, 1124.399, samples);
        assertEquals(3871, actual.supportItemOptions().getFirst().itemId());
        assertEquals(Integer.valueOf(0), actual.supportItemOptions().getFirst().timedMatches());
        assertEquals("4-7", actual.summonerSpellOptions().getFirst().id());
        assertEquals("4-14", reversed.summonerSpellOptions().getFirst().id());
        assertEquals(407, actual.summonerSpells().getFirst().itemId());
        assertEquals(8229, actual.runeOptions().getFirst().configuration().keystone());
        assertEquals(8214, reversed.runeOptions().getFirst().configuration().keystone());
        assertEquals(List.of(5008, 5008, 5001), actual.getStatShards());
        assertEquals(5008, actual.getOffense());
        assertEquals(5008, actual.getFlex());
        assertEquals(5001, actual.getDefense());
        Build.SkillOrderOption actualSkills = actual.skillOrders().getFirst();
        Build.SkillOrderOption reversedSkills = reversed.skillOrders().getFirst();
        assertEquals(Build.TimingMethod.PREFIX_AVERAGE_INTERVALS, actualSkills.timingMethod());
        assertNull(actual.skillEvolutions().timingMethod());
        assertEquals(actualSkills.order(), reversedSkills.order());
        assertEquals(17, actualSkills.timedOrder().size());
        assertEquals(new Build.SkillTiming(1, 1, 2, 30.37, samples), actualSkills.timedOrder().getFirst());
        assertEquals(new Build.SkillTiming(1, 1, 2, 40.37, samples), reversedSkills.timedOrder().getFirst());
        assertEquals(List.of(1, 3), actual.skillEvolutions().order());
        assertEquals(List.of(new Build.SkillTiming(7, 1, 1, 720, samples), new Build.SkillTiming(10, 2, 3, 1200, samples)),
            actual.skillEvolutions().timedOrder());
        assertEquals(List.of(2), reversed.skillEvolutions().order());
        assertEquals(List.of(new Build.SkillTiming(7, 1, 2, 660, samples)), reversed.skillEvolutions().timedOrder());

        engine.clear();
        ChampionBuildSnapshot empty = engine.finish();
        assertTrue(empty.builds().isEmpty());
        assertNull(empty.defaultBuildId());
        assertNull(empty.bestAdjustedWinrateBuildId());
    }

    @Test
    public void untypedSkillEventsTakePriorityOverTheParticipantFallback() throws Exception {
        Fixture fixture = xerath();
        List<Integer> storedOrder = List.of(2, 1, 3);
        Document participant = new Document(fixture.participant()).append("skillOrder", storedOrder);
        Game legacy = fixture.parse(participant, fixture.timeline());
        ChampionBuildEngine engine = new ChampionBuildEngine(fixture.filter());
        int samples = 20;
        for (int i = 0; i < samples; i++) {
            engine.accept(legacy);
        }

        Build build = engine.finish().builds().getFirst();
        assertEquals(samples, build.games());
        Build.SkillOrderOption observed = build.skillOrders().getFirst();
        assertEquals(17, observed.order().size());
        assertEquals(new Build.SkillTiming(1, 1, 2, 30.37, samples), observed.timedOrder().getFirst());

        JSONObject noSkillEvents = new JSONObject(fixture.timeline().toString()).put("skill_events", new JSONArray());
        Game withoutSkillEvents = fixture.parse(participant, noSkillEvents);
        engine.clear();
        for (int i = 0; i < samples; i++) {
            engine.accept(withoutSkillEvents);
        }

        Build.SkillOrderOption fallback = engine.finish().builds().getFirst().skillOrders().getFirst();
        assertEquals(storedOrder, fallback.order());
        assertTrue(fallback.timedOrder().isEmpty());
    }

    @Test
    public void representativeRuneUsesMostPlayedShardsWithinTheCore() throws Exception {
        Fixture fixture = xerath();
        Game sample = fixture.parse(fixture.participant(), fixture.timeline());
        RuneSignature original = sample.runes();
        RuneSignature alternative = new RuneSignature(original.primaryTree(), original.keystone(), original.primaryRunes(),
            original.secondaryTree(), original.secondaryRunes(), List.of(5008, 5008, 5011));
        Game common = new Game(sample.signature(), original, false,
            sample.roleBoundId(), sample.itemPurchaseTimesMillis(), sample.skills());
        Game rare = new Game(sample.signature(), alternative, true,
            sample.roleBoundId(), sample.itemPurchaseTimesMillis(), sample.skills());
        ChampionBuildEngine engine = new ChampionBuildEngine(fixture.filter());
        engine.accept(rare);
        for (int i = 0; i < 19; i++) {
            engine.accept(common);
        }

        ChampionBuildSnapshot ranked = engine.finish();
        Build.RuneOption variant = ranked.builds().getFirst().runeOptions().getFirst();
        assertEquals(original, variant.configuration());
        assertEquals(20, variant.matches());
        assertEquals(1, variant.wins());
        assertEquals("6655-4628", ranked.defaultBuildId());
        assertNull(ranked.bestAdjustedWinrateBuildId());
    }

    @Test
    public void selectedSkillOrderUsesCompatibleIntervalsAndStopsAtMissingTimestamps() {
        SkillOrder skillOrder = new SkillOrder();
        skillOrder.accept(List.of(), List.of(new SkillUpgrade(2, 30000), new SkillUpgrade(1, 130000),
            new SkillUpgrade(3, 190000), new SkillUpgrade(1, 244000)));
        skillOrder.accept(List.of(), List.of(new SkillUpgrade(2, 60000), new SkillUpgrade(1, 135000),
            new SkillUpgrade(3, -1), new SkillUpgrade(1, 256000)));
        skillOrder.accept(List.of(), List.of(new SkillUpgrade(2, 90000),
            new SkillUpgrade(3, 140000), new SkillUpgrade(1, 215000)));
        skillOrder.accept(List.of(2, 1, 0), List.of());

        Build.SkillOrderOption selected = skillOrder.recommended(SkillOrder.Selection.PREFIX);
        assertEquals(List.of(2, 1, 3, 1), selected.order());
        assertEquals(Build.TimingMethod.PREFIX_AVERAGE_INTERVALS, selected.timingMethod());
        assertEquals(List.of(new Build.SkillTiming(null, 1, 2, 60, 3), new Build.SkillTiming(null, 2, 1, 147.5, 2),
            new Build.SkillTiming(null, 3, 3, 207.5, 1), new Build.SkillTiming(null, 4, 1, 261.5, 1)), selected.timedOrder());
    }

    @Test
    public void fullSkillOrderUsesShorterGamesAsSupportForObservedContinuations() {
        List<Integer> complete = List.of(1, 2, 3, 1, 1, 4, 1, 3, 1, 3, 4, 3, 3, 2, 2, 4, 2, 2);
        List<Integer> shorter = complete.subList(0, 15);
        List<Integer> alternate = List.of(1, 3, 2, 1, 1, 4, 1, 3, 1, 3, 4, 3, 3, 2, 2, 4, 2, 2);
        SkillOrder orders = new SkillOrder();
        for (int i = 0; i < 5; i++) {
            orders.accept(shorter, List.of());
        }
        assertEquals(shorter, orders.recommended(SkillOrder.Selection.PREFIX).order());

        orders.accept(alternate, List.of());
        orders.accept(alternate, List.of());
        assertEquals(shorter, orders.recommended(SkillOrder.Selection.PREFIX).order());

        orders.accept(complete, List.of());
        assertEquals(complete, orders.recommended(SkillOrder.Selection.PREFIX).order());
        assertEquals(shorter, orders.recommended(SkillOrder.Selection.MOST_PLAYED).order());
    }

    private static void checkPurchase(Build.Option option, int item, double seconds, int samples) {
        assertEquals(item, option.itemId());
        assertEquals(samples, option.matches());
        assertEquals(Integer.valueOf(samples), option.timedMatches());
        assertEquals(seconds, option.averagePurchaseTimeSeconds(), 0.000001);
    }

    private static Fixture xerath() throws Exception {
        Document match = Document.parse(resource("xerath-match.json"));
        Document events = Document.parse(resource("xerath-events.json"));
        String data = events.getString("data");
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        assertEquals(match.getString("_id"), events.getString("_id"));
        assertEquals("json", events.getString("encoding"));
        assertEquals(events.getInteger("uncompressedBytes").intValue(), bytes.length);
        assertEquals(events.getString("checksum"), checksum);

        JSONObject timeline = MatchupTimelineUtils.eventObject(data);
        assertNotNull(timeline);
        Document participant = match.getList("participants", Document.class).stream()
            .filter(row -> row.getInteger("champion") == 101).findFirst().orElseThrow();
        assertEquals(participant.getString("puuid"),
            timeline.getJSONObject("participants").getString(Integer.toString(participant.getInteger("id"))));
        Filter filter = Filter.championBuild(101, match.getString("patchMajor"),
            GameQueueType.valueOf(match.getString("queue")));

        JSONObject catalog = new JSONObject(resource("xerath-items.json"));
        Map<Integer, Item> items = new HashMap<>();
        for (String key : catalog.keySet()) {
            int id = Integer.parseInt(key);
            JSONObject fields = catalog.getJSONObject(key).put("id", id);
            Item item = JsonCodec.fromJson(fields.toString(), Item.class);
            assertNotNull(item);
            items.put(id, item);
        }
        ChampionBuildProvider provider = new ChampionBuildProvider(filter);
        Field itemsById = ChampionBuildProvider.class.getDeclaredField("itemsById");
        itemsById.setAccessible(true);
        itemsById.set(provider, items);
        return new Fixture(filter, provider, match, participant, timeline);
    }

    private static String resource(String name) throws IOException {
        try (InputStream input = ChampionBuildEngineTest.class.getResourceAsStream("/lol/builds/" + name)) {
            assertNotNull(input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private record Fixture(Filter filter, ChampionBuildProvider provider, Document match, Document participant, JSONObject timeline) {
        Game parse(Document row, JSONObject events) {
            Game game = provider.parse(new ChampionRawMatch(match, events, true), row);
            assertNotNull(game);
            return game;
        }
    }
}
