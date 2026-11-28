package com.safjnest.lol.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mongodb.MongoException;
import com.safjnest.exception.Exception;
import com.safjnest.lol.queue.scheduler.ComputeScheduler.ChampionWork;

import com.safjnest.database.nosql.MongoDB;
import com.safjnest.database.redis.RedisClient;
import com.safjnest.database.redis.RedisKey;
import com.safjnest.lol.arena.ArenaItemCatalog;
import com.safjnest.lol.champion.ChampionBuildProvider;
import com.safjnest.lol.model.ApiResult;
import com.safjnest.lol.model.Build;
import com.safjnest.lol.model.ArenaBuildData;
import com.safjnest.lol.model.ChampionBuildSnapshot;
import com.safjnest.lol.model.ChampionStatistics;
import com.safjnest.lol.model.ChampionTierList;
import com.safjnest.lol.model.ChampionTierSource;
import com.safjnest.lol.model.ChampionView;
import com.safjnest.lol.model.Filter;
import com.safjnest.lol.model.ChampionIndexable;
import com.safjnest.lol.model.ResponseMetadata;
import com.safjnest.lol.model.statistics.ChampionStatsDocument;
import com.safjnest.lol.model.statistics.shared.ChampionLeafStats;
import com.safjnest.lol.model.statistics.shared.ChampionNode;
import com.safjnest.lol.model.statistics.shared.ChampionStatsScope;
import com.safjnest.lol.model.statistics.shared.MatchupStats;
import com.safjnest.lol.queue.scheduler.ComputeScheduler;
import com.safjnest.lol.queue.job.Job;
import com.safjnest.lol.utils.ChampionUtils;
import com.safjnest.lol.utils.GameQueueTypeUtils;
import com.safjnest.lol.utils.LaneTypeUtils;
import com.safjnest.lol.utils.LeagueShardUtils;
import com.safjnest.lol.utils.PatchUtils;
import com.safjnest.lol.utils.TierDivisionUtils;
import com.safjnest.utils.TimeConstant;

import no.stelar7.api.r4j.basic.constants.api.regions.LeagueShard;
import no.stelar7.api.r4j.basic.constants.types.lol.GameQueueType;
import no.stelar7.api.r4j.basic.constants.types.lol.LaneType;
import no.stelar7.api.r4j.basic.constants.types.lol.TierType;
import no.stelar7.api.r4j.pojo.lol.staticdata.champion.StaticChampion;

public class ChampionService {

    private static final Logger LOG = LoggerFactory.getLogger(ChampionService.class);

    public record MatrixRefreshResult(int combinations, int skipped, int generated, int empty, int persistedChampions) {}
    public record ChampionData(ChampionStatistics stats, ChampionBuildSnapshot snapshot, Build arenaBuild) {
        public Build build() {
            return snapshot == null ? arenaBuild : snapshot.defaultBuild();
        }

        boolean hasBuild() {
            return snapshot != null || arenaBuild != null;
        }
    }

    private record Availability(long statsUpdatedAt, long buildUpdatedAt) {
        boolean missingStats() { return statsUpdatedAt == 0; }
        boolean missingBuild() { return buildUpdatedAt == 0; }
        boolean missing() { return missingStats() || missingBuild(); }
        long lastUpdate() { return oldest(statsUpdatedAt, buildUpdatedAt); }
    }

    private record CachedPage(Availability availability, ChampionView page) {}

    public static Filter dataFilter(Filter filter) {
        return Filter.championBuild(filter.champion(), filter.patch(), filter.queue())
            .setRank(filter.rank()).setRankBehavior(filter.rankBehavior()).setRegion(filter.region())
            .setLane(GameQueueTypeUtils.hasLane(filter.queue()) ? filter.lane() : null);
    }

    public static Filter arenaFilterSnapshot(Filter filter) {
        if (filter == null || filter.champion() <= 0 || filter.queue() != GameQueueType.CHERRY
                || filter.patch() == null || !filter.patch().matches("[0-9]+\\.[0-9]+(?:\\.[0-9]+)*")
                || filter.lane() != null || filter.opponent() != 0 || filter.duo() != 0)
            throw new IllegalArgumentException("Champion/patch/CHERRY without role, opponent or duo required");
        return Filter.championBuild(filter.champion(), filter.patch(), GameQueueType.CHERRY)
            .setRank(filter.rank()).setRankBehavior(filter.rankBehavior()).setRegion(filter.region());
    }

    static ArenaChampionAnalyzer.Accumulator arenaAccumulator(Filter filter) {
        Filter snapshot = arenaFilterSnapshot(filter);
        ArenaItemCatalog catalog = ArenaItemCatalog.fromItems(StaticDataService.getItems(snapshot.patch()));
        return ArenaChampionAnalyzer.accumulator(snapshot.champion(), snapshot.patch(), catalog);
    }

    public boolean refreshArena(Filter filter, ArenaItemCatalog catalog, Job<?> job) {
        return refreshArenaInternal(filter, catalog, Objects.requireNonNull(job, "Arena job required"));
    }

    public ApiResult<ChampionView> get(
        String championValue,
        String patch,
        TierType rank,
        LeagueShard region,
        GameQueueType queue,
        LaneType role
    ) {
        StaticChampion champion = ChampionUtils.findChampion(championValue);
        if (champion == null) return ApiResult.notFound();
        GameQueueType selectedQueue = queue != null ? queue : GameQueueType.TEAM_BUILDER_RANKED_SOLO;
        Filter filter = Filter.championBuild(champion.getId(), patch != null ? patch : PatchUtils.getPatch(), selectedQueue)
            .setRank(rank).setRegion(region).setLane(role);
        Availability availability = availability(filter);
        if (availability.missing()) {
            return pending(filter, availability);
        }

        boolean statsStale = isStale(availability.statsUpdatedAt());
        boolean buildStale = isStale(availability.buildUpdatedAt());
        if (statsStale || buildStale) {
            try {
                schedule(filter, statsStale, buildStale);
            } catch (Exception failure) {
                LOG.warn("[ChampionRefresh] filter=" + filter.toKey() + " reason=" + failure.getMessage());
            }
        }

        String key = RedisKey.CHAMPION_PAGE.of(filter.champion(), filter.pageKey());
        CachedPage cached = RedisClient.get(key, CachedPage.class);
        boolean arena = selectedQueue == GameQueueType.CHERRY;
        ChampionView rawPage;
        if (cached != null && availability.equals(cached.availability()) && cached.page() != null
                && cached.page().stats() != null && (arena ? cached.page().build() != null : cached.page().builds() != null)) {
            rawPage = cached.page();
        } else {
            ChampionData data = readData(filter);
            if (data.stats() == null || !data.hasBuild()) {
                Availability remaining = new Availability(data.stats() == null ? 0 : availability.statsUpdatedAt(),
                    data.hasBuild() ? availability.buildUpdatedAt() : 0);
                return pending(filter, remaining);
            }
            rawPage = new ChampionView(new ChampionView.Champion(champion.getId(), champion.getName(),
                ChampionUtils.getChampionProfilePic(champion.getId())), data.stats(), data.snapshot(), data.arenaBuild());
            RedisClient.set(RedisKey.CHAMPION_PAGE, new CachedPage(availability, rawPage), filter.champion(), filter.pageKey());
        }
        ChampionView page = rawPage.withStatistics(MatchupRankingAnalyzer.rank(rawPage.stats()),
            ResponseMetadata.of(availability.lastUpdate(), statsStale || buildStale, filter));
        return ApiResult.ready(page, page.metadata());
    }

    public CompletableFuture<ChampionData> getForDiscord(Filter filter) {
        Filter snapshot = dataFilter(filter);
        Availability availability = availability(snapshot);
        if (!availability.missing())
            return CompletableFuture.completedFuture(readRequiredData(snapshot));
        return schedule(snapshot, availability.missingStats(), availability.missingBuild())
            .future().thenApplyAsync(ignored -> readRequiredData(snapshot));
    }

    public boolean buildFresh(Filter filter) {
        return !isStale(MongoDB.findChampionBuildLastUpdate(filter));
    }

    public Map<Integer, ChampionStatistics> getStatisticsAll(Filter filter) {
        if (filter == null) return Map.of();
        ChampionStatsDocument document = MongoDB.findChampionStatsDocument(ChampionStatsScope.from(filter));
        if (document == null || !document.ready) return Map.of();
        Map<Integer, ChampionStatistics> result = new LinkedHashMap<>();
        for (Map.Entry<Integer, ChampionNode> entry : document.champions.entrySet()) {
            ChampionLeafStats leaf = entry.getValue().lanes.get(filter.lane() == null ? null : filter.lane().name());
            if (leaf == null && filter.lane() == null) leaf = entry.getValue().overall();
            if (leaf != null && leaf.games > 0) {
                Filter championFilter = new Filter().setChampion(entry.getKey()).setLane(filter.lane()).setQueue(filter.queue()).setRank(filter.rank()).setRankBehavior(filter.rankBehavior()).setPatch(filter.patch()).setRegion(filter.region()).setPeriod(filter.timeStart(), filter.timeEnd());
                result.put(entry.getKey(), ChampionAnalyzer.toChampionStatistics(championFilter, document, entry.getValue(), leaf));
            }
        }
        return result;
    }

    public List<ChampionIndexable> getIndexables() {
        String patch = PatchUtils.getPatch();
        return patch == null ? List.of() : MongoDB.findChampionIndexables(patch);
    }

    public boolean refreshBuild(Filter filter, Job<?> job) {
        if (filter == null || filter.champion() == 0) {
            return false;
        }

        Filter snapshot = dataFilter(filter);
        if (snapshot.queue() == GameQueueType.CHERRY) {
            ArenaItemCatalog catalog = ArenaItemCatalog.fromItems(StaticDataService.getItems(snapshot.patch()));
            return refreshArenaInternal(snapshot, catalog, job);
        }

        ChampionBuildEngine engine = new ChampionBuildEngine(snapshot);
        ChampionBuildProvider provider = new ChampionBuildProvider(snapshot);
        boolean refreshed;
        try {
            provider.forEachGame(job, engine::accept);
            if (job != null) {
                job.phase("PUBLISH_BUILD");
            }

            ChampionBuildSnapshot buildSnapshot = engine.finish();
            BuildNaming naming = new BuildNaming(provider.loadedItems(), StaticDataService::getRuneName);
            refreshed = MongoDB.upsertChampionBuild(naming.label(buildSnapshot));
        } finally {
            engine.clear();
        }
        if (refreshed) {
            invalidate(snapshot);
        }
        return refreshed;
    }

    public ApiResult<ChampionTierList> getTierList(
        String patch,
        TierType rank,
        LeagueShard region,
        GameQueueType queue
    ) {
        GameQueueType selectedQueue = queue != null ? queue : GameQueueType.TEAM_BUILDER_RANKED_SOLO;
        Filter base = new Filter().setChampion(0).setLane(null).setRank(rank).setRegion(region).setQueue(selectedQueue);
        if (patch != null) base.setPatch(patch);
        String key = tierListKey(base);
        ChampionTierList cached;
        try {
            cached = RedisClient.get(key, ChampionTierList.class);
        } catch (RuntimeException exception) {
            CacheInvalidationService.clearRedis(
                RedisKey.CHAMPION_TIER_LIST, tierFilter(base, null).genericKey());
            cached = null;
        }
        if (cached != null) return ApiResult.ready(cached, cached.metadata());

        List<Filter> filters = tierFilters(base);
        Map<String, ChampionTierSource> sources = new LinkedHashMap<>();
        ChampionStatsScope baseScope = ChampionStatsScope.from(base);
        ChampionStatsDocument doc = MongoDB.findChampionStatsDocument(baseScope);
        if (doc != null && doc.ready) {
            for (Filter filter : filters) {
                Map<Integer, ChampionLeafStats> laneStats = new LinkedHashMap<>();
                for (Map.Entry<Integer, ChampionNode> e : doc.champions.entrySet()) {
                    ChampionLeafStats leaf = filter.lane() == null
                        ? e.getValue().overall() : e.getValue().lanes.get(filter.lane().name());
                    if (leaf != null && leaf.games > 0) laneStats.put(e.getKey(), leaf);
                }
                Map<Integer, ChampionTierSource.Champion> champions = new LinkedHashMap<>();
                for (Map.Entry<Integer, ChampionLeafStats> e : laneStats.entrySet()) {
                    ChampionLeafStats leaf = e.getValue();
                    ChampionNode node = doc.champions.get(e.getKey());
                    long bans = node == null ? 0 : node.bans;
                    double winrate = leaf.winrate();
                    double pickrate = doc.games == 0 ? 0 : (double) leaf.games / doc.games;
                    Double banrate = doc.banGames == 0 ? null : (double) bans / doc.banGames;
                    List<ChampionTierSource.Matchup> tierMatchups = new ArrayList<>();
                    for (Map.Entry<Integer, MatchupStats> me : leaf.matchups.entrySet()) {
                        MatchupStats ms = me.getValue();
                        tierMatchups.add(new ChampionTierSource.Matchup(me.getKey(), (int)ms.games, (int)ms.wins));
                    }
                    champions.put(e.getKey(), new ChampionTierSource.Champion(
                        new ChampionTierList.Statistics((int)doc.games, (int)leaf.games, (int)bans, (int)leaf.wins, winrate, pickrate, banrate),
                        tierMatchups
                    ));
                }
                sources.put(filter.genericKey(), new ChampionTierSource(true, doc.updatedAt, champions));
            }
        }
        List<Filter> ready = new ArrayList<>();
        long lastUpdate = 0;
        boolean refresh = false;
        for (Filter filter : filters) {
            ChampionTierSource source = sources.get(filter.genericKey());
            if (source == null || !source.ready() || isStale(source.lastUpdate())) {
                refresh = true;
                continue;
            }
            ready.add(filter);
            lastUpdate = oldest(lastUpdate, source.lastUpdate());
        }
        ResponseMetadata metadata = ResponseMetadata.of(lastUpdate, refresh, base);
        ChampionTierList result = new ChampionTierList(ChampionTierAnalyzer.analyze(ready, sources), metadata);
        if (refresh) {
            try {
                ComputeScheduler.enqueueChampionStatsMatrix(base.patch(), base.queue());
            } catch (Exception failure) {
                LOG.warn("[ChampionRefresh] statsScope=" + baseScope.toKey() + " reason=" + failure.getMessage());
            }
            return ApiResult.partial(result, metadata);
        }
        RedisClient.set(RedisKey.CHAMPION_TIER_LIST, result, base.genericKey());
        return ApiResult.ready(result, metadata);
    }

    public MatrixRefreshResult refreshStatisticsMatrix(String patch, GameQueueType queue, List<Filter> buildFilters,
            boolean force, Job<?> job) {
        List<Filter> combinations = matrixFilters(patch, queue);
        if (job != null)
            job.phase("CHECK_AVAILABILITY");
        Set<String> readyKeys = force ? Set.of()
            : MongoDB.findFreshChampionStatsKeys(combinations, System.currentTimeMillis() - TimeConstant.WEEK);
        List<Filter> missing = missingMatrixFilters(combinations, readyKeys);
        List<Filter> pendingBuilds = new ArrayList<>();
        for (Filter filter : buildFilters) {
            Filter snapshot = dataFilter(filter);
            if (force || !buildFresh(snapshot)) {
                pendingBuilds.add(snapshot);
            }
        }
        ChampionAnalyzer.MatrixResult result = ChampionAnalyzer.recomputeMatrixCoalesced(missing, pendingBuilds, job);
        invalidateTierLists(missing);
        for (Filter filter : pendingBuilds) {
            if (missing.isEmpty()) refreshBuild(filter, job);
            else invalidate(filter);
        }
        return new MatrixRefreshResult(combinations.size(), combinations.size() - missing.size(),
            result.filters() - result.emptyFilters(), result.emptyFilters(), result.persistedChampions());
    }

    public List<ChampionIndexable> refreshIndexables() {
        String patch = PatchUtils.getPatch();
        if (patch == null || patch.isBlank()) return List.of();
        Map<Integer, Map<LaneType, Integer>> gamesByChampion = MongoDB.findChampionRoleGames();
        List<ChampionIndexable> values = new ArrayList<>();
        for (Map.Entry<Integer, Map<LaneType, Integer>> entry : gamesByChampion.entrySet()) {
            int totalGames = 0;
            for (int games : entry.getValue().values()) totalGames += games;
            List<LaneType> roles = new ArrayList<>(LaneTypeUtils.playables());
            roles.sort(java.util.Comparator.comparingInt(
                (LaneType role) -> -entry.getValue().getOrDefault(role, 0)
            ).thenComparingInt(LaneTypeUtils::playableOrder));
            for (LaneType role : roles) {
                int games = entry.getValue().getOrDefault(role, 0);
                values.add(new ChampionIndexable(entry.getKey(), role, games,
                    isIndexable(games, totalGames), 0L));
            }
        }
        MongoDB.upsertChampionIndexables(patch, values);
        return MongoDB.findChampionIndexables(patch);
    }

    public void refresh(String patch, Job<?> job) {
        job.phase("PLAN_SCOPES");
        List<GameQueueType> queues = new ArrayList<>(getStatQueues(patch));
        List<Filter> builds = getBuildFilters(patch);
        builds.removeIf(filter -> !queues.contains(filter.queue()));
        for (GameQueueType queue : queues)
            job.trackItem(ComputeScheduler.championStatsMatrixKey(patch, queue));
        for (Filter filter : builds) job.trackItem(filter.toKey());
        LOG.info("[refresh build]: starting patch=" + patch);
        job.phase("STATISTICS");
        int queueIndex = 0;
        for (GameQueueType queue : queues) {
            String key = ComputeScheduler.championStatsMatrixKey(patch, queue);
            job.currentItem(key);
            LOG.info("[refresh build]: generating stats | queue " + (++queueIndex) + "/" + queues.size()
                + " (" + GameQueueTypeUtils.prettyName(queue) + ")");
            refreshStatisticsMatrix(patch, queue, List.of(), true, job);
            job.done(key);
        }
        job.phase("BUILDS");
        int buildIndex = 0;
        for (Filter filter : builds) {
            job.currentItem(filter.toKey());
            LOG.info("[refresh build]: generating build champion " + filter.champion()
                + " | queue " + (queues.indexOf(filter.queue()) + 1) + "/" + queues.size()
                + " (" + GameQueueTypeUtils.prettyName(filter.queue()) + ")"
                + " | filter " + (++buildIndex) + "/" + builds.size());
            try {
                if (refreshBuild(filter, job))
                    job.done(filter.toKey());
                else job.missing(filter.toKey());
            } catch (RuntimeException exception) {
                job.failed(filter.toKey());
                LOG.warn("[ChampionRefresh] pid={} filter={} phase={} outcome=FAILED",
                    job.pid(), filter.toKey(), job.phase(), exception);
            }
        }
        LOG.info("[refresh build]: finished patch=" + patch);
    }

    public static List<Filter> matrixFilters(String patch, GameQueueType queue) {
        if (patch == null || patch.isBlank() || queue == null) return List.of();
        List<Filter> filters = new ArrayList<>();
        List<LeagueShard> regions = new ArrayList<>();
        regions.add(null);
        regions.addAll(LeagueShardUtils.getActives());
        List<TierType> ranks = new ArrayList<>();
        ranks.add(null);
        ranks.addAll(TierDivisionUtils.getHigherTiers(TierType.IRON));
        for (LeagueShard region : regions) for (TierType rank : ranks)
            filters.add(Filter.championBuild(0, patch, queue).setRank(rank).setRegion(region));
        return filters;
    }

    public static List<Filter> missingMatrixFilters(List<Filter> combinations, Set<String> readyKeys) {
        if (combinations == null || combinations.isEmpty()) return List.of();
        List<Filter> missing = new ArrayList<>();
        for (Filter filter : combinations) {
            String key = filter == null ? null : ChampionStatsScope.from(filter).toKey();
            if (filter != null && (readyKeys == null || !readyKeys.contains(key))) missing.add(filter);
        }
        return missing;
    }

    public static void invalidate(Filter filter) {
        if (filter != null) CacheInvalidationService.clearRedis(RedisKey.CHAMPION_PAGE, filter.champion(), filter.pageKey());
    }

    public static void invalidateTierList(Filter filter) {
        if (filter != null) CacheInvalidationService.clearRedis(
            RedisKey.CHAMPION_TIER_LIST, tierFilter(filter, null).genericKey());
    }

    // ============================================================================

    private boolean refreshArenaInternal(Filter filter, ArenaItemCatalog catalog, Job<?> job) {
        Filter snapshot = arenaFilterSnapshot(filter);
        ArenaChampionAnalyzer.Accumulator accumulator = ArenaChampionAnalyzer.accumulator(
            snapshot.champion(), snapshot.patch(), Objects.requireNonNull(catalog, "Patch catalog required"));
        try {
            MongoDB.forEachChampionRawMatchEventBatch(snapshot, true, job, input -> {
                if (!accumulator.accept(input)) {
                    throw new IllegalArgumentException("Arena match does not match the requested scope");
                }
            });
            if (job != null)
                job.phase("PUBLISH_BUILD");
            ArenaBuildData arena = accumulator.finish();
            if (!MongoDB.upsertChampionArena(snapshot, BuildNaming.labelArena(arena, catalog))) {
                throw new IllegalStateException("Arena write was not completed");
            }
            invalidate(snapshot);
            return true;
        } finally {
            accumulator.clear();
        }
    }

    private static ChampionWork schedule(Filter filter, boolean stats, boolean build) {
        ChampionWork work = ComputeScheduler.startChampionData(filter, stats, build);
        if (!work.future().isCompletedExceptionally())
            return work;
        try {
            work.future().join();
            return work;
        } catch (CompletionException | CancellationException failure) {
            throw new Exception(Exception.Type.UNAVAILABLE, "champion_job_unavailable",
                "Champion jobs " + work.jobIds() + " could not complete. See the job logs.", failure, null);
        }
    }

    private static ApiResult<ChampionView> pending(Filter filter, Availability availability) {
        ChampionWork work = schedule(filter, availability.missingStats(), availability.missingBuild());
        List<String> missing = availability.missingStats()
            ? (availability.missingBuild() ? List.of("stats", "build") : List.of("stats"))
            : List.of("build");
        return ApiResult.pending(ResponseMetadata.championPending(availability.lastUpdate(), filter, missing, work.jobIds()));
    }

    private static Availability availability(Filter filter) {
        ChampionStatsScope scope = ChampionStatsScope.from(filter);
        try {
            return new Availability(MongoDB.findChampionStatsLastUpdate(scope), MongoDB.findChampionBuildLastUpdate(filter));
        } catch (MongoException failure) {
            throw lookupFailure(filter, failure);
        } catch (Exception failure) {
            LOG.warn("[ChampionLookup] filter=" + filter.toKey() + " statsScope=" + scope.toKey()
                + " code=" + failure.getCode() + " cause=" + failure.getMessage());
            throw failure;
        }
    }

    private static ChampionData readData(Filter filter) {
        try {
            ChampionStatsDocument document = MongoDB.findChampionStatsDocument(ChampionStatsScope.from(filter), true);
            if (document != null && !document.ready) {
                throw new Exception(Exception.Type.UNAVAILABLE, "champion_data_changed",
                    "Champion snapshot is no longer available for " + filter.toKey());
            }

            boolean arena = filter.queue() == GameQueueType.CHERRY;
            ChampionBuildSnapshot snapshot = arena ? null : MongoDB.findChampionBuildSnapshot(filter);
            Build arenaBuild = arena ? MongoDB.findChampionArenaBuild(filter) : null;
            ChampionStatistics stats = null;
            if (document != null) {
                ChampionNode node = document.champions.get(filter.champion());
                ChampionLeafStats leaf = node == null ? null
                    : (filter.lane() == null ? node.overall() : node.lanes.get(filter.lane().name()));
                stats = leaf != null && leaf.games > 0
                    ? ChampionAnalyzer.toChampionStatistics(filter, document, node, leaf) : ChampionAnalyzer.empty(filter);
            }
            return new ChampionData(stats, snapshot, arenaBuild);
        } catch (MongoException failure) {
            throw lookupFailure(filter, failure);
        } catch (Exception failure) {
            LOG.warn("[ChampionLookup] filter=" + filter.toKey()
                + " code=" + failure.getCode() + " cause=" + failure.getMessage());
            throw failure;
        }
    }

    private static ChampionData readRequiredData(Filter filter) {
        ChampionData data = readData(filter);
        if (data.stats() == null || !data.hasBuild()) {
            throw new Exception(Exception.Type.UNAVAILABLE, "champion_data_changed",
                "Champion snapshot is no longer available for " + filter.toKey());
        }
        return data;
    }

    private static Exception lookupFailure(Filter filter, MongoException failure) {
        LOG.warn("[ChampionLookup] filter=" + filter.toKey() + " statsScope="
            + ChampionStatsScope.from(filter).toKey() + " cause=" + failure);
        return new Exception(Exception.Type.UNAVAILABLE, "champion_lookup_failed",
            "Unable to read champion data for " + filter.toKey(), failure, null);
    }

    private List<Filter> getBuildFilters(String patch) {
        Map<String, Filter> filters = new LinkedHashMap<>();
        for (Filter filter : MongoDB.findChampionBuildRefreshFilters(patch)) addBuildFilter(filters, filter, patch);
        for (Filter filter : MongoDB.findStoredChampionBuildFilters()) addBuildFilter(filters, filter, patch);
        return new ArrayList<>(filters.values());
    }

    private Set<GameQueueType> getStatQueues(String patch) {
        Set<GameQueueType> queues = new LinkedHashSet<>();
        for (GameQueueType queue : MongoDB.findChampionQueues(patch))
            if (GameQueueTypeUtils.championBuildQueues().contains(queue)) queues.add(queue);
        return queues;
    }

    private void addBuildFilter(Map<String, Filter> filters, Filter filter, String patch) {
        if (filter == null || filter.champion() == 0 || !patch.equals(filter.patch())) return;
        Filter snapshot = dataFilter(filter);
        filters.put(snapshot.toKey(), snapshot);
    }

    private static List<Filter> tierFilters(Filter base) {
        if (base == null || base.queue() == null) return List.of();
        List<Filter> result = new ArrayList<>();
        if (!GameQueueTypeUtils.hasLane(base.queue())) {
            result.add(tierFilter(base, null));
            return result;
        }
        for (LaneType lane : LaneTypeUtils.playables()) result.add(tierFilter(base, lane));
        return result;
    }

    private static Filter tierFilter(Filter source, LaneType lane) {
        return new Filter().setChampion(0).setPatch(source.patch()).setQueue(source.queue()).setRank(source.rank())
            .setRegion(source.region()).setLane(lane);
    }

    private static String tierListKey(Filter filter) {
        return RedisKey.CHAMPION_TIER_LIST.of(tierFilter(filter, null).genericKey());
    }

    private static void invalidateTierLists(List<Filter> filters) {
        if (filters == null || filters.isEmpty()) return;
        Set<String> keys = new HashSet<>();
        for (Filter filter : filters) if (filter != null) keys.add(tierListKey(filter));
        CacheInvalidationService.clearRedis(new ArrayList<>(keys));
    }

    private static boolean isStale(long lastUpdate) {
        return lastUpdate <= 0 || System.currentTimeMillis() - lastUpdate >= TimeConstant.WEEK;
    }

    private static long oldest(long first, long second) {
        if (first <= 0) return second;
        if (second <= 0) return first;
        return Math.min(first, second);
    }

    static boolean isIndexable(int games, int totalGames) {
        return totalGames > 0 && games > 0 && (double) games / totalGames >= 0.10D;
    }
}
