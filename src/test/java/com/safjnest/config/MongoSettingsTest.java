package com.safjnest.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class MongoSettingsTest {

    @Rule
    public TemporaryFolder directory = new TemporaryFolder();

    @Test
    public void selectsTheConfiguredLeagueDatabaseForEachMode() throws IOException {
        MongoSettings mongo = load("""
              mongo:
                uri: mongodb://localhost
                league_collection: league_prod
                league_test_collection: league_test
            """);

        assertEquals("mongodb://localhost", mongo.uri());
        assertEquals("league_prod", mongo.databaseName(false));
        assertEquals("league_test", mongo.databaseName(true));
    }

    @Test
    public void missingTestDatabaseNeverFallsBackToProduction() throws IOException {
        MongoSettings mongo = load("""
              mongo:
                uri: mongodb://localhost
                league_collection: league_prod
            """);

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> mongo.databaseName(true));
        assertEquals("Missing services.mongo.league_test_collection", failure.getMessage());
    }

    @Test
    public void supportsReadingTheLegacyScalarUri() throws IOException {
        MongoSettings mongo = load("  mongo: mongodb://localhost\n");

        assertEquals("mongodb://localhost", mongo.uri());
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> mongo.databaseName(false));
        assertEquals("Missing services.mongo.league_collection", failure.getMessage());
    }

    private MongoSettings load(String mongo) throws IOException {
        Path root = directory.getRoot().toPath();
        Files.writeString(root.resolve("beebot.yml"), """
            runtime:
              bot: beebot
            bot:
              beebot:
                discordToken: sample-token
            services:
              database:
                host: localhost
                databaseName: bot
                username: beebot
            """ + mongo);

        return SettingsLoader.load(root, Map.of()).services().getMongo();
    }
}
