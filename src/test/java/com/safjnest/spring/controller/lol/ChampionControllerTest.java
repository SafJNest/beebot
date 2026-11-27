package com.safjnest.spring.controller.lol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
import com.safjnest.lol.model.ChampionView;
import com.safjnest.lol.model.Filter;
import com.safjnest.lol.model.ResponseMetadata;
import com.safjnest.spring.config.LolApiConfig;
import com.safjnest.spring.exception.ApiErrors.LolApiError;
import com.safjnest.spring.exception.ApiExceptionHandler;

import no.stelar7.api.r4j.basic.constants.types.lol.GameQueueType;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
        Build highest = variant(filter, List.of(4628, 6655), 100, 0.60);
        ChampionBuildSnapshot snapshot = new ChampionBuildSnapshot(filter, List.of(popular, highest),
            "6655-4628", "4628-6655");
        ChampionView page = new ChampionView(new ChampionView.Champion(101, "Xerath", null), null, snapshot, null);

        JsonNode body = readyBody(page);

        assertEquals(2, body.path("builds").size());
        assertEquals("6655-4628", body.at("/builds/0/coreBuilds/0/id").asText());
        assertEquals("4628-6655", body.at("/builds/1/coreBuilds/0/id").asText());
        assertEquals("6655-4628", body.path("defaultBuildId").asText());
        assertEquals("4628-6655", body.path("bestAdjustedWinrateBuildId").asText());
        assertFalse(body.has("build"));
        assertFalse(body.path("builds").get(0).has("filter"));
        assertSame(popular, snapshot.defaultBuild());
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
        ArenaBuildData arena = new ArenaBuildData(20, 8, List.of(),
            Collections.nCopies(ArenaBuildData.MAX_AUGMENTS, List.of()), List.of(), null);
        ChampionView page = new ChampionView(new ChampionView.Champion(101, "Xerath", null), null, null,
            Build.fromArena(filter, arena));

        JsonNode body = readyBody(page);

        assertEquals(20, body.at("/build/games").asInt());
        assertFalse(body.path("build").has("skillOrders"));
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
