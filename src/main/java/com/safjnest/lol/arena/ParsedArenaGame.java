package com.safjnest.lol.arena;

import java.util.List;

import com.safjnest.lol.utils.ChampionBuildTimelineUtils.ItemEvent;
import com.safjnest.lol.utils.ChampionBuildTimelineUtils.SkillEvents;

public record ParsedArenaGame(boolean win, int placement, ItemEvent boots, ItemEvent firstPrismatic,
        boolean firstPrismaticFallback, List<ItemEvent> prismatics, List<ItemEvent> items,
        int orderedItemCount, List<Integer> fallbackBuildItems, List<Augment> augments,
        boolean buildTimeline, List<Integer> fallbackSkillOrder, SkillEvents skills) {

    public record Augment(int id, int position) {}
}
