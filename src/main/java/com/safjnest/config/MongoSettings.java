package com.safjnest.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public record MongoSettings(
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String uri,
        @JsonProperty("league_collection") String leagueCollection,
        @JsonProperty("league_test_collection") String leagueTestCollection) {

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    static MongoSettings fromLegacyUri(String uri) {
        return new MongoSettings(uri, null, null);
    }

    public String databaseName(boolean testing) {
        String name = testing ? leagueTestCollection : leagueCollection;
        if (name == null || name.isBlank()) {
            String field = testing ? "league_test_collection" : "league_collection";
            throw new IllegalStateException("Missing services.mongo." + field);
        }
        return name;
    }

    @Override
    public String toString() {
        return "MongoSettings[leagueCollection=" + leagueCollection + ", leagueTestCollection=" + leagueTestCollection + "]";
    }
}
