package com.safjnest.lol.champion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bson.Document;

import com.safjnest.database.nosql.MongoDB.ChampionRawMatch;
import com.safjnest.utils.ParseUtils;

import no.stelar7.api.r4j.basic.constants.types.lol.LaneType;
import no.stelar7.api.r4j.basic.constants.types.lol.GameQueueType;
import no.stelar7.api.r4j.basic.constants.types.lol.TeamType;
import no.stelar7.api.r4j.basic.constants.types.lol.TierType;
import no.stelar7.api.r4j.basic.constants.api.regions.LeagueShard;

public final class ChampionStatsProvider {

    private ChampionStatsProvider() {}

    public static ChampionStatsData.RawMatch read(ChampionRawMatch raw) {
        Document document = raw.document();
        String matchId = String.valueOf(document.get("_id"));
        ChampionStatsData.MatchMeta metadata = new ChampionStatsData.MatchMeta(
                map(document.get("bans")), raw.usableTimeline() ? raw.timeline() : null,
                ParseUtils.longValue(document.get("timeStart")), ParseUtils.longValue(document.get("timeEnd")),
                region(document.getString("region")), rank(document.getString("rank")), queue(document.getString("queue")));
        List<ChampionStatsData.RawParticipant> participants = new ArrayList<>();
        for (Document participant : participants(document.get("participants"))) {
            participants.add(new ChampionStatsData.RawParticipant(
                    participant.getInteger("champion", 0), lane(participant), participant.getBoolean("win", false),
                    team(participant), matchId, participant.getString("kda"), participant.getInteger("cs", 0),
                    participant.getInteger("goldEarned", 0), participant.getString("puuid"),
                    participant.getInteger("subTeam", 0), participant.getInteger("subTeamPlacement", 0)));
        }
        return new ChampionStatsData.RawMatch(matchId, metadata, participants);
    }

    private static List<Document> participants(Object value) {
        if (!(value instanceof List<?> values)) return List.of();
        List<Document> result = new ArrayList<>(values.size());
        for (Object item : values) if (item instanceof Document document) result.add(document);
        return result;
    }

    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source) || source.isEmpty()) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() != null) result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

    private static LaneType lane(Document participant) {
        try { return LaneType.valueOf(participant.getString("lane")); }
        catch (RuntimeException ignored) { return null; }
    }

    private static TeamType team(Document participant) {
        try { return TeamType.valueOf(participant.getString("team")); }
        catch (RuntimeException ignored) { return null; }
    }

    private static LeagueShard region(String value) {
        try { return value == null ? null : LeagueShard.valueOf(value); }
        catch (RuntimeException ignored) { return null; }
    }

    private static TierType rank(String value) {
        try { return value == null ? null : TierType.valueOf(value); }
        catch (RuntimeException ignored) { return null; }
    }

    private static GameQueueType queue(String value) {
        try { return value == null ? null : GameQueueType.valueOf(value); }
        catch (RuntimeException ignored) { return null; }
    }
}
