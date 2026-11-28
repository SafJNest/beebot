package com.safjnest.lol.queue.scheduler;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import com.safjnest.lol.model.Filter;
import com.safjnest.lol.queue.Registry;
import com.safjnest.lol.queue.Router;
import com.safjnest.lol.queue.Router.Submission;
import com.safjnest.lol.queue.job.JobPriority;

import no.stelar7.api.r4j.basic.constants.types.lol.GameQueueType;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ChampionMatrixRequestTest {

    @Test
    public void aRunningMatrixCanStillSatisfyBuildsItAlreadyIncluded() {
        ChampionMatrixRequest request = new ChampionMatrixRequest();
        Filter first = Filter.championBuild(222, "16.12", GameQueueType.TEAM_BUILDER_RANKED_SOLO);
        Filter late = Filter.championBuild(103, "16.12", GameQueueType.TEAM_BUILDER_RANKED_SOLO);

        assertTrue(request.tryAddBuild(first));
        assertEquals(List.of(first), request.startAndGetBuildFilters());
        assertTrue(request.tryAddBuild(first));
        assertFalse(request.tryAddBuild(late));
        assertFalse(request.includesBuild(late));
    }

    @Test
    public void trackedSubmissionReturnsTheActivePidAndRunsOnlyOnce() throws Exception {
        Registry registry = new Registry(Clock.systemUTC());
        Router router = new Router(registry);
        TestScheduler scheduler = new TestScheduler(registry);
        router.register(scheduler);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Submission<Boolean> first = router.submitTracked(TestScheduler.class, "champion", "build:222",
                "first", JobPriority.NORMAL, job -> {
                    started.countDown();
                    try {
                        return release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(failure);
                    }
                });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            Submission<Boolean> next = router.submitTracked(TestScheduler.class, "champion", "build:222",
                "follower", JobPriority.NORMAL, job -> false);

            assertEquals(first.pid(), next.pid());
            release.countDown();
            assertTrue(first.future().get(5, TimeUnit.SECONDS));
            assertTrue(next.future().get(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            scheduler.shutdownDispatcher();
        }
    }

    private static final class TestScheduler extends AbstractScheduler<String> {
        TestScheduler(Registry registry) {
            super("test", "test stopped", registry);
        }

        protected String routeName(String route) { return route; }
        protected String workerThreadName(String route) { return "champion-test-"; }
        protected String routeForJob(Object route) { return (String) route; }
    }
}
