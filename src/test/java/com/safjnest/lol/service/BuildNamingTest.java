package com.safjnest.lol.service;

import com.safjnest.database.nosql.ChampionBuildSnapshotStorageTest;
import com.safjnest.lol.champion.BuildSignature;
import com.safjnest.lol.champion.ChampionBuildData.Game;
import com.safjnest.lol.champion.RuneSignature;
import com.safjnest.lol.model.Build;
import com.safjnest.lol.model.ChampionBuildSnapshot;
import com.safjnest.lol.model.Filter;
import com.safjnest.lol.utils.ChampionBuildTimelineUtils.SkillEvents;
import com.safjnest.utils.JsonCodec;
import no.stelar7.api.r4j.basic.constants.types.lol.GameQueueType;
import no.stelar7.api.r4j.pojo.lol.staticdata.item.Item;
import org.bson.Document;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static com.safjnest.lol.model.Build.Badge.BEST_ADJUSTED_WINRATE;
import static com.safjnest.lol.model.Build.Badge.MOST_POPULAR;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class BuildNamingTest {

    @Test
    public void kaisaOnHitKeepsItsNameAndPopularityBadgeInRankedAndAram() {
        Map<Integer, Item> items = Map.of(
            6672, item("Kraken Slayer", "Damage", "AttackSpeed", "OnHit"),
            3124, item("Guinsoo's Rageblade", "Damage", "AttackSpeed", "SpellDamage", "OnHit"),
            3302, item("Terminus", "Damage", "AttackSpeed", "OnHit"),
            3031, item("Infinity Edge", "Damage", "CriticalStrike"),
            3032, item("Yun Tal Wildarrows", "Damage", "CriticalStrike", "AttackSpeed"));
        for (GameQueueType queue : List.of(GameQueueType.TEAM_BUILDER_RANKED_SOLO, GameQueueType.ARAM)) {
            ChampionBuildEngine engine = new ChampionBuildEngine(Filter.championBuild(145, "16.23", queue));
            accept(engine, List.of(6672, 3124, 3302), null, 200, 100);
            accept(engine, List.of(3031, 3032), null, 110, 75);
            accept(engine, List.of(3302, 3031), null, 10, 10);

            ChampionBuildSnapshot original = engine.finish();
            BuildNaming naming = new BuildNaming(items, id -> null);
            ChampionBuildSnapshot snapshot = ChampionBuildSnapshotStorageTest.roundTrip(naming.label(original));
            assertLabelOnly(original, snapshot);

            assertEquals("6672-3124", snapshot.defaultBuildId());
            assertEquals("3031-3032", snapshot.bestAdjustedWinrateBuildId());
            assertEquals(List.of("On-Hit", "Crit"), snapshot.builds().stream().map(Build::name).toList());
            assertEquals(List.of(MOST_POPULAR), snapshot.defaultBuild().badges());
            assertEquals(List.of(MOST_POPULAR, BEST_ADJUSTED_WINRATE), snapshot.builds().getLast().badges());
            assertEquals("On-Hit", JsonCodec.toDocument(snapshot.defaultBuild()).getString("name"));
        }
    }

    @Test
    public void anAmbiguousCoreNeedsCoverageBeforeAThirdItemCanNameIt() {
        Map<Integer, Item> items = Map.of(
            3087, item("Statikk Shiv", "Damage", "SpellDamage", "OnHit"),
            3124, item("Guinsoo's Rageblade", "Damage", "AttackSpeed", "SpellDamage", "OnHit"),
            3089, item("Rabadon's Deathcap", "SpellDamage"),
            6672, item("Kraken Slayer", "Damage", "AttackSpeed", "OnHit"));
        Filter filter = Filter.championBuild(145, "16.23", GameQueueType.TEAM_BUILDER_RANKED_SOLO);
        ChampionBuildEngine weak = new ChampionBuildEngine(filter);
        accept(weak, List.of(3087, 3124), null, 95, 50);
        accept(weak, List.of(3087, 3124, 3089), null, 5, 5);
        accept(weak, List.of(6672, 3124), null, 30, 15);
        BuildNaming naming = new BuildNaming(items, id -> null);
        assertNull(naming.label(weak.finish()).defaultBuild().name());

        ChampionBuildEngine observed = new ChampionBuildEngine(filter);
        accept(observed, List.of(3087, 3124, 3089), null, 100, 55);
        accept(observed, List.of(6672, 3124), null, 30, 15);
        Build build = naming.label(observed.finish()).defaultBuild();
        assertEquals("AP", build.name());
        assertEquals(List.of(MOST_POPULAR, BEST_ADJUSTED_WINRATE), build.badges());
    }

    @Test
    public void sumsRunePagesBeforeChoosingAThreshKeystoneName() {
        RuneSignature aftershock = new RuneSignature(8400, 8439, List.of(8463), 8300, List.of(8347), List.of());
        RuneSignature aftershockOtherPage = new RuneSignature(8400, 8439, List.of(8446), 8300, List.of(), List.of());
        RuneSignature glacial = new RuneSignature(8300, 8351, List.of(8347), 8400, List.of(8463), List.of());
        Map<Integer, Item> items = Map.of(3190, item("Locket of the Iron Solari", "Armor", "Health"),
            3109, item("Knight's Vow", "Armor", "Health"),
            3107, item("Redemption", "Health", "HealthRegen"));
        ChampionBuildEngine engine = new ChampionBuildEngine(
            Filter.championBuild(412, "16.23", GameQueueType.TEAM_BUILDER_RANKED_SOLO));
        List<Integer> core = List.of(3190, 3109);
        accept(engine, core, aftershock, 35, 20);
        accept(engine, core, aftershockOtherPage, 35, 15);
        accept(engine, core, glacial, 30, 15);

        BuildNaming naming = new BuildNaming(items, Map.of(8439, "Aftershock", 8351, "Glacial Augment")::get);
        assertNull(naming.label(engine.finish()).defaultBuild().name());

        accept(engine, List.of(3190, 3107), glacial, 50, 25);
        ChampionBuildSnapshot original = engine.finish();
        ChampionBuildSnapshot snapshot = naming.label(original);
        assertLabelOnly(original, snapshot);
        assertEquals(List.of("Aftershock", "Glacial Augment"), snapshot.builds().stream().map(Build::name).toList());
        assertEquals(Integer.valueOf(8439), snapshot.defaultBuild().nameKeystoneId());
        assertEquals(Integer.valueOf(8351), snapshot.builds().getLast().nameKeystoneId());
        BuildNaming unavailable = new BuildNaming(items, id -> { throw new IllegalStateException("Missing rune catalog"); });
        assertSame(original, unavailable.label(original));
    }

    @Test
    public void homogeneousBrandBuildsOnlyCarryRankingBadges() {
        Map<Integer, Item> items = Map.of(
            3116, item("Rylai's Crystal Scepter", "SpellDamage"),
            6653, item("Liandry's Torment", "SpellDamage"),
            3137, item("Cryptbloom", "SpellDamage"));
        ChampionBuildEngine engine = new ChampionBuildEngine(
            Filter.championBuild(63, "16.23", GameQueueType.TEAM_BUILDER_RANKED_SOLO));
        RuneSignature comet = new RuneSignature(8200, 8229, List.of(), 0, List.of(), List.of());
        RuneSignature harvest = new RuneSignature(8100, 8128, List.of(), 0, List.of(), List.of());
        accept(engine, List.of(6653, 3116), comet, 180, 80);
        accept(engine, List.of(6653, 3137), harvest, 100, 60);

        ChampionBuildSnapshot original = engine.finish();
        BuildNaming naming = new BuildNaming(items, id -> { throw new AssertionError("Rune names are irrelevant"); });
        ChampionBuildSnapshot labeled = naming.label(original);

        assertLabelOnly(original, labeled);
        assertNull(labeled.defaultBuild().name());
        assertNull(labeled.builds().getLast().name());
        assertEquals(List.of(MOST_POPULAR), labeled.defaultBuild().badges());
        assertEquals(List.of(BEST_ADJUSTED_WINRATE), labeled.builds().getLast().badges());
    }

    @Test
    public void onlyTheMostPlayedCoreInEachShacoCategoryGetsThePopularityBadge() {
        Map<Integer, Item> items = Map.of(
            3142, item("Youmuu's Ghostblade", "Damage"),
            6699, item("Voltaic Cyclosword", "Damage"),
            6698, item("Profane Hydra", "Damage"),
            6653, item("Liandry's Torment", "SpellDamage"),
            3089, item("Rabadon's Deathcap", "SpellDamage"));
        ChampionBuildEngine engine = new ChampionBuildEngine(
            Filter.championBuild(35, "16.23", GameQueueType.TEAM_BUILDER_RANKED_SOLO));
        accept(engine, List.of(3142, 6699), null, 160, 80);
        accept(engine, List.of(3142, 6698), null, 130, 70);
        accept(engine, List.of(6653, 3089), null, 50, 25);

        ChampionBuildSnapshot original = engine.finish();
        ChampionBuildSnapshot labeled = new BuildNaming(items, id -> null).label(original);

        assertLabelOnly(original, labeled);
        assertEquals(List.of("AD", "AD", "AP"), labeled.builds().stream().map(Build::name).toList());
        assertEquals(List.of(MOST_POPULAR), labeled.builds().get(0).badges());
        assertEquals(List.of(BEST_ADJUSTED_WINRATE), labeled.builds().get(1).badges());
        assertEquals(List.of(MOST_POPULAR), labeled.builds().get(2).badges());
    }

    private static void assertLabelOnly(ChampionBuildSnapshot original, ChampionBuildSnapshot labeled) {
        Document before = JsonCodec.toDocument(original);
        Document after = JsonCodec.toDocument(labeled);
        for (Document variant : after.getList("builds", Document.class)) {
            variant.remove("name");
            variant.remove("nameKeystoneId");
            variant.remove("badges");
        }
        assertEquals(before, after);
    }

    private static void accept(ChampionBuildEngine engine, List<Integer> items, RuneSignature rune, int games, int wins) {
        BuildSignature signature = new BuildSignature(List.of(), 0, 0, items.subList(0, 2), items,
            List.of(), List.of(), List.of(), List.of());
        SkillEvents skills = new SkillEvents(List.of(), List.of());
        for (int index = 0; index < games; index++) {
            engine.accept(new Game(signature, rune, index < wins, 0, Map.of(), skills));
        }
    }

    private static Item item(String name, String... tags) {
        return JsonCodec.fromJson(new Document("name", name).append("tags", List.of(tags)).toJson(), Item.class);
    }
}
