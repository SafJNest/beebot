package com.safjnest.lol.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.safjnest.lol.model.Build.SkillOrderOption;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"games", "wins", "winrate", "averagePlacement", "firstPlaceRate",
    "coreBuilds", "items", "boots", "augments", "prismatics", "skillOrders", "skillEvolutions", "coverage"})
public record ArenaBuildData(long games, long wins, List<Core> coreBuilds, List<List<Option>> augments,
        List<Option> prismatics, Coverage coverage,
        @JsonProperty(value = "consumables", access = JsonProperty.Access.WRITE_ONLY) List<Option> legacyConsumables,
        List<Option> items, List<Option> boots, Double averagePlacement, Double firstPlaceRate,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<SkillOrderOption> skillOrders,
        @JsonInclude(JsonInclude.Include.NON_NULL) SkillOrderOption skillEvolutions) {

    public static final int MAX_AUGMENTS = 6;

    public ArenaBuildData {
        coreBuilds = List.copyOf(coreBuilds);
        prismatics = List.copyOf(prismatics);
        legacyConsumables = legacyConsumables == null ? null : List.copyOf(legacyConsumables);
        augments = copySlots(augments);
        items = items == null ? List.of() : List.copyOf(items);
        boots = boots == null ? List.of() : List.copyOf(boots);
        skillOrders = skillOrders == null ? List.of() : List.copyOf(skillOrders);
    }

    public ArenaBuildData(long games, long wins, List<Core> coreBuilds,
            List<List<Option>> augments, List<Option> prismatics, Coverage coverage) {
        this(games, wins, coreBuilds, augments, prismatics, coverage, null,
            List.of(), List.of(), null, null, List.of(), null);
    }

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public double winrate() {
        return rate(wins, games);
    }

    public ArenaBuildData withCoreBuilds(List<Core> cores) {
        return new ArenaBuildData(games, wins, cores, augments, prismatics, coverage, legacyConsumables,
            items, boots, averagePlacement, firstPlaceRate, skillOrders, skillEvolutions);
    }

    public record Core(String id, List<Integer> items, long matches, long wins, double pickrate,
            List<FullBuild> builds, List<List<Option>> augments, List<Option> prismatics,
            Double averagePlacement, Double firstPlaceRate, List<Option> itemOptions, List<List<Option>> slots,
            @JsonInclude(JsonInclude.Include.NON_NULL) Option boots,
            @JsonInclude(JsonInclude.Include.NON_NULL) Double averageCompletionTimeSeconds,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long timedMatches,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) List<SkillOrderOption> skillOrders,
            @JsonInclude(JsonInclude.Include.NON_NULL) SkillOrderOption skillEvolutions,
            @JsonInclude(JsonInclude.Include.NON_NULL) String name,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) List<Build.Badge> badges) {
        public Core {
            items = List.copyOf(items);
            builds = List.copyOf(builds);
            prismatics = List.copyOf(prismatics);
            augments = copySlots(augments);
            itemOptions = itemOptions == null ? List.of() : List.copyOf(itemOptions);
            slots = slots == null ? List.of() : copySlots(slots);
            skillOrders = skillOrders == null ? List.of() : List.copyOf(skillOrders);
            badges = badges == null ? List.of() : List.copyOf(badges);
        }

        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        public double winrate() {
            return rate(wins, matches);
        }

        public Core withLabels(String name, List<Build.Badge> badges) {
            return new Core(id, items, matches, wins, pickrate, builds, augments, prismatics, averagePlacement,
                firstPlaceRate, itemOptions, slots, boots, averageCompletionTimeSeconds, timedMatches,
                skillOrders, skillEvolutions, name, badges);
        }
    }

    public record FullBuild(List<Integer> items, long matches, long wins,
            @JsonProperty(value = "consumables", access = JsonProperty.Access.WRITE_ONLY) List<Integer> legacyConsumables,
            Double pickrate, Double averagePlacement, Double firstPlaceRate,
            @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean inventoryFallback) {
        public FullBuild {
            items = List.copyOf(items);
            legacyConsumables = legacyConsumables == null ? null : List.copyOf(legacyConsumables);
        }

        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        public double winrate() {
            return rate(wins, matches);
        }
    }

    public record Option(int id, long matches, long wins, Double pickrate, Double averagePlacement, Double firstPlaceRate,
            @JsonInclude(JsonInclude.Include.NON_NULL) Double averagePurchaseTimeSeconds,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long timedMatches) {
        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        public double winrate() {
            return rate(wins, matches);
        }
    }

    public record Coverage(long matches, long coreGames, long missingCoreGames,
            long firstPrismaticFallbackGames, long buildTimelineGames, long orderedItemGames,
            long coreGamesWithOrderedItems, long coreGamesWithInventoryOnlyItems, long coreGamesWithoutItems) {}

    private static double rate(long count, long total) {
        return total == 0 ? 0 : (double) count / total;
    }

    private static List<List<Option>> copySlots(List<List<Option>> values) {
        return values.stream().map(List::copyOf).toList();
    }
}
