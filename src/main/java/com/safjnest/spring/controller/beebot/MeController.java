package com.safjnest.spring.controller.beebot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;

import com.fasterxml.jackson.databind.JsonNode;
import com.safjnest.bot.cache.managers.SoundCache;
import com.safjnest.bot.cache.managers.UserCache;
import com.safjnest.database.sql.QueryRecord;
import com.safjnest.database.sql.database.BotDB;
import com.safjnest.exception.Exception;
import com.safjnest.exception.Exception.Type;
import com.safjnest.discord.auth.DiscordApi.AuthUser;
import com.safjnest.discord.auth.DiscordApi.Server;
import com.safjnest.discord.auth.DiscordAuthService.AuthContext;
import com.safjnest.discord.auth.DiscordAuthService;
import com.safjnest.lol.model.summoner.LinkedSummoner;
import com.safjnest.lol.model.summoner.Summoner;
import com.safjnest.lol.service.SummonerService;
import com.safjnest.lol.utils.LeagueShardUtils;
import com.safjnest.sound.model.Sound;
import com.safjnest.spring.config.AuthFilter;
import com.safjnest.utils.ValidationUtils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletResponse;
import no.stelar7.api.r4j.basic.constants.api.regions.LeagueShard;

@RestController
@RequestMapping("/api/beebot")
public class MeController {

    private static final String LOL_ACCOUNT = "/me/lol/account";

    private final DiscordAuthService auth;
    private final SoundCache sounds;
    private final UserCache users;
    private final BotDB database;

    public record Me(AuthUser user, List<Server> servers, Map<String, Sound> greets,
            List<LinkedSummoner> riotAccounts) {}
    public record GreetSettings(Map<String, GreetResult> greets) {}
    public record GreetResult(String status, List<GreetError> errors) {}
    public record GreetError(String field, String code, String message) {}

    @Autowired
    public MeController(DiscordAuthService auth) {
        this(auth, SoundCache.getInstance(), UserCache.getInstance(), BotDB.get());
    }

    public MeController(DiscordAuthService auth, SoundCache sounds, UserCache users, BotDB database) {
        this.auth = auth;
        this.sounds = sounds;
        this.users = users;
        this.database = database;
    }

    @ModelAttribute
    public void preventCaching(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }

    @GetMapping("/me")
    public Me me(@RequestAttribute(AuthFilter.AUTH_CONTEXT) AuthContext auth) {
        SoundCache.Context context = context(auth);
        Map<String, String> configured = users.with(context.userId(), user -> user.getAllGreets());
        List<LinkedSummoner> riotAccounts = users.with(context.userId(), user -> {
            List<LinkedSummoner> accounts = new ArrayList<>();

            for (Summoner summoner : user.getRiotAccounts().values())
                accounts.add(LinkedSummoner.from(summoner));

            return List.copyOf(accounts);
        });

        return new Me(auth.user(), this.auth.servers(auth), sounds.greets(context, configured), riotAccounts);
    }

    @PostMapping(LOL_ACCOUNT)
    @ResponseStatus(HttpStatus.CREATED)
    public LinkedSummoner linkAccount(@RequestAttribute(AuthFilter.AUTH_CONTEXT) AuthContext auth,
            @RequestBody(required = false) JsonNode body) {
        String userId = userId(auth);
        if (body == null || !body.isObject() || body.size() != (body.has("tracking") ? 3 : 2))
            throw Exception.invalidRequest("riotId and region are required, with optional tracking");

        String[] riotId = requiredText(body, "riotId").split("#", -1);
        if (riotId.length != 2 || riotId[0].isBlank() || riotId[1].isBlank())
            throw Exception.invalidRequest("Riot ID must have the form Name#Tag");

        LeagueShard shard = region(requiredText(body, "region"));
        boolean tracking = body.has("tracking") && tracking(body);

        Summoner target;
        try {
            target = SummonerService.getSummonerAsync(riotId[0].strip(), riotId[1].strip(), shard).join();
        } catch (CompletionException failure) {
            throw new Exception(Type.UNAVAILABLE, "riot_unavailable", "Cannot resolve the Riot profile", failure, null);
        }

        if (target == null)
            throw new Exception(Type.NOT_FOUND, "summoner_not_found", "Riot profile not found in this region");

        Summoner linked = users.with(userId, user -> {
            return user.addRiotAccount(target.puuid(), tracking);
        });

        if (linked == null)
            throw new Exception(Type.CONFLICT, "account_already_linked", "Riot profile is already linked");

        return LinkedSummoner.from(linked);
    }

    @PatchMapping(LOL_ACCOUNT)
    public LinkedSummoner trackAccount(@RequestAttribute(AuthFilter.AUTH_CONTEXT) AuthContext auth,
            @RequestBody(required = false) JsonNode body) {
        String userId = userId(auth);
        if (body == null || !body.isObject() || body.size() != 2)
            throw Exception.invalidRequest("puuid and tracking are required");

        String puuid = requiredText(body, "puuid");
        boolean tracking = tracking(body);

        Summoner updated = users.with(userId, user -> {
            return user.setRiotAccountTracking(puuid, tracking);
        });

        if (updated == null)
            throw accountNotLinked();

        return LinkedSummoner.from(updated);
    }

    @DeleteMapping(LOL_ACCOUNT)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlinkAccount(@RequestAttribute(AuthFilter.AUTH_CONTEXT) AuthContext auth,
            @RequestParam("puuid") String puuid) {
        String userId = userId(auth);
        if (puuid.isBlank())
            throw Exception.invalidRequest("puuid is required");

        boolean removed = users.with(userId, user -> {
            return user.deleteRiotAccount(puuid.strip());
        });

        if (!removed)
            throw accountNotLinked();
    }

    @PostMapping("/me/settings")
    public GreetSettings settings(@RequestAttribute(AuthFilter.AUTH_CONTEXT) AuthContext auth,
            @RequestBody(required = false) JsonNode body) {
        SoundCache.Context context = context(auth);
        if (body == null || !body.isObject() || body.size() != 1 || !body.path("greets").isObject())
            throw Exception.invalidRequest("greets object required");

        Set<String> requestedIds = new LinkedHashSet<>();
        for (JsonNode value : body.get("greets")) {
            if (value.isTextual() && ValidationUtils.isDecimalId(value.asText()))
                requestedIds.add(value.asText());
        }

        Map<String, QueryRecord> candidates = new HashMap<>();
        Exception preloadError = null;

        try {
            for (QueryRecord row : database.readSounds(requestedIds))
                candidates.put(row.get("id"), row);
        } catch (Exception exception) {
            preloadError = exception;
        }

        Map<String, GreetResult> results = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : body.get("greets").properties()) {
            String scope = entry.getKey();

            try {
                if (!scope.equals("0"))
                    sounds.requireGuild(context, scope);

                String id = null;
                if (!entry.getValue().isNull()) {
                    if (!entry.getValue().isTextual())
                        throw Exception.invalidRequest("Sound ID must be a string");

                    id = entry.getValue().asText();
                    if (!ValidationUtils.isDecimalId(id))
                        throw Exception.invalidRequest("Invalid ID");
                    if (preloadError != null)
                        throw preloadError;

                    QueryRecord candidate = candidates.get(id);
                    if (candidate == null || !BotDB.isSoundVisible(candidate, context.userId(), context.guilds().keySet()))
                        throw new Exception(Type.NOT_FOUND, "sound_not_found", "Sound not found");
                }

                String selected = id;
                users.with(context.userId(), user -> {
                    user.saveGreet(scope, selected);

                    return true;
                });

                results.put(scope, new GreetResult("ok", List.of()));
            } catch (Exception exception) {
                results.put(scope, new GreetResult("error", List.of(
                    new GreetError("soundId", exception.getCode(), exception.getMessage()))));
            } catch (IllegalStateException exception) {
                results.put(scope, new GreetResult("error", List.of(
                    new GreetError("soundId", "sound_storage_unavailable", "Sound storage is unavailable"))));
            }
        }

        return new GreetSettings(results);
    }

    /* -------------------------------------------------------------------------- */

    private SoundCache.Context context(AuthContext auth) {
        return sounds.context(userId(auth));
    }

    private static String userId(AuthContext auth) {
        if (auth == null || auth.user() == null)
            throw new Exception(Type.UNAUTHENTICATED, "invalid_session", "User session required");

        return auth.user().id();
    }

    private static String requiredText(JsonNode body, String field) {
        JsonNode value = body.path(field);
        if (!value.isTextual() || value.asText().isBlank())
            throw Exception.invalidRequest(field + " must be a non-empty string");

        return value.asText().strip();
    }

    private static LeagueShard region(String value) {
        String name = value.toUpperCase(Locale.ROOT);

        for (LeagueShard shard : LeagueShardUtils.getActives()) {
            if (shard.name().equals(name))
                return shard;
        }

        throw Exception.invalidRequest("Unsupported League of Legends region");
    }

    private static boolean tracking(JsonNode body) {
        JsonNode value = body.path("tracking");
        if (!value.isBoolean())
            throw Exception.invalidRequest("tracking must be a boolean");

        return value.asBoolean();
    }

    private static Exception accountNotLinked() {
        return new Exception(Type.NOT_FOUND, "account_not_linked", "Riot profile is not linked to your account");
    }
}
