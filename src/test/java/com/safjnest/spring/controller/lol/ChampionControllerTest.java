package com.safjnest.spring.controller.lol;

import java.util.List;

import org.junit.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import com.safjnest.lol.model.ApiResult;
import com.safjnest.lol.model.Filter;
import com.safjnest.lol.model.ResponseMetadata;
import com.safjnest.spring.exception.ApiErrors.LolApiError;

import no.stelar7.api.r4j.basic.constants.types.lol.GameQueueType;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

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
}
