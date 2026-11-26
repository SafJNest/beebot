package com.safjnest.lol.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

public record ResponseMetadata(
    Pagination pagination,
    Long lastUpdate,
    Boolean refresh,
    Filter filter,
    @JsonInclude(JsonInclude.Include.NON_NULL) List<String> missing,
    @JsonInclude(JsonInclude.Include.NON_NULL) List<Long> jobIds
) {

    public ResponseMetadata(Pagination pagination, Long lastUpdate, Boolean refresh, Filter filter) {
        this(pagination, lastUpdate, refresh, filter, null, null);
    }

    public static ResponseMetadata championPending(long lastUpdate, Filter filter, List<String> missing, List<Long> jobIds) {
        return new ResponseMetadata(null, lastUpdate > 0 ? lastUpdate : null, true, filter, missing, jobIds);
    }

    public static ResponseMetadata of(long lastUpdate, boolean refresh, Filter filter) {
        return new ResponseMetadata(null, lastUpdate > 0 ? lastUpdate : null, refresh, filter);
    }

    public static ResponseMetadata ready(Long lastUpdate, Filter filter) {
        return of(lastUpdate == null ? 0 : lastUpdate, false, filter);
    }

    public static ResponseMetadata pending(Long lastUpdate, Filter filter) {
        return of(lastUpdate == null ? 0 : lastUpdate, true, filter);
    }

    public record Pagination(
        Integer page,
        Integer pageSize,
        Integer limit,
        Integer offset,
        Long total,
        Long pages,
        Boolean hasMore
    ) {}
}
