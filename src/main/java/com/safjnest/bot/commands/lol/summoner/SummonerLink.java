package com.safjnest.bot.commands.lol.summoner;

import java.util.Arrays;

import com.jagrosh.jdautilities.command.SlashCommand;
import com.jagrosh.jdautilities.command.SlashCommandEvent;
import com.safjnest.bot.cache.managers.UserCache;
import com.safjnest.lol.LeagueHandler;
import com.safjnest.lol.model.summoner.Summoner;
import com.safjnest.lol.utils.LeagueShardUtils;
import com.safjnest.utils.BotCommand;
import com.safjnest.utils.CommandsLoader;

import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;

/**
 * @author <a href="https://github.com/NeutronSun">NeutronSun</a>
 * @since 1.3
 */
public class SummonerLink extends SlashCommand {
    
    /**
     * Constructor
     */
    public SummonerLink(String father){
        this.name = this.getClass().getSimpleName().replace("Slash", "").replace(father, "").toLowerCase();

        BotCommand commandData = CommandsLoader.getCommand(father).getChild(this.name);
        
        this.help = commandData.getHelp();
        this.cooldown = commandData.getCooldown();
        this.category = commandData.getCategory();

        this.contexts = new InteractionContextType[]{InteractionContextType.GUILD, InteractionContextType.BOT_DM};

        this.options = Arrays.asList(
            new OptionData(OptionType.STRING, "summoner", "Name and tag of the summoner you want to link", true).setAutoComplete(true),
            LeagueShardUtils.getAsOptions()
        );
        commandData.setThings(this);
    }

    /**
     * This method is called every time a member executes the command.
     */
    @Override
	protected void execute(SlashCommandEvent event) {
        event.deferReply(false).queue();
        Summoner s = LeagueHandler.getSummonerByArgs(event);
        if(s == null){
            event.getHook().editOriginal("Couldn't find the specified summoner. Remember to specify the tag").queue();
            return;
        }

        String name = event.getOption("summoner").getAsString();

        String userId = event.getUser().getId();
        Summoner linked = UserCache.withUser(userId, user -> {
            return user.addRiotAccount(s.puuid(), false);
        });

        if (linked == null) {
            boolean alreadyLinked = UserCache.withUser(userId, user -> {
                return user.getRiotAccounts().containsKey(s.puuid());
            });

            String response = alreadyLinked
                ? "This account is already connected to your profile."
                : "This account is already connected to another profile.\nIf you think someone has linked your account please write to our discord server support or use /bug";

            event.getHook().editOriginal(response).queue();
            return;
        }

        event.getHook().editOriginal("Connected " + name + " to your profile.").queue();

	}

}
