package com.safjnest.database.nosql;

import com.safjnest.lol.model.Build;
import com.safjnest.lol.model.ChampionBuildSnapshot;
import com.safjnest.lol.model.Filter;

import no.stelar7.api.r4j.basic.constants.types.lol.GameQueueType;
import org.bson.Document;
import org.bson.RawBsonDocument;
import org.bson.codecs.DocumentCodec;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ChampionBuildSnapshotStorageTest {

    @Test
    public void emptySnapshotIsReady() {
        ChampionBuildSnapshot snapshot = roundTrip(emptySnapshot());

        assertEquals(0, snapshot.aggregate().games());
        assertTrue(snapshot.builds().isEmpty());
        assertNull(snapshot.defaultBuildId());
        assertNull(snapshot.bestAdjustedWinrateBuildId());
    }

    @Test
    public void legacyAggregateIsReadableAndNeedsARefresh() {
        ChampionBuildSnapshot snapshot = emptySnapshot();
        Filter filter = snapshot.aggregate().filter();
        Document document = storedDocument(snapshot);
        long updatedAt = document.getLong("lastUpdate");
        document.put("lastUpdate", updatedAt + 1);

        assertNull(MongoDB.readChampionBuildSnapshot(filter, document));
        document.put("lastUpdate", updatedAt);
        document.get("buildSnapshot", Document.class).put("version", 0);
        assertNull(MongoDB.readChampionBuildSnapshot(filter, document));
        document.remove("buildSnapshot");
        assertNull(MongoDB.readChampionBuildSnapshot(filter, document));
        assertEquals(0, MongoDB.readStandardChampionBuild(filter, document).games());
    }

    @Test(expected = com.safjnest.exception.Exception.class)
    public void rejectsAnIncompleteCurrentSnapshot() {
        ChampionBuildSnapshot snapshot = emptySnapshot();
        Document document = storedDocument(snapshot);
        document.get("buildSnapshot", Document.class).remove("builds");

        MongoDB.readChampionBuildSnapshot(snapshot.aggregate().filter(), document);
    }

    public static ChampionBuildSnapshot roundTrip(ChampionBuildSnapshot snapshot) {
        Filter filter = snapshot.aggregate().filter();
        ChampionBuildSnapshot restored = MongoDB.readChampionBuildSnapshot(filter, storedDocument(snapshot));
        assertNotNull(restored);
        return restored;
    }

    private static Document storedDocument(ChampionBuildSnapshot snapshot) {
        DocumentCodec codec = new DocumentCodec();
        Document update = MongoDB.standardBuildUpdate(snapshot);
        Document decoded = new RawBsonDocument(update, codec).decode(codec);
        Document result = new Document(decoded.get("$setOnInsert", Document.class));
        Document aggregate = new Document();
        for (Map.Entry<String, Object> field : decoded.get("$set", Document.class).entrySet()) {
            String key = field.getKey();
            if (key.startsWith("build.")) {
                aggregate.put(key.substring("build.".length()), field.getValue());
            } else {
                result.put(key, field.getValue());
            }
        }
        return result.append("build", aggregate);
    }

    private static ChampionBuildSnapshot emptySnapshot() {
        Filter filter = Filter.championBuild(101, "16.22", GameQueueType.TEAM_BUILDER_RANKED_SOLO);
        Build aggregate = new Build(filter, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of(), null, 0, 0);
        return new ChampionBuildSnapshot(aggregate, List.of(), null, null);
    }
}
