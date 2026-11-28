package com.safjnest.bot.commands.lol;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.safjnest.bot.Bot;
import com.safjnest.bot.model.customemoji.CustomEmojiHandler;
import com.safjnest.lol.LeagueHandler;
import com.safjnest.lol.model.ArenaBuildData;
import com.safjnest.lol.model.Build;
import com.safjnest.lol.model.Build.Option;
import com.safjnest.lol.model.Build.SlotOption;
import com.safjnest.lol.model.ChampionStatistics;
import com.safjnest.lol.model.ChampionStatistics.Matchup;
import com.safjnest.lol.model.Filter;
import com.safjnest.lol.model.rune.PageRunes;
import com.safjnest.lol.model.rune.Rune;
import com.safjnest.lol.service.StaticDataService;
import com.safjnest.lol.utils.BuildUtils;
import com.safjnest.lol.utils.ChampionUtils;
import com.safjnest.lol.utils.GameQueueTypeUtils;
import com.safjnest.lol.utils.ItemUtils;
import com.safjnest.lol.utils.LaneTypeUtils;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.emoji.RichCustomEmoji;
import no.stelar7.api.r4j.basic.constants.types.lol.LaneType;
import no.stelar7.api.r4j.pojo.lol.staticdata.champion.StaticChampion;
import no.stelar7.api.r4j.pojo.lol.staticdata.item.Item;

final class ChampionEmbed {

    private static final int STARTER_LIMIT = 3;
    private static final int BOOTS_LIMIT = 3;
    private static final int SLOT_OPTION_LIMIT = 4;
    private static final int FOLLOW_UP_SLOT_LIMIT = 3;
    private static final int SPELL_PAIR_LIMIT = 2;

    private ChampionEmbed() {}

    static MessageEmbed render(StaticChampion champion, Filter filter, ChampionStatistics stats, Build build) {
        String portrait = ChampionUtils.getChampionProfilePic(champion.getId());
        String queue = GameQueueTypeUtils.prettyName(filter.queue());
        String role = filter.lane() == LaneType.BOT ? "ADC"
            : filter.lane() == null ? queue : LaneTypeUtils.getPrettyName(filter.lane());

        EmbedBuilder embed = new EmbedBuilder();
        embed.setAuthor(champion.getName() + " · " + role, null, portrait);
        embed.setThumbnail(portrait);
        embed.setColor(Bot.getColor());
        embed.setFooter(queue + " · Patch " + filter.patch() + " · All ranks");

        if (build == null || build.games() == 0) {
            embed.setTitle("No build data yet");
        } else if (build.arena() != null) {
            addArenaBuild(embed, build);
        } else {
            embed.setTitle(buildLabel(build));
            String core = formatCore(build.coreBuilds().getFirst().items());
            embed.setDescription(sample(build.games(), build.winrate()));
            embed.addField("Core Build", core, false);
            addItemOptions(embed, "Starters", build.starterOptions(), STARTER_LIMIT);
            addItemOptions(embed, "Boots", build.bootOptions(), BOOTS_LIMIT);
            addSummonerSpells(embed, build);
            addFollowUpItems(embed, build.itemSlots());
            addRunes(embed, build);
            addSkillOrder(embed, build);
        }

        if (stats != null) {
            addChampionStats(embed, stats, filter.lane());
        }
        return embed.build();
    }

    static String buildLabel(Build build) {
        boolean popular = build.badges().contains(Build.Badge.MOST_POPULAR);
        boolean best = build.badges().contains(Build.Badge.BEST_ADJUSTED_WINRATE);
        String badge = popular && best ? "Most Played · Best Adjusted WR"
            : popular ? "Most Played" : best ? "Best Adjusted WR" : "";
        String name = build.name();
        if (name == null || name.isBlank()) {
            return badge.isEmpty() ? "Recommended Build" : badge;
        }
        return badge.isEmpty() ? name : name + " · " + badge;
    }

    static List<Option> visibleOptions(List<Option> options, int limit) {
        return options.stream().filter(option -> {
            int itemId = option.itemId();
            return itemId > 0 && !ItemUtils.isUnupgradedBoots(itemId);
        }).sorted(Comparator.comparingInt(Option::matches).reversed()).limit(limit).toList();
    }

    private static void addArenaBuild(EmbedBuilder embed, Build build) {
        embed.setTitle("Arena Build");
        ArenaBuildData.Core core = build.arena().coreBuilds().stream()
            .min(Comparator.comparingLong(ArenaBuildData.Core::matches).reversed().thenComparing(ArenaBuildData.Core::id))
            .orElse(null);
        embed.setDescription("Overall · " + sample(build.games(), build.winrate()));
        if (core != null) {
            embed.addField("Most Played Core", formatCore(core.items()) + "\n" + sample(core.matches(), core.winrate()), false);
        }
        addItemOptions(embed, "Boots", build.bootOptions(), BOOTS_LIMIT);
        addItemOptions(embed, "Prismatics", build.prismaticOptions(), SLOT_OPTION_LIMIT);

        StringBuilder augments = new StringBuilder();
        List<SlotOption> popular = build.augments().stream()
            .sorted(Comparator.comparingInt(SlotOption::matches).reversed()).limit(SLOT_OPTION_LIMIT).toList();
        for (SlotOption augment : popular) {
            String name = BuildUtils.toAugmentName(augment.itemId()).replaceFirst(", $", "");
            augments.append(CustomEmojiHandler.getFormattedEmoji("a" + augment.itemId())).append(" ")
                .append(name).append(" · ").append(augment.prettyMatches()).append(" games\n");
        }
        if (!augments.isEmpty()) {
            embed.addField("Augments", value(augments), true);
        }
    }

    private static void addFollowUpItems(EmbedBuilder embed, List<List<Option>> slots) {
        for (int i = 0; i < Math.min(slots.size(), FOLLOW_UP_SLOT_LIMIT); i++) {
            addItemOptions(embed, "#" + (i + 1) + " After Core", slots.get(i), SLOT_OPTION_LIMIT);
        }
    }

    private static void addItemOptions(EmbedBuilder embed, String name, List<Option> options, int limit) {
        StringBuilder text = new StringBuilder();
        for (Option option : visibleOptions(options, limit)) {
            String icon = compactIcon(option.id());
            text.append(icon).append('\n').append(compactStats(option.matches(), option.winrate())).append('\n');
        }
        if (!text.isEmpty()) {
            embed.addField(name, value(text), true);
        }
    }

    private static String formatCore(List<Integer> items) {
        return String.join(" → ", items.stream().map(ChampionEmbed::formatItem).toList());
    }

    private static String formatItem(int id) {
        String name = Integer.toString(id);
        try {
            Item item = StaticDataService.getItem(id);
            if (item != null) {
                name = item.getName();
            }
        } catch (RuntimeException ignored) {
            // The item's ID remains usable when the static catalog is unavailable.
        }
        return CustomEmojiHandler.getFormattedEmoji(id) + " " + name;
    }

    private static void addSummonerSpells(EmbedBuilder embed, Build build) {
        StringBuilder spells = new StringBuilder();
        for (Build.SummonerSpellOption pair : build.summonerSpellOptions().stream().limit(SPELL_PAIR_LIMIT).toList()) {
            int first = Math.max(pair.getSpell1(), pair.getSpell2());
            int second = Math.min(pair.getSpell1(), pair.getSpell2());
            spells.append(CustomEmojiHandler.getFormattedEmoji(first + "_")).append(" ")
                .append(CustomEmojiHandler.getFormattedEmoji(second + "_"))
                .append(" · ").append(pair.matches()).append(" games\n");
        }
        if (!spells.isEmpty()) {
            embed.addField("Summoner Spells", value(spells), true);
        }
    }

    private static void addRunes(EmbedBuilder embed, Build build) {
        if (build.runes() == null) {
            return;
        }
        List<Integer> primary = new ArrayList<>(build.getPrimaryRunes().size() + 1);
        primary.add(build.getKeystone());
        primary.addAll(build.getPrimaryRunes());
        addRuneTree(embed, build.getPrimaryTree(), primary, "Primary Runes");
        addRuneTree(embed, build.getSecondaryTree(), build.getSecondaryRunes(), "Secondary Runes");

        if (build.getStatShards().size() >= 3) {
            String shards = CustomEmojiHandler.getFormattedEmoji(build.getOffense()) + " Offense\n"
                + CustomEmojiHandler.getFormattedEmoji(build.getFlex()) + " Flex\n"
                + CustomEmojiHandler.getFormattedEmoji(build.getDefense()) + " Defense";
            embed.addField("Shards", shards, true);
        }
    }

    private static void addRuneTree(EmbedBuilder embed, int treeId, List<Integer> runes, String title) {
        if (runes.isEmpty()) {
            return;
        }
        PageRunes tree = LeagueHandler.getRunesHandler().get(Integer.toString(treeId));
        StringBuilder text = new StringBuilder();
        for (int id : runes) {
            String key = Integer.toString(id);
            Rune rune = tree == null ? null : tree.getRune(key);
            text.append(CustomEmojiHandler.getFormattedEmoji(key)).append(" ")
                .append(rune == null ? key : rune.getName()).append("\n");
        }
        embed.addField(title, value(text), true);
    }

    private static void addSkillOrder(EmbedBuilder embed, Build build) {
        List<String> steps = build.spellOrder().stream().filter(slot -> slot >= 1 && slot <= 4)
            .map(slot -> CustomEmojiHandler.getFormattedEmoji("qwer".charAt(slot - 1) + "_")).toList();
        if (!steps.isEmpty()) {
            embed.addField("Skill Order", String.join(" → ", steps), false);
        }
    }

    private static void addChampionStats(EmbedBuilder embed, ChampionStatistics stats, LaneType lane) {
        ChampionStatistics.LaneStat laneStats = lane == null ? null : stats.getLaneStat(lane);
        String overview = laneStats == null
            ? stats.picks() + " picks · " + stats.prettyWinrate() + " WR · " + stats.prettyPickrate() + " pick"
            : laneStats.prettyGames() + " picks · " + laneStats.prettyWinrate() + " WR · " + laneStats.prettyPickrate(stats.games()) + " pick";
        embed.addField("Champion Overview", overview + " · " + stats.prettyBanrate() + " ban", false);

        if (lane != null) {
            addMatchups(embed, "Weak Against", stats.weakAgainst());
            addMatchups(embed, "Strong Against", stats.strongAgainst());
            addMatchups(embed, "Popular Matchups", stats.popularMatchups());
        }
    }

    private static void addMatchups(EmbedBuilder embed, String title, List<Map.Entry<Integer, Matchup>> matchups) {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<Integer, Matchup> entry : matchups) {
            StaticChampion champion = ChampionUtils.getChampion(entry.getKey());
            if (champion == null) {
                continue;
            }
            Matchup matchup = entry.getValue();
            String icon = compactIcon(champion.getName());
            text.append(icon).append(' ').append(champion.getName()).append('\n')
                .append(compactStats(matchup.matches(), matchup.winrate())).append('\n');
        }
        if (!text.isEmpty()) {
            embed.addField(title, value(text), true);
        }
    }

    private static String compactIcon(String key) {
        RichCustomEmoji emoji = CustomEmojiHandler.getRichEmoji(key);
        return emoji != null ? emoji.getFormatted() : "▫️";
    }

    private static String compactStats(int games, double winrate) {
        return String.format(Locale.ITALY, "`%dg · %.2f%% WR`", games, winrate * 100);
    }

    private static String sample(long games, double winrate) {
        return String.format(Locale.US, "%,d games · %.1f%% WR", games, winrate * 100);
    }

    static String value(StringBuilder builder) {
        int limit = MessageEmbed.VALUE_MAX_LENGTH;
        if (builder.length() <= limit) {
            return builder.toString();
        }
        int lineEnd = builder.lastIndexOf("\n", limit);
        return builder.substring(0, lineEnd > 0 ? lineEnd : limit);
    }
}
