package com.safjnest.lol.arena;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.safjnest.lol.model.Build.Kind;
import com.safjnest.lol.utils.ItemUtils;
import no.stelar7.api.r4j.pojo.lol.staticdata.item.Item;

public final class ArenaItemCatalog {

    private static final int FIRST_ANVIL_ITEM = 220000;
    private static final int LAST_ANVIL_ITEM = 220011;
    private static final int MIN_COMPLETED_ITEM_COST = 1200;

    private final Map<Integer, Kind> kinds;
    private final Set<Integer> consumables;
    private final Set<Integer> juices;
    private final Map<String, Map<Integer, Classification>> classifications;
    private final Map<Integer, Item> items;

    public ArenaItemCatalog(Map<Integer, Kind> kinds) {
        this(kinds, Set.of(), Set.of(), Map.of());
    }

    public ArenaItemCatalog(Map<Integer, Kind> kinds,
            Map<String, Map<Integer, Classification>> classifications) {
        this(kinds, Set.of(), Set.of(), classifications);
    }

    public ArenaItemCatalog(Map<Integer, Kind> kinds, Set<Integer> consumables,
            Map<String, Map<Integer, Classification>> classifications) {
        this(kinds, consumables, Set.of(), classifications);
    }

    public ArenaItemCatalog(Map<Integer, Kind> kinds, Set<Integer> consumables, Set<Integer> juices,
            Map<String, Map<Integer, Classification>> classifications) {
        this(kinds, consumables, juices, classifications, Map.of());
    }

    private ArenaItemCatalog(Map<Integer, Kind> kinds, Set<Integer> consumables, Set<Integer> juices,
            Map<String, Map<Integer, Classification>> classifications, Map<Integer, Item> items) {
        this.kinds = Map.copyOf(kinds == null ? Map.of() : kinds);
        this.consumables = Set.copyOf(consumables == null ? Set.of() : consumables);
        this.juices = Set.copyOf(juices == null ? Set.of() : juices);
        Map<String, Map<Integer, Classification>> copy = new HashMap<>();
        if (classifications != null) for (Map.Entry<String, Map<Integer, Classification>> entry : classifications.entrySet()) {
            String patch = patchMajor(entry.getKey());
            if (patch != null && entry.getValue() != null) copy.put(patch, Map.copyOf(entry.getValue()));
        }
        this.classifications = Map.copyOf(copy);
        this.items = items == null ? Map.of() : items;
    }

    public static ArenaItemCatalog fromItems(Map<Integer, Item> items) {
        Map<Integer, Kind> kinds = new HashMap<>();
        Set<Integer> consumables = new HashSet<>();
        Set<Integer> juices = new HashSet<>();
        if (items != null) for (Map.Entry<Integer, Item> entry : items.entrySet()) {
            int id = entry.getKey();
            Item item = entry.getValue();
            if (item == null) {
                continue;
            }
            if (item.getName() != null && item.getName().toLowerCase(Locale.ROOT).startsWith("juice")) {
                juices.add(id);
            }
            if (ItemUtils.isConsumable(item)) {
                consumables.add(id);
                continue;
            }
            if (ItemUtils.isTrinket(item)) {
                continue;
            }

            boolean availableInArena = ItemUtils.isAvailableInArena(item);
            if (availableInArena && ItemUtils.isArenaPrismatic(id, item)) {
                kinds.put(id, Kind.PRISMATIC);
            } else if (ItemUtils.isBoots(item, items)) {
                kinds.put(id, Kind.BOOTS);
            } else if (availableInArena ? item.getGold() != null && item.getGold().getTotal() >= MIN_COMPLETED_ITEM_COST
                    : item.getDepth() == 3) {
                kinds.put(id, Kind.ITEM);
            }
        }
        return new ArenaItemCatalog(kinds, consumables, juices, Map.of(), items);
    }

    public static ArenaItemCatalog fromItems(Map<Integer, Item> items,
            Map<String, Map<Integer, Classification>> classifications) {
        ArenaItemCatalog catalog = fromItems(items);
        return new ArenaItemCatalog(catalog.kinds, catalog.consumables, catalog.juices, classifications, catalog.items);
    }

    public String name(int itemId) {
        Item item = items.get(itemId);
        String name = item == null ? null : item.getName();
        return name == null || name.isBlank() ? null : name;
    }

    public Kind kind(String patch, int itemId) {
        if (itemId <= 0 || ItemUtils.isUnupgradedBoots(itemId) || isConsumable(itemId) || isJuice(itemId)) {
            return null;
        }

        Classification override = classification(patch, itemId);
        Kind kind = kinds.get(itemId);
        return switch (override) {
            case UNKNOWN -> null;
            case PRISMATIC -> Kind.PRISMATIC;
            case NON_PRISMATIC -> kind == Kind.PRISMATIC ? Kind.ITEM : kind;
            case null -> kind != null ? kind : ItemUtils.isArenaPrismatic(itemId, null) ? Kind.PRISMATIC : null;
        };
    }

    public boolean isConsumable(int itemId) {
        return (itemId >= FIRST_ANVIL_ITEM && itemId <= LAST_ANVIL_ITEM) || consumables.contains(itemId);
    }

    public boolean isJuice(int itemId) {
        return juices.contains(itemId);
    }

    public boolean isPrismatic(String patch, int itemId) {
        return kind(patch, itemId) == Kind.PRISMATIC;
    }

    private Classification classification(String patch, int itemId) {
        String major = patchMajor(patch);
        return major == null ? null : classifications.getOrDefault(major, Map.of()).get(itemId);
    }

    private static String patchMajor(String patch) {
        if (patch == null || patch.isBlank()) return null;
        String value = patch.trim();
        int separator = value.indexOf('.', value.indexOf('.') + 1);
        return separator < 0 ? value : value.substring(0, separator);
    }

    public enum Classification { PRISMATIC, NON_PRISMATIC, UNKNOWN }
}
