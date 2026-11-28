package com.safjnest.spring.controller.lol;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.safjnest.lol.model.ApiResult;
import com.safjnest.lol.service.ChampionService;
import com.safjnest.lol.utils.GameQueueTypeUtils;
import com.safjnest.lol.utils.LeagueShardUtils;

import no.stelar7.api.r4j.basic.constants.api.regions.LeagueShard;
import no.stelar7.api.r4j.basic.constants.types.lol.GameQueueType;
import no.stelar7.api.r4j.basic.constants.types.lol.LaneType;
import no.stelar7.api.r4j.basic.constants.types.lol.TierType;

@RestController
@RequestMapping("/api/lol")
public class ChampionController {

    private final ChampionService championService;

    public ChampionController() {
        this.championService = new ChampionService();
    }

    @GetMapping("/champion/indexables")
    public ResponseEntity<?> indexables() {
        return ResponseEntity.ok(championService.getIndexables());
    }

    @GetMapping("/champions/tier-list")
    public ResponseEntity<?> tierList(
            @RequestParam(value = "patch", required = false) String patchValue,
            @RequestParam(value = "rank", required = false) String rankValue,
            @RequestParam(value = "region", required = false) String regionValue,
            @RequestParam(value = "queue", required = false) String queueValue
    ) {
        ApiResult<?> result = championService.getTierList(
            LolApiParameters.patch(patchValue),
            LolApiParameters.rank(rankValue),
            LolApiParameters.region(regionValue),
            LolApiParameters.queue(queueValue)
        );
        return LolApiResponses.from(
            result,
            "champion_tier_list_pending",
            "Champion tier list is being prepared",
            "Champion tier list not found"
        );
    }

    @GetMapping("/champion/{champion}")
    public ResponseEntity<?> champion(
            @PathVariable("champion") String championValue,
            @RequestParam(value = "patch", required = false) String patchValue,
            @RequestParam(value = "rank", required = false) String rankValue,
            @RequestParam(value = "region", required = false) String regionValue,
            @RequestParam(value = "queue", required = false) String queueValue,
            @RequestParam(value = "role", required = false) String roleValue,
            @RequestParam(value = "lane", required = false) String laneValue
    ) {
        GameQueueType queue = queueValue == null || queueValue.isBlank() ? null : LolApiParameters.queue(queueValue);
        TierType rank = LolApiParameters.rank(rankValue);
        LeagueShard region = LolApiParameters.region(regionValue);
        LaneType role = LolApiParameters.role(roleValue);
        LaneType lane = LolApiParameters.role(laneValue);
        if (role != null && lane != null && role != lane)
            throw LolApiParameters.invalid("role", "conflicts with lane");
        if (role == null) role = lane;
        LolApiParameters.validateRole(queue, role);
        if (queue != null && !GameQueueTypeUtils.championBuildQueues().contains(queue))
            throw LolApiParameters.invalid("queue", "is not supported for champion builds");
        if (rank == TierType.UNRANKED)
            throw LolApiParameters.invalid("rank", "omit rank for all ranks");
        if (region != null && !LeagueShardUtils.getActives().contains(region))
            throw LolApiParameters.invalid("region", "is not supported for champion statistics");

        String patch = LolApiParameters.patch(patchValue);
        if (patch != null && !patch.matches("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"))
            throw LolApiParameters.invalid("patch", "use major.minor without leading zeroes");
        ApiResult<?> result = championService.get(championValue, patch, rank, region, queue, role);
        return LolApiResponses.from(
            result,
            "champion_data_pending",
            "Champion data is being prepared",
            "Champion not found"
        );
    }
}
