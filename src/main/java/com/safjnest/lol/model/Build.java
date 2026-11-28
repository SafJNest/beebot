package com.safjnest.lol.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.safjnest.lol.champion.RuneSignature;
import com.safjnest.lol.utils.BuildUtils;
import com.safjnest.lol.utils.ItemUtils;
import com.safjnest.lol.model.statistics.StatisticalLeaf;
import com.safjnest.utils.JsonCodec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The complete build aggregate. The order of every option list is
 * presentation-neutral; callers choose the option they want to use.
 */
public record Build(
    Filter filter,
    int games,
    int wins,
    double winrate,
    List<CoreBuildOption> coreBuilds,
    List<Option> coreItems,
    @JsonProperty("starters") List<Option> starterOptions,
    @JsonProperty("boots") List<Option> bootOptions,
    @JsonProperty("supportItems") List<Option> supportItemOptions,
    @JsonProperty("roleBoundItems") List<Option> roleBoundItemOptions,
    @JsonProperty("slots") List<List<Option>> itemSlots,
    @JsonProperty("runes") List<RuneOption> runeOptions,
    @JsonProperty("summonerSpells") List<SummonerSpellOption> summonerSpellOptions,
    @JsonInclude(JsonInclude.Include.NON_NULL) List<SkillOrderOption> skillOrders,
    @JsonInclude(JsonInclude.Include.NON_NULL) SkillOrderOption skillEvolutions,
    @JsonProperty("prismatics") List<Option> prismaticOptions,
    @JsonProperty("augments") List<List<Option>> augmentOptions,
    @JsonInclude(JsonInclude.Include.NON_NULL) ArenaBuildData arena,
    @JsonInclude(JsonInclude.Include.NON_NULL) String name,
    @JsonInclude(JsonInclude.Include.NON_NULL) Integer nameKeystoneId,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) List<Badge> badges
) {

    public Build {
        starterOptions = withoutUnupgradedBoots(starterOptions);
        bootOptions = withoutUnupgradedBoots(bootOptions);
        roleBoundItemOptions = roleBoundItemOptions == null ? List.of() : withoutUnupgradedBoots(List.copyOf(roleBoundItemOptions));
        badges = badges == null ? List.of() : List.copyOf(badges);
        if (skillOrders != null && skillOrders.size() > 1) {
            skillOrders = List.of(skillOrders.getFirst());
        }
    }

    public enum Kind { ITEM, BOOTS, PRISMATIC, AUGMENT }

    public enum Badge { MOST_POPULAR, BEST_ADJUSTED_WINRATE }

    public Build withLabels(String name, Integer keystoneId, List<Badge> badges) {
        return new Build(filter, games, wins, winrate, coreBuilds, coreItems, starterOptions, bootOptions,
            supportItemOptions, roleBoundItemOptions, itemSlots, runeOptions, summonerSpellOptions,
            skillOrders, skillEvolutions, prismaticOptions, augmentOptions, arena, name, keystoneId, badges);
    }

    public static Build empty(Filter filter) {
        return new Build(filter, 0, 0, 0, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of(), List.of(), null, List.of(), List.of(), null, null, null, List.of());
    }

    public static Build fromArena(Filter filter, ArenaBuildData arena) {
        if (arena == null) return null;
        long total = arena.games();
        List<CoreBuildOption> cores = new ArrayList<>();
        Map<Integer, long[]> coreItems = new TreeMap<>();
        for (ArenaBuildData.Core core : arena.coreBuilds()) {
            cores.add(new CoreBuildOption(core.id(), core.items(), Math.toIntExact(core.matches()),
                Math.toIntExact(core.wins()), core.winrate(), core.pickrate(), core.averageCompletionTimeSeconds(),
                core.timedMatches() == null ? null : Math.toIntExact(core.timedMatches())));
            for (int id : core.items()) addArenaCount(coreItems, id, core.matches(), core.wins());
        }
        List<Option> bootOptions = new ArrayList<>();
        for (ArenaBuildData.Option boot : arena.boots()) {
            bootOptions.add(new Option(String.valueOf(boot.id()), Math.toIntExact(boot.matches()),
                Math.toIntExact(boot.wins()), boot.winrate(), total == 0 ? 0 : (double) boot.matches() / total,
                boot.averagePurchaseTimeSeconds(),
                boot.timedMatches() == null ? null : Math.toIntExact(boot.timedMatches())));
        }
        Map<Integer, long[]> prismatics = new TreeMap<>();
        for (ArenaBuildData.Option item : arena.prismatics())
            addArenaCount(prismatics, item.id(), item.matches(), item.wins());
        List<List<Option>> augments = new ArrayList<>();
        for (List<ArenaBuildData.Option> position : arena.augments()) {
            Map<Integer, long[]> values = new TreeMap<>();
            for (ArenaBuildData.Option augment : position)
                addArenaCount(values, augment.id(), augment.matches(), augment.wins());
            augments.add(arenaOptions(values, total));
        }
        return new Build(filter, Math.toIntExact(total), Math.toIntExact(arena.wins()),
            arena.winrate(), cores, arenaOptions(coreItems, total), List.of(), bootOptions,
            List.of(), List.of(), List.of(), List.of(), List.of(),
            arena.skillOrders().isEmpty() ? null : arena.skillOrders(), arena.skillEvolutions(),
            arenaOptions(prismatics, total), augments, arena, null, null, List.of());
    }

    private static void addArenaCount(Map<Integer, long[]> values, int id, long games, long wins) {
        long[] count = values.computeIfAbsent(id, ignored -> new long[2]);
        count[0] += games;
        count[1] += wins;
    }

    private static List<Option> arenaOptions(Map<Integer, long[]> values, long total) {
        List<Option> result = new ArrayList<>();
        for (var entry : values.entrySet()) {
            long[] count = entry.getValue();
            result.add(new Option(String.valueOf(entry.getKey()), Math.toIntExact(count[0]),
                Math.toIntExact(count[1]), count[0] == 0 ? 0 : (double) count[1] / count[0],
                total == 0 ? 0 : (double) count[0] / total));
        }
        return List.copyOf(result);
    }

    public record Timing(long timeSumMillis, long timeCount) {
        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        public Double averageTimeMillis() {
            return timeCount == 0 ? null : (double) timeSumMillis / timeCount;
        }
    }

    public record Choice(Kind kind, int id, Integer position, StatisticalLeaf stats, Timing timing,
                         long denominator, long unpositionedGames) {
        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        public double pickrate() {
            return denominator == 0 ? 0 : (double) stats.games() / denominator;
        }
    }

    public record Slot(Kind kind, int position, StatisticalLeaf population, List<Choice> options) {
        public Slot { options = List.copyOf(options); }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Option(String id, int matches, int wins, double winrate, double pickrate,
                         Double averagePurchaseTimeSeconds, Integer timedMatches) {
        public Option(String id, int matches, int wins, double winrate, double pickrate) {
            this(id, matches, wins, winrate, pickrate, null, null);
        }

        @JsonIgnore
        public List<Integer> itemIds() {
            try { return BuildUtils.parseDashList(id); }
            catch (RuntimeException ignored) { return List.of(); }
        }

        public int itemId() {
            try { return Integer.parseInt(id); }
            catch (Exception ignored) {
                try {
                    List<Integer> spells = BuildUtils.parseDashList(id);
                    return spells.size() >= 2 ? spells.get(0) * 100 + spells.get(1) : 0;
                } catch (RuntimeException ignoredAgain) {
                    return 0;
                }
            }
        }

        public String prettyWinrate() {
            return String.format("%.2f", winrate * 100) + "%";
        }

        public String prettyMatches() {
            return String.format("%d", matches);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CoreBuildOption(String id, List<Integer> items, int matches, int wins,
                                  double winrate, double pickrate,
                                  Double averageCompletionTimeSeconds, Integer timedMatches) {
        public CoreBuildOption(String id, List<Integer> items, int matches, int wins,
                               double winrate, double pickrate) {
            this(id, items, matches, wins, winrate, pickrate, null, null);
        }
    }

    public record RuneOption(String id, RuneSignature configuration, int matches, int wins,
                             double winrate, double pickrate) {}

    public record SummonerSpellOption(String id, int matches, int wins, double winrate, double pickrate) {
        int packedId() {
            int separator = id.indexOf('-');
            if (separator < 0) {
                return Integer.parseInt(id);
            }
            int first = Integer.parseInt(id, 0, separator, 10);
            int second = Integer.parseInt(id, separator + 1, id.length(), 10);
            return first * 100 + second;
        }

        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        public int getSpell1() {
            return packedId() / 100;
        }

        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        public int getSpell2() {
            return packedId() % 100;
        }
    }

    @JsonIgnoreProperties({"id", "matches", "wins", "winrate", "pickrate"})
    public record SkillOrderOption(List<Integer> order, List<SkillTiming> timedOrder,
                                   @JsonInclude(JsonInclude.Include.NON_NULL) TimingMethod timingMethod) {
        public SkillOrderOption {
            timedOrder = timedOrder == null ? List.of() : List.copyOf(timedOrder);
        }

        public SkillOrderOption(List<Integer> order, List<SkillTiming> timedOrder) {
            this(order, timedOrder, null);
        }
    }

    public enum TimingMethod { PREFIX_AVERAGE_INTERVALS }

    public record SkillTiming(@JsonInclude(JsonInclude.Include.NON_NULL) Integer level, Integer skill,
                              int abilitySlot, double averageUpgradeTimeSeconds, int timedMatches) {
        @JsonCreator
        public static SkillTiming fromJson(@JsonProperty("level") Integer level,
                                           @JsonProperty("skill") Integer skill,
                                           @JsonProperty("abilitySlot") int abilitySlot,
                                           @JsonProperty("averageUpgradeTimeSeconds") double averageUpgradeTimeSeconds,
                                           @JsonProperty("timedMatches") int timedMatches,
                                           @JsonProperty("championLevel") Integer championLevel) {
            if (skill == null) {
                return new SkillTiming(championLevel, level, abilitySlot, averageUpgradeTimeSeconds, timedMatches);
            }
            return new SkillTiming(level, skill, abilitySlot, averageUpgradeTimeSeconds, timedMatches);
        }
    }

    /** Compatibility shape used by the Discord command and old callers. */
    public record SlotOption(int itemId, int matches, double winrate) {
        public int getSpell1() {
            return itemId / 100;
        }

        public int getSpell2() {
            return itemId % 100;
        }

        public String prettyWinrate() {
            return String.format("%.2f", winrate * 100) + "%";
        }

        public String prettyMatches() {
            return String.format("%d", matches);
        }
    }

    /** Compatibility constructor for persisted callers and non-HTTP consumers. */
    public Build(
        Filter filter,
        List<Integer> starter,
        List<SlotOption> boots,
        List<SlotOption> suppItems,
        List<Integer> core,
        List<List<SlotOption>> slots,
        List<List<SlotOption>> prismatics,
        List<SlotOption> summonerSpells,
        List<SlotOption> augments,
        List<Integer> spellOrder,
        RuneSignature runes,
        int games,
        double winrate
    ) {
        this(
            filter,
            games,
            winsFor(games, winrate),
            winrate,
            core.isEmpty() ? List.of() : List.of(new CoreBuildOption(
                BuildUtils.joinInts(core), core, games, winsFor(games, winrate), winrate, 1
            )),
            toItemOptions(core, games, winsFor(games, winrate)),
            toItemOptions(starter, games, winsFor(games, winrate)),
            toSlotOptions(boots, games),
            toSlotOptions(suppItems, games),
            List.of(),
            toOptionSlots(slots),
            runes == null ? List.of() : List.of(new RuneOption(
                runes.toKey(), runes, games, winsFor(games, winrate), winrate, 1
            )),
            toSummonerSpellOptions(summonerSpells, games),
            spellOrder.isEmpty() ? List.of() : List.of(new SkillOrderOption(spellOrder, List.of())),
            null,
            flattenOptions(prismatics),
            augments.isEmpty() ? List.of() : List.of(toSlotOptions(augments, games)),
            null, null, null, List.of()
        );
    }

    public String toJson() {
        return JsonCodec.toJson(this);
    }

    public static Build fromJson(String json) {
        try {
            Build build = JsonCodec.fromJson(json, Build.class);
            if (build == null) return null;
            if (build.filter() != null && !(build.filter() instanceof Filter)) return null;
            if (build.coreBuilds() == null || build.coreItems() == null || build.starterOptions() == null
                    || build.bootOptions() == null || build.supportItemOptions() == null
                    || build.itemSlots() == null || build.runeOptions() == null
                    || build.summonerSpellOptions() == null || build.skillOrders() == null
                    || build.prismaticOptions() == null || build.augmentOptions() == null) return null;
            return build;
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    @JsonIgnore
    public List<Integer> starter() {
        return starterIds(starterOptions);
    }

    @JsonIgnore
    public List<SlotOption> boots() {
        return toLegacy(bootOptions);
    }

    @JsonIgnore
    public List<SlotOption> suppItems() {
        return toLegacy(supportItemOptions);
    }

    @JsonIgnore
    public List<Integer> core() {
        return ids(coreItems);
    }

    @JsonIgnore
    public List<List<SlotOption>> slots() {
        return toLegacySlots(itemSlots);
    }

    @JsonIgnore
    public List<List<SlotOption>> prismatics() {
        return prismaticOptions.isEmpty() ? List.of() : List.of(toLegacy(prismaticOptions));
    }

    @JsonIgnore
    public List<SlotOption> summonerSpells() {
        List<SlotOption> result = new ArrayList<>(summonerSpellOptions.size());
        for (SummonerSpellOption option : summonerSpellOptions) {
            result.add(new SlotOption(option.packedId(), option.matches(), option.winrate()));
        }
        return result;
    }

    @JsonIgnore
    public List<SlotOption> augments() {
        return flattenLegacySlots(augmentOptions);
    }

    @JsonIgnore
    public List<Integer> spellOrder() {
        return skillOrders.isEmpty() ? List.of() : skillOrders.get(0).order();
    }

    @JsonIgnore
    public List<String> getSkillOrder() {
        List<String> result = new ArrayList<>();
        for (int value : spellOrder()) result.add(String.valueOf(value));
        while (result.size() < 18) result.add("0");
        return result;
    }

    @JsonIgnore
    public RuneSignature runes() {
        return runeOptions.isEmpty() ? null : runeOptions.get(0).configuration();
    }

    @JsonIgnore
    public int getKeystone() {
        return runes() == null ? 0 : runes().keystone();
    }

    @JsonIgnore
    public int getPrimaryTree() {
        return runes() == null ? 0 : runes().primaryTree();
    }

    @JsonIgnore
    public int getSecondaryTree() {
        return runes() == null ? 0 : runes().secondaryTree();
    }

    @JsonIgnore
    public List<Integer> getPrimaryRunes() {
        return runes() == null ? List.of() : runes().primaryRunes();
    }

    @JsonIgnore
    public List<Integer> getSecondaryRunes() {
        return runes() == null ? List.of() : runes().secondaryRunes();
    }

    @JsonIgnore
    public List<Integer> getStatShards() {
        return runes() == null ? List.of() : runes().statShards();
    }

    @JsonIgnore
    public int getOffense() {
        return shard(0);
    }

    @JsonIgnore
    public int getFlex() {
        return shard(1);
    }

    @JsonIgnore
    public int getDefense() {
        return shard(2);
    }

    public void print() {
        System.out.printf("=== ChampionBuild === games=%d wins=%d winrate=%.1f%%%n", games, wins, winrate * 100);
        System.out.println("coreBuilds=" + coreBuilds);
        System.out.println("coreItems=" + coreItems);
        System.out.println("starters=" + starterOptions);
        System.out.println("boots=" + bootOptions);
        System.out.println("supportItems=" + supportItemOptions);
        System.out.println("slots=" + itemSlots);
        System.out.println("runes=" + runeOptions);
        System.out.println("summonerSpells=" + summonerSpellOptions);
        System.out.println("skillOrders=" + skillOrders);
        System.out.println("prismatics=" + prismaticOptions);
        System.out.println("augments=" + augmentOptions);
    }

    private int shard(int index) {
        List<Integer> shards = getStatShards();
        return index < shards.size() ? shards.get(index) : 0;
    }

    private static int winsFor(int games, double winrate) {
        return (int) Math.round(games * winrate);
    }

    private static List<Option> toItemOptions(List<Integer> ids, int matches, int wins) {
        List<Option> result = new ArrayList<>();
        for (Integer id : ids) result.add(new Option(String.valueOf(id), matches, wins, rate(wins, matches), 1));
        return result;
    }

    private static List<Integer> starterIds(List<Option> options) {
        List<Integer> result = new ArrayList<>();
        for (Option option : options) result.addAll(option.itemIds());
        return result;
    }

    private static List<Option> withoutUnupgradedBoots(List<Option> options) {
        if (options != null) {
            for (Option option : options) {
                if (ItemUtils.isUnupgradedBoots(option.itemId())) {
                    return options.stream().filter(value -> !ItemUtils.isUnupgradedBoots(value.itemId())).toList();
                }
            }
        }
        return options;
    }

    private static List<Option> toSlotOptions(List<SlotOption> options, int fallbackMatches) {
        List<Option> result = new ArrayList<>();
        for (SlotOption option : options) {
            int matches = option.matches() > 0 ? option.matches() : fallbackMatches;
            int wins = winsFor(matches, option.winrate());
            result.add(new Option(String.valueOf(option.itemId()), matches, wins, option.winrate(), 0));
        }
        return result;
    }

    private static List<SummonerSpellOption> toSummonerSpellOptions(List<SlotOption> options, int fallbackMatches) {
        List<SummonerSpellOption> result = new ArrayList<>(options.size());
        for (SlotOption option : options) {
            int matches = option.matches() > 0 ? option.matches() : fallbackMatches;
            int wins = winsFor(matches, option.winrate());
            result.add(new SummonerSpellOption(String.valueOf(option.itemId()), matches, wins, option.winrate(), 0));
        }
        return result;
    }

    private static List<List<Option>> toOptionSlots(List<List<SlotOption>> slots) {
        List<List<Option>> result = new ArrayList<>();
        for (List<SlotOption> slot : slots) result.add(toSlotOptions(slot, 0));
        return result;
    }

    private static List<Option> flattenOptions(List<List<SlotOption>> slots) {
        List<Option> result = new ArrayList<>();
        for (List<SlotOption> slot : slots) result.addAll(toSlotOptions(slot, 0));
        return result;
    }

    private static List<Integer> ids(List<Option> options) {
        List<Integer> result = new ArrayList<>();
        for (Option option : options) result.add(option.itemId());
        return result;
    }

    private static List<SlotOption> toLegacy(List<Option> options) {
        List<SlotOption> result = new ArrayList<>();
        for (Option option : options) result.add(new SlotOption(option.itemId(), option.matches(), option.winrate()));
        return result;
    }

    private static List<List<SlotOption>> toLegacySlots(List<List<Option>> slots) {
        List<List<SlotOption>> result = new ArrayList<>();
        for (List<Option> slot : slots) result.add(toLegacy(slot));
        return result;
    }

    private static List<SlotOption> flattenLegacySlots(List<List<Option>> slots) {
        List<SlotOption> result = new ArrayList<>();
        for (List<Option> slot : slots) result.addAll(toLegacy(slot));
        return result;
    }

    private static double rate(int wins, int matches) {
        return matches > 0 ? (double) wins / matches : 0;
    }

}
