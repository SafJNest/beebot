package com.safjnest.lol.service;

import com.safjnest.lol.utils.TierMathUtils;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.longs.LongList;

final class BuildRankingAnalyzer {

    private static final int MIN_VARIANT_GAMES = 20;
    private static final int MIN_VARIANT_SHARE_PERCENT = 1;
    private static final int MIN_WINRATE_GAMES = 100;
    private static final int WINRATE_PRIOR_GAMES = 50;

    record Ranking(IntList indices, int bestAdjustedWinrateIndex) {}

    private BuildRankingAnalyzer() {}

    static Ranking rank(LongList keys, int[] matches, int[] wins,
                        int totalMatches, int totalWins, boolean rankWinrate) {
        IntArrayList indices = new IntArrayList();
        for (int index = 0; index < matches.length; index++) {
            int games = matches[index];
            if (games >= MIN_VARIANT_GAMES && 100L * games >= (long) MIN_VARIANT_SHARE_PERCENT * totalMatches) {
                indices.add(index);
            }
        }
        indices.unstableSort((left, right) -> {
            int byMatches = Integer.compare(matches[right], matches[left]);
            return byMatches != 0 ? byMatches : Long.compare(keys.getLong(left), keys.getLong(right));
        });

        if (!rankWinrate || indices.isEmpty()) {
            return new Ranking(indices, -1);
        }

        double baseline = (double) totalWins / totalMatches;
        double bestAdjustedWinrate = Double.NEGATIVE_INFINITY;
        int bestIndex = -1;
        for (int position = 0; position < indices.size(); position++) {
            int index = indices.getInt(position);
            int games = matches[index];
            if (games < MIN_WINRATE_GAMES) {
                break;
            }

            double adjustedWinrate = TierMathUtils.shrinkRate(wins[index], games, baseline, WINRATE_PRIOR_GAMES);
            if (adjustedWinrate > bestAdjustedWinrate) {
                bestAdjustedWinrate = adjustedWinrate;
                bestIndex = index;
            }
        }
        return new Ranking(indices, bestIndex);
    }
}
