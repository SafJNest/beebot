package com.safjnest.lol.champion;

import java.util.List;
import java.util.Map;

import org.bson.Document;
import org.json.JSONObject;
import org.junit.Test;

import com.safjnest.database.nosql.MongoDB.ChampionRawMatch;
import com.safjnest.lol.arena.ArenaGameParser;
import com.safjnest.lol.arena.ArenaItemCatalog;
import com.safjnest.lol.arena.ParsedArenaGame;
import com.safjnest.lol.model.Build.Kind;
import com.safjnest.lol.model.Filter;
import com.safjnest.lol.service.ChampionService;
import com.safjnest.lol.utils.ChampionBuildTimelineUtils;
import com.safjnest.lol.utils.ChampionBuildTimelineUtils.ItemEvent;
import com.safjnest.lol.utils.ItemUtils;
import com.safjnest.utils.JsonCodec;

import no.stelar7.api.r4j.basic.constants.api.regions.LeagueShard;
import no.stelar7.api.r4j.basic.constants.types.lol.GameQueueType;
import no.stelar7.api.r4j.basic.constants.types.lol.LaneType;
import no.stelar7.api.r4j.basic.constants.types.lol.TierType;
import no.stelar7.api.r4j.pojo.lol.staticdata.item.Item;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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

    @Test
    public void buildUsesObservedPurchasesWithUndoAndKeepsItemsSoldLater() {
        JSONObject timeline = new JSONObject("""
            {"participants":{"1":"player"},"item_events":[
              {"participant":1,"timestamp":1200000,"event":"ITEM_PURCHASED","item":3089},
              {"participant":1,"timestamp":1000,"event":"ITEM_PURCHASED","item":2031},
              {"participant":1,"timestamp":1100,"event":"ITEM_PURCHASED","item":2010},
              {"participant":1,"timestamp":1200,"event":"ITEM_PURCHASED","item":2422},
              {"participant":1,"timestamp":1300,"event":"ITEM_PURCHASED","item":1001},
              {"participant":1,"timestamp":2000,"event":"ITEM_PURCHASED","item":1055},
              {"participant":1,"timestamp":500000,"event":"ITEM_PURCHASED","item":3078},
              {"participant":1,"timestamp":500000,"event":"ITEM_DESTROYED","item":1028},
              {"participant":1,"timestamp":500100,"event":"ITEM_UNDO","before":3078,"after":0},
              {"participant":1,"timestamp":600000,"event":"ITEM_PURCHASED","item":3031},
              {"participant":1,"timestamp":900000,"event":"ITEM_SOLD","item":3031},
              {"participant":1,"timestamp":1500000,"event":"ITEM_PURCHASED","item":3078}
            ]}
            """);
        Map<Integer, Item> items = Map.of(
            2031, item(2031, """
                {"tags":["Consumable"],"gold":{"purchasable":true,"total":150}}
                """),
            2010, item(2010, """
                {"consumed":true,"gold":{"purchasable":true,"total":50}}
                """),
            2422, item(2422, """
                {"tags":["Boots"],"gold":{"purchasable":false,"total":300}}
                """),
            1001, item(1001, """
                {"tags":["Boots"],"gold":{"purchasable":true,"total":300}}
                """),
            3006, item(3006, """
                {"tags":["Boots"],"from":["1001"],"gold":{"purchasable":true,"total":1100}}
                """),
            1055, item(1055, """
                {"depth":1,"gold":{"purchasable":true,"total":450}}
                """),
            3031, item(3031, """
                {"depth":2,"from":["1038","1037","1018"],"gold":{"purchasable":true,"total":3500}}
                """),
            3089, item(3089, """
                {"depth":2,"from":["1058","1058","1026"],"gold":{"purchasable":true,"total":3500}}
                """),
            3078, item(3078, """
                {"depth":3,"gold":{"purchasable":true,"total":3333}}
                """));
        List<ItemEvent> purchases = ChampionBuildTimelineUtils.itemEvents(timeline, "player");
        BuildSignature signature = BuildSignature.from(List.of(3089, 3078, 2422, 1001, 3006), 2422,
            List.of(), List.of(), List.of(), items, purchases);

        assertNotNull(signature);
        assertEquals(List.of(1055), signature.starter());
        assertEquals(3006, signature.boots());
        assertEquals(List.of(3031, 3089), signature.core());
        assertEquals(List.of(3031, 3089, 3078), signature.fullBuild());
        assertTrue(purchases.contains(new ItemEvent(3078, 1500000)));
        assertFalse(purchases.contains(new ItemEvent(3078, 500000)));

        BuildSignature onlyBasicBoots = BuildSignature.from(List.of(3089, 3078, 1001), 1001,
            List.of(), List.of(), List.of(), items, purchases);
        assertEquals(0, onlyBasicBoots.boots());

        List<ItemEvent> reverse = List.of(new ItemEvent(3089, 600000), new ItemEvent(3031, 1200000));
        BuildSignature reverseSignature = BuildSignature.from(List.of(3031, 3089), 0,
            List.of(), List.of(), List.of(), items, reverse);
        assertEquals(List.of(3089, 3031), reverseSignature.core());
        assertNull(BuildSignature.from(List.of(3031, 3089), 0,
            List.of(), List.of(), List.of(), items, List.of()));
    }

    @Test
    public void arenaUsesPricedPrismaticsAndExcludesVouchers() {
        Map<Integer, Item> items = Map.of(
            226630, item(226630, """
                {"maps":{"30":true},"gold":{"total":2750,"sell":1100},"consumeOnFull":true}
                """),
            226693, item(226693, """
                {"maps":{"30":true},"gold":{"total":2750,"sell":1100}}
                """),
            447111, item(447111, """
                {"maps":{"30":true},"gold":{"total":2500,"sell":1250}}
                """),
            220008, item(220008, """
                {"maps":{"30":true},"gold":{"total":750,"sell":0}}
                """),
            221026, item(221026, """
                {"maps":{"30":true},"gold":{"total":500,"sell":350}}
                """),
            2145, item(2145, """
                {"maps":{"30":true},"tags":["Consumable"]}
                """));
        ArenaItemCatalog catalog = ArenaItemCatalog.fromItems(items);
        Document participant = new Document("item0", 220008).append("item1", 2145)
            .append("item2", 226630).append("item3", 220012).append("item4", 447111).append("item5", 221026);

        ParsedArenaGame game = ArenaGameParser.parse("16.23", null, participant, catalog);

        assertEquals(List.of(new ItemEvent(226630, -1), new ItemEvent(220012, -1)), game.prismatics());
        assertEquals(List.of(447111), game.fallbackBuildItems());
        assertEquals(Kind.PRISMATIC, catalog.kind("16.23", 226693));
        assertFalse(ItemUtils.isConsumable(items.get(226630)));
    }

    @Test
    public void arenaSkipsStoredBaseBootsAndFindsCompletedBootsInInventory() {
        ArenaItemCatalog catalog = new ArenaItemCatalog(Map.of(1001, Kind.BOOTS, 2422, Kind.BOOTS,
            223006, Kind.BOOTS, 226630, Kind.PRISMATIC));
        Document participant = new Document("boots", 1001).append("item0", 2422)
            .append("item1", 223006).append("item2", 226630);

        ParsedArenaGame game = ArenaGameParser.parse("16.23", null, participant, catalog);

        assertEquals(new ItemEvent(223006, -1), game.boots());
        assertEquals(new ItemEvent(226630, -1), game.firstPrismatic());
        assertTrue(game.firstPrismaticFallback());
    }

    private static Item item(int id, String data) {
        JSONObject fields = new JSONObject(data).put("id", id);
        return JsonCodec.fromJson(fields.toString(), Item.class);
    }
}
