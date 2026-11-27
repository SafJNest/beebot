package com.safjnest.lol.service;

import com.safjnest.lol.model.Build;
import com.safjnest.lol.utils.BuildUtils;
import com.safjnest.lol.utils.ChampionBuildTimelineUtils.SkillUpgrade;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class SkillOrder {

    private static final int MAX_POINTS = 18;
    private static final int MAX_ABILITY_SLOT = 4;
    private static final int SLOT_BITS = 2;
    private static final long START = 1;

    private final Long2IntOpenHashMap indices = new Long2IntOpenHashMap(4);
    private final LongArrayList keys = new LongArrayList();
    private final IntArrayList matches = new IntArrayList();
    private final IntArrayList wins = new IntArrayList();
    private final IntArrayList offsets = new IntArrayList();
    private final LongArrayList elapsedMillis = new LongArrayList();
    private final IntArrayList timedMatches = new IntArrayList();

    void accept(List<Integer> fallback, List<SkillUpgrade> upgrades, boolean win) {
        long key = START;
        int points = 0;
        boolean fromTimeline = upgrades != null && !upgrades.isEmpty();
        if (fromTimeline) {
            int observed = Math.min(upgrades.size(), MAX_POINTS);
            for (int point = 0; point < observed; point++) {
                int ability = upgrades.get(point).skillSlot();
                if (ability < 1 || ability > MAX_ABILITY_SLOT) {
                    break;
                }

                key = (key << SLOT_BITS) | (ability - 1);
                points++;
            }
        } else if (fallback != null) {
            for (Integer ability : fallback) {
                if (points == MAX_POINTS || ability == null || ability < 1 || ability > MAX_ABILITY_SLOT) {
                    break;
                }

                key = (key << SLOT_BITS) | (ability - 1);
                points++;
            }
        }
        if (points == 0) {
            return;
        }

        int row = getOrCreateRow(key, points);
        matches.set(row, matches.getInt(row) + 1);
        wins.set(row, wins.getInt(row) + (win ? 1 : 0));

        if (fromTimeline) {
            int offset = offsets.getInt(row);
            for (int point = 0; point < points; point++) {
                long time = upgrades.get(point).timestampMillis();
                if (time >= 0) {
                    int index = offset + point;
                    elapsedMillis.set(index, elapsedMillis.getLong(index) + time);
                    timedMatches.set(index, timedMatches.getInt(index) + 1);
                }
            }
        }
    }

    void merge(SkillOrder source) {
        for (int from = 0; from < source.keys.size(); from++) {
            long key = source.keys.getLong(from);
            int points = points(key);
            int to = getOrCreateRow(key, points);
            matches.set(to, matches.getInt(to) + source.matches.getInt(from));
            wins.set(to, wins.getInt(to) + source.wins.getInt(from));

            int fromOffset = source.offsets.getInt(from);
            int toOffset = offsets.getInt(to);
            for (int point = 0; point < points; point++) {
                int fromIndex = fromOffset + point;
                int toIndex = toOffset + point;
                elapsedMillis.set(toIndex, elapsedMillis.getLong(toIndex) + source.elapsedMillis.getLong(fromIndex));
                timedMatches.set(toIndex, timedMatches.getInt(toIndex) + source.timedMatches.getInt(fromIndex));
            }
        }
    }

    List<Build.SkillOrderOption> toOptions(int totalGames) {
        List<Build.SkillOrderOption> result = new ArrayList<>(keys.size());
        for (int row = 0; row < keys.size(); row++) {
            long key = keys.getLong(row);
            int points = points(key);
            int offset = offsets.getInt(row);
            List<Integer> order = new ArrayList<>(points);
            List<Build.SkillTiming> timings = new ArrayList<>(points);
            for (int point = 0; point < points; point++) {
                int shift = (points - point - 1) * SLOT_BITS;
                int ability = (int) ((key >>> shift) & 3) + 1;
                order.add(ability);

                int index = offset + point;
                int sample = timedMatches.getInt(index);
                if (sample > 0) {
                    double average = (double) elapsedMillis.getLong(index) / sample / 1000;
                    // A delayed point can be spent at the same champion level as another point.
                    timings.add(new Build.SkillTiming(point + 1, ability, average, sample));
                }
            }

            String id = BuildUtils.joinInts(order);
            int played = matches.getInt(row);
            int won = wins.getInt(row);
            double winrate = (double) won / played;
            double pickrate = totalGames > 0 ? (double) played / totalGames : 0;
            result.add(new Build.SkillOrderOption(id, List.copyOf(order), played, won, winrate, pickrate, timings));
        }
        result.sort(Comparator.comparingInt(Build.SkillOrderOption::matches).reversed()
            .thenComparing(Comparator.comparingInt((Build.SkillOrderOption option) -> option.order().size()).reversed())
            .thenComparing(Build.SkillOrderOption::id));
        return result;
    }

    // ============================================================================

    private int getOrCreateRow(long key, int points) {
        int row = indices.getOrDefault(key, -1);
        if (row >= 0) {
            return row;
        }

        row = keys.size();
        int offset = elapsedMillis.size();
        indices.put(key, row);
        keys.add(key);
        matches.add(0);
        wins.add(0);
        offsets.add(offset);
        elapsedMillis.size(offset + points);
        timedMatches.size(offset + points);
        return row;
    }

    private static int points(long key) {
        // The leading sentinel keeps orders with initial slot 1 and their prefixes distinct.
        return (Long.SIZE - 1 - Long.numberOfLeadingZeros(key)) / SLOT_BITS;
    }
}
