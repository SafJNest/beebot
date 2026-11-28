package com.safjnest.lol.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import com.safjnest.lol.arena.ArenaGameParser;
import com.safjnest.lol.arena.ArenaItemCatalog;
import com.safjnest.lol.arena.ParsedArenaGame;
import com.safjnest.lol.model.ArenaBuildData;
import com.safjnest.lol.model.ArenaBuildData.Option;
import com.safjnest.lol.model.ArenaBuildData.Core;
import com.safjnest.lol.model.ArenaBuildData.FullBuild;
import com.safjnest.lol.utils.BuildUtils;
import com.safjnest.database.nosql.MongoDB.ChampionRawMatch;
import com.safjnest.lol.champion.ChampionBuildProvider;
import com.safjnest.lol.model.Build.SkillOrderOption;
import com.safjnest.lol.utils.ChampionBuildTimelineUtils.ItemEvent;
import org.bson.Document;
import com.safjnest.lol.utils.PatchUtils;
import no.stelar7.api.r4j.basic.constants.types.lol.GameQueueType;

public final class ArenaChampionAnalyzer {

    private static final Pattern MATCH_ID_PATTERN = Pattern.compile("[A-Z0-9]+_[0-9]+");

    private ArenaChampionAnalyzer() {}

    public static Accumulator accumulator(int championId, String patch, ArenaItemCatalog catalog) {
        return new Accumulator(championId, patch, catalog);
    }

    public static final class Accumulator {
        private final int championId;
        private final String patch;
        private final boolean majorPatch;
        private final ArenaItemCatalog catalog;
        private final Map<String, Map<Integer, LongOpenHashSet>> matchesByPlatform = new HashMap<>();
        private final Set<String> oversizedMatchIds = new HashSet<>();
        private final StatsCounter overall = new StatsCounter();
        private final Options options = new Options();
        private final Int2ObjectOpenHashMap<StatsCounter> boots = new Int2ObjectOpenHashMap<>(4);
        private final Long2ObjectOpenHashMap<CoreCounter> cores = new Long2ObjectOpenHashMap<>();
        private long matches;
        private long missingCoreGames;
        private long firstPrismaticFallbackGames;
        private long buildTimelineGames;
        private long orderedItemGames;
        private long coreGamesWithOrderedItems;
        private long coreGamesWithInventoryOnlyItems;

        private Accumulator(int championId, String patch, ArenaItemCatalog catalog) {
            if (championId <= 0 || patch == null || !patch.matches("[0-9]+\\.[0-9]+(?:\\.[0-9]+)*"))
                throw new IllegalArgumentException("Champion and patch required");
            this.championId = championId;
            this.patch = patch;
            this.majorPatch = patch.equals(PatchUtils.patchMajor(patch));
            this.catalog = java.util.Objects.requireNonNull(catalog);
        }

        public boolean accept(ChampionRawMatch raw) {
            Document match = raw.document();
            String id = match.getString("_id");
            if (id == null || !MATCH_ID_PATTERN.matcher(id).matches()) {
                throw new IllegalArgumentException("Full match ID required");
            }
            String matchPatch = match.getString("patch");
            boolean patchMatches = patch.equals(majorPatch ? PatchUtils.patchMajor(matchPatch) : matchPatch);
            if (!GameQueueType.CHERRY.name().equals(match.getString("queue")) || !patchMatches) {
                return false;
            }

            MatchIdentity matchId = matchIdentity(id);
            if (containsMatch(matchId)) {
                throw new IllegalArgumentException("Duplicate match: " + id);
            }
            List<Document> participants = ChampionBuildProvider.participants(raw);
            validateParticipants(participants);
            boolean found = false;
            for (Document participant : participants) {
                if (participant.getInteger("champion", 0) != championId) {
                    continue;
                }
                ParsedArenaGame game = ArenaGameParser.parse(matchPatch, raw.timeline(), participant, catalog);
                add(game);
                found = true;
            }
            if (found) {
                rememberMatch(matchId);
                matches++;
            }
            return found;
        }

        public ArenaBuildData finish() {
            long games = overall.games;
            List<Core> coreValues = new ArrayList<>(cores.size());
            for (var entry : cores.long2ObjectEntrySet()) {
                coreValues.add(entry.getValue().finish(entry.getLongKey(), games));
            }
            coreValues.sort(Comparator.comparingDouble(Core::winrate).reversed()
                .thenComparing(Comparator.comparingLong(Core::matches).reversed()).thenComparing(Core::id));
            long coreGames = games - missingCoreGames;
            ArenaBuildData.Coverage coverage = new ArenaBuildData.Coverage(matches, coreGames, missingCoreGames,
                firstPrismaticFallbackGames, buildTimelineGames, orderedItemGames, coreGamesWithOrderedItems,
                coreGamesWithInventoryOnlyItems, coreGames - coreGamesWithOrderedItems - coreGamesWithInventoryOnlyItems);
            return new ArenaBuildData(games, overall.wins, coreValues, augmentValues(options.augments, games),
                optionValues(options.prismatics, games), coverage, null, optionValues(options.items, games),
                optionValues(boots, games), overall.averagePlacement(), overall.firstPlaceRate(),
                options.skillOrders(), options.evolutions());
        }

        ArenaItemCatalog catalog() {
            return catalog;
        }

        public void clear() {
            matchesByPlatform.clear();
            oversizedMatchIds.clear();
            boots.clear();
            cores.clear();
            options.clear();
            overall.clear();
            matches = 0;
            missingCoreGames = 0;
            firstPrismaticFallbackGames = 0;
            buildTimelineGames = 0;
            orderedItemGames = 0;
            coreGamesWithOrderedItems = 0;
            coreGamesWithInventoryOnlyItems = 0;
        }

        // ============================================================================

        private void add(ParsedArenaGame game) {
            overall.add(game);
            options.add(game);
            if (game.firstPrismaticFallback()) firstPrismaticFallbackGames++;
            if (game.buildTimeline()) buildTimelineGames++;
            if (game.orderedItemCount() > 0) orderedItemGames++;
            if (game.boots() != null) {
                recordItem(boots, game.boots(), game);
            }

            if (game.boots() == null || game.firstPrismatic() == null) {
                missingCoreGames++;
                return;
            }

            if (game.orderedItemCount() > 0) {
                coreGamesWithOrderedItems++;
            } else if (!game.fallbackBuildItems().isEmpty()) {
                coreGamesWithInventoryOnlyItems++;
            }
            long key = longKey(game.boots().itemId(), game.firstPrismatic().itemId());
            cores.computeIfAbsent(key, ignored -> new CoreCounter()).add(game);
        }

        private boolean containsMatch(MatchIdentity matchId) {
            if (matchId.overflowId() != null) return oversizedMatchIds.contains(matchId.overflowId());
            Map<Integer, LongOpenHashSet> idsByLength = matchesByPlatform.get(matchId.platform());
            LongOpenHashSet ids = idsByLength == null ? null : idsByLength.get(matchId.digits());
            return ids != null && ids.contains(matchId.numericId());
        }

        private void rememberMatch(MatchIdentity matchId) {
            if (matchId.overflowId() != null) {
                oversizedMatchIds.add(matchId.overflowId());
                return;
            }
            matchesByPlatform.computeIfAbsent(matchId.platform(), ignored -> new HashMap<>())
                .computeIfAbsent(matchId.digits(), ignored -> new LongOpenHashSet())
                .add(matchId.numericId());
        }

        private static void validateParticipants(List<Document> participants) {
            Set<Integer> ids = new HashSet<>();
            Set<String> puuids = new HashSet<>();
            for (Document participant : participants) {
                int id = participant.getInteger("id", 0);
                String puuid = participant.getString("puuid");
                if (id <= 0 || puuid == null || puuid.isBlank() || !ids.add(id) || !puuids.add(puuid)) {
                    throw new IllegalArgumentException("Invalid or duplicate participant identity");
                }
            }
        }
    }

    private static long longKey(int high, int low) {
        return ((long) high << Integer.SIZE) | (low & 0xffff_ffffL);
    }

    private static int highInt(long key) {
        return (int) (key >>> Integer.SIZE);
    }

    private static int lowInt(long key) {
        return (int) key;
    }

    private static MatchIdentity matchIdentity(String value) {
        int separator = value.lastIndexOf('_');
        String platform = value.substring(0, separator);
        String numeric = value.substring(separator + 1);
        try {
            return new MatchIdentity(platform, numeric.length(), Long.parseUnsignedLong(numeric), null);
        } catch (NumberFormatException exception) {
            return new MatchIdentity(null, 0, 0, value);
        }
    }

    private record MatchIdentity(String platform, int digits, long numericId, String overflowId) {}

    private static final class CoreCounter {
        private final StatsCounter stats = new StatsCounter();
        private final StatsCounter boots = new StatsCounter();
        private final Options options = new Options();
        private final Map<FullBuildKey, StatsCounter> builds = new HashMap<>();
        private final Long2ObjectOpenHashMap<StatsCounter> slots = new Long2ObjectOpenHashMap<>(4);
        private int slotCount;

        private void add(ParsedArenaGame game) {
            stats.add(game);
            boots.add(game);
            long bootTime = game.boots().timestampMillis();
            long prismaticTime = game.firstPrismatic().timestampMillis();
            boots.time(bootTime);
            if (bootTime >= 0 && prismaticTime >= 0) {
                stats.time(Math.max(bootTime, prismaticTime));
            }
            options.add(game);

            int ordered = game.orderedItemCount();
            if (ordered == 0) {
                if (!game.fallbackBuildItems().isEmpty()) {
                    FullBuildKey key = new FullBuildKey(List.copyOf(game.fallbackBuildItems()), true);
                    builds.computeIfAbsent(key, ignored -> new StatsCounter()).add(game);
                }
                return;
            }

            IntArrayList path = new IntArrayList(ordered);
            for (int slot = 0; slot < ordered; slot++) {
                ItemEvent item = game.items().get(slot);
                path.add(item.itemId());
                long key = longKey(slot, item.itemId());
                StatsCounter row = slots.computeIfAbsent(key, ignored -> new StatsCounter());
                row.add(game);
                row.time(item.timestampMillis());
            }
            slotCount = Math.max(slotCount, ordered);
            FullBuildKey key = new FullBuildKey(path, false);
            builds.computeIfAbsent(key, ignored -> new StatsCounter()).add(game);
        }

        private Core finish(long key, long totalGames) {
            long games = stats.games;
            List<FullBuild> values = new ArrayList<>(builds.size());
            for (var entry : builds.entrySet()) {
                FullBuildKey build = entry.getKey();
                StatsCounter row = entry.getValue();
                values.add(new FullBuild(build.items(), row.games, row.wins, null, row.pickrate(games),
                    row.averagePlacement(), row.firstPlaceRate(), build.inventoryFallback()));
            }
            values.sort(Comparator.comparingDouble(FullBuild::winrate).reversed()
                .thenComparing(Comparator.comparingLong(FullBuild::matches).reversed())
                .thenComparing(value -> BuildUtils.joinInts(value.items())).thenComparing(FullBuild::inventoryFallback));
            List<List<Option>> slotValues = new ArrayList<>(slotCount);
            for (int slot = 0; slot < slotCount; slot++) {
                slotValues.add(new ArrayList<>());
            }
            for (var entry : slots.long2ObjectEntrySet()) {
                long slotKey = entry.getLongKey();
                slotValues.get(highInt(slotKey)).add(entry.getValue().option(lowInt(slotKey), games));
            }
            for (List<Option> slot : slotValues) {
                slot.sort(optionOrder());
            }
            List<Integer> items = List.of(highInt(key), lowInt(key));
            return new Core(BuildUtils.joinInts(items), items, games, stats.wins, stats.pickrate(totalGames),
                values, augmentValues(options.augments, games), optionValues(options.prismatics, games),
                stats.averagePlacement(), stats.firstPlaceRate(), optionValues(options.items, games), slotValues,
                boots.option(highInt(key), games), stats.averageSeconds(), stats.timedMatches(),
                options.skillOrders(), options.evolutions(), null, List.of());
        }
    }

    private static final class Options {
        private final Int2ObjectOpenHashMap<StatsCounter> items = new Int2ObjectOpenHashMap<>(4);
        private final Int2ObjectOpenHashMap<StatsCounter> prismatics = new Int2ObjectOpenHashMap<>(4);
        private final Long2ObjectOpenHashMap<StatsCounter> augments = new Long2ObjectOpenHashMap<>(4);
        private SkillOrder skills;
        private SkillOrder evolutions;

        private void add(ParsedArenaGame game) {
            for (ItemEvent item : game.items()) {
                recordItem(items, item, game);
            }
            for (ItemEvent item : game.prismatics()) {
                recordItem(prismatics, item, game);
            }
            for (ParsedArenaGame.Augment augment : game.augments()) {
                long key = longKey(augment.position() - 1, augment.id());
                augments.computeIfAbsent(key, ignored -> new StatsCounter()).add(game);
            }
            if (!game.skills().upgrades().isEmpty() || !game.fallbackSkillOrder().isEmpty()) {
                if (skills == null) {
                    skills = SkillOrder.arena();
                }
                skills.accept(game.fallbackSkillOrder(), game.skills().upgrades());
            }
            if (!game.skills().evolutions().isEmpty()) {
                if (evolutions == null) {
                    evolutions = SkillOrder.arena();
                }
                evolutions.accept(List.of(), game.skills().evolutions());
            }
        }

        private List<SkillOrderOption> skillOrders() {
            SkillOrderOption order = skills == null ? null : skills.recommended(SkillOrder.Selection.PREFIX);
            return order == null ? List.of() : List.of(order);
        }

        private SkillOrderOption evolutions() {
            return evolutions == null ? null : evolutions.recommended(SkillOrder.Selection.MOST_PLAYED);
        }

        private void clear() {
            items.clear();
            prismatics.clear();
            augments.clear();
            skills = null;
            evolutions = null;
        }
    }

    private static void recordItem(Int2ObjectOpenHashMap<StatsCounter> options, ItemEvent item, ParsedArenaGame game) {
        StatsCounter row = options.computeIfAbsent(item.itemId(), ignored -> new StatsCounter());
        row.add(game);
        row.time(item.timestampMillis());
    }

    private static List<Option> optionValues(Int2ObjectOpenHashMap<StatsCounter> source, long games) {
        List<Option> result = new ArrayList<>(source.size());
        for (var entry : source.int2ObjectEntrySet()) {
            result.add(entry.getValue().option(entry.getIntKey(), games));
        }
        result.sort(optionOrder());
        return result;
    }

    private record FullBuildKey(List<Integer> items, boolean inventoryFallback) {}

    private static List<List<Option>> augmentValues(Long2ObjectOpenHashMap<StatsCounter> source, long games) {
        List<List<Option>> result = new ArrayList<>(ArenaBuildData.MAX_AUGMENTS);
        for (int position = 0; position < ArenaBuildData.MAX_AUGMENTS; position++) {
            result.add(new ArrayList<>());
        }
        for (var entry : source.long2ObjectEntrySet()) {
            long key = entry.getLongKey();
            result.get(highInt(key)).add(entry.getValue().option(lowInt(key), games));
        }
        for (List<Option> slot : result) {
            slot.sort(optionOrder());
        }
        return result;
    }

    private static Comparator<Option> optionOrder() {
        return Comparator.comparingDouble(Option::winrate).reversed()
            .thenComparing(Comparator.comparingLong(Option::matches).reversed()).thenComparingInt(Option::id);
    }

    private static final class StatsCounter {
        private long games;
        private long wins;
        private long placed;
        private long placementTotal;
        private long firsts;
        private long timed;
        private long timeMillis;

        private void add(ParsedArenaGame game) {
            games++;
            if (game.win()) wins++;
            int placement = game.placement();
            if (placement > 0) {
                placed++;
                placementTotal += placement;
                if (placement == 1) firsts++;
            }
        }

        private void time(long timestampMillis) {
            if (timestampMillis >= 0) {
                timed++;
                timeMillis += timestampMillis;
            }
        }

        private double pickrate(long total) {
            return total == 0 ? 0 : (double) games / total;
        }

        private Double averagePlacement() {
            return placed == 0 ? null : (double) placementTotal / placed;
        }

        private Double firstPlaceRate() {
            return placed == 0 ? null : (double) firsts / placed;
        }

        private Double averageSeconds() {
            return timed == 0 ? null : timeMillis / 1000.0 / timed;
        }

        private Long timedMatches() {
            return timed == 0 ? null : timed;
        }

        private Option option(int id, long total) {
            return new Option(id, games, wins, pickrate(total), averagePlacement(), firstPlaceRate(),
                averageSeconds(), timedMatches());
        }

        private void clear() {
            games = 0;
            wins = 0;
            placed = 0;
            placementTotal = 0;
            firsts = 0;
            timed = 0;
            timeMillis = 0;
        }
    }
}
