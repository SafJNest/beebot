package com.safjnest.lol.champion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.bson.Document;

import com.safjnest.database.nosql.MongoDB;
import com.safjnest.database.nosql.MongoDB.ChampionRawMatch;
import com.safjnest.lol.model.Filter;
import com.safjnest.lol.queue.job.Job;
import com.safjnest.lol.service.StaticDataService;
import com.safjnest.lol.utils.ChampionBuildTimelineUtils;
import com.safjnest.lol.utils.GameQueueTypeUtils;
import com.safjnest.lol.utils.ItemUtils;

import no.stelar7.api.r4j.basic.constants.types.lol.TierType;
import no.stelar7.api.r4j.pojo.lol.staticdata.item.Item;

public final class ChampionBuildProvider {

    private final Filter filter;
    private Map<Integer, Item> itemsById;

    public ChampionBuildProvider(Filter filter) {
        this.filter = filter;
    }

    public void forEachGame(Job<?> job, Consumer<ChampionBuildData.Game> consumer) {
        MongoDB.forEachChampionRawMatchEventBatch(filter, true, job, raw -> {
            if (!raw.usableTimeline())
                return;
            for (Document participant : participants(raw)) {
                if (!matches(filter, raw, participant)) continue;
                ChampionBuildData.Game game = parse(raw, participant);
                if (game != null)
                    consumer.accept(game);
            }
        });
    }

    public Map<Integer, Item> loadedItems() {
        return itemsById == null ? Map.of() : itemsById;
    }

    public static List<Document> participants(ChampionRawMatch raw) {
        return raw.document().getList("participants", Document.class, List.of());
    }

    public static boolean matches(Filter filter, ChampionRawMatch raw, Document participant) {
        if (filter.champion() != participant.getInteger("champion", 0))
            return false;
        if (filter.lane() != null && GameQueueTypeUtils.hasLane(filter.queue())
                && !filter.lane().name().equals(participant.getString("lane"))) return false;
        if (filter.region() != null && !filter.region().name().equals(raw.document().getString("region")))
            return false;
        if (filter.rank() == null)
            return true;
        TierType rank;
        try {
            rank = TierType.valueOf(raw.document().getString("rank"));
        } catch (IllegalArgumentException | NullPointerException ignored) {
            return false;
        }
        return filter.rankBehavior() == Filter.RankBehavior.EXACT
            ? rank == filter.rank() : rank.ordinal() <= filter.rank().ordinal();
    }

    public ChampionBuildData.Game parse(ChampionRawMatch raw, Document participant) {
        if (itemsById == null) {
            itemsById = StaticDataService.getItems(filter.patch());
        }

        String puuid = participant.getString("puuid");
        int roleBoundId = participant.getInteger("roleQuestId", 0);
        List<ChampionBuildTimelineUtils.ItemEvent> purchases = ChampionBuildTimelineUtils.itemEvents(raw.timeline(), puuid);
        List<Integer> inventory = List.of(
            participant.getInteger("item0", 0), participant.getInteger("item1", 0), participant.getInteger("item2", 0),
            participant.getInteger("item3", 0), participant.getInteger("item4", 0), participant.getInteger("item5", 0),
            participant.getInteger("item6", 0));
        List<Integer> summonerSpells = List.of(participant.getInteger("summonerSpell1", 0), participant.getInteger("summonerSpell2", 0));
        BuildSignature signature = BuildSignature.from(inventory, roleBoundId, skillOrder(participant),
            integers(participant, "augments"), summonerSpells, itemsById, purchases);
        if (signature == null) {
            return null;
        }

        ChampionBuildTimelineUtils.SkillEvents skills = ChampionBuildTimelineUtils.skillEvents(raw.timeline(), puuid);
        RuneSignature runes = RuneSignature.from(integers(participant, "primaryRunes"),
            integers(participant, "secondaryRunes"), integers(participant, "statsRunes"));
        Map<Integer, Long> itemTimes = new LinkedHashMap<>();
        for (ChampionBuildTimelineUtils.ItemEvent purchase : purchases) {
            if (!ItemUtils.isGrantedByRune(purchase.itemId())) {
                itemTimes.putIfAbsent(purchase.itemId(), purchase.timestampMillis());
            }
        }
        return new ChampionBuildData.Game(signature, runes, participant.getBoolean("win", false), roleBoundId, itemTimes, skills);
    }

    // ============================================================================

    private static List<Integer> integers(Document participant, String field) {
        if (!(participant.get(field) instanceof List<?> values))
            return List.of();
        List<Integer> result = new ArrayList<>(values.size());
        for (Object value : values) {
            if (value instanceof Number number)
                result.add(number.intValue());
        }
        return result;
    }

    public static List<Integer> skillOrder(Document participant) {
        List<Integer> order = integers(participant, "skillOrder");
        for (int id : order) {
            if (id >= 0 && id < 10) continue;
            // Older data can store several levels as digits in one integer.
            List<Integer> decoded = new ArrayList<>();
            for (Integer value : order) {
                String digits = value.toString();
                for (int i = 0; i < digits.length(); i++) {
                    decoded.add(Character.getNumericValue(digits.charAt(i)));
                }
            }
            return decoded;
        }
        return order;
    }
}
