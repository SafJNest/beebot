package com.safjnest.lol.service;

import com.safjnest.lol.champion.ChampionBuildData;
import com.safjnest.lol.model.ChampionBuildSnapshot;
import com.safjnest.lol.model.Filter;

final class ChampionBuildEngine {

    private final Filter filter;
    private final BuildCoreStats cores = new BuildCoreStats();

    ChampionBuildEngine(Filter filter) {
        this.filter = filter;
    }

    Filter filter() {
        return filter;
    }

    void accept(ChampionBuildData.Game game) {
        if (game == null || game.signature().core().size() != 2) {
            return;
        }

        cores.accept(game);
    }

    ChampionBuildSnapshot finish() {
        return cores.snapshot(filter);
    }

    void clear() {
        cores.clear();
    }
}
