package com.safjnest.lol.champion;

import com.safjnest.utils.ParseUtils;

import com.safjnest.lol.utils.BuildUtils;
import com.safjnest.lol.utils.ItemUtils;
import com.safjnest.lol.utils.ChampionBuildTimelineUtils;

import no.stelar7.api.r4j.pojo.lol.staticdata.item.Item;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public record BuildSignature(
        List<Integer> starter,
        int boots,
        int suppItem,
        List<Integer> core,
        List<Integer> fullBuild,
        List<Integer> spellOrder,
        List<Integer> prismatics,
        List<Integer> augments,
        List<Integer> summonerSpells
) {

    private static final long STARTER_WINDOW_MILLIS = 2 * 60 * 1000L;
    private static final int CORE_SIZE = 2;

    static BuildSignature from(List<Integer> inventory, int roleBoundId, List<Integer> skillOrder,
                               List<Integer> augments, List<Integer> summonerSpells, Map<Integer, Item> items,
                               List<ChampionBuildTimelineUtils.ItemEvent> purchases,
                               List<ChampionBuildTimelineUtils.SkillUpgrade> skillUpgrades) {
        List<Integer> fullBuild = new ArrayList<>();
        int starter = 0;
        for (ChampionBuildTimelineUtils.ItemEvent purchase : purchases) {
            int id = purchase.itemId();
            Item item = items.get(id);
            if (starter == 0 && purchase.timestampMillis() < STARTER_WINDOW_MILLIS && ItemUtils.isBuildPurchase(item)) {
                starter = id;
            }
            if (id != starter && ItemUtils.isCoreItem(item, items) && !fullBuild.contains(id)) {
                fullBuild.add(id);
            }
        }
        if (fullBuild.size() < CORE_SIZE) {
            return null;
        }

        List<Integer> starterItems = starter == 0 ? List.of() : List.of(starter);
        List<Integer> core = List.copyOf(fullBuild.subList(0, CORE_SIZE));
        Item roleBoundItem = items.get(roleBoundId);
        int boots = ItemUtils.isBoots(roleBoundItem, items) ? roleBoundId : 0;
        int supportItem = ItemUtils.isSupportItem(roleBoundItem) ? roleBoundId : 0;
        for (int id : inventory) {
            Item item = items.get(id);
            if (boots == 0 && ItemUtils.isBoots(item, items)) {
                boots = id;
            }
            if (supportItem == 0 && ItemUtils.isSupportItem(item)) {
                supportItem = id;
            }
        }

        List<Integer> spellOrder = ChampionBuildTimelineUtils.skillOrder(skillOrder, skillUpgrades);
        List<Integer> augmentIds = new ArrayList<>();
        for (int id : augments) {
            if (id != 0) {
                augmentIds.add(id);
            }
        }

        List<Integer> summonerSpellIds = new ArrayList<>(2);
        for (int id : summonerSpells) {
            if (id != 0) {
                summonerSpellIds.add(id);
            }
        }
        Collections.sort(summonerSpellIds);

        return new BuildSignature(starterItems, boots, supportItem, core, List.copyOf(fullBuild),
            List.copyOf(spellOrder), List.of(), List.copyOf(augmentIds), List.copyOf(summonerSpellIds));
    }

    public String toCoreKey() {
        String raw = BuildUtils.joinInts(starter) + "|" + suppItem + "|" + BuildUtils.joinInts(core);
        return BuildUtils.toBase64(raw);
    }

    public String toKey() {
        String raw = BuildUtils.joinInts(starter) + "|" + boots + "|" + suppItem + "|"
                + BuildUtils.joinInts(core) + "|" + BuildUtils.joinInts(fullBuild) + "|"
                + BuildUtils.joinInts(spellOrder) + "|" + BuildUtils.joinInts(prismatics) + "|"
                + BuildUtils.joinInts(augments) + "|" + BuildUtils.joinInts(summonerSpells);
        return BuildUtils.toBase64(raw);
    }

    public static BuildSignature decode(String key) {
        String[] parts = BuildUtils.fromBase64(key).split("\\|", -1);
        List<Integer> emptySkillOrder = new ArrayList<>(18);
        for (int level = 0; level < 18; level++) emptySkillOrder.add(0);
        return new BuildSignature(
                parts.length > 0 ? BuildUtils.parseDashList(parts[0]) : List.of(),
                ParseUtils.parseInt(parts[1]),
                ParseUtils.parseInt(parts[2]),
                parts.length > 3 ? BuildUtils.parseDashList(parts[3]) : List.of(),
                parts.length > 4 ? BuildUtils.parseDashList(parts[4]) : List.of(),
                parts.length > 5 ? decodeSpellOrderSegment(parts[5]) : emptySkillOrder,
                parts.length > 6 ? BuildUtils.parseDashList(parts[6]) : List.of(),
                parts.length > 7 ? BuildUtils.parseDashList(parts[7]) : List.of(),
                parts.length > 8 ? BuildUtils.parseDashList(parts[8]) : List.of());
    }


    /** Supports dash-separated ints or legacy 18-digit string without separators. */
    private static List<Integer> decodeSpellOrderSegment(String segment) {
        if (segment == null || segment.isBlank()) {
            List<Integer> emptySkillOrder = new ArrayList<>(18);
            for (int level = 0; level < 18; level++) emptySkillOrder.add(0);
            return emptySkillOrder;
        }
        if (segment.contains("-")) {
            List<Integer> skillOrder = new ArrayList<>(BuildUtils.parseDashList(segment));
            while (skillOrder.size() < 18) skillOrder.add(0);
            return skillOrder.size() > 18
                    ? new ArrayList<>(skillOrder.subList(0, 18))
                    : skillOrder;
        }
        List<Integer> skillOrder = new ArrayList<>(18);
        for (int level = 0; level < 18; level++) {
            int slot = level < segment.length() ? Character.getNumericValue(segment.charAt(level)) : 0;
            skillOrder.add(slot >= 1 && slot <= 4 ? slot : 0);
        }
        return skillOrder;
    }
}
