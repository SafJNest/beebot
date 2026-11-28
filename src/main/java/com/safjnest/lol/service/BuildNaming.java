package com.safjnest.lol.service;

import com.safjnest.lol.arena.ArenaItemCatalog;
import com.safjnest.lol.model.ArenaBuildData;
import com.safjnest.lol.model.Build;
import com.safjnest.lol.model.ChampionBuildSnapshot;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import no.stelar7.api.r4j.pojo.lol.staticdata.item.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;

final class BuildNaming {

    private static final Logger LOG = LoggerFactory.getLogger(BuildNaming.class);
    private static final int MIN_THIRD_ITEM_GAMES = 20;
    private static final int DOMINANT_KEYSTONE_PERCENT = 60;
    private static final double MIN_STYLE_SCORE = 4;
    private static final double MIN_STYLE_MARGIN = 2;
    private static final int STATIKK_SHIV = 3087;
    private static final int STATIKK_SHIV_VARIANT = 773087;

    private enum Style {
        CRIT("Crit"), ON_HIT("On-Hit"), AP("AP"), AD("AD");

        final String name;

        Style(String name) {
            this.name = name;
        }
    }

    private static final Style[] STYLES = Style.values();

    private record Name(String text, Integer keystoneId) {}

    private final Map<Integer, Item> items;
    private final IntFunction<String> runeName;

    BuildNaming(Map<Integer, Item> items, IntFunction<String> runeName) {
        this.items = items;
        this.runeName = runeName;
    }

    ChampionBuildSnapshot label(ChampionBuildSnapshot snapshot) {
        try {
            return labelStandardBuilds(snapshot);
        } catch (RuntimeException failure) {
            LOG.warn("Could not label champion builds for {}: {}", snapshot.filter().toKey(), failure.toString());
            return snapshot;
        }
    }

    static ArenaBuildData labelArena(ArenaBuildData arena, ArenaItemCatalog catalog) {
        try {
            return labelArenaCores(arena, catalog);
        } catch (RuntimeException failure) {
            LOG.warn("Could not label Arena cores: {}", failure.toString());
            return arena;
        }
    }

    // ============================================================================

    private ChampionBuildSnapshot labelStandardBuilds(ChampionBuildSnapshot snapshot) {
        List<Build> builds = snapshot.builds();
        if (builds.isEmpty()) {
            return snapshot;
        }

        Name[] names = categoryNames(builds);
        Map<Name, Build> mostPlayedByCategory = new HashMap<>();
        for (int index = 0; index < builds.size(); index++) {
            Name category = names[index];
            if (category != null) {
                mostPlayedByCategory.merge(category, builds.get(index),
                    (previous, candidate) -> candidate.games() > previous.games() ? candidate : previous);
            }
        }

        List<Build> labeled = new ArrayList<>(builds.size());
        for (int index = 0; index < builds.size(); index++) {
            Build build = builds.get(index);
            Name category = names[index];
            String coreId = build.coreBuilds().getFirst().id();
            List<Build.Badge> badges = new ArrayList<>(2);
            if (mostPlayedByCategory.get(category) == build || coreId.equals(snapshot.defaultBuildId())) {
                badges.add(Build.Badge.MOST_POPULAR);
            }
            if (coreId.equals(snapshot.bestAdjustedWinrateBuildId())) {
                badges.add(Build.Badge.BEST_ADJUSTED_WINRATE);
            }
            labeled.add(build.withLabels(category == null ? null : category.text(),
                category == null ? null : category.keystoneId(), badges));
        }
        return new ChampionBuildSnapshot(snapshot.filter(), labeled, snapshot.defaultBuildId(),
            snapshot.bestAdjustedWinrateBuildId());
    }

    private static ArenaBuildData labelArenaCores(ArenaBuildData arena, ArenaItemCatalog catalog) {
        List<ArenaBuildData.Core> cores = arena.coreBuilds();
        if (cores.isEmpty()) {
            return arena;
        }

        Object2IntOpenHashMap<String> titleCounts = new Object2IntOpenHashMap<>();
        ArenaBuildData.Core mostPlayed = cores.getFirst();
        for (ArenaBuildData.Core core : cores) {
            String name = catalog.name(core.items().get(1));
            if (name != null) {
                titleCounts.addTo(name, 1);
            }
            if (core.matches() > mostPlayed.matches()
                    || (core.matches() == mostPlayed.matches() && core.id().compareTo(mostPlayed.id()) < 0)) {
                mostPlayed = core;
            }
        }

        List<ArenaBuildData.Core> labeled = new ArrayList<>(cores.size());
        for (ArenaBuildData.Core core : cores) {
            int bootsId = core.items().get(0);
            int prismaticId = core.items().get(1);
            String name = catalog.name(prismaticId);
            if (name == null || titleCounts.getInt(name) > 1) {
                String boots = catalog.name(bootsId);
                name = (boots == null ? "Boots " + bootsId : boots) + " + "
                    + (name == null ? "Prismatic " + prismaticId : name);
            }
            List<Build.Badge> badges = core == mostPlayed ? List.of(Build.Badge.MOST_POPULAR) : List.of();
            labeled.add(core.withLabels(name, badges));
        }
        return arena.withCoreBuilds(labeled);
    }

    private Name[] categoryNames(List<Build> builds) {
        Name[] names = new Name[builds.size()];
        if (builds.size() < 2) {
            return names;
        }

        Style[] styles = new Style[builds.size()];
        EnumSet<Style> distinct = EnumSet.noneOf(Style.class);
        for (int index = 0; index < builds.size(); index++) {
            Style style = itemStyle(builds.get(index));
            styles[index] = style;
            if (style != null) {
                distinct.add(style);
            }
        }
        if (distinct.size() > 1) {
            for (int index = 0; index < styles.length; index++) {
                Style style = styles[index];
                if (style != null) {
                    names[index] = new Name(style.name, null);
                }
            }
        } else if (distinct.isEmpty()) {
            return keystoneNames(builds, names);
        }
        return names;
    }

    private Name[] keystoneNames(List<Build> builds, Name[] names) {
        int[] keystones = new int[builds.size()];
        IntOpenHashSet distinct = new IntOpenHashSet();
        for (int index = 0; index < builds.size(); index++) {
            Build build = builds.get(index);
            int keystone = dominantKeystone(build.runeOptions(), build.games());
            if (keystone > 0) {
                distinct.add(keystone);
                keystones[index] = keystone;
            }
        }
        if (distinct.size() < 2) {
            return names;
        }

        Int2ObjectOpenHashMap<Name> validNames = new Int2ObjectOpenHashMap<>(distinct.size());
        Set<String> titles = new HashSet<>();
        for (int keystone : distinct) {
            String rune = runeName.apply(keystone);
            if (rune != null && !rune.isBlank()) {
                validNames.put(keystone, new Name(rune, keystone));
                titles.add(rune);
            }
        }
        if (titles.size() > 1) {
            for (int index = 0; index < names.length; index++) {
                names[index] = validNames.get(keystones[index]);
            }
        }
        return names;
    }

    private Style itemStyle(Build build) {
        double[] scores = new double[STYLES.length];
        Build.CoreBuildOption core = build.coreBuilds().getFirst();
        addItem(scores, core.items().get(0), 1);
        addItem(scores, core.items().get(1), 1);

        Style style = dominantStyle(scores);
        List<List<Build.Option>> slots = build.itemSlots();
        if (style == null && !slots.isEmpty()) {
            List<Build.Option> third = slots.getFirst();
            long observed = 0;
            for (Build.Option option : third) {
                observed += option.matches();
            }
            if (observed >= MIN_THIRD_ITEM_GAMES && observed * 2 >= core.matches()) {
                for (Build.Option option : third) {
                    addItem(scores, option.itemId(), (double) option.matches() / core.matches());
                }
                style = dominantStyle(scores);
            }
        }
        return style;
    }

    private void addItem(double[] scores, int id, double weight) {
        Item item = items.get(id);
        // Statikk Shiv flexes into several paths and contributes primarily waveclear.
        if (item == null || item.getTags() == null || id == STATIKK_SHIV || id == STATIKK_SHIV_VARIANT) {
            return;
        }

        List<String> tags = item.getTags();
        boolean crit = tags.contains("CriticalStrike");
        boolean onHit = tags.contains("OnHit");
        boolean ap = tags.contains("SpellDamage");
        if (crit) {
            scores[Style.CRIT.ordinal()] += 3 * weight;
        }
        if (onHit) {
            scores[Style.ON_HIT.ordinal()] += 3 * weight;
        }
        if (ap) {
            scores[Style.AP.ordinal()] += (onHit || crit ? 1 : 4) * weight;
        }
        if (!crit && !onHit && !ap && tags.contains("Damage")) {
            scores[Style.AD.ordinal()] += 2 * weight;
        }
    }

    private static Style dominantStyle(double[] scores) {
        Style best = null;
        double first = 0;
        double second = 0;
        for (Style style : STYLES) {
            double score = scores[style.ordinal()];
            if (score > first) {
                second = first;
                first = score;
                best = style;
            } else if (score > second) {
                second = score;
            }
        }
        return first >= MIN_STYLE_SCORE && first - second >= MIN_STYLE_MARGIN ? best : null;
    }

    private static int dominantKeystone(List<Build.RuneOption> runes, int games) {
        if (runes.isEmpty()) {
            return 0;
        }

        Int2IntOpenHashMap picks = new Int2IntOpenHashMap(4);
        for (Build.RuneOption option : runes) {
            int keystone = option.configuration().keystone();
            if (keystone > 0) {
                picks.addTo(keystone, option.matches());
            }
        }
        for (var entry : picks.int2IntEntrySet()) {
            if (100L * entry.getIntValue() >= (long) DOMINANT_KEYSTONE_PERCENT * games) {
                return entry.getIntKey();
            }
        }
        return 0;
    }

}
