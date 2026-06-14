package at.blvckbytes.chestshop_extensions;

import at.blvckbytes.chestshop_extensions.skin_cache.SkinCache;
import at.blvckbytes.chestshop_extensions.skin_cache.CachedSkinUpdateEvent;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.sign.Side;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import javax.annotation.Nullable;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ChestShopRegistry implements Listener {

  private static final Gson GSON_INSTANCE = new GsonBuilder().setPrettyPrinting().create();

  private final Plugin plugin;
  private final SkinCache skinCache;
  private final NameScopedKeyValueStore keyValueStore;
  private final RegionContainer regionContainer;
  private final File persistenceFile;
  private final Logger logger;

  private final Map<WorldAndRegionManager, Long2ObjectMap<EnumMap<Side, ChestShopEntry>>> shopBySideByFastHashByWorldManager;

  private final Map<String, ShopOwner> shopOwnerByNameLower;
  private final List<Consumer<ChestShopEntry>> stockChangeListeners;

  private long relativeTime;

  public ChestShopRegistry(
    Plugin plugin,
    SkinCache skinCache,
    NameScopedKeyValueStore keyValueStore,
    File persistenceFile,
    Logger logger
  ) {
    this.plugin = plugin;
    this.skinCache = skinCache;
    this.keyValueStore = keyValueStore;
    this.regionContainer = WorldGuard.getInstance().getPlatform().getRegionContainer();
    this.persistenceFile = persistenceFile;
    this.logger = logger;

    this.shopBySideByFastHashByWorldManager = new HashMap<>();
    this.shopOwnerByNameLower = new HashMap<>();
    this.stockChangeListeners = new ArrayList<>();

    var timerPeriod = ChestShopEntry.SHOP_UPDATE_INTERVAL_T / 2;

    load();

    Bukkit.getScheduler().runTaskTimer(plugin, () -> {
      relativeTime += timerPeriod;
      updateAllShops();
    }, timerPeriod, timerPeriod);

    Bukkit.getScheduler().runTaskTimer(plugin, () -> save(true), 20L * 30, 20L * 30);
  }

  @EventHandler
  public void onSkinUpdate(CachedSkinUpdateEvent event) {
    shopOwnerByNameLower.values().forEach(it -> it.onCachedSkinUpdate(event.cachedSkin));
  }

  public void registerStockChangeListener(Consumer<ChestShopEntry> entry) {
    this.stockChangeListeners.add(entry);
  }

  private void callStockChangeListeners(ChestShopEntry entry) {
    for (var listener : stockChangeListeners)
      listener.accept(entry);
  }

  private boolean checkIfShopIsHidden(ChestShopEntry shopEntry, RegionManager regionManager) {
    var regionSet = regionManager.getApplicableRegions(shopEntry.blockVector);
    var ownerName = shopEntry.owner;

    for (var region : regionSet) {
      var visibilityState = keyValueStore.read(ownerName, NameScopedKeyValueStore.makeRegionVisibilityKey(region.getId()));

      if (!"false".equals(visibilityState))
        continue;

      return true;
    }

    return false;
  }

  public List<ShopOwner> getKnownOwners() {
    return new ArrayList<>(shopOwnerByNameLower.values());
  }

  public void updateAllShops() {
    var removedOwnerNamesLower = new HashSet<String>();

    for (var worldBucketEntry : shopBySideByFastHashByWorldManager.entrySet()) {
      worldBucketEntry.getValue()
        .values()
        .removeIf(shopBySide -> {
          shopBySide
            .entrySet()
            .removeIf(sideEntry -> {
              var shop = sideEntry.getValue();

              if (shop.updateAndGetIfRemove(relativeTime, false, logger, sideEntry::setValue)) {
                removedOwnerNamesLower.add(shop.owner.toLowerCase());
                return true;
              }

              return false;
            });

          return shopBySide.isEmpty();
        });
    }

    removedOwnerNamesLower.forEach(nameLower -> {
      if (hasNoMoreShops(nameLower))
        shopOwnerByNameLower.remove(nameLower);
    });
  }

  public void deleteShopIf(Predicate<ChestShopEntry> predicate) {
    var removedOwnerNamesLower = new HashSet<String>();

    iterateKnownShops(shop -> {
      if (!predicate.test(shop))
        return false;

      removedOwnerNamesLower.add(shop.owner.toLowerCase());
      return true;
    }, null);

    removedOwnerNamesLower.forEach(nameLower -> {
      if (hasNoMoreShops(nameLower))
        shopOwnerByNameLower.remove(nameLower);
    });
  }

  public void forEachKnownShop(Consumer<ChestShopEntry> consumer) {
    iterateKnownShops(null, consumer);
  }

  private void iterateKnownShops(
    @Nullable Predicate<ChestShopEntry> deletionHandler,
    @Nullable Consumer<ChestShopEntry> entryHandler
  ) {
    for (var worldBucketEntry : shopBySideByFastHashByWorldManager.entrySet()) {
      var regionManager = worldBucketEntry.getKey().regionManager();

      for (var shopBySide : worldBucketEntry.getValue().values()) {
        for (var iterator = shopBySide.values().iterator(); iterator.hasNext();) {
          var shop = iterator.next();

          if (deletionHandler != null && deletionHandler.test(shop)) {
            iterator.remove();
            continue;
          }

          if (checkIfShopIsHidden(shop, regionManager))
            continue;

          if (entryHandler != null)
            entryHandler.accept(shop);
        }
      }
    }
  }

  public void save(boolean async) {
    var shopEntries = new ArrayList<ChestShopEntry>();

    for (var worldBucket : shopBySideByFastHashByWorldManager.values()) {
      for (var shopBySide : worldBucket.values()) {
        shopEntries.addAll(shopBySide.values());
      }
    }

    if (!async) {
      writeShopEntries(shopEntries);
      return;
    }

    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> writeShopEntries(shopEntries));
  }

  private void writeShopEntries(List<ChestShopEntry> shopEntries) {
    var jsonShops = new JsonArray();

    for (var shopEntry : shopEntries) {
      var shopJson = shopEntry.toJson(logger);

      if (shopJson != null)
        jsonShops.add(shopJson);
    }

    try (
      var fileWriter = new FileWriter(persistenceFile)
    ) {
      fileWriter.write(GSON_INSTANCE.toJson(jsonShops));
    } catch (Throwable e) {
      logger.log(Level.SEVERE, "An error occurred while trying to save the persistence-file", e);
    }
  }

  private void load() {
    try (
      var fileReader = new FileReader(persistenceFile)
    ) {
      if (!fileReader.ready())
        return;

      shopBySideByFastHashByWorldManager.clear();

      var jsonShops = GSON_INSTANCE.fromJson(fileReader, JsonArray.class);
      var loadedCounter = 0;

      for (var jsonShop : jsonShops) {
        var shopEntry = ChestShopEntry.fromJson(jsonShop, logger, relativeTime);

        if (shopEntry == null)
          continue;

        var shopBySide = getOrCreateShopBySide(shopEntry.signLocation, true);

        if (shopBySide == null)
          continue;

        shopBySide.put(shopEntry.side, shopEntry);

        registerOwnerName(shopEntry.owner);

        ++loadedCounter;
      }

      logger.info("Loaded " + loadedCounter + " shops from the persistence-file.");
    } catch (Throwable e) {
      logger.log(Level.SEVERE, "An error occurred while trying to load the persistence-file", e);
    }
  }

  public @Nullable ChestShopEntry getShopAt(Location signLocation, Side side) {
    var shopBySide = getOrCreateShopBySide(signLocation, false);

    if (shopBySide == null)
      return null;

    return shopBySide.get(side);
  }

  public void onCreation(ChestShopEntry chestShopEntry) {
    var shopBySide = getOrCreateShopBySide(chestShopEntry.signLocation, true);

    if (shopBySide == null)
      return;

    shopBySide.put(chestShopEntry.side, chestShopEntry);
    registerOwnerName(chestShopEntry.owner);
  }

  public static boolean isAdminShop(String name) {
    return name.replace(" ", "").equalsIgnoreCase("adminshop");
  }

  private void registerOwnerName(String name) {
    if (isAdminShop(name))
      return;

    var nameLower = name.toLowerCase();

    if (shopOwnerByNameLower.containsKey(nameLower))
      return;

    var shopOwner = new ShopOwner(name);

    shopOwnerByNameLower.put(nameLower, shopOwner);

    var cachedSkin = skinCache.getOrTryUpdateSkin(name);

    if (cachedSkin != null)
      shopOwner.onCachedSkinUpdate(cachedSkin);
  }

  public void onDestruction(Location signLocation, @Nullable Side side) {
    var shopBySide = getOrCreateShopBySide(signLocation, false);

    if (shopBySide == null)
      return;

    var shopEntry = shopBySide.remove(side);

    if (shopEntry != null) {
      if (hasNoMoreShops(shopEntry.owner))
        shopOwnerByNameLower.remove(shopEntry.owner.toLowerCase());
    }
  }

  public void onStockChange(Location signLocation, Side side, int remainingStock, int containerSize) {
    var worldBucket = getOrCreateShopBySide(signLocation, false);

    if (worldBucket == null)
      return;

    var shop = worldBucket.get(side);

    // Do not try to modify the stock of a shop that is unlimited
    if (shop == null || shop.stock < 0)
      return;

    shop.stock = remainingStock;
    shop.containerSize = containerSize;

    // Just to be safe
    if (shop.stock < 0)
      shop.stock = 0;

    callStockChangeListeners(shop);
  }

  @SuppressWarnings("BooleanMethodIsAlwaysInverted")
  private boolean isAdminShopWithItemType(ChestShopEntry shop, ItemStack item) {
    if (!isAdminShop(shop.owner))
      return false;

    if (shop.sellPrice <= 0)
      return false;

    return shop.item.isSimilar(item);
  }

  public @Nullable ChestShopEntry locateValidatedAdminShopToSellItemTo(ItemStack item) {
    var removedOwnerNamesLower = new HashSet<String>();

    for (var shopBySideByFastHash : shopBySideByFastHashByWorldManager.values()) {
      for (var shopBySide : shopBySideByFastHash.values()) {
        for (var iterator = shopBySide.entrySet().iterator(); iterator.hasNext();) {
          var entry = iterator.next();
          var shop = entry.getValue();

          if (!isAdminShopWithItemType(shop, item))
            continue;

          if (shop.updateAndGetIfRemove(relativeTime, false, logger, entry::setValue)) {
            iterator.remove();
            removedOwnerNamesLower.add(shop.owner.toLowerCase());
            continue;
          }

          shop = entry.getValue();

          if (!isAdminShopWithItemType(shop, item))
            continue;

          return shop;
        }
      }
    }

    removedOwnerNamesLower.forEach(nameLower -> {
      if (hasNoMoreShops(nameLower))
        shopOwnerByNameLower.remove(nameLower);
    });

    return null;
  }

  private boolean hasNoMoreShops(String ownerName) {
    // I could also keep a separate set of active owner-names, but for now, this is good enough.
    for (var shopBySideByFastHash : shopBySideByFastHashByWorldManager.values()) {
      for (var shopBySide : shopBySideByFastHash.values()) {
        for (var shop : shopBySide.values()) {
          if (shop.owner.equalsIgnoreCase(ownerName))
            return false;
        }
      }
    }

    return true;
  }

  private @Nullable EnumMap<Side, ChestShopEntry> getOrCreateShopBySide(Location signLocation, boolean create) {
    var signWorld = signLocation.getWorld();

    if (signWorld == null) {
      logger.warning("Encountered null-world location: " + signLocation);
      return null;
    }

    var regionManager = regionContainer.get(BukkitAdapter.adapt(signWorld));

    if (regionManager == null) {
      logger.warning("Could not locate region-manager of world " + signWorld);
      return null;
    }

    var worldAndRegionManager = new WorldAndRegionManager(signWorld, regionManager);
    var shopBySideByFastHash = shopBySideByFastHashByWorldManager.get(worldAndRegionManager);

    if (shopBySideByFastHash == null) {
      if (!create)
        return null;

      shopBySideByFastHash = new Long2ObjectOpenHashMap<>();
      shopBySideByFastHashByWorldManager.put(worldAndRegionManager, shopBySideByFastHash);
    }

    var fastHash = fastCoordinateHash(signLocation.getBlockX(), signLocation.getBlockY(), signLocation.getBlockZ());
    var shopBySide = shopBySideByFastHash.get(fastHash);

    if (shopBySide == null) {
      if (!create)
        return null;

      shopBySide = new EnumMap<>(Side.class);
      shopBySideByFastHash.put(fastHash, shopBySide);
    }

    return shopBySide;
  }

  private static long fastCoordinateHash(int x, int y, int z) {
    // y in [-64;320] - adding 64 will result in [0;384], thus 9 bits will suffice
    // long has 64 bits, (64-9)/2 = 27.5, thus, let's reserve 10 bits for y, and add 128, for future-proofing
    // 27 bits per x/z axis, with one sign-bit, => +- 67,108,864
    // As far as I know, the world is limited to around +- 30,000,000 - so we're fine

    return (
      // 2^10 - 1 = 0x3FF
      // 2^26 - 1 = 0x3FFFFFF
      // 2^26     = 0x4000000
      ((y + 128) & 0x3FF) |
      (((x & 0x3FFFFFF) | (x < 0 ? 0x4000000L : 0)) << 10) |
      (((z & 0x3FFFFFF) | (z < 0 ? 0x4000000L : 0)) << (10 + 27))
    );
  }
}
