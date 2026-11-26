package com.safjnest.lol.queue.scheduler;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.safjnest.lol.queue.Router.Submission;

import com.safjnest.lol.model.Filter;
import com.safjnest.lol.service.ChampionService;

final class ChampionMatrixRequest {

    private final Map<String, Filter> buildFilters;
    private Submission<ChampionService.MatrixRefreshResult> submission;
    private boolean running;

    ChampionMatrixRequest() {
        buildFilters = new HashMap<>();
    }

    synchronized boolean tryAddBuild(Filter filter) {
        if (filter == null) return true;
        if (running)
            return includesBuild(filter);
        buildFilters.putIfAbsent(filter.toKey(), filter);
        return true;
    }

    synchronized List<Filter> startAndGetBuildFilters() {
        running = true;
        return List.copyOf(buildFilters.values());
    }

    synchronized boolean includesBuild(Filter filter) {
        return buildFilters.containsKey(filter.toKey());
    }

    synchronized void setSubmission(Submission<ChampionService.MatrixRefreshResult> value) {
        submission = value;
    }

    synchronized Submission<ChampionService.MatrixRefreshResult> submission() {
        return submission;
    }

}
