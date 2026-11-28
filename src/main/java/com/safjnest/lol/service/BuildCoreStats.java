package com.safjnest.lol.service;

import com.safjnest.lol.champion.BuildSignature;
import com.safjnest.lol.champion.ChampionBuildData;
import com.safjnest.lol.champion.RuneSignature;
import com.safjnest.lol.model.Build;
import com.safjnest.lol.model.ChampionBuildSnapshot;
import com.safjnest.lol.model.Filter;
import com.safjnest.lol.utils.BuildUtils;
import com.safjnest.lol.utils.ChampionBuildTimelineUtils.SkillEvents;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import no.stelar7.api.r4j.basic.constants.types.lol.GameQueueType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class BuildCoreStats {

    private static final int ITEM_SLOT_COUNT = 4;
    private static final int AUGMENT_SLOT_COUNT = 6;
    private static final int COMPLETION = 0;
    private static final int CORE_FIRST = 1;
    private static final int CORE_SECOND = 2;
    private static final int ITEM_SLOT = 3;
    private static final int STARTER = ITEM_SLOT + ITEM_SLOT_COUNT;
    private static final int BOOTS = STARTER + 1;
    private static final int SUPPORT = BOOTS + 1;
    private static final int ROLE_BOUND = SUPPORT + 1;
    private static final int PRISMATIC = ROLE_BOUND + 1;
    private static final int RUNE_PAGE = PRISMATIC + 1;
    private static final int SPELLS = RUNE_PAGE + 1;
    private static final int AUGMENT_SLOT = SPELLS + 1;
    private static final long NO_TIMESTAMP = -1;

    private final Long2IntOpenHashMap coreIndices = new Long2IntOpenHashMap();
    private final LongArrayList coreKeys = new LongArrayList();
    private final List<Options> cores = new ArrayList<>();
    private final Object2IntOpenHashMap<RuneSignature> runeIndices = new Object2IntOpenHashMap<>();
    private final List<RuneSignature> runePages = new ArrayList<>();
    private final Object2IntOpenHashMap<List<Integer>> spellIndices = new Object2IntOpenHashMap<>();
    private final List<List<Integer>> spellPairs = new ArrayList<>();
    private int games;
    private int wins;

    void accept(ChampionBuildData.Game game) {
        BuildSignature signature = game.signature();
        int first = signature.core().get(0);
        int second = signature.core().get(1);
        long key = pair(first, second);
        int index = coreIndices.getOrDefault(key, -1);
        if (index < 0) {
            index = cores.size();
            coreIndices.put(key, index);
            coreKeys.add(key);
            cores.add(new Options());
        }

        Options core = cores.get(index);
        Map<Integer, Long> purchaseTimes = game.itemPurchaseTimesMillis();
        long firstTime = purchaseTimes.getOrDefault(first, NO_TIMESTAMP);
        long secondTime = purchaseTimes.getOrDefault(second, NO_TIMESTAMP);
        long completedAt = firstTime < 0 || secondTime < 0 ? NO_TIMESTAMP : Math.max(firstTime, secondTime);
        int win = game.win() ? 1 : 0;

        games++;
        wins += win;
        core.record(COMPLETION, 0, win, completedAt);
        core.record(CORE_FIRST, first, win, firstTime);
        core.record(CORE_SECOND, second, win, secondTime);

        List<Integer> starter = signature.starter();
        if (!starter.isEmpty()) {
            core.record(STARTER, starter.getFirst(), win, NO_TIMESTAMP);
        }
        core.recordItem(BOOTS, signature.boots(), win, purchaseTimes);
        core.recordItem(SUPPORT, signature.suppItem(), win, purchaseTimes);
        core.recordItem(ROLE_BOUND, game.roleBoundId(), win, purchaseTimes);

        List<Integer> items = signature.fullBuild();
        int itemCount = Math.min(ITEM_SLOT_COUNT, items.size() - 2);
        for (int slot = 0; slot < itemCount; slot++) {
            core.recordItem(ITEM_SLOT + slot, items.get(slot + 2), win, purchaseTimes);
        }
        for (int id : signature.prismatics()) {
            core.recordItem(PRISMATIC, id, win, purchaseTimes);
        }

        List<Integer> augments = signature.augments();
        int augmentCount = Math.min(AUGMENT_SLOT_COUNT, augments.size());
        for (int slot = 0; slot < augmentCount; slot++) {
            core.record(AUGMENT_SLOT + slot, augments.get(slot), win, NO_TIMESTAMP);
        }

        RuneSignature runes = game.runes();
        if (runes != null) {
            int id = runeIndices.getOrDefault(runes, -1);
            if (id < 0) {
                id = runePages.size();
                runeIndices.put(runes, id);
                runePages.add(runes);
            }
            core.record(RUNE_PAGE, id, win, NO_TIMESTAMP);
        }

        List<Integer> spells = signature.summonerSpells();
        if (!spells.isEmpty()) {
            int id = spellIndices.getOrDefault(spells, -1);
            if (id < 0) {
                id = spellPairs.size();
                spellIndices.put(spells, id);
                spellPairs.add(spells);
            }
            core.record(SPELLS, id, win, NO_TIMESTAMP);
        }

        SkillEvents skills = game.skills();
        core.skills.accept(signature.spellOrder(), skills.upgrades());
        if (!skills.evolutions().isEmpty()) {
            if (core.evolutions == null) {
                core.evolutions = new SkillOrder();
            }
            core.evolutions.accept(List.of(), skills.evolutions());
        }
    }

    ChampionBuildSnapshot snapshot(Filter filter) {
        int count = cores.size();
        int[] coreMatches = new int[count];
        int[] coreWins = new int[count];
        for (int index = 0; index < count; index++) {
            Options core = cores.get(index);
            int row = core.firstRow[COMPLETION];
            coreMatches[index] = core.matches.getInt(row);
            coreWins[index] = core.wins.getInt(row);
        }

        boolean rankWinrate = filter.queue() != GameQueueType.CHERRY;
        BuildRankingAnalyzer.Ranking ranking = BuildRankingAnalyzer.rank(coreKeys, coreMatches, coreWins,
            games, wins, rankWinrate);
        IntList indices = ranking.indices();
        List<Build> builds = new ArrayList<>(indices.size());
        String defaultBuildId = null;
        String bestAdjustedWinrateBuildId = null;
        for (int position = 0; position < indices.size(); position++) {
            int index = indices.getInt(position);
            Build.CoreBuildOption option = coreOption(index);
            if (position == 0) {
                defaultBuildId = option.id();
            }
            if (index == ranking.bestAdjustedWinrateIndex()) {
                bestAdjustedWinrateBuildId = option.id();
            }
            builds.add(toBuild(filter, cores.get(index), option));
        }
        return new ChampionBuildSnapshot(filter, builds, defaultBuildId, bestAdjustedWinrateBuildId);
    }

    void clear() {
        coreIndices.clear();
        coreKeys.clear();
        cores.clear();
        runeIndices.clear();
        runePages.clear();
        spellIndices.clear();
        spellPairs.clear();
        games = 0;
        wins = 0;
    }

    // ============================================================================

    private Build.CoreBuildOption coreOption(int index) {
        long key = coreKeys.getLong(index);
        int first = (int) (key >>> 32);
        int second = (int) key;
        Options core = cores.get(index);
        int row = core.firstRow[COMPLETION];
        int matches = core.matches.getInt(row);
        int won = core.wins.getInt(row);
        return new Build.CoreBuildOption(first + "-" + second, List.of(first, second),
            matches, won, rate(won, matches), rate(matches, games), core.averageSeconds(row), core.timed.getInt(row));
    }

    private Build toBuild(Filter filter, Options options, Build.CoreBuildOption core) {
        int total = core.matches();
        List<Build.Option> coreItems = options.items(CORE_FIRST, total, true);
        coreItems.addAll(options.items(CORE_SECOND, total, true));
        List<Build.Option> starters = options.items(STARTER, total, false);
        starters.sort(Comparator.comparingInt(Build.Option::matches).reversed().thenComparing(Build.Option::id));
        List<List<Build.Option>> itemSlots = options.slots(ITEM_SLOT, ITEM_SLOT_COUNT, total, true);
        List<List<Build.Option>> augments = options.slots(AUGMENT_SLOT, AUGMENT_SLOT_COUNT, total, false);
        Build.SkillOrderOption order = options.skills.recommended(SkillOrder.Selection.PREFIX);
        List<Build.SkillOrderOption> skills = order == null ? List.of() : List.of(order);
        Build.SkillOrderOption evolutions = options.evolutions == null ? null
            : options.evolutions.recommended(SkillOrder.Selection.MOST_PLAYED);
        List<Build.RuneOption> runes = runeOptions(options, total);
        return new Build(filter, total, core.wins(), core.winrate(), List.of(core), coreItems, starters,
            options.items(BOOTS, total, true), options.items(SUPPORT, total, true),
            options.items(ROLE_BOUND, total, true), itemSlots, runes,
            spellOptions(options, total), skills, evolutions, options.items(PRISMATIC, total, true), augments, null,
            null, null, List.of());
    }

    private List<Build.RuneOption> runeOptions(Options options, int total) {
        Map<String, RuneGroup> groups = new HashMap<>();
        for (int row = options.firstRow[RUNE_PAGE]; row >= 0; row = options.nextRow.getInt(row)) {
            int id = (int) options.keys.getLong(row);
            RuneSignature page = runePages.get(id);
            RuneGroup group = groups.computeIfAbsent(page.toKey(), ignored -> new RuneGroup());
            group.add(page, options.matches.getInt(row), options.wins.getInt(row));
        }

        List<Build.RuneOption> result = new ArrayList<>(groups.size());
        for (Map.Entry<String, RuneGroup> entry : groups.entrySet()) {
            RuneGroup group = entry.getValue();
            result.add(new Build.RuneOption(entry.getKey(), group.best,
                group.matches, group.wins, rate(group.wins, group.matches), rate(group.matches, total)));
        }
        result.sort(Comparator.comparingInt(Build.RuneOption::matches).reversed().thenComparing(Build.RuneOption::id));
        return result;
    }

    private List<Build.SummonerSpellOption> spellOptions(Options options, int total) {
        List<Build.SummonerSpellOption> result = new ArrayList<>();
        for (int row = options.firstRow[SPELLS]; row >= 0; row = options.nextRow.getInt(row)) {
            int pairId = (int) options.keys.getLong(row);
            String id = BuildUtils.joinInts(spellPairs.get(pairId));
            int count = options.matches.getInt(row);
            int won = options.wins.getInt(row);
            result.add(new Build.SummonerSpellOption(id, count, won, rate(won, count), rate(count, total)));
        }
        result.sort(Comparator.comparingInt(Build.SummonerSpellOption::matches).reversed()
            .thenComparing(Build.SummonerSpellOption::id));
        return result;
    }

    private static long pair(int first, int second) {
        return ((long) first << 32) | Integer.toUnsignedLong(second);
    }

    private static double rate(int numerator, int denominator) {
        return denominator == 0 ? 0 : (double) numerator / denominator;
    }

    private static final class Options {

        private final Long2IntOpenHashMap indices = new Long2IntOpenHashMap(4);
        private final LongArrayList keys = new LongArrayList();
        private final IntArrayList matches = new IntArrayList();
        private final IntArrayList wins = new IntArrayList();
        private final LongArrayList elapsed = new LongArrayList();
        private final IntArrayList timed = new IntArrayList();
        private final int[] firstRow = new int[AUGMENT_SLOT + AUGMENT_SLOT_COUNT];
        private final IntArrayList nextRow = new IntArrayList();
        private final SkillOrder skills = new SkillOrder();
        private SkillOrder evolutions;

        Options() {
            Arrays.fill(firstRow, -1);
        }

        void recordItem(int column, int item, int win, Map<Integer, Long> purchaseTimes) {
            if (item > 0) {
                record(column, item, win, purchaseTimes.getOrDefault(item, NO_TIMESTAMP));
            }
        }

        void record(int column, int id, int win, long time) {
            long key = pair(column, id);
            int index = indices.getOrDefault(key, -1);
            long millis = Math.max(0, time);
            int timedCount = time < 0 ? 0 : 1;
            if (index < 0) {
                index = keys.size();
                indices.put(key, index);
                nextRow.add(firstRow[column]);
                firstRow[column] = index;
                keys.add(key);
                matches.add(1);
                wins.add(win);
                elapsed.add(millis);
                timed.add(timedCount);
                return;
            }
            matches.set(index, matches.getInt(index) + 1);
            wins.set(index, wins.getInt(index) + win);
            elapsed.set(index, elapsed.getLong(index) + millis);
            timed.set(index, timed.getInt(index) + timedCount);
        }

        List<List<Build.Option>> slots(int first, int count, int total, boolean withTiming) {
            if (total == 0) {
                return List.of();
            }
            List<List<Build.Option>> result = new ArrayList<>(count);
            for (int slot = 0; slot < count; slot++) {
                result.add(items(first + slot, total, withTiming));
            }
            return result;
        }

        List<Build.Option> items(int column, int total, boolean withTiming) {
            IntArrayList rows = new IntArrayList();
            for (int row = firstRow[column]; row >= 0; row = nextRow.getInt(row)) {
                rows.add(row);
            }
            rows.sort((left, right) -> {
                int count = Integer.compare(matches.getInt(right), matches.getInt(left));
                return count != 0 ? count : Integer.compare((int) keys.getLong(left), (int) keys.getLong(right));
            });
            List<Build.Option> result = new ArrayList<>(rows.size());
            for (int index = 0; index < rows.size(); index++) {
                int row = rows.getInt(index);
                String id = Integer.toString((int) keys.getLong(row));
                result.add(option(row, id, total, withTiming));
            }
            return result;
        }

        Build.Option option(int row, String id, int total, boolean withTiming) {
            int count = matches.getInt(row);
            int won = wins.getInt(row);
            return new Build.Option(id, count, won, rate(won, count), rate(count, total),
                withTiming ? averageSeconds(row) : null, withTiming ? timed.getInt(row) : null);
        }

        Double averageSeconds(int row) {
            int count = timed.getInt(row);
            return count == 0 ? null : (double) elapsed.getLong(row) / count / 1000;
        }
    }

    private static final class RuneGroup {

        private RuneSignature best;
        private int bestCount;
        private int matches;
        private int wins;

        void add(RuneSignature page, int count, int won) {
            matches += count;
            wins += won;
            if (best == null || betterPage(page, count)) {
                best = page;
                bestCount = count;
            }
        }

        private boolean betterPage(RuneSignature page, int count) {
            boolean complete = page.statShards().size() == 3;
            boolean bestComplete = best.statShards().size() == 3;
            if (complete != bestComplete) {
                return complete;
            }
            if (count != bestCount) {
                return count > bestCount;
            }
            return page.statShardsKey().compareTo(best.statShardsKey()) < 0;
        }
    }
}
