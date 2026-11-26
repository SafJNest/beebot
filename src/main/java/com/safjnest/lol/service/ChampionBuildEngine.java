package com.safjnest.lol.service;

import com.safjnest.lol.champion.BuildSignature;
import com.safjnest.lol.champion.ChampionBuildData;
import com.safjnest.lol.champion.RuneSignature;
import com.safjnest.lol.model.Build;
import com.safjnest.lol.model.Filter;
import com.safjnest.lol.utils.BuildUtils;
import com.safjnest.lol.utils.ChampionBuildTimelineUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ChampionBuildEngine {

    private static final int SLOT_COUNT = 4;
    private static final int AUGMENT_SLOT_COUNT = 6;
    private static final int MAX_ABILITY_SLOT = 4;

    private final Filter filter;
    private final Map<String, int[]> coreBuilds = new LinkedHashMap<>();
    private final Map<String, List<Integer>> coreBuildItems = new LinkedHashMap<>();
    private final Map<String, TimingStats> coreBuildTimes = new LinkedHashMap<>();
    private final Map<Integer, int[]> coreItems = new LinkedHashMap<>();
    private final Map<Integer, TimingStats> coreItemTimes = new LinkedHashMap<>();
    private final Map<String, int[]> starters = new LinkedHashMap<>();
    private final Map<Integer, int[]> boots = new LinkedHashMap<>();
    private final Map<Integer, TimingStats> bootTimes = new LinkedHashMap<>();
    private final Map<Integer, int[]> supportItems = new LinkedHashMap<>();
    private final Map<Integer, TimingStats> supportTimes = new LinkedHashMap<>();
    private final Map<Integer, int[]> roleBoundItems = new LinkedHashMap<>();
    private final Map<Integer, TimingStats> roleBoundTimes = new LinkedHashMap<>();
    private final Map<Integer, Map<Integer, int[]>> slots = new LinkedHashMap<>();
    private final Map<Integer, Map<Integer, TimingStats>> slotTimes = new LinkedHashMap<>();
    private final RuneOptionAccumulator runes = new RuneOptionAccumulator();
    private final Map<String, int[]> summonerSpells = new LinkedHashMap<>();
    private final SkillOrderTrie skillOrders = new SkillOrderTrie();
    private final Map<Integer, int[]> prismatics = new LinkedHashMap<>();
    private final Map<Integer, TimingStats> prismaticTimes = new LinkedHashMap<>();
    private final Map<Integer, Map<Integer, int[]>> augments = new LinkedHashMap<>();
    private int games;
    private int wins;

    ChampionBuildEngine(Filter filter) {
        this.filter = filter;
    }

    Filter filter() {
        return filter;
    }

    void accept(ChampionBuildData.Game game) {
        if (game == null) {
            return;
        }

        BuildSignature signature = game.signature();
        Map<Integer, Long> purchaseTimes = game.itemPurchaseTimesMillis();
        boolean win = game.win();

        games++;
        wins += win ? 1 : 0;

        addCore(signature.core(), purchaseTimes, win);
        add(starters, BuildUtils.joinInts(signature.starter()), win);
        addTimedItem(signature.boots(), purchaseTimes, win, boots, bootTimes);
        addTimedItem(signature.suppItem(), purchaseTimes, win, supportItems, supportTimes);
        addTimedItem(game.roleBoundId(), purchaseTimes, win, roleBoundItems, roleBoundTimes);
        addSlots(signature, purchaseTimes, win);

        RuneSignature configuration = game.runes();
        if (configuration != null) {
            runes.add(configuration, win);
        }

        List<Integer> spells = signature.summonerSpells();
        if (!spells.isEmpty()) {
            add(summonerSpells, BuildUtils.joinInts(spells), win);
        }
        skillOrders.add(signature.spellOrder(), win, game.skillUpgrades());

        for (int item : signature.prismatics()) {
            addTimedItem(item, purchaseTimes, win, prismatics, prismaticTimes);
        }

        List<Integer> augmentIds = signature.augments();
        int augmentCount = Math.min(AUGMENT_SLOT_COUNT, augmentIds.size());
        for (int slot = 0; slot < augmentCount; slot++) {
            Map<Integer, int[]> options = augments.computeIfAbsent(slot, ignored -> new LinkedHashMap<>());
            add(options, augmentIds.get(slot), win);
        }
    }

    Build finish() {
        List<Build.CoreBuildOption> coreBuildOptions = toCoreBuilds();
        List<Build.Option> coreItemOptions = toTimedOptions(coreItems, coreItemTimes, games);
        List<Build.Option> starterOptions = toConfigOptions(starters, games);
        List<Build.Option> bootOptions = toTimedOptions(boots, bootTimes, games);
        List<Build.Option> supportItemOptions = toTimedOptions(supportItems, supportTimes, games);
        List<Build.Option> roleBoundOptions = toTimedOptions(roleBoundItems, roleBoundTimes, games);
        List<List<Build.Option>> itemSlots = games == 0 ? List.of() : toSlots(slots, slotTimes, SLOT_COUNT, games);

        List<Build.RuneOption> runeOptions = runes.toOptions(games);
        List<Build.Option> spellOptions = toConfigOptions(summonerSpells, games);
        List<Build.SkillOrderOption> skillOrderOptions = skillOrders.toOptions(games);
        List<Build.Option> prismaticOptions = toTimedOptions(prismatics, prismaticTimes, games);
        List<List<Build.Option>> augmentSlots = games == 0 ? List.of() : toSlots(augments, AUGMENT_SLOT_COUNT, games);

        return new Build(filter, games, wins, rate(wins, games),
            coreBuildOptions, coreItemOptions, starterOptions, bootOptions, supportItemOptions,
            roleBoundOptions, itemSlots, runeOptions, spellOptions, skillOrderOptions, prismaticOptions, augmentSlots);
    }

    void clear() {
        coreBuilds.clear();
        coreBuildItems.clear();
        coreBuildTimes.clear();
        coreItems.clear();
        coreItemTimes.clear();
        starters.clear();
        boots.clear();
        bootTimes.clear();
        supportItems.clear();
        supportTimes.clear();
        roleBoundItems.clear();
        roleBoundTimes.clear();
        slots.clear();
        slotTimes.clear();
        runes.clear();
        summonerSpells.clear();
        skillOrders.clear();
        prismatics.clear();
        prismaticTimes.clear();
        augments.clear();
        games = 0;
        wins = 0;
    }

    // ============================================================================

    static final class RuneOptionAccumulator {

        private final Map<String, int[]> values = new LinkedHashMap<>();
        private final Map<String, RuneSignature> configurations = new LinkedHashMap<>();
        private final Map<String, Map<RuneSignature, int[]>> completeShardVariants = new LinkedHashMap<>();

        void add(RuneSignature signature, boolean win) {
            String key = signature.toKey();
            ChampionBuildEngine.add(values, key, win);
            configurations.putIfAbsent(key, signature);
            if (signature.statShards().size() == 3)
                ChampionBuildEngine.add(completeShardVariants.computeIfAbsent(key, ignored -> new LinkedHashMap<>()),
                    signature, win);
        }

        List<Build.RuneOption> toOptions(int totalGames) {
            List<Map.Entry<String, int[]>> entries = sortedByGames(values, String::compareTo);

            List<Build.RuneOption> result = new ArrayList<>();
            for (Map.Entry<String, int[]> entry : entries) {
                int matches = entry.getValue()[0];
                int wins = entry.getValue()[1];
                result.add(new Build.RuneOption(entry.getKey(), bestSignature(entry.getKey()), matches, wins,
                    rate(wins, matches), rate(matches, totalGames)));
            }
            return result;
        }

        private RuneSignature bestSignature(String key) {
            RuneSignature best = configurations.get(key);
            int[] bestStats = null;
            Map<RuneSignature, int[]> variants = completeShardVariants.get(key);
            if (variants == null) return best;
            for (Map.Entry<RuneSignature, int[]> entry : variants.entrySet()) {
                if (bestStats == null || isBetter(entry, best, bestStats)) {
                    best = entry.getKey();
                    bestStats = entry.getValue();
                }
            }
            return best;
        }

        private static boolean isBetter(Map.Entry<RuneSignature, int[]> candidate, RuneSignature current,
                                        int[] currentStats) {
            int[] candidateStats = candidate.getValue();
            int winrate = Double.compare(rate(candidateStats[1], candidateStats[0]),
                rate(currentStats[1], currentStats[0]));
            if (winrate != 0) return winrate > 0;
            if (candidateStats[0] != currentStats[0]) return candidateStats[0] > currentStats[0];
            return candidate.getKey().statShardsKey().compareTo(current.statShardsKey()) < 0;
        }

        void clear() {
            values.clear();
            configurations.clear();
            completeShardVariants.clear();
        }
    }

    private void addCore(List<Integer> core, Map<Integer, Long> purchaseTimes, boolean win) {
        if (core.isEmpty()) {
            return;
        }

        String key = BuildUtils.joinInts(core);
        add(coreBuilds, key, win);
        coreBuildItems.putIfAbsent(key, core);

        long completedAt = 0;
        boolean complete = true;
        for (int item : core) {
            add(coreItems, item, win);

            Long acquiredAt = purchaseTimes.get(item);
            if (acquiredAt == null) {
                complete = false;
                continue;
            }

            TimingStats timing = coreItemTimes.computeIfAbsent(item, ignored -> new TimingStats());
            timing.add(acquiredAt / 1000.0);
            completedAt = Math.max(completedAt, acquiredAt);
        }

        if (complete) {
            TimingStats timing = coreBuildTimes.computeIfAbsent(key, ignored -> new TimingStats());
            timing.add(completedAt / 1000.0);
        }
    }

    private static void addTimedItem(int item, Map<Integer, Long> purchaseTimes, boolean win,
                                     Map<Integer, int[]> values, Map<Integer, TimingStats> times) {
        if (item == 0) {
            return;
        }

        add(values, item, win);
        Long acquiredAt = purchaseTimes.get(item);
        if (acquiredAt != null) {
            TimingStats timing = times.computeIfAbsent(item, ignored -> new TimingStats());
            timing.add(acquiredAt / 1000.0);
        }
    }

    private void addSlots(BuildSignature signature, Map<Integer, Long> purchaseTimes, boolean win) {
        List<Integer> fullBuild = signature.fullBuild();
        int coreSize = signature.core().size();
        int slotCount = Math.min(SLOT_COUNT, fullBuild.size() - coreSize);
        for (int slot = 0; slot < slotCount; slot++) {
            int item = fullBuild.get(coreSize + slot);
            Map<Integer, int[]> options = slots.computeIfAbsent(slot, ignored -> new LinkedHashMap<>());
            add(options, item, win);

            Long acquiredAt = purchaseTimes.get(item);
            if (acquiredAt != null) {
                Map<Integer, TimingStats> timings = slotTimes.computeIfAbsent(slot, ignored -> new LinkedHashMap<>());
                TimingStats timing = timings.computeIfAbsent(item, ignored -> new TimingStats());
                timing.add(acquiredAt / 1000.0);
            }
        }
    }

    private List<Build.CoreBuildOption> toCoreBuilds() {
        List<Build.CoreBuildOption> result = new ArrayList<>();
        List<Map.Entry<String, int[]>> entries = sortedByGames(coreBuilds, String::compareTo);
        for (Map.Entry<String, int[]> entry : entries) {
            String key = entry.getKey();
            int matches = entry.getValue()[0];
            int wins = entry.getValue()[1];
            List<Integer> items = coreBuildItems.get(key);
            TimingStats timing = coreBuildTimes.get(key);

            result.add(new Build.CoreBuildOption(key, items, matches, wins,
                rate(wins, matches), rate(matches, games), average(timing), timedMatches(timing)));
        }
        return result;
    }

    private static List<Build.Option> toOptions(Map<Integer, int[]> values, int totalGames) {
        List<Build.Option> result = new ArrayList<>();
        List<Map.Entry<Integer, int[]>> entries = sortedByGames(values, Integer::compareTo);
        for (Map.Entry<Integer, int[]> entry : entries)
            result.add(option(String.valueOf(entry.getKey()), entry.getValue(), totalGames));
        return result;
    }

    private static List<Build.Option> toTimedOptions(Map<Integer, int[]> values,
                                                      Map<Integer, TimingStats> times, int totalGames) {
        List<Build.Option> result = new ArrayList<>();
        List<Map.Entry<Integer, int[]>> entries = sortedByGames(values, Integer::compareTo);
        for (Map.Entry<Integer, int[]> entry : entries) {
            TimingStats timing = times.get(entry.getKey());
            int matches = entry.getValue()[0];
            int wins = entry.getValue()[1];
            result.add(new Build.Option(String.valueOf(entry.getKey()), matches, wins, rate(wins, matches),
                rate(matches, totalGames), average(timing), timedMatches(timing)));
        }
        return result;
    }

    private static List<Build.Option> toConfigOptions(Map<String, int[]> values, int totalGames) {
        List<Build.Option> result = new ArrayList<>();
        List<Map.Entry<String, int[]>> entries = sortedByGames(values, String::compareTo);
        for (Map.Entry<String, int[]> entry : entries)
            result.add(option(entry.getKey(), entry.getValue(), totalGames));
        return result;
    }

    private static List<List<Build.Option>> toSlots(Map<Integer, Map<Integer, int[]>> values,
                                                     Map<Integer, Map<Integer, TimingStats>> times,
                                                     int count, int totalGames) {
        List<List<Build.Option>> result = new ArrayList<>();
        for (int slot = 0; slot < count; slot++)
            result.add(toTimedOptions(values.getOrDefault(slot, Map.of()),
                times.getOrDefault(slot, Map.of()), totalGames));
        return result;
    }

    private static List<List<Build.Option>> toSlots(Map<Integer, Map<Integer, int[]>> values,
                                                     int count, int totalGames) {
        List<List<Build.Option>> result = new ArrayList<>();
        for (int slot = 0; slot < count; slot++)
            result.add(toOptions(values.getOrDefault(slot, Map.of()), totalGames));
        return result;
    }

    private static Build.Option option(String id, int[] stats, int totalGames) {
        int matches = stats[0];
        int wins = stats[1];
        return new Build.Option(id, matches, wins, rate(wins, matches), rate(matches, totalGames));
    }

    private static Double average(TimingStats timing) {
        return timing == null || timing.count == 0 ? null : timing.sum / timing.count;
    }

    private static int timedMatches(TimingStats timing) {
        return timing == null ? 0 : timing.count;
    }

    private static <K> List<Map.Entry<K, int[]>> sortedByGames(Map<K, int[]> values,
                                                                Comparator<? super K> comparator) {
        List<Map.Entry<K, int[]>> result = new ArrayList<>(values.entrySet());
        result.sort((left, right) -> {
            int matches = Integer.compare(right.getValue()[0], left.getValue()[0]);
            return matches != 0 ? matches : comparator.compare(left.getKey(), right.getKey());
        });
        return result;
    }

    private static <K> void add(Map<K, int[]> values, K key, boolean win) {
        if (key == null || key instanceof String string && string.isBlank()) {
            return;
        }

        int[] stats = values.computeIfAbsent(key, ignored -> new int[2]);
        stats[0]++;
        stats[1] += win ? 1 : 0;
    }

    private static double rate(int numerator, int denominator) {
        return denominator > 0 ? (double) numerator / denominator : 0;
    }

    private static final class TimingStats {
        private double sum;
        private int count;

        private void add(double value) {
            sum += value;
            count++;
        }
    }

    static final class SkillOrderTrie {

        private final SkillOrderNode root = new SkillOrderNode();

        void add(List<Integer> order, boolean win) {
            add(order, win, List.of());
        }

        void add(List<Integer> order, boolean win, List<ChampionBuildTimelineUtils.SkillUpgrade> upgrades) {
            if (order == null || order.isEmpty()) return;

            SkillOrderNode current = root;
            int depth = 0;
            for (Integer ability : order) {
                if (ability == null || ability < 1 || ability > MAX_ABILITY_SLOT) break;
                current = current.children.computeIfAbsent(ability, ignored -> new SkillOrderNode());
                depth++;
                if (upgrades != null && depth <= upgrades.size()) {
                    ChampionBuildTimelineUtils.SkillUpgrade upgrade = upgrades.get(depth - 1);
                    if (upgrade.skillSlot() == ability && upgrade.championLevel() != null)
                        current.times.computeIfAbsent(upgrade.championLevel(), ignored -> new TimingStats())
                            .add(upgrade.timestampMillis() / 1000.0);
                }
            }
            if (depth == 0) return;

            current.matches++;
            if (win) current.wins++;
        }

        List<Build.SkillOrderOption> toOptions(int totalGames) {
            int targetDepth = deepestObservedDepth(root, 0);
            if (targetDepth == 0) return List.of();

            List<SkillOrderCandidate> candidates = new ArrayList<>();
            collectCandidates(root, new ArrayList<>(), new ArrayList<>(), 0, 0, targetDepth, candidates);
            candidates.sort((left, right) -> {
                int support = Integer.compare(right.matches(), left.matches());
                if (support != 0) return support;
                int exact = Integer.compare(right.exactMatches(), left.exactMatches());
                return exact != 0 ? exact : left.id().compareTo(right.id());
            });

            List<Build.SkillOrderOption> result = new ArrayList<>();
            for (SkillOrderCandidate candidate : candidates)
                result.add(new Build.SkillOrderOption(candidate.id(), candidate.order(), candidate.matches(),
                    candidate.wins(), rate(candidate.wins(), candidate.matches()), rate(candidate.matches(), totalGames),
                    candidate.timedOrder()));
            return result;
        }

        void clear() {
            root.clear();
        }

        private static int deepestObservedDepth(SkillOrderNode node, int depth) {
            int result = node.matches > 0 ? depth : 0;
            for (SkillOrderNode child : node.children.values())
                result = Math.max(result, deepestObservedDepth(child, depth + 1));
            return result;
        }

        private static void collectCandidates(SkillOrderNode node, List<Integer> order,
                                              List<SkillOrderNode> path, int prefixMatches,
                                              int prefixWins, int targetDepth,
                                              List<SkillOrderCandidate> candidates) {
            int matches = prefixMatches + node.matches;
            int wins = prefixWins + node.wins;
            if (order.size() == targetDepth) {
                if (node.matches > 0) candidates.add(new SkillOrderCandidate(BuildUtils.joinInts(order),
                    List.copyOf(order), matches, wins, node.matches, skillTimings(order, path)));
                return;
            }

            for (Map.Entry<Integer, SkillOrderNode> entry : node.children.entrySet()) {
                order.add(entry.getKey());
                path.add(entry.getValue());
                collectCandidates(entry.getValue(), order, path, matches, wins, targetDepth, candidates);
                path.remove(path.size() - 1);
                order.remove(order.size() - 1);
            }
        }

        private static List<Build.SkillTiming> skillTimings(List<Integer> order, List<SkillOrderNode> path) {
            List<Build.SkillTiming> result = new ArrayList<>();
            for (int index = 0; index < path.size(); index++) {
                SkillOrderNode node = path.get(index);
                for (Map.Entry<Integer, TimingStats> timing : node.times.entrySet()) {
                    TimingStats stats = timing.getValue();
                    result.add(new Build.SkillTiming(timing.getKey(), order.get(index),
                        stats.sum / stats.count, stats.count));
                }
            }
            result.sort(Comparator.comparing(Build.SkillTiming::level, Comparator.nullsLast(Integer::compareTo))
                .thenComparingInt(Build.SkillTiming::abilitySlot));
            return List.copyOf(result);
        }
    }

    private static final class SkillOrderNode {

        private final Map<Integer, SkillOrderNode> children = new LinkedHashMap<>();
        private final Map<Integer, TimingStats> times = new LinkedHashMap<>();
        private int matches;
        private int wins;

        private void clear() {
            for (SkillOrderNode child : children.values()) child.clear();
            children.clear();
            times.clear();
            matches = 0;
            wins = 0;
        }
    }

    private record SkillOrderCandidate(String id, List<Integer> order, int matches, int wins, int exactMatches,
                                       List<Build.SkillTiming> timedOrder) {}
}
