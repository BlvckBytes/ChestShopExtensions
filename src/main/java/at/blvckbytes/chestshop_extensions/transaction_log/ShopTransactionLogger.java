package at.blvckbytes.chestshop_extensions.transaction_log;

import at.blvckbytes.chestshop_extensions.TransactionItem;
import at.blvckbytes.chestshop_extensions.config.MainSection;
import at.blvckbytes.cm_mapper.ConfigKeeper;
import com.google.gson.*;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.*;
import java.util.logging.Level;

public class ShopTransactionLogger {

  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

  private final File logsFile;
  private final Plugin plugin;
  private final ConfigKeeper<MainSection> config;

  private final Map<UUID, List<ShopTransaction>> transactionsByOwnerId;
  private boolean isMapDirty;

  public ShopTransactionLogger(
    Plugin plugin,
    ConfigKeeper<MainSection> config
  ) throws Exception {
    this.logsFile = new File(plugin.getDataFolder(), "transaction-logs.json");
    this.plugin = plugin;
    this.config = config;

    this.transactionsByOwnerId = new HashMap<>();

    if (!this.logsFile.exists()) {
      if (!this.logsFile.createNewFile())
        throw new IllegalStateException("Could not create file " + logsFile);
    }

    else if (!this.logsFile.isFile())
      throw new IllegalStateException("Expected file but found directory at " + logsFile);

    Bukkit.getScheduler().runTaskAsynchronously(plugin, this::load);
    Bukkit.getScheduler().runTaskTimer(plugin, () -> save(true), 20 * 60 * 5, 20 * 60 * 5);
  }

  public void onShutdown() {
    save(false);
  }

  public List<ShopTransaction> getTransactionsForOwner(OfflinePlayer owner) {
    var list = transactionsByOwnerId.get(owner.getUniqueId());

    if (list == null)
      return Collections.emptyList();

    return Collections.unmodifiableList(list);
  }

  public void clearTransactionsForOwner(OfflinePlayer owner) {
    transactionsByOwnerId.remove(owner.getUniqueId());
  }

  public void onTransaction(
    Player client, OfflinePlayer owner,
    Location signLocation, TransactionItem item,
    double price, boolean wasBuy, boolean didExhaust
  ) {
    if (owner.isOnline())
      return;

    addTransaction(new ShopTransaction(
      client.getName(),
      client.getUniqueId(),
      owner.getUniqueId(),
      signLocation,
      item.itemClone,
      item.totalAmount,
      price,
      wasBuy,
      didExhaust,
      System.currentTimeMillis()
    ));
  }

  private void addTransaction(ShopTransaction transaction) {
    isMapDirty = true;

    var ownerBucket = transactionsByOwnerId.computeIfAbsent(transaction.ownerId, k -> new ArrayList<>());

    for (var index = 0; index < ownerBucket.size(); ++index) {
      var mergeResult = ownerBucket.get(index).mergeWithIfPossible(transaction, config);

      if (mergeResult != null) {
        mergeResult.indexInList = index;
        ownerBucket.set(index, mergeResult);
        return;
      }
    }

    transaction.indexInList = ownerBucket.size();

    ownerBucket.add(transaction);
  }

  private void load() {
    if (logsFile.length() == 0)
      return;

    if (!transactionsByOwnerId.isEmpty())
      throw new IllegalStateException("Refusing to load into an already populated map");

    try (var reader = new FileReader(logsFile)) {
      if (!(GSON.fromJson(reader, JsonElement.class) instanceof JsonArray jsonArray))
        throw new IllegalStateException("Expected top-level json-array");

      var loadCounter = 0;

      for (var index = 0; index < jsonArray.size(); ++index) {
        if (!(jsonArray.get(index) instanceof JsonObject jsonObject)) {
          plugin.getLogger().warning("Encountered unexpected non-object at index=" + index + " of " + logsFile + "; skipping");
          continue;
        }

        var transaction = ShopTransaction.fromJson(jsonObject);
        var ageMillis = transaction.getAge();

        if (ageMillis / 1000 / 60 / 60 / 24 > config.rootSection.transactionLog.maxAgeDays)
          continue;

        ++loadCounter;

        addTransaction(transaction);
      }

      isMapDirty = false;

      plugin.getLogger().info("Loaded " + loadCounter + " logged shop-transactions");
    } catch (Throwable e) {
      plugin.getLogger().log(Level.SEVERE, "An error occurred while trying to load transactions from " + logsFile, e);
    }
  }

  private void save(boolean asynchronously) {
    if (!isMapDirty)
      return;

    var items = new ArrayList<ShopTransaction>();
    transactionsByOwnerId.values().forEach(items::addAll);

    if (!asynchronously) {
      writeItems(items);
      return;
    }

    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> writeItems(items));
  }

  private void writeItems(List<ShopTransaction> items) {
    try {
      var jsonArray = new JsonArray();

      for (var item : items)
        jsonArray.add(item.toJson());

      try (var writer = new FileWriter(logsFile)) {
        GSON.toJson(jsonArray, writer);
      }

      isMapDirty = false;
    } catch (Throwable e) {
      plugin.getLogger().log(Level.SEVERE, "An error occurred while trying to save transactions to " + logsFile, e);
    }
  }
}
