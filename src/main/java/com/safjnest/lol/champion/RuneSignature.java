package com.safjnest.lol.champion;

import com.safjnest.utils.ParseUtils;
import com.safjnest.lol.utils.BuildUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record RuneSignature(
        int primaryTree,
        int keystone,
        List<Integer> primaryRunes,
        int secondaryTree,
        List<Integer> secondaryRunes,
        List<Integer> statShards
) {

    static RuneSignature from(List<Integer> primary, List<Integer> secondary, List<Integer> shards) {
        if (primary.size() < 2)
            return null;
        return new RuneSignature(primary.get(0), primary.get(1), sortedRunes(primary, 2),
            secondary.isEmpty() ? 0 : secondary.get(0), sortedRunes(secondary, 1), List.copyOf(shards));
    }

    private static List<Integer> sortedRunes(List<Integer> ids, int start) {
        if (ids.size() <= start)
            return List.of();
        List<Integer> result = new ArrayList<>(ids.subList(start, ids.size()));
        Collections.sort(result);
        return result;
    }

    public String toKey() {
        String raw = primaryTree + "|" + keystone + "|" + BuildUtils.joinInts(primaryRunes) + "|"
                + secondaryTree + "|" + BuildUtils.joinInts(secondaryRunes);
        return BuildUtils.toBase64(raw);
    }

    public String statShardsKey() {
        return BuildUtils.joinInts(statShards);
    }

    public static RuneSignature decode(String key) {
        String[] parts = BuildUtils.fromBase64(key).split("\\|", -1);
        return new RuneSignature(
                ParseUtils.parseInt(parts[0]),
                ParseUtils.parseInt(parts[1]),
                parts.length > 2 ? BuildUtils.parseDashList(parts[2]) : List.of(),
                ParseUtils.parseInt(parts[3]),
                parts.length > 4 ? BuildUtils.parseDashList(parts[4]) : List.of(),
                parts.length > 5 ? BuildUtils.parseDashList(parts[5]) : List.of());
    }
}
