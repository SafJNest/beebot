package com.safjnest.spring.controller.lol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safjnest.exception.Exception;
import com.safjnest.lol.model.ApiResult;
import com.safjnest.lol.model.ArenaBuildData;
import com.safjnest.lol.model.Build;
import com.safjnest.lol.model.ChampionBuildSnapshot;
import com.safjnest.lol.model.ChampionStatistics;
import com.safjnest.lol.model.ChampionView;
import com.safjnest.lol.model.Filter;
import com.safjnest.lol.model.ResponseMetadata;
import com.safjnest.spring.config.LolApiConfig;
import com.safjnest.spring.exception.ApiErrors.LolApiError;
import com.safjnest.spring.exception.ApiExceptionHandler;

import no.stelar7.api.r4j.basic.constants.types.lol.GameQueueType;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ChampionControllerTest {

    @Test
    public void rejectsScopesThatTheMatrixCannotGenerate() {
        ChampionController controller = new ChampionController();
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
            () -> controller.champion("Jinx", "16.12", "UNRANKED", null, null, null, null)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
            () -> controller.champion("Jinx", "16.12", null, "PH2", null, null, null)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
            () -> controller.champion("Jinx", "16.12", null, null, null, "MID", "BOT")).getStatusCode());
    }

    @Test
    public void pendingKeepsTheMissingComponentsAndWorkerPid() {
        Filter filter = Filter.championBuild(222, "16.12", GameQueueType.TEAM_BUILDER_RANKED_SOLO);
        ResponseMetadata metadata = ResponseMetadata.championPending(0, filter, List.of("build"), List.of(17L));
        ResponseEntity<?> response = LolApiResponses.from(ApiResult.pending(metadata), "champion_data_pending",
            "Champion data is being prepared", "Champion not found");

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        LolApiError error = (LolApiError) response.getBody();
        assertEquals(List.of("build"), error.metadata().missing());
        assertEquals(List.of(17L), error.metadata().jobIds());
        assertEquals(filter.toKey(), error.metadata().filter().toKey());
    }

    @Test
    public void readyStandardPageSerializesAllVariantsAndSelectionIds() {
        Filter filter = Filter.championBuild(101, "16.22", GameQueueType.TEAM_BUILDER_RANKED_SOLO);
        Build popular = variant(filter, List.of(6655, 4628), 200, 0.51);
        popular = Build.fromJson(new JSONObject(popular.toJson()).put("name", "AP")
            .put("badges", List.of("MOST_POPULAR")).toString());
        Build highest = variant(filter, List.of(4628, 6655), 100, 0.60);
        ChampionBuildSnapshot snapshot = new ChampionBuildSnapshot(filter, List.of(popular, highest),
            "6655-4628", "4628-6655");
        ChampionView page = new ChampionView(new ChampionView.Champion(101, "Xerath", null), null, snapshot, null);

        JsonNode body = readyBody(page);

        assertEquals(2, body.path("builds").size());
        assertEquals("6655-4628", body.at("/builds/0/coreBuilds/0/id").asText());
        assertEquals("AP", body.at("/builds/0/name").asText());
        assertEquals("MOST_POPULAR", body.at("/builds/0/badges/0").asText());
        assertEquals("4628-6655", body.at("/builds/1/coreBuilds/0/id").asText());
        assertFalse(body.at("/builds/1").has("name"));
        assertEquals("6655-4628", body.path("defaultBuildId").asText());
        assertEquals("4628-6655", body.path("bestAdjustedWinrateBuildId").asText());
        assertFalse(body.has("build"));
        assertFalse(body.path("builds").get(0).has("filter"));
        assertSame(popular, snapshot.defaultBuild());
    }

    @Test
    public void storedBuildReturnsOneSkillOrderAndOnlySummonerSpellsHavePairFields() {
        Filter filter = Filter.championBuild(145, "16.26", GameQueueType.TEAM_BUILDER_RANKED_SOLO);
        Build original = new Build(filter, List.of(), List.of(), List.of(new Build.SlotOption(3102, 6, 2 / 6d)),
            List.of(6672, 3124), List.of(), List.of(), List.of(new Build.SlotOption(407, 200, 0.51)),
            List.of(), List.of(1, 3, 2, 1), null, 200, 0.51);
        JSONObject stored = new JSONObject(original.toJson());
        JSONObject order = stored.getJSONArray("skillOrders").getJSONObject(0).put("id", "1-3-2-1").put("matches", 50);
        order.put("timedOrder", new JSONArray("""
            [{"level":1,"abilitySlot":1,"averageUpgradeTimeSeconds":34.5,"timedMatches":5},
             {"level":2,"championLevel":1,"abilitySlot":3,"averageUpgradeTimeSeconds":35,"timedMatches":3}]
            """));
        stored.getJSONArray("skillOrders").put(new JSONObject(order.toString()).put("order", List.of(1, 2, 3, 1)));
        Build restored = Build.fromJson(stored.toString());
        assertNotNull(restored);
        ChampionBuildSnapshot snapshot = new ChampionBuildSnapshot(filter, List.of(restored), "6672-3124", null);
        ChampionView page = new ChampionView(new ChampionView.Champion(145, "Kai'Sa", null), null, snapshot, null);

        JsonNode build = readyBody(page).at("/builds/0");

        assertEquals(1, build.path("skillOrders").size());
        assertEquals(3, build.at("/skillOrders/0/order/1").asInt());
        assertEquals(2, build.at("/skillOrders/0").size());
        JsonNode firstPoint = build.at("/skillOrders/0/timedOrder/0");
        assertEquals(1, firstPoint.path("skill").asInt());
        assertFalse(firstPoint.has("level"));
        JsonNode secondPoint = build.at("/skillOrders/0/timedOrder/1");
        assertEquals(2, secondPoint.path("skill").asInt());
        assertEquals(1, secondPoint.path("level").asInt());
        assertEquals(3, secondPoint.path("abilitySlot").asInt());
        assertEquals(35d, secondPoint.path("averageUpgradeTimeSeconds").asDouble(), 0d);
        assertEquals(3, secondPoint.path("timedMatches").asInt());
        assertFalse(secondPoint.has("championLevel"));
        assertFalse(build.has("skillEvolutions"));
        JsonNode supportItem = build.at("/supportItems/0");
        assertEquals("3102", supportItem.path("id").asText());
        assertFalse(supportItem.has("spell1"));
        assertFalse(supportItem.has("spell2"));
        assertEquals(4, build.at("/summonerSpells/0/spell1").asInt());
        assertEquals(7, build.at("/summonerSpells/0/spell2").asInt());
    }

    @Test
    public void completedEmptySnapshotReturnsAnEmptyArray() {
        Filter filter = Filter.championBuild(101, "16.22", GameQueueType.TEAM_BUILDER_RANKED_SOLO);
        ChampionBuildSnapshot snapshot = new ChampionBuildSnapshot(filter, List.of(), null, null);
        ChampionView page = new ChampionView(new ChampionView.Champion(101, "Xerath", null), null, snapshot, null);

        JsonNode body = readyBody(page);

        assertTrue(body.path("builds").isArray());
        assertEquals(0, body.path("builds").size());
        assertFalse(body.has("defaultBuildId"));
        assertFalse(body.has("bestAdjustedWinrateBuildId"));
        assertEquals(0, snapshot.defaultBuild().games());
    }

    @Test
    public void arenaKeepsItsDedicatedBuildPayload() {
        Filter filter = Filter.championBuild(101, "16.22", GameQueueType.CHERRY);
        ArenaBuildData.Option item = new ArenaBuildData.Option(223072, 5, 2, 0.25, 3.8, 0.2, 390d, 3L);
        ArenaBuildData.Option boot = new ArenaBuildData.Option(223006, 4, 2, 0.2, 3d, 0.25, 140d, 4L);
        Build.SkillOrderOption skill = new Build.SkillOrderOption(List.of(1),
            List.of(new Build.SkillTiming(3, 1, 1, 5, 1)));
        ArenaBuildData.Core core = new ArenaBuildData.Core("223006-446656", List.of(223006, 446656), 4, 2,
            0.2, List.of(), Collections.nCopies(ArenaBuildData.MAX_AUGMENTS, List.of()), List.of(), 3d, 0.25,
            List.of(item), List.of(), boot, null, null, List.of(skill), null,
            "Everfrost", List.of(Build.Badge.MOST_POPULAR));
        ArenaBuildData arena = new ArenaBuildData(20, 8, List.of(core),
            Collections.nCopies(ArenaBuildData.MAX_AUGMENTS, List.of()), List.of(), null, null,
            List.of(item), List.of(boot), 4.5, 0.1, List.of(skill), null);
        ChampionStatistics.Overview overview = new ChampionStatistics.Overview(
            100, 20, 0, 8, 0.4, 0.2, null, null, null, null, null);
        ChampionStatistics stats = new ChampionStatistics(filter, overview, List.of(), Map.of(), List.of(),
            List.of(), null, List.of(new ChampionStatistics.ArenaSynergy(List.of(35, 45), 3, 2, 2 / 3d)),
            Map.of(), Map.of(), List.of(new ChampionStatistics.ArenaAllySynergy(35, 3, 2, 2 / 3d, 0.15),
                new ChampionStatistics.ArenaAllySynergy(45, 3, 2, 2 / 3d, 0.15)));
        ChampionView page = new ChampionView(new ChampionView.Champion(101, "Xerath", null), stats, null,
            Build.fromArena(filter, arena));

        JsonNode body = readyBody(page);

        assertEquals(20, body.at("/build/games").asInt());
        assertEquals(8, body.at("/build/wins").asInt());
        assertEquals(0.2, body.at("/build/items/0/firstPlaceRate").asDouble(), 0);
        assertEquals(223006, body.at("/build/boots/0/id").asInt());
        assertEquals("Everfrost", body.at("/build/coreBuilds/0/name").asText());
        assertEquals("MOST_POPULAR", body.at("/build/coreBuilds/0/badges/0").asText());
        assertEquals(3, body.at("/build/skillOrders/0/timedOrder/0/level").asInt());
        assertEquals(1, body.at("/stats/synergies").size());
        assertEquals(2, body.at("/stats/allySynergies").size());
        assertEquals(35, body.at("/stats/allySynergies/0/allyChampion").asInt());
        assertFalse(body.path("build").has("arena"));
        assertFalse(body.has("builds"));
    }

    @Test
    public void invalidStoredBuildUsesTheErrorPath() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/lol/champion/Xerath");
        Exception failure = new Exception(Exception.Type.INVALID_UPSTREAM_RESPONSE, "champion_build_invalid", "Invalid build payload");

        ResponseEntity<?> response = new ApiExceptionHandler().application(failure, request);

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
    }

    private static JsonNode readyBody(ChampionView page) {
        ResponseEntity<?> response = LolApiResponses.from(ApiResult.ready(page, page.metadata()),
            "champion_data_pending", "Champion data is being prepared", "Champion not found");
        assertEquals(HttpStatus.OK, response.getStatusCode());

        List<HttpMessageConverter<?>> converters = new ArrayList<>();
        new LolApiConfig().configureMessageConverters(converters);
        ObjectMapper mapper = ((MappingJackson2HttpMessageConverter) converters.getFirst()).getObjectMapper();
        return mapper.valueToTree(response.getBody());
    }

    private static Build variant(Filter filter, List<Integer> core, int games, double winrate) {
        return new Build(filter, List.of(), List.of(), List.of(), core, List.of(), List.of(), List.of(),
            List.of(), List.of(), null, games, winrate);
    }
}
