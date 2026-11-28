package com.safjnest.bot.commands.lol;

import java.util.List;

import org.junit.Test;

import com.safjnest.lol.model.Build.Option;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.interactions.commands.Command.Choice;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ChampionCommandTest {

    @Test
    public void registersOnlyChampionRoleAndQueue() {
        List<OptionData> options = new Champion().getOptions();

        assertEquals(List.of("champion", "role", "queue"), options.stream().map(OptionData::getName).toList());
        assertTrue(options.get(0).isRequired());
        assertFalse(options.get(1).isRequired());
        assertFalse(options.get(2).isRequired());
        assertEquals(List.of("TEAM_BUILDER_RANKED_SOLO", "RANKED_FLEX_SR", "TEAM_BUILDER_DRAFT_UNRANKED_5X5", "ARAM", "CHERRY"),
            options.get(2).getChoices().stream().map(Choice::getAsString).toList());
    }

    @Test
    public void longArenaOptionListFitsTheDiscordFieldLimit() {
        String row = "Prismatic Option\n" + "185 games 52.40% WR\n";
        StringBuilder options = new StringBuilder(row.repeat(60));
        assertThrows(IllegalArgumentException.class,
            () -> new EmbedBuilder().addField("Prismatic Options", options.toString(), true));

        String value = ChampionEmbed.value(options);
        MessageEmbed embed = new EmbedBuilder().addField("Prismatic Options", value, true).build();

        assertEquals(value, embed.getFields().getFirst().getValue());
        assertTrue(value.length() <= MessageEmbed.VALUE_MAX_LENGTH);
        assertEquals('\n', options.charAt(value.length()));
    }

    @Test
    public void displayLimitsSkipUnupgradedBootsBeforeCountingOptions() {
        List<Option> options = List.of(option(1001, 90), option(3089, 11), option(2422, 80),
            option(3020, 40), option(3006, 35), option(3111, 20), option(3047, 6));

        assertEquals(List.of(3020, 3006, 3111),
            ChampionEmbed.visibleOptions(options, 3).stream().map(Option::itemId).toList());
        assertEquals(List.of(3020, 3006, 3111, 3089),
            ChampionEmbed.visibleOptions(options, 4).stream().map(Option::itemId).toList());
    }

    private static Option option(int itemId, int matches) {
        return new Option(Integer.toString(itemId), matches, 0, 0, 0);
    }
}
