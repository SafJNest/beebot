package com.safjnest.lol.service;

import com.safjnest.lol.model.Build;
import com.safjnest.lol.utils.ChampionBuildTimelineUtils.SkillUpgrade;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.ObjectIterator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class SkillOrder {

    private static final int MAX_POINTS = 18;
    private static final int MAX_ABILITY_SLOT = 4;
    private static final int SLOT_BITS = 2;
    private static final int SLOT_MASK = (1 << SLOT_BITS) - 1;
    private static final int LEVEL_BITS = 8;
    private static final int LEVEL_MASK = (1 << LEVEL_BITS) - 1;
    private static final long START = 1;

    enum Selection { PREFIX, MOST_PLAYED }

    private final Long2IntOpenHashMap matches = new Long2IntOpenHashMap(4);
    private final Long2IntOpenHashMap timingRows = new Long2IntOpenHashMap(4);
    private final Long2IntOpenHashMap championLevelVotes = new Long2IntOpenHashMap(4);
    // Each row contains a match count followed by one timestamp sum per point.
    private final LongArrayList timingTotals = new LongArrayList();
    private final int maxPoints;
    private int highestChampionLevel;

    SkillOrder() {
        this(MAX_POINTS);
    }

    private SkillOrder(int maxPoints) {
        this.maxPoints = maxPoints;
    }

    static SkillOrder arena() {
        return new SkillOrder((Long.SIZE - 1 - LEVEL_BITS) / SLOT_BITS);
    }

    void accept(List<Integer> fallback, List<SkillUpgrade> upgrades) {
        long key = START;
        long timedKey = START;
        int timedPoints = 0;
        int points = 0;
        if (!upgrades.isEmpty()) {
            int observed = Math.min(upgrades.size(), maxPoints);
            long previousTime = 0;
            for (int point = 0; point < observed; point++) {
                SkillUpgrade upgrade = upgrades.get(point);
                int slot = upgrade.skillSlot() - 1;
                if (slot < 0 || slot >= MAX_ABILITY_SLOT) {
                    break;
                }

                key = (key << SLOT_BITS) | slot;
                points++;
                long time = upgrade.timestampMillis();
                if (timedPoints == point && time >= previousTime) {
                    timedKey = key;
                    timedPoints++;
                    previousTime = time;
                    int level = upgrade.championLevel();
                    if (level > 0 && level <= LEVEL_MASK) {
                        championLevelVotes.addTo((key << LEVEL_BITS) | level, 1);
                        highestChampionLevel = Math.max(highestChampionLevel, level);
                    }
                }
            }
        } else {
            for (Integer ability : fallback) {
                if (points == maxPoints || ability == null || ability < 1 || ability > MAX_ABILITY_SLOT) {
                    break;
                }

                key = (key << SLOT_BITS) | (ability - 1);
                points++;
            }
        }
        if (points == 0) {
            return;
        }

        matches.addTo(key, 1);
        if (timedPoints > 0) {
            recordTiming(timedKey, timedPoints, upgrades);
        }
    }

    Build.SkillOrderOption recommended(Selection selection) {
        if (matches.isEmpty()) {
            return null;
        }

        long key = switch (selection) {
            case PREFIX -> mostSupportedPrefix();
            case MOST_PLAYED -> mostPlayedOrder();
        };
        int depth = points(key);
        List<Integer> order = new ArrayList<>(depth);
        for (int point = 0; point < depth; point++) {
            int shift = (depth - point - 1) * SLOT_BITS;
            int slot = (int) ((key >>> shift) & SLOT_MASK);
            order.add(slot + 1);
        }

        if (selection == Selection.PREFIX) {
            List<Build.SkillTiming> timings = prefixTimings(key, order);
            return new Build.SkillOrderOption(List.copyOf(order), timings, Build.TimingMethod.PREFIX_AVERAGE_INTERVALS);
        }
        List<Build.SkillTiming> timings = cohortTimings(key, order);
        return new Build.SkillOrderOption(List.copyOf(order), timings);
    }

    // ============================================================================

    private void recordTiming(long key, int points, List<SkillUpgrade> upgrades) {
        int offset = timingRows.getOrDefault(key, -1);
        if (offset < 0) {
            offset = timingTotals.size();
            timingRows.put(key, offset);
            timingTotals.size(offset + points + 1);
        }
        timingTotals.set(offset, timingTotals.getLong(offset) + 1);
        for (int point = 0; point < points; point++) {
            int index = offset + point + 1;
            timingTotals.set(index, timingTotals.getLong(index) + upgrades.get(point).timestampMillis());
        }
    }

    private long mostSupportedPrefix() {
        Long2IntOpenHashMap prefixVotes = new Long2IntOpenHashMap(matches.size());
        ObjectIterator<Long2IntMap.Entry> entries = matches.long2IntEntrySet().fastIterator();
        while (entries.hasNext()) {
            Long2IntMap.Entry entry = entries.next();
            long key = entry.getLongKey();
            int count = entry.getIntValue();
            for (long prefix = key; prefix > START; prefix >>>= SLOT_BITS) {
                prefixVotes.addTo(prefix, count);
            }
        }

        long key = START;
        for (int point = 0; point < maxPoints; point++) {
            long next = 0;
            int bestVotes = 0;
            for (int slot = 0; slot < MAX_ABILITY_SLOT; slot++) {
                long candidate = (key << SLOT_BITS) | slot;
                int votes = prefixVotes.get(candidate);
                if (votes > bestVotes) {
                    bestVotes = votes;
                    next = candidate;
                }
            }
            if (next == 0) {
                break;
            }
            key = next;
        }
        return key;
    }

    private long mostPlayedOrder() {
        long key = 0;
        int played = 0;
        int depth = 0;
        ObjectIterator<Long2IntMap.Entry> entries = matches.long2IntEntrySet().fastIterator();
        while (entries.hasNext()) {
            Long2IntMap.Entry entry = entries.next();
            long candidate = entry.getLongKey();
            int count = entry.getIntValue();
            int candidateDepth = points(candidate);
            if (count > played || (count == played && (candidateDepth > depth
                    || (candidateDepth == depth && candidate < key)))) {
                played = count;
                depth = candidateDepth;
                key = candidate;
            }
        }
        return key;
    }

    private List<Build.SkillTiming> prefixTimings(long key, List<Integer> order) {
        int depth = order.size();
        long[] elapsed = new long[depth];
        int[] samples = new int[depth];
        ObjectIterator<Long2IntMap.Entry> entries = timingRows.long2IntEntrySet().fastIterator();
        while (entries.hasNext()) {
            Long2IntMap.Entry entry = entries.next();
            int common = commonPoints(key, depth, entry.getLongKey());
            int offset = entry.getIntValue();
            int count = Math.toIntExact(timingTotals.getLong(offset));
            long previous = 0;
            for (int point = 0; point < common; point++) {
                long total = timingTotals.getLong(offset + point + 1);
                elapsed[point] += total - previous;
                samples[point] += count;
                previous = total;
            }
        }

        List<Build.SkillTiming> timings = new ArrayList<>(depth);
        double estimatedMillis = 0;
        for (int point = 0; point < depth && samples[point] > 0; point++) {
            estimatedMillis += (double) elapsed[point] / samples[point];
            long prefix = key >>> ((depth - point - 1) * SLOT_BITS);
            Integer championLevel = mostObservedChampionLevel(prefix);
            timings.add(new Build.SkillTiming(championLevel, point + 1, order.get(point), estimatedMillis / 1000,
                samples[point]));
        }
        return timings;
    }

    private Integer mostObservedChampionLevel(long prefix) {
        long key = prefix << LEVEL_BITS;
        int bestLevel = 0;
        int bestVotes = 0;
        for (int level = 1; level <= highestChampionLevel; level++) {
            int votes = championLevelVotes.get(key | level);
            if (votes > bestVotes) {
                bestLevel = level;
                bestVotes = votes;
            }
        }
        return bestVotes == 0 ? null : bestLevel;
    }

    private List<Build.SkillTiming> cohortTimings(long key, List<Integer> order) {
        if (timingRows.isEmpty()) {
            return List.of();
        }

        int depth = order.size();
        int timedDepth = 0;
        long[] elapsed = new long[depth];
        int samples = 0;
        ObjectIterator<Long2IntMap.Entry> entries = timingRows.long2IntEntrySet().fastIterator();
        while (entries.hasNext()) {
            Long2IntMap.Entry entry = entries.next();
            int common = commonPoints(key, depth, entry.getLongKey());
            if (common == 0 || common < timedDepth) {
                continue;
            }

            // Use one cohort across the whole timed prefix so the means remain comparable.
            if (common > timedDepth) {
                Arrays.fill(elapsed, 0);
                samples = 0;
                timedDepth = common;
            }
            int offset = entry.getIntValue();
            samples += Math.toIntExact(timingTotals.getLong(offset));
            for (int point = 0; point < timedDepth; point++) {
                elapsed[point] += timingTotals.getLong(offset + point + 1);
            }
        }

        List<Build.SkillTiming> timings = new ArrayList<>(timedDepth);
        for (int point = 0; point < timedDepth; point++) {
            double average = elapsed[point] / 1000.0 / samples;
            long prefix = key >>> ((depth - point - 1) * SLOT_BITS);
            Integer championLevel = mostObservedChampionLevel(prefix);
            timings.add(new Build.SkillTiming(championLevel, point + 1, order.get(point), average, samples));
        }
        return timings;
    }

    private static int commonPoints(long key, int depth, long observed) {
        int observedDepth = points(observed);
        int common = Math.min(depth, observedDepth);
        long expected = key >>> ((depth - common) * SLOT_BITS);
        observed >>>= (observedDepth - common) * SLOT_BITS;
        while (expected != observed) {
            expected >>>= SLOT_BITS;
            observed >>>= SLOT_BITS;
            common--;
        }
        return common;
    }

    private static int points(long key) {
        // The leading sentinel keeps orders with initial slot 1 and their prefixes distinct.
        return (Long.SIZE - 1 - Long.numberOfLeadingZeros(key)) / SLOT_BITS;
    }
}
