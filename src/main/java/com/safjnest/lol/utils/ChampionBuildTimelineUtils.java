package com.safjnest.lol.utils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

public final class ChampionBuildTimelineUtils {

    private static final int MAX_SKILL_SLOT = 4;
    private static final SkillEvents EMPTY_SKILLS = new SkillEvents(List.of(), List.of());

    private ChampionBuildTimelineUtils() {}

    public static SkillEvents skillEvents(Object rawEvents, String puuid) {
        if (puuid == null || puuid.isBlank()) {
            return EMPTY_SKILLS;
        }
        JSONObject events = json(rawEvents);
        if (events == null) {
            return EMPTY_SKILLS;
        }
        JSONObject refs = object(events, "participants");
        JSONArray skills = array(events, "skill_events");
        if (refs == null || skills == null) {
            return EMPTY_SKILLS;
        }

        List<SkillUpgrade> upgrades = new ArrayList<>();
        List<SkillUpgrade> evolutions = null;
        for (int i = 0; i < skills.length(); i++) {
            JSONObject skill = skills.optJSONObject(i);
            if (skill == null || !puuid.equals(resolve(skill.opt("participant"), refs))) {
                continue;
            }
            long timestamp = skill.optLong("timestamp", -1);
            int slot = skill.optInt("skill_slot", 0);
            if (timestamp < 0 || slot < 1 || slot > MAX_SKILL_SLOT) {
                continue;
            }
            SkillUpgrade upgrade = new SkillUpgrade(slot, timestamp);
            if ("EVOLVE".equals(skill.optString("level_up_type"))) {
                if (evolutions == null) {
                    evolutions = new ArrayList<>(4);
                }
                evolutions.add(upgrade);
            } else {
                upgrades.add(upgrade);
            }
        }
        upgrades.sort(Comparator.comparingLong(SkillUpgrade::timestampMillis));
        if (evolutions != null) {
            evolutions.sort(Comparator.comparingLong(SkillUpgrade::timestampMillis));
        }
        return new SkillEvents(upgrades, evolutions == null ? List.of() : evolutions);
    }

    public static List<ItemEvent> itemEvents(Object rawEvents, String puuid) {
        if (puuid == null || puuid.isBlank()) {
            return List.of();
        }
        JSONObject events = json(rawEvents);
        if (events == null) {
            return List.of();
        }
        JSONObject refs = object(events, "participants");
        JSONArray items = array(events, "item_events");
        if (refs == null || items == null) {
            return List.of();
        }

        List<JSONObject> ordered = new ArrayList<>();
        for (int index = 0; index < items.length(); index++) {
            JSONObject item = items.optJSONObject(index);
            if (item != null && item.optLong("timestamp", -1) >= 0
                    && puuid.equals(resolve(item.opt("participant"), refs))) {
                ordered.add(item);
            }
        }
        ordered.sort(Comparator.comparingLong(item -> item.optLong("timestamp", Long.MAX_VALUE)));

        List<ItemEvent> purchases = new ArrayList<>();
        for (JSONObject event : ordered) {
            String type = event.optString("event");
            int before = event.optInt("before");
            if ("ITEM_UNDO".equals(type) && before > 0) {
                for (int index = purchases.size() - 1; index >= 0; index--) {
                    if (purchases.get(index).itemId() == before) {
                        purchases.remove(index);
                        break;
                    }
                }
            } else if ("ITEM_PURCHASED".equals(type)) {
                int after = event.optInt("after");
                int itemId = after > 0 ? after : event.optInt("item");
                if (itemId > 0) {
                    long timestamp = event.getLong("timestamp");
                    purchases.add(new ItemEvent(itemId, timestamp));
                }
            }
        }
        return List.copyOf(purchases);
    }

    private static JSONObject json(Object raw) {
        if (raw instanceof JSONObject object) return object;
        if (raw instanceof String value && !value.isBlank()) {
            try { return new JSONObject(value); }
            catch (RuntimeException exception) { return null; }
        }
        if (raw instanceof Map<?, ?> map && !map.isEmpty()) {
            try { return new JSONObject(map); }
            catch (RuntimeException exception) { return null; }
        }
        return null;
    }

    private static JSONObject object(JSONObject source, String key) {
        JSONObject value = source.optJSONObject(key);
        if (value != null) return value;
        String raw = source.optString(key, "");
        if (raw.isBlank()) return null;
        try { return new JSONObject(raw); }
        catch (RuntimeException ignored) { return null; }
    }

    private static JSONArray array(JSONObject source, String key) {
        JSONArray value = source.optJSONArray(key);
        if (value != null) return value;
        String raw = source.optString(key, "");
        if (raw.isBlank()) return null;
        try { return new JSONArray(raw); }
        catch (RuntimeException ignored) { return null; }
    }

    private static String resolve(Object id, JSONObject refs) {
        if (id == null || id == JSONObject.NULL) return null;
        String key = String.valueOf(id);
        return refs == null ? key : refs.optString(key, null);
    }

    public record SkillUpgrade(int skillSlot, long timestampMillis) {}

    public record SkillEvents(List<SkillUpgrade> upgrades, List<SkillUpgrade> evolutions) {}

    public record ItemEvent(int itemId, long timestampMillis) {}

}
