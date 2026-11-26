package com.safjnest.lol.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

public final class ChampionBuildTimelineUtils {

    private static final int SKILL_ORDER_LENGTH = 18;
    private static final int MAX_SKILL_SLOT = 4;

    private ChampionBuildTimelineUtils() {}

    public static List<Integer> skillOrder(JSONArray fallback, List<SkillUpgrade> upgrades) {
        List<Integer> fallbackOrder = new ArrayList<>();
        if (fallback != null) {
            StringBuilder rawSkillOrder = new StringBuilder();
            for (int index = 0; index < fallback.length(); index++)
                rawSkillOrder.append(fallback.optString(index, ""));
            for (int index = 0; index < rawSkillOrder.length(); index++) {
                int slot = Character.getNumericValue(rawSkillOrder.charAt(index));
                fallbackOrder.add(slot >= 1 && slot <= MAX_SKILL_SLOT ? slot : 0);
            }
        }
        return skillOrder(fallbackOrder, upgrades);
    }

    public static List<Integer> skillOrder(List<Integer> fallback, List<SkillUpgrade> upgrades) {
        List<Integer> result = new ArrayList<>(Collections.nCopies(SKILL_ORDER_LENGTH, 0));
        if (upgrades != null && !upgrades.isEmpty()) {
            int fallbackLevel = 1;
            for (SkillUpgrade upgrade : upgrades) {
                int level = upgrade.championLevel() == null ? fallbackLevel : upgrade.championLevel();
                fallbackLevel = Math.max(fallbackLevel + 1, level + 1);
                if (level >= 1 && level <= result.size()
                        && upgrade.skillSlot() >= 1 && upgrade.skillSlot() <= MAX_SKILL_SLOT)
                    result.set(level - 1, upgrade.skillSlot());
            }
            return result;
        }
        if (fallback == null) return result;
        for (int index = 0; index < Math.min(result.size(), fallback.size()); index++) {
            Integer slot = fallback.get(index);
            if (slot != null && slot >= 1 && slot <= MAX_SKILL_SLOT) result.set(index, slot);
        }
        return result;
    }

    public static List<SkillUpgrade> skillUpgrades(Object rawEvents, String puuid) {
        if (puuid == null || puuid.isBlank()) return List.of();
        JSONObject events = json(rawEvents);
        if (events == null) return List.of();
        JSONObject refs = object(events, "participants");
        JSONArray skills = array(events, "skill_events");
        if (refs == null || skills == null) return List.of();

        List<LevelEvent> levels = new ArrayList<>();
        JSONArray levelEvents = array(events, "level_events");
        if (levelEvents != null) for (int i = 0; i < levelEvents.length(); i++) {
            JSONObject level = levelEvents.optJSONObject(i);
            if (level == null || !puuid.equals(resolve(level.opt("participant"), refs))) continue;
            long timestamp = level.optLong("timestamp", -1);
            int value = level.optInt("level", 0);
            if (timestamp >= 0 && value > 0) levels.add(new LevelEvent(timestamp, value));
        }
        levels.sort(Comparator.comparingLong(LevelEvent::timestampMillis));

        List<SkillUpgrade> result = new ArrayList<>();
        for (int i = 0; i < skills.length(); i++) {
            JSONObject skill = skills.optJSONObject(i);
            if (skill == null || !puuid.equals(resolve(skill.opt("participant"), refs))) continue;
            long timestamp = skill.optLong("timestamp", -1);
            int slot = skill.optInt("skill_slot", 0);
            if (timestamp < 0 || slot < 1 || slot > 4) continue;
            Integer championLevel = null;
            for (LevelEvent level : levels) {
                if (level.timestampMillis() > timestamp) break;
                championLevel = level.level();
            }
            result.add(new SkillUpgrade(championLevel, slot, timestamp));
        }
        result.sort(Comparator.comparingLong(SkillUpgrade::timestampMillis));
        return List.copyOf(result);
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

    public record SkillUpgrade(Integer championLevel, int skillSlot, long timestampMillis) {}

    public record ItemEvent(int itemId, long timestampMillis) {}

    private record LevelEvent(long timestampMillis, int level) {}
}
