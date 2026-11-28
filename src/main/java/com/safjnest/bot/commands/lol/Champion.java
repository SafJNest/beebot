package com.safjnest.bot.commands.lol;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jagrosh.jdautilities.command.SlashCommand;
import com.jagrosh.jdautilities.command.SlashCommandEvent;
import com.safjnest.lol.model.Build;
import com.safjnest.lol.model.ChampionStatistics;
import com.safjnest.lol.model.Filter;
import com.safjnest.lol.service.ChampionService;
import com.safjnest.lol.utils.ChampionUtils;
import com.safjnest.utils.BotCommand;
import com.safjnest.utils.CommandsLoader;
import com.safjnest.utils.SafJNest;

import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import no.stelar7.api.r4j.basic.constants.types.lol.GameQueueType;
import no.stelar7.api.r4j.basic.constants.types.lol.LaneType;
import no.stelar7.api.r4j.pojo.lol.staticdata.champion.StaticChampion;

/**
 * @author <a href="https://github.com/NeutronSun">NeutronSun</a>
 * @since 1.3
 */
public class Champion extends SlashCommand {

    private static final Logger LOG = LoggerFactory.getLogger(Champion.class);
    private static final ChampionService CHAMPION_SERVICE = new ChampionService();

    public Champion() {
        this.name = this.getClass().getSimpleName().replace("Slash", "").toLowerCase();

        BotCommand commandData = CommandsLoader.getCommand(this.name);
        
        this.help = commandData.getHelp();
        this.cooldown = commandData.getCooldown();
        this.category = commandData.getCategory();

        this.contexts = new InteractionContextType[]{InteractionContextType.GUILD, InteractionContextType.BOT_DM};
        
        this.options = List.of(
            new OptionData(OptionType.STRING, "champion", "Champion Name", true).setAutoComplete(true),
            new OptionData(OptionType.STRING, "role", "Champion Role", false)
                .addChoice("Top", LaneType.TOP.name())
                .addChoice("Jungle", LaneType.JUNGLE.name())
                .addChoice("Mid", LaneType.MID.name())
                .addChoice("ADC", LaneType.BOT.name())
                .addChoice("Support", LaneType.UTILITY.name()),
            new OptionData(OptionType.STRING, "queue", "Queue", false)
                .addChoice("Ranked Solo/Duo", GameQueueType.TEAM_BUILDER_RANKED_SOLO.name())
                .addChoice("Ranked Flex", GameQueueType.RANKED_FLEX_SR.name())
                .addChoice("Draft Pick", GameQueueType.TEAM_BUILDER_DRAFT_UNRANKED_5X5.name())
                .addChoice("ARAM", GameQueueType.ARAM.name())
                .addChoice("Arena", GameQueueType.CHERRY.name())
        );

        commandData.setThings(this);
    }

    @Override
    protected void execute(SlashCommandEvent event) {
        event.deferReply(false).queue();

        StaticChampion champion = getChampion(event.getOption("champion").getAsString());
        if (champion == null) {
            event.getHook().editOriginal("Cannot find the champion you are looking for.").queue();
            return;
        }

        Filter filter;
        try {
            GameQueueType queue = event.getOption("queue", option -> GameQueueType.valueOf(option.getAsString()));
            LaneType role = queue == GameQueueType.CHERRY ? null
                : event.getOption("role", option -> LaneType.valueOf(option.getAsString()));
            filter = CHAMPION_SERVICE.resolveFilter(champion.getId(), null, null, null, queue, role);
        } catch (RuntimeException failure) {
            replyFailure(event, champion.getId(), null, "resolve_filter", failure);
            return;
        }

        CompletableFuture.completedFuture(filter)
            .thenCompose(CHAMPION_SERVICE::getForDiscord)
            .whenComplete((data, failure) -> {
                if (failure != null) {
                    replyFailure(event, champion.getId(), filter, "load_data", failure);
                    return;
                }
                try {
                    replyBuild(event, champion, filter, data.stats(), data.build());
                } catch (RuntimeException renderFailure) {
                    replyFailure(event, champion.getId(), filter, "render_embed", renderFailure);
                }
            });
    }

    private void replyBuild(SlashCommandEvent event, StaticChampion champion, Filter filter,
            ChampionStatistics stats, Build build) {
        MessageEmbed embed = ChampionEmbed.render(champion, filter, stats, build);
        event.getHook().editOriginalEmbeds(embed).queue(null,
            failure -> replyFailure(event, champion.getId(), filter, "send_embed", failure));
    }

    private void replyFailure(SlashCommandEvent event, int champion, Filter filter, String phase, Throwable failure) {
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
        String code = cause instanceof com.safjnest.exception.Exception error ? error.getCode() : phase;
        String key = filter == null ? "-" : filter.toKey();
        LOG.warn("[ChampionDiscord] champion={} filter={} phase={} code={}", champion, key, phase, code, cause);
        event.getHook().editOriginal("Champion data could not be loaded (" + code + ").").queue();
    }

    private StaticChampion getChampion(String name) {
        String championName = SafJNest.findSimilarWord(name, new ArrayList<>(ChampionUtils.getChampionsNames()));
        return ChampionUtils.getChampion(championName);
    }
}
