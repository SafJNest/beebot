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
import com.safjnest.lol.utils.ChampionBuildTimelineUtils;
import com.safjnest.lol.utils.GameQueueTypeUtils;

import no.stelar7.api.r4j.basic.constants.types.lol.TierType;

public final class ChampionBuildProvider {

    private final Filter filter;
    private BuildSignature.Catalog catalog;

    public ChampionBuildProvider(Filter filter) {
        this.filter = filter;
    }

    public static void forEachGame(Filter filter, Job<?> job, Consumer<ChampionBuildData.Game> consumer) {
        ChampionBuildProvider provider = new ChampionBuildProvider(filter);
        MongoDB.forEachChampionRawMatchEventBatch(filter, true, job, raw -> {
            if (!raw.usableTimeline())
                return;
            for (Document participant : participants(raw)) {
                if (!matches(filter, raw, participant)) continue;
                ChampionBuildData.Game game = provider.parse(raw, participant);
                if (game != null)
                    consumer.accept(game);
            }
        });
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
        if (catalog == null) catalog = BuildSignature.Catalog.forPatch(filter.patch());
        String puuid = participant.getString("puuid");
        List<ChampionBuildTimelineUtils.ItemEvent> items = ChampionBuildTimelineUtils.itemEvents(raw.timeline(), puuid);
        List<ChampionBuildTimelineUtils.ItemEvent> starters = ChampionBuildTimelineUtils.starterItemEvents(raw.timeline(), puuid);
        List<ChampionBuildTimelineUtils.SkillUpgrade> skills = ChampionBuildTimelineUtils.skillUpgrades(raw.timeline(), puuid);
        List<Integer> inventory = List.of(
            participant.getInteger("item0", 0), participant.getInteger("item1", 0), participant.getInteger("item2", 0),
            participant.getInteger("item3", 0), participant.getInteger("item4", 0), participant.getInteger("item5", 0),
            participant.getInteger("item6", 0));
        List<Integer> summonerSpells = List.of(participant.getInteger("summonerSpell1", 0), participant.getInteger("summonerSpell2", 0));
        BuildSignature signature = BuildSignature.from(inventory, skillOrder(participant),
            integers(participant, "augments"), summonerSpells, filter, catalog, items, starters, skills);
        if (signature == null)
            return null;

        RuneSignature runes = RuneSignature.from(integers(participant, "primaryRunes"),
            integers(participant, "secondaryRunes"), integers(participant, "statsRunes"));
        Map<Integer, Long> itemTimes = new LinkedHashMap<>();
        for (ChampionBuildTimelineUtils.ItemEvent item : items) {
            itemTimes.put(item.itemId(), item.timestampMillis());
        }
        return new ChampionBuildData.Game(signature, runes, participant.getBoolean("win", false),
            participant.getInteger("roleQuestId", 0), itemTimes, skills);
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

    private static List<Integer> skillOrder(Document participant) {
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
