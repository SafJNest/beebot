package com.safjnest.database.nosql;

import com.safjnest.lol.model.ChampionBuildSnapshot;
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
