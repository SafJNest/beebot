package com.safjnest.lol.utils;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.safjnest.lol.service.StaticDataService;
import com.safjnest.utils.ParseUtils;

import no.stelar7.api.r4j.pojo.lol.staticdata.item.Item;
import no.stelar7.api.r4j.pojo.lol.staticdata.shared.Gold;

public class ItemUtils {

  public static final int BASE_BOOTS = 1001;
  private static final int MAGICAL_FOOTWEAR = 2422;
  private static final int MIN_COMPLETED_RECIPE_COST = 1200;
  private static final int ARENA_SHARDBLADE = 220012;
  private static final int ARENA_GOREDRINKER = 226630;
  private static final int ARENA_PROWLERS_CLAW = 226693;
  private static final int ARENA_OVERLORDS_BLOODMAIL = 447111;
  private static final int ARENA_PRISMATIC_COST = 2750;
  private static final int ARENA_PRISMATIC_SELL = 1100;
  private static volatile Map<Integer, Item> items;

  private ItemUtils() {}

  public static Map<Integer, Item> getItems() {
    if (items == null) synchronized (ItemUtils.class) {
      if (items == null) {
        Map<Integer, Item> loadedItems = StaticDataService.getItems();
        items = loadedItems == null ? new HashMap<>() : loadedItems;
      }
    }
    return items;
  }

  public static Item getItem(int id) {
    return getItems().get(id);
  }

  public static boolean isBoots(Item item) {
    if (item == null) {
      return false;
    }

    boolean isBaseBoots = item.getId() == BASE_BOOTS || isGrantedByRune(item.getId());
    boolean fromBoots = item.getFrom() != null && item.getFrom().contains("1001");
    return isBaseBoots || fromBoots || hasTag(item, "Boots")
        || item.getName() != null && item.getName().toLowerCase(Locale.ROOT).contains("boots");
  }

  public static boolean isBoots(Item item, Map<Integer, Item> patchItems) {
    if (item == null) {
      return false;
    }
    if (isBoots(item)) {
      return true;
    }

    List<String> recipe = item.getFrom();
    if (recipe == null || recipe.size() != 1) {
      return false;
    }

    int componentId = ParseUtils.parseInt(recipe.getFirst());
    return componentId > 0 && isBoots(patchItems.get(componentId));
  }

  public static boolean isGrantedByRune(int itemId) {
    return itemId == MAGICAL_FOOTWEAR;
  }

  public static boolean isConsumable(Item item) {
    return item != null && (item.isConsumed() || hasTag(item, "Consumable"));
  }

  public static boolean isTrinket(Item item) {
    return hasTag(item, "Trinket");
  }

  public static boolean isSupportItem(Item item) {
    return hasTag(item, "GoldPer") && hasTag(item, "Vision");
  }

  public static boolean isBuildPurchase(Item item) {
    return isPurchasable(item) && !isGrantedByRune(item.getId()) && !isConsumable(item)
        && !isTrinket(item) && !isSupportItem(item);
  }

  public static boolean isCoreItem(Item item, Map<Integer, Item> patchItems) {
    if (!isBuildPurchase(item) || isBoots(item, patchItems)) {
      return false;
    }

    List<String> recipe = item.getFrom();
    List<String> upgrades = item.getInto();
    return item.getDepth() >= 3 || (recipe != null && !recipe.isEmpty()
        && (upgrades == null || upgrades.isEmpty()) && item.getGold().getTotal() >= MIN_COMPLETED_RECIPE_COST);
  }

  public static boolean isAvailableInArena(Item item) {
    return item != null && item.getMaps() != null && Boolean.TRUE.equals(item.getMaps().get("30"));
  }

  public static boolean isArenaPrismatic(int id, Item item) {
    if (id == ARENA_OVERLORDS_BLOODMAIL) {
      return false;
    }
    if (id == ARENA_SHARDBLADE) {
      return true;
    }
    if (isAvailableInArena(item) && item.getGold() != null) {
      Gold gold = item.getGold();
      // Matches the client PrismaticItems group on Arena-enabled Data Dragon entries.
      return gold.getTotal() == ARENA_PRISMATIC_COST && gold.getSell() == ARENA_PRISMATIC_SELL;
    }
    return id > 440000 && id < 450000 || id == ARENA_GOREDRINKER || id == ARENA_PROWLERS_CLAW;
  }

  private static boolean isPurchasable(Item item) {
    Gold gold = item == null ? null : item.getGold();
    return gold != null && gold.getPurchasable() && gold.getTotal() > 0;
  }

  private static boolean hasTag(Item item, String tag) {
    return item != null && item.getTags() != null && item.getTags().contains(tag);
  }
}
