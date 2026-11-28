package com.safjnest.database.nosql;

import com.safjnest.lol.model.ChampionBuildSnapshot;
import com.safjnest.lol.model.Build;
import com.safjnest.lol.model.Filter;

import no.stelar7.api.r4j.basic.constants.types.lol.GameQueueType;
import org.bson.Document;
import org.bson.RawBsonDocument;
import org.bson.codecs.DocumentCodec;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ChampionBuildSnapshotStorageTest {

    @Test
    public void emptySnapshotIsReady() {
        ChampionBuildSnapshot snapshot = roundTrip(emptySnapshot());

        assertTrue(snapshot.builds().isEmpty());
        assertNull(snapshot.defaultBuildId());
        assertNull(snapshot.bestAdjustedWinrateBuildId());
    }

    @Test
    public void storesOnlyTheRankedSnapshotForTheFilter() {
        ChampionBuildSnapshot snapshot = emptySnapshot();
        Document document = MongoDB.standardBuildDocument(snapshot);

        assertEquals(snapshot.filter().toKey(), document.getString("_id"));
        assertEquals(List.of("_id", "lastUpdate", "builds", "defaultBuildId",
            "bestAdjustedWinrateBuildId"), List.copyOf(document.keySet()));
        assertNull(MongoDB.readChampionBuildSnapshot(snapshot.filter(), null));
    }

    @Test(expected = com.safjnest.exception.Exception.class)
    public void rejectsANonemptyDocumentWithoutBuilds() {
        ChampionBuildSnapshot snapshot = emptySnapshot();
        Document document = MongoDB.standardBuildDocument(snapshot);
        document.remove("builds");

        MongoDB.readChampionBuildSnapshot(snapshot.filter(), document);
    }

    @Test
    public void restoresLegacyItemOptionsWithoutUnupgradedBoots() {
        Filter filter = emptySnapshot().filter();
        Build build = new Build(filter, List.of(), List.of(), List.of(), List.of(6672, 3124),
            List.of(), List.of(), List.of(), List.of(), List.of(), null, 120, 0.5);
        ChampionBuildSnapshot snapshot = new ChampionBuildSnapshot(filter, List.of(build), "6672-3124", null);
        Document document = MongoDB.standardBuildDocument(snapshot);
        Document variant = document.getList("builds", Document.class).getFirst();
        Document boots = new Document("id", "1001");
        Document magical = new Document("id", "2422");
        Document completed = new Document("id", "3006");
        variant.put("starters", List.of(boots, new Document("id", "1055")));
        variant.put("boots", List.of(boots, magical, completed));
        variant.put("roleBoundItems", List.of(boots, magical, completed));

        Build restored = MongoDB.readChampionBuildSnapshot(filter, document).defaultBuild();

        assertEquals(List.of(1055), restored.starter());
        assertEquals(List.of(3006), restored.bootOptions().stream().map(Build.Option::itemId).toList());
        assertEquals(List.of(3006), restored.roleBoundItemOptions().stream().map(Build.Option::itemId).toList());
    }

    public static ChampionBuildSnapshot roundTrip(ChampionBuildSnapshot snapshot) {
        DocumentCodec codec = new DocumentCodec();
        Document document = MongoDB.standardBuildDocument(snapshot);
        Document decoded = new RawBsonDocument(document, codec).decode(codec);
        ChampionBuildSnapshot restored = MongoDB.readChampionBuildSnapshot(snapshot.filter(), decoded);
        assertNotNull(restored);
        return restored;
    }

    private static ChampionBuildSnapshot emptySnapshot() {
        Filter filter = Filter.championBuild(101, "16.22", GameQueueType.TEAM_BUILDER_RANKED_SOLO);
        return new ChampionBuildSnapshot(filter, List.of(), null, null);
    }
}
