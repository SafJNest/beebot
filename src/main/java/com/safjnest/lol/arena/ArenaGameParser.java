package com.safjnest.lol.arena;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.safjnest.lol.arena.ParsedArenaGame.Augment;
import com.safjnest.lol.model.Build.Kind;
import com.safjnest.lol.model.ArenaBuildData;
import com.safjnest.lol.champion.ChampionBuildProvider;
import com.safjnest.lol.champion.ChampionStatsProvider;
import com.safjnest.lol.utils.ChampionBuildTimelineUtils;
import com.safjnest.lol.utils.ChampionBuildTimelineUtils.ItemEvent;
import com.safjnest.lol.utils.ChampionBuildTimelineUtils.SkillEvents;
import it.unimi.dsi.fastutil.ints.Int2LongLinkedOpenHashMap;
import org.bson.Document;
import org.json.JSONArray;
import org.json.JSONObject;

public final class ArenaGameParser {

    private static final Set<String> ITEM_EVENTS = Set.of(
        "ITEM_PURCHASED", "ITEM_SOLD", "ITEM_UNDO", "ITEM_DESTROYED");
    private static final List<String> INVENTORY_SLOTS = List.of("item0", "item1", "item2", "item3", "item4", "item5");
    private static final long UNKNOWN_TIME = -1;

    private ArenaGameParser() {}

    public static ParsedArenaGame parse(String patch, JSONObject timeline, Document participant, ArenaItemCatalog catalog) {
        int participantId = participant.getInteger("id", 0);
        String puuid = participant.getString("puuid");
        List<Event> events = readEvents(timeline, participantId, puuid);
        Int2LongLinkedOpenHashMap items = new Int2LongLinkedOpenHashMap(6);
        Int2LongLinkedOpenHashMap boots = new Int2LongLinkedOpenHashMap(2);
        Int2LongLinkedOpenHashMap prismatics = new Int2LongLinkedOpenHashMap(4);

        for (Event event : acquisitions(events)) {
            int id = event.acquiredId();
            switch (catalog.kind(patch, id)) {
                case ITEM -> items.putIfAbsent(id, event.timestamp());
                case BOOTS -> boots.putIfAbsent(id, event.timestamp());
                case PRISMATIC -> prismatics.putIfAbsent(id, event.timestamp());
                case null, default -> {}
            }
        }
        int orderedItemCount = items.size();
        int firstPrismatic = prismatics.isEmpty() ? 0 : prismatics.firstIntKey();
        int selectedBoots = participant.getInteger("boots", 0);
        int finalBoots = 0;
        boolean multipleFinalBoots = false;
        List<Integer> fallbackBuild = orderedItemCount == 0 ? new ArrayList<>(6) : List.of();

        for (String slot : INVENTORY_SLOTS) {
            int id = participant.getInteger(slot, 0);
            switch (catalog.kind(patch, id)) {
                case ITEM -> {
                    items.putIfAbsent(id, UNKNOWN_TIME);
                    if (orderedItemCount == 0) {
                        fallbackBuild.add(id);
                    }
                }
                case BOOTS -> {
                    multipleFinalBoots |= finalBoots != 0 && finalBoots != id;
                    finalBoots = id;
                }
                case PRISMATIC -> prismatics.putIfAbsent(id, UNKNOWN_TIME);
                case null, default -> {}
            }
        }

        if (catalog.kind(patch, selectedBoots) != Kind.BOOTS) {
            selectedBoots = finalBoots != 0 && !multipleFinalBoots ? finalBoots
                : boots.size() == 1 ? boots.firstIntKey() : 0;
        }
        boolean prismaticFallback = firstPrismatic == 0 && !prismatics.isEmpty();
        if (prismaticFallback) {
            firstPrismatic = prismatics.firstIntKey();
        }
        ItemEvent boot = selectedBoots == 0 ? null
            : new ItemEvent(selectedBoots, boots.getOrDefault(selectedBoots, UNKNOWN_TIME));
        ItemEvent prismatic = firstPrismatic == 0 ? null
            : new ItemEvent(firstPrismatic, prismatics.get(firstPrismatic));
        if (orderedItemCount == 0) {
            fallbackBuild.sort(Integer::compareTo);
        }

        List<Augment> augments = new ArrayList<>(ArenaBuildData.MAX_AUGMENTS);
        List<Integer> storedAugments = participant.getList("augments", Integer.class, List.of());
        for (int index = 0; index < Math.min(storedAugments.size(), ArenaBuildData.MAX_AUGMENTS); index++) {
            Integer id = storedAugments.get(index);
            if (id != null && id > 0) {
                augments.add(new Augment(id, index + 1));
            }
        }
        SkillEvents skills = ChampionBuildTimelineUtils.skillEvents(timeline, puuid);
        List<Integer> fallbackSkills = skills.upgrades().isEmpty() ? ChampionBuildProvider.skillOrder(participant) : List.of();

        return new ParsedArenaGame(ChampionStatsProvider.win(participant), participant.getInteger("subTeamPlacement", 0),
            boot, prismatic, prismaticFallback, itemValues(prismatics), itemValues(items), orderedItemCount,
            fallbackBuild, augments, !events.isEmpty(), fallbackSkills, skills);
    }

    private static List<ItemEvent> itemValues(Int2LongLinkedOpenHashMap source) {
        List<ItemEvent> events = new ArrayList<>(source.size());
        for (var entry : source.int2LongEntrySet()) {
            events.add(new ItemEvent(entry.getIntKey(), entry.getLongValue()));
        }
        return events;
    }

    // ============================================================================

    private static List<Event> readEvents(JSONObject root, int participantId, String puuid) {
        JSONArray items = array(root, "item_events");
        if (items == null) return List.of();
        JSONObject references = root.optJSONObject("participants");
        if (!validReferences(references, participantId, puuid)) return List.of();

        List<Event> events = new ArrayList<>();
        Set<EventIdentity> seen = new HashSet<>();
        for (int index = 0; index < items.length(); index++) {
            JSONObject source = items.optJSONObject(index);
            if (source == null) continue;
            Integer actor = integer(source.opt("participant"));
            Integer actorId = integer(source.opt("participantId"));
            if (actor == null || actor <= 0 || actor != participantId
                    || source.has("participantId") && !Objects.equals(actor, actorId)) continue;

            String type = source.optString("event");
            Integer item = integer(source.opt("item"));
            Integer before = integer(source.opt("before"));
            Integer after = integer(source.opt("after"));
            Long timestamp = time(source.opt("timestamp"));
            if (!ITEM_EVENTS.contains(type) || item == null || before == null || after == null
                    || item < 0 || before < 0 || after < 0 || timestamp == null) continue;

            Event event = new Event(index, type, item, before, after, timestamp);
            if (seen.add(new EventIdentity(type, item, before, after, timestamp))) events.add(event);
        }
        events.sort(Comparator.comparingLong(Event::timestamp).thenComparingInt(Event::index));
        return events;
    }

    private static List<Event> acquisitions(List<Event> events) {
        List<Event> result = new ArrayList<>();
        Event previous = null;
        for (Event event : events) {
            if ("ITEM_PURCHASED".equals(event.type()) && event.acquiredId() > 0) {
                result.add(event);
            } else if ("ITEM_UNDO".equals(event.type()) && previous != null
                    && "ITEM_PURCHASED".equals(previous.type()) && event.identifies(previous)) {
                result.remove(previous);
            }
            previous = event;
        }
        return result;
    }

    private static boolean validReferences(JSONObject references, int participantId, String puuid) {
        if (references == null) return true;
        String id = Integer.toString(participantId);
        if (participantId <= 0 || puuid == null || puuid.isBlank()
                || !puuid.equals(references.optString(id))) return false;
        for (String key : references.keySet()) if (!key.equals(id)
                && !"0".equals(key) && puuid.equals(references.optString(key))) return false;
        return true;
    }

    private static Integer integer(Object raw) {
        if (!(raw instanceof Number number)) return null;
        long value = number.longValue();
        return value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE
            && number.doubleValue() == value ? (int) value : null;
    }

    private static Long time(Object raw) {
        if (!(raw instanceof Number number)) return null;
        long value = number.longValue();
        return value >= 0 && number.doubleValue() == value ? value : null;
    }

    private static JSONArray array(JSONObject root, String key) {
        if (root == null) return null;
        Object value = root.opt(key);
        if (value instanceof JSONArray array) return array;
        if (value instanceof String text) try { return new JSONArray(text); }
        catch (RuntimeException ignored) { return null; }
        return null;
    }

    private record EventIdentity(String type, int item, int before, int after, long timestamp) {}
    private record Event(int index, String type, int item, int before, int after, long timestamp) {
        private int acquiredId() { return after > 0 ? after : item; }
        private boolean involves(int id) { return item == id || before == id || after == id; }
        private boolean identifies(Event target) {
            Set<Integer> ids = new HashSet<>();
            if (item > 0) ids.add(item);
            if (before > 0) ids.add(before);
            if (after > 0) ids.add(after);
            if (ids.isEmpty()) return false;
            for (int id : ids) if (!target.involves(id)) return false;
            return true;
        }
    }
}
