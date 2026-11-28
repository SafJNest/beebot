package com.safjnest.lol.queue.scheduler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.safjnest.exception.Exception.Type;
import com.safjnest.lol.model.Filter;
import com.safjnest.lol.arena.ArenaItemCatalog;
import com.safjnest.lol.model.summoner.Summoner;
import com.safjnest.lol.queue.QueueHandler;
import com.safjnest.lol.queue.Router;
import com.safjnest.lol.queue.Router.Submission;
import com.safjnest.lol.queue.job.Job;
import com.safjnest.lol.queue.job.JobPriority;
import com.safjnest.lol.service.ChampionService;
import com.safjnest.lol.service.ProfileService;
import com.safjnest.lol.service.ProfileRecordService;
import com.safjnest.lol.utils.PatchUtils;
import com.safjnest.status.log.BotLogger;

import no.stelar7.api.r4j.basic.constants.api.regions.LeagueShard;
import no.stelar7.api.r4j.basic.constants.types.lol.GameQueueType;

public final class ComputeScheduler extends AbstractScheduler<DatabaseWorkerType> {

    private static final ComputeScheduler INSTANCE = new ComputeScheduler();
    private static final ConcurrentMap<String, ChampionMatrixRequest> CHAMPION_MATRICES = new ConcurrentHashMap<>();
    private static final ProfileService PROFILE_SERVICE = new ProfileService();
    private static final ProfileRecordService PROFILE_RECORD_SERVICE = new ProfileRecordService();
    private static final ChampionService CHAMPION_SERVICE = new ChampionService();
    private static final Logger CHAMPION_LOG = LoggerFactory.getLogger("ChampionJob");
    private static final int CHAMPION_FAILURE_LIMIT = 128;
    private static final long CHAMPION_RETRY_MS = 30_000;
    private static final Map<String, ChampionFailure> CHAMPION_FAILURES = new LinkedHashMap<>();

    private static final CompletableFuture<Void> VOID = CompletableFuture.completedFuture(null);
    private static final CompletableFuture<Boolean> NOT_SCHEDULED = CompletableFuture.completedFuture(false);

    private ComputeScheduler() {
        super("compute", "Compute request cancelled during shutdown");
        registerRoutes(List.of(DatabaseWorkerType.PROFILE, DatabaseWorkerType.PROFILE_2, DatabaseWorkerType.CHAMPION, DatabaseWorkerType.MONGO));
    }

    public static AbstractScheduler<DatabaseWorkerType> scheduler() {
        return INSTANCE;
    }

    public static CompletableFuture<Boolean> startChampionArena(Filter filter, ArenaItemCatalog catalog) {
        return startChampionArena(filter, catalog, CHAMPION_SERVICE);
    }

    static CompletableFuture<Boolean> startChampionArena(Filter filter, ArenaItemCatalog catalog,
            ChampionService service) {
        Filter snapshot = ChampionService.arenaFilterSnapshot(filter);
        Objects.requireNonNull(catalog, "Detached catalog for the requested patch required");
        Objects.requireNonNull(service);
        String key = "champion-arena:" + snapshot.toKey();
        String name = "champion arena champion=" + snapshot.champion()
            + " patch=" + snapshot.patch() + " shard=" + (snapshot.region() == null ? "ALL" : snapshot.region().name());
        return submit(DatabaseWorkerType.CHAMPION, JobPriority.BACKGROUND, key, name,
            job -> service.refreshArena(snapshot, catalog, job));
    }

    public static CompletableFuture<Boolean> startProfileStatistics(Summoner summoner, Filter filter) {
        return startProfileStatistics(summoner, filter, false);
    }

    public static CompletableFuture<Boolean> startProfileStatistics(
        Summoner summoner,
        Filter filter,
        boolean rebuild
    ) {
        return startProfileStatistics(summoner, filter, rebuild, JobPriority.NORMAL);
    }

    public static CompletableFuture<Boolean> startStaleProfileStatistics(Summoner summoner, Filter filter) {
        return startProfileStatistics(summoner, filter, false, JobPriority.BACKGROUND);
    }

    public static CompletableFuture<Boolean> startProfileRecords(
        String puuid,
        LeagueShard shard,
        Filter filter,
        boolean rebuild
    ) {
        if (puuid == null || puuid.isBlank() || shard == null || filter == null) return NOT_SCHEDULED;
        Filter requestFilter = Filter.fromStateKey(filter.toStateKey());
        String key = "profile-records:" + puuid + ":" + requestFilter.toSummonerKey();
        String name = "profile records " + (rebuild ? "rebuild " : "") + "puuid=" + puuid;
        return submit(DatabaseWorkerType.PROFILE, JobPriority.NORMAL, key, name,
            ignored -> generateProfileRecords(puuid, shard, requestFilter));
    }

    public static CompletableFuture<Boolean> startProfileMatchups(
        String puuid,
        LeagueShard shard,
        Filter filter
    ) {
        return startProfileMatchups(puuid, shard, filter, JobPriority.NORMAL);
    }

    public static CompletableFuture<Boolean> startStaleProfileMatchups(
        String puuid,
        LeagueShard shard,
        Filter filter
    ) {
        return startProfileMatchups(puuid, shard, filter, JobPriority.BACKGROUND);
    }

    public static CompletableFuture<Boolean> startProfileActivity(
        String puuid,
        LeagueShard shard,
        Filter filter
    ) {
        return startProfileActivity(puuid, shard, filter, JobPriority.NORMAL);
    }

    public static CompletableFuture<Boolean> startStaleProfileActivity(
        String puuid,
        LeagueShard shard,
        Filter filter
    ) {
        return startProfileActivity(puuid, shard, filter, JobPriority.BACKGROUND);
    }

    public static CompletableFuture<Boolean> refreshProfile(
        Summoner summoner,
        LeagueShard shard
    ) {
        if (summoner == null || summoner.puuid() == null || summoner.puuid().isBlank() || shard == null) {
            return NOT_SCHEDULED;
        }
        String puuid = summoner.puuid();
        String key = profileRefreshKey(puuid);
        String name = "profile refresh puuid=" + puuid;
        return submit(DatabaseWorkerType.PROFILE, JobPriority.IMMEDIATE, key, name,
            ignored -> refreshProfile(puuid, shard));
    }

    public record ChampionWork(List<Long> jobIds, CompletableFuture<?> future) {}

    public static ChampionWork startChampionData(Filter filter, boolean stats, boolean build) {
        Filter snapshot = ChampionService.dataFilter(filter);
        synchronized (INSTANCE.lifecycleLock()) {
            ChampionMatrixRequest existing = CHAMPION_MATRICES.get(championStatsMatrixKey(snapshot.patch(), snapshot.queue()));
            if (!stats) {
                Submission<?> submission = existing != null && existing.includesBuild(snapshot)
                    ? existing.submission() : startChampionBuild(snapshot);
                return work(submission);
            }
            if (existing != null && build && !existing.tryAddBuild(snapshot)) {
                Submission<?> matrix = existing.submission();
                Submission<?> standalone = startChampionBuild(snapshot);
                return new ChampionWork(List.of(matrix.pid(), standalone.pid()),
                    CompletableFuture.allOf(matrix.future(), standalone.future()));
            }
            return work(enqueueChampionStatsMatrix(snapshot.patch(), snapshot.queue(), build ? snapshot : null));
        }
    }

    private static ChampionWork work(Submission<?> submission) {
        return new ChampionWork(List.of(submission.pid()), submission.future());
    }

    public static CompletableFuture<Void> enqueueChampionDataRefresh() {
        return enqueueChampionDataRefresh(new Filter().patch());
    }

    public static CompletableFuture<Void> enqueueChampionDataRefresh(String patch) {
        String key = "champion-data-refresh:" + patch;
        return submit(DatabaseWorkerType.CHAMPION, JobPriority.BACKGROUND, key,
            "champion data refresh patch=" + patch, job -> {
                CHAMPION_SERVICE.refresh(patch, job);
                return null;
            });
    }

    public static CompletableFuture<ChampionService.MatrixRefreshResult> enqueueChampionStatsMatrix(
        String patch,
        GameQueueType queue
    ) {
        if (patch == null || patch.isBlank() || queue == null)
            return CompletableFuture.completedFuture(new ChampionService.MatrixRefreshResult(0, 0, 0, 0, 0));
        return enqueueChampionStatsMatrix(patch, queue, null).future();
    }

    public static CompletableFuture<Void> enqueueRecentChampionStatsMatrices(GameQueueType queue) {
        if (queue == null) return VOID;
        List<String> patches = PatchUtils.getRecentPatches(3);
        if (patches.isEmpty()) return VOID;
        List<CompletableFuture<ChampionService.MatrixRefreshResult>> futures = new ArrayList<>();
        for (int index = 0; index < patches.size(); index++) {
            CompletableFuture<ChampionService.MatrixRefreshResult> future = enqueueChampionStatsMatrix(patches.get(index), queue);
            futures.add(future);
        }
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }

    public static void shutdown() {
        INSTANCE.shutdownDispatcher();
    }

    public static com.safjnest.lol.model.status.SchedulerStatus status() {
        return INSTANCE.snapshot();
    }

    // ============================================================================

    @Override
    protected String routeName(DatabaseWorkerType route) {
        return switch (route) {
            case PROFILE -> "profile";
            case PROFILE_2 -> "profile-2";
            case CHAMPION -> "champion";
            case MONGO -> "mongo";
        };
    }

    @Override
    protected String workerThreadName(DatabaseWorkerType route) {
        return switch (route) {
            case PROFILE -> "lol-db-profile-worker-";
            case PROFILE_2 -> "lol-db-profile-2-worker-";
            case CHAMPION -> "lol-db-champion-worker-";
            case MONGO -> "lol-db-mongo-worker-";
        };
    }

    @Override
    protected DatabaseWorkerType routeForJob(Object route) {
        if (route instanceof DatabaseWorkerType workerType) return workerType;
        throw new IllegalArgumentException("Compute jobs require a DatabaseWorkerType");
    }

    @Override
    protected DatabaseWorkerType queueFor(DatabaseWorkerType route) {
        if (route == DatabaseWorkerType.CHAMPION) return DatabaseWorkerType.CHAMPION;
        if (route == DatabaseWorkerType.MONGO) return DatabaseWorkerType.MONGO;
        if (route == DatabaseWorkerType.PROFILE_2) return DatabaseWorkerType.PROFILE_2;
        return profileQueue(
            load(DatabaseWorkerType.PROFILE),
            load(DatabaseWorkerType.PROFILE_2),
            load(DatabaseWorkerType.CHAMPION),
            load(DatabaseWorkerType.MONGO),
            championReserved()
        );
    }

    @Override
    protected void onBodyFailed(com.safjnest.lol.queue.job.Job<?> job, Throwable failure) {
        try {
            BotLogger.error("Database task failed for key=" + job.key()
                + " message=" + failure.getMessage());
        } catch (Throwable ignored) {
        }
    }

    private static CompletableFuture<Boolean> startProfileStatistics(
        Summoner summoner,
        Filter filter,
        boolean rebuild,
        JobPriority priority
    ) {
        if (summoner == null || summoner.puuid() == null || summoner.puuid().isBlank() || filter == null)
            return NOT_SCHEDULED;

        Filter requestFilter = Filter.fromStateKey(filter.toStateKey());
        String puuid = summoner.puuid();
        LeagueShard shard = summoner.region();
        String key = "profile-statistics:" + puuid + ":" + requestFilter.toSummonerKey();
        String name = "profile statistics " + (rebuild ? "rebuild " : "") + "puuid=" + puuid;
        return submit(DatabaseWorkerType.PROFILE, priority, key, name,
            ignored -> generateProfileStatistics(puuid, shard, requestFilter, rebuild));
    }

    private static CompletableFuture<Boolean> startProfileMatchups(
        String puuid,
        LeagueShard shard,
        Filter filter,
        JobPriority priority
    ) {
        if (puuid == null || puuid.isBlank() || shard == null || filter == null)
            return NOT_SCHEDULED;

        Filter requestFilter = Filter.fromStateKey(filter.toStateKey());
        String key = "profile-matchups:" + puuid + ":" + requestFilter.toSummonerKey();
        String name = "profile matchups puuid=" + puuid;
        return submit(DatabaseWorkerType.PROFILE, priority, key, name,
            ignored -> generateProfileMatchups(puuid, shard, requestFilter));
    }

    private static CompletableFuture<Boolean> startProfileActivity(
        String puuid,
        LeagueShard shard,
        Filter filter,
        JobPriority priority
    ) {
        if (puuid == null || puuid.isBlank() || shard == null || filter == null)
            return NOT_SCHEDULED;
        Filter requestFilter = Filter.fromStateKey(filter.toStateKey());
        String key = "profile-activity:" + puuid + ":" + requestFilter.toSummonerKey();
        String name = "profile activity puuid=" + puuid;
        return submit(DatabaseWorkerType.PROFILE, priority, key, name,
            ignored -> generateProfileActivity(puuid, shard, requestFilter));
    }

    private static Submission<ChampionService.MatrixRefreshResult> enqueueChampionStatsMatrix(
        String patch, GameQueueType queue, Filter buildFilter
    ) {
        String key = championStatsMatrixKey(patch, queue);
        synchronized (INSTANCE.lifecycleLock()) {
            ChampionMatrixRequest existing = CHAMPION_MATRICES.get(key);
            if (existing != null) {
                existing.tryAddBuild(buildFilter);
                return existing.submission();
            }

            ChampionMatrixRequest request = new ChampionMatrixRequest();
            request.tryAddBuild(buildFilter);
            Submission<ChampionService.MatrixRefreshResult> submission = submitChampion(key,
                "champion stats matrix patch=" + patch + " queue=" + queue.name(), job ->
                    CHAMPION_SERVICE.refreshStatisticsMatrix(patch, queue, request.startAndGetBuildFilters(), false, job));
            request.setSubmission(submission);
            CHAMPION_MATRICES.put(key, request);
            submission.future().whenComplete((result, failure) -> CHAMPION_MATRICES.remove(key, request));
            return submission;
        }
    }

    public static String championStatsMatrixKey(String patch, GameQueueType queue) {
        return "champion-stats-matrix:" + patch + ":" + queue.name();
    }

    public static String profileRefreshKey(String puuid) {
        return "profile-refresh:" + puuid;
    }

    static DatabaseWorkerType profileQueue(int profileLoad, int profile2Load, int championLoad, int mongoLoad, boolean championReserved) {
        if (championReserved) {
            return profile2Load < profileLoad ? DatabaseWorkerType.PROFILE_2 : DatabaseWorkerType.PROFILE;
        }
        int minProfile = Math.min(profileLoad, profile2Load);
        int minAll = Math.min(Math.min(minProfile, championLoad), mongoLoad);
        if (minAll == mongoLoad) return DatabaseWorkerType.MONGO;
        if (minAll == championLoad) return DatabaseWorkerType.CHAMPION;
        return profile2Load < profileLoad ? DatabaseWorkerType.PROFILE_2 : DatabaseWorkerType.PROFILE;
    }

    static DatabaseWorkerType profileQueue(int profileLoad, int championLoad, boolean championReserved) {
        return profileQueue(profileLoad, 0, championLoad, 0, championReserved);
    }

    static boolean isHeavyChampionTaskKey(String key) {
        return key != null && (key.startsWith("champion-stats-matrix:")
            || key.startsWith("champion-build:")
            || key.startsWith("champion-arena:")
            || key.startsWith("champion-data-refresh:"));
    }

    private static Submission<Boolean> startChampionBuild(Filter filter) {
        String key = (filter.queue() == GameQueueType.CHERRY ? "champion-arena:" : "champion-build:") + filter.toKey();
        return submitChampion(key, "champion build filter=" + filter.toKey(), job -> {
            if (CHAMPION_SERVICE.buildFresh(filter))
                return true;
            return CHAMPION_SERVICE.refreshBuild(filter, job);
        });
    }

    private static <T> Submission<T> submitChampion(String key, String name, Function<Job<T>, T> work) {
        synchronized (CHAMPION_FAILURES) {
            ChampionFailure previous = CHAMPION_FAILURES.get(key);
            if (previous != null) {
                long retryIn = CHAMPION_RETRY_MS - (System.currentTimeMillis() - previous.at());
                if (retryIn > 0)
                    throw new com.safjnest.exception.Exception(Type.UNAVAILABLE, "champion_job_failed",
                        "Champion job " + previous.pid() + " failed in " + previous.phase(), retryIn / 1_000D);
            }
            CHAMPION_FAILURES.remove(key);
        }
        QueueHandler.start();
        return Router.global().submitTracked(ComputeScheduler.class, DatabaseWorkerType.CHAMPION, key, name,
            JobPriority.NORMAL, job -> {
                long started = System.nanoTime();
                job.phase("CHECK_AVAILABILITY");
                job.currentItem(key);
                CHAMPION_LOG.info("pid={} key={} outcome=STARTED", job.pid(), key);
                try {
                    T result = work.apply(job);
                    CHAMPION_LOG.info("pid={} key={} outcome=DONE elapsedMs={}",
                        job.pid(), key, (System.nanoTime() - started) / 1_000_000);
                    return result;
                } catch (RuntimeException | Error failure) {
                    synchronized (CHAMPION_FAILURES) {
                        if (CHAMPION_FAILURES.size() >= CHAMPION_FAILURE_LIMIT)
                            CHAMPION_FAILURES.remove(CHAMPION_FAILURES.keySet().iterator().next());
                        CHAMPION_FAILURES.put(key, new ChampionFailure(job.pid(), job.phase(), System.currentTimeMillis()));
                    }
                    CHAMPION_LOG.warn("pid={} key={} outcome=FAILED phase={} cause={}",
                        job.pid(), key, job.phase(), failure.toString());
                    throw failure;
                }
            });
    }

    private record ChampionFailure(long pid, String phase, long at) {}

    private boolean championReserved() {
        return hasIncompleteTask(DatabaseWorkerType.CHAMPION,
            task -> isHeavyChampionTaskKey(task.key()));
    }

    private static <T> CompletableFuture<T> submit(DatabaseWorkerType route, JobPriority priority,
        String key, String name, Function<Job<T>, T> work) {
        return switch (priority) {
            case IMMEDIATE -> QueueHandler.immediate(ComputeScheduler.class, route, key, name, work);
            case NORMAL -> QueueHandler.normal(ComputeScheduler.class, route, key, name, work);
            case BACKGROUND -> QueueHandler.background(ComputeScheduler.class, route, key, name, work);
        };
    }

    private static boolean generateProfileStatistics(
        String puuid,
        LeagueShard shard,
        Filter filter,
        boolean rebuild
    ) {
        try {
            if (!PROFILE_SERVICE.generateStatistics(puuid, shard, filter, rebuild)) {
                BotLogger.error("Profile statistics refresh failed for summoner=" + puuid);
                return false;
            }

            ProfileService.invalidate(puuid, shard);
            return true;
        } catch (Exception exception) {
            BotLogger.error("Profile statistics refresh failed for summoner=" + puuid
                + " message=" + exception.getMessage());
            throw new IllegalStateException(exception);
        }
    }

    private static boolean generateProfileRecords(String puuid, LeagueShard shard, Filter filter) {
        try {
            if (!PROFILE_RECORD_SERVICE.generate(puuid, shard, filter)) {
                BotLogger.error("Profile records refresh failed for summoner=" + puuid);
                return false;
            }
            return true;
        } catch (Exception exception) {
            BotLogger.error("Profile records refresh failed for summoner=" + puuid
                + " message=" + exception.getMessage());
            throw new IllegalStateException(exception);
        }
    }

    private static boolean generateProfileMatchups(String puuid, LeagueShard shard, Filter filter) {
        try {
            if (!PROFILE_SERVICE.generateMatchups(puuid, shard, filter)) {
                BotLogger.error("Profile matchups refresh failed for puuid=" + puuid);
                return false;
            }
            return true;
        } catch (Exception exception) {
            BotLogger.error("Profile matchups refresh failed for puuid=" + puuid
                + " message=" + exception.getMessage());
            throw new IllegalStateException(exception);
        }
    }

    private static boolean generateProfileActivity(String puuid, LeagueShard shard, Filter filter) {
        try {
            if (!PROFILE_SERVICE.generateActivity(shard, puuid, filter)) {
                BotLogger.error("Profile activity refresh failed for puuid=" + puuid);
                return false;
            }
            return true;
        } catch (Exception exception) {
            BotLogger.error("Profile activity refresh failed for puuid=" + puuid
                + " message=" + exception.getMessage());
            throw new IllegalStateException(exception);
        }
    }

    private static boolean refreshProfile(String puuid, LeagueShard shard) {
        try {
            if (!PROFILE_SERVICE.refreshProfile(shard, puuid)) {
                BotLogger.error("Profile aggregate refresh failed for puuid=" + puuid);
                return false;
            }
            ProfileService.invalidate(puuid, shard);
            return true;
        } catch (Exception exception) {
            BotLogger.error("Profile aggregate refresh failed for puuid=" + puuid
                + " message=" + exception.getMessage());
            throw new IllegalStateException(exception);
        }
    }



}
