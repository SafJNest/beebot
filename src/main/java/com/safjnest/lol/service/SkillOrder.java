package com.safjnest.lol.service;

import com.safjnest.lol.model.Build;
import com.safjnest.lol.utils.ChampionBuildTimelineUtils.SkillUpgrade;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.ObjectIterator;

import java.util.ArrayList;
import java.util.List;

final class SkillOrder {

    private static final int MAX_POINTS = 18;
    private static final int MAX_ABILITY_SLOT = 4;
    private static final int SLOT_BITS = 2;
    private static final int RANK_BITS = 5;
    private static final int RANK_MASK = (1 << RANK_BITS) - 1;
    private static final long START = 1;

    private final Long2IntOpenHashMap matches = new Long2IntOpenHashMap(4);
    private final LongArrayList elapsedMillis = new LongArrayList();
    private final IntArrayList timedMatches = new IntArrayList();

    void accept(List<Integer> fallback, List<SkillUpgrade> upgrades) {
        long key = START;
        int points = 0;
        boolean fromTimeline = upgrades != null && !upgrades.isEmpty();
        if (fromTimeline) {
            int ranks = 0;
            int observed = Math.min(upgrades.size(), MAX_POINTS);
            for (int point = 0; point < observed; point++) {
                SkillUpgrade upgrade = upgrades.get(point);
                int slot = upgrade.skillSlot() - 1;
                if (slot < 0 || slot >= MAX_ABILITY_SLOT) {
                    break;
                }

                key = (key << SLOT_BITS) | slot;
                points++;

                // Each slot has a separate packed rank counter for this match.
                int shift = slot * RANK_BITS;
                int rank = (ranks >>> shift) & RANK_MASK;
                int index = (rank << SLOT_BITS) | slot;
                ranks += 1 << shift;
                if (upgrade.timestampMillis() < 0) {
                    continue;
                }
                if (index >= timedMatches.size()) {
                    elapsedMillis.size(index + 1);
                    timedMatches.size(index + 1);
                }
                elapsedMillis.set(index, elapsedMillis.getLong(index) + upgrade.timestampMillis());
                timedMatches.set(index, timedMatches.getInt(index) + 1);
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

        matches.addTo(key, 1);
    }

    Build.SkillOrderOption recommended() {
        int played = 0;
        int depth = 0;
        long key = 0;
        ObjectIterator<Long2IntMap.Entry> entries = matches.long2IntEntrySet().fastIterator();
        while (entries.hasNext()) {
            Long2IntMap.Entry entry = entries.next();
            int count = entry.getIntValue();
            long candidate = entry.getLongKey();
            int candidateDepth = points(candidate);
            if (count > played || (count == played && (candidateDepth > depth
                    || (candidateDepth == depth && candidate < key)))) {
                played = count;
                depth = candidateDepth;
                key = candidate;
            }
        }
        if (key == 0) {
            return null;
        }

        int points = points(key);
        List<Integer> order = new ArrayList<>(points);
        List<Build.SkillTiming> timings = new ArrayList<>(points);
        int ranks = 0;
        for (int point = 0; point < points; point++) {
            int slot = (int) ((key >>> ((points - point - 1) * SLOT_BITS)) & 3);
            int shift = slot * RANK_BITS;
            int rank = (ranks >>> shift) & RANK_MASK;
            int index = (rank << SLOT_BITS) | slot;
            ranks += 1 << shift;
            order.add(slot + 1);

            int sample = index < timedMatches.size() ? timedMatches.getInt(index) : 0;
            if (sample > 0) {
                double average = (double) elapsedMillis.getLong(index) / sample / 1000;
                timings.add(new Build.SkillTiming(point + 1, slot + 1, average, sample));
            }
        }

        return new Build.SkillOrderOption(List.copyOf(order), timings);
    }

    private static int points(long key) {
        // The leading sentinel keeps orders with initial slot 1 and their prefixes distinct.
        return (Long.SIZE - 1 - Long.numberOfLeadingZeros(key)) / SLOT_BITS;
    }
}
