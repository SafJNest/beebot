package com.safjnest.lol.service;

import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.longs.LongList;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BuildRankingAnalyzerTest {

    @Test
    public void keepsRelevantVariantsAndRanksPopularitySeparatelyFromWinrate() {
        int[] matches = {1, 5, 149, 150, 10_000, 4_000, 695};
        int[] wins = {1, 5, 149, 87, 5_200, 1_900, 416};

        BuildRankingAnalyzer.Ranking ranked = BuildRankingAnalyzer.rank(LongList.of(1, 2, 3, 4, 5, 6, 7),
            matches, wins, 15_000, 7_758, true);

        assertEquals(IntList.of(4, 5, 6, 3), ranked.indices());
        assertEquals(6, ranked.bestAdjustedWinrateIndex());
    }

    @Test
    public void adjustsTheWinrateTowardTheCohortAndRequiresASeparateSample() {
        LongList keys = LongList.of(1, 2, 3, 4, 5);
        int[] matches = {1, 40, 100, 1_000, 2_300};
        int[] wins = {1, 40, 60, 580, 1_000};
        BuildRankingAnalyzer.Ranking ranked = BuildRankingAnalyzer.rank(keys, matches, wins, 3_441, 1_681, true);
        BuildRankingAnalyzer.Ranking smaller = BuildRankingAnalyzer.rank(LongList.of(2, 3),
            new int[]{40, 100}, new int[]{30, 60}, 140, 90, true);

        assertEquals(IntList.of(4, 3, 2, 1), ranked.indices());
        assertEquals(3, ranked.bestAdjustedWinrateIndex());
        assertEquals(1, smaller.bestAdjustedWinrateIndex());
    }

    @Test
    public void sortsTiesByCoreKeyAndRejectsASingleWin() {
        BuildRankingAnalyzer.Ranking tied = BuildRankingAnalyzer.rank(LongList.of(3, 1, 2),
            new int[]{100, 100, 100}, new int[]{60, 60, 60}, 300, 180, true);
        BuildRankingAnalyzer.Ranking single = BuildRankingAnalyzer.rank(LongList.of(1),
            new int[]{1}, new int[]{1}, 1, 1, true);

        assertEquals(IntList.of(1, 2, 0), tied.indices());
        assertEquals(1, tied.bestAdjustedWinrateIndex());
        assertTrue(single.indices().isEmpty());
        assertEquals(-1, single.bestAdjustedWinrateIndex());
    }
}
