package at.blvckbytes.chestshop_extensions.command.sell_gui;

import at.blvckbytes.chestshop_extensions.ChestShopEntry;
import at.blvckbytes.chestshop_extensions.ChestShopRegistry;
import at.blvckbytes.chestshop_extensions.eco_log.EcoLogger;
import at.blvckbytes.chestshop_extensions.config.MainSection;
import at.blvckbytes.cm_mapper.ConfigKeeper;
import at.blvckbytes.component_markup.expression.interpreter.InterpretationEnvironment;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Tag;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class SellToShopSession {

  private enum ShulkerResult {
    NOT_A_SHULKER,
    NO_ITEMS_SOLD,
    SOME_ITEMS_SOLD,
  }

  private final ChestShopRegistry shopRegistry;
  private final boolean trackUnsoldItems;

  private final List<ItemAndShop> sellableItemBuckets;
  private final List<ItemAndCount> unsellableItemBuckets;

  public SellToShopSession(ChestShopRegistry shopRegistry, boolean trackUnsoldItems) {
    this.shopRegistry = shopRegistry;
    this.trackUnsoldItems = trackUnsoldItems;

    this.sellableItemBuckets = new ArrayList<>();
    this.unsellableItemBuckets = new ArrayList<>();
  }

  public boolean removeItemsToSell(Inventory inventory, @Nullable Predicate<ItemStack> predicate) {
    var storageContents = inventory.getStorageContents();
    var didSellAny = false;

    for (var itemIndex = 0; itemIndex < storageContents.length; ++itemIndex) {
      var currentItem = storageContents[itemIndex];

      if (currentItem == null)
        continue;

      var shulkerResult = trySellFromShulkerBox(currentItem, predicate);

      if (shulkerResult != ShulkerResult.NOT_A_SHULKER) {
        if (shulkerResult == ShulkerResult.SOME_ITEMS_SOLD)
          didSellAny = true;

        continue;
      }

      if (predicate != null && !predicate.test(currentItem))
        continue;

      if (!addToBucketAndGetIfSellable(currentItem))
        continue;

      storageContents[itemIndex] = null;
      didSellAny = true;
    }

    if (didSellAny)
      inventory.setStorageContents(storageContents);

    return didSellAny;
  }

  private ShulkerResult trySellFromShulkerBox(ItemStack shulkerItem, @Nullable Predicate<ItemStack> predicate) {
    if (!Tag.SHULKER_BOXES.isTagged(shulkerItem.getType()))
      return ShulkerResult.NOT_A_SHULKER;

    if (!(shulkerItem.getItemMeta() instanceof BlockStateMeta blockStateMeta))
      return ShulkerResult.NOT_A_SHULKER;

    if (!(blockStateMeta.getBlockState() instanceof Container container))
      return ShulkerResult.NOT_A_SHULKER;

    if (!removeItemsToSell(container.getInventory(), predicate))
      return ShulkerResult.NO_ITEMS_SOLD;

    blockStateMeta.setBlockState(container);
    shulkerItem.setItemMeta(blockStateMeta);

    return ShulkerResult.SOME_ITEMS_SOLD;
  }

  private boolean addToBucketAndGetIfSellable(ItemStack itemToSell) {
    var matchingSoldItem = getOrCreateItemEntry(itemToSell, sellableItemBuckets, () -> {
      var shop = shopRegistry.locateValidatedAdminShopToSellItemTo(itemToSell);
      return shop == null ? null : new ItemAndShop(itemToSell, shop);
    });

    if (matchingSoldItem != null) {
      matchingSoldItem.count.value += itemToSell.getAmount();
      return true;
    }

    if (!trackUnsoldItems)
      return false;

    var matchingUnsoldItem = getOrCreateItemEntry(itemToSell, unsellableItemBuckets, () -> new ItemAndCount(itemToSell));

    if (matchingUnsoldItem != null)
      matchingUnsoldItem.count.value += itemToSell.getAmount();

    return false;
  }

  @SuppressWarnings("BooleanMethodIsAlwaysInverted")
  public boolean sendMessagesAndTransact(
    Player player,
    Economy economy,
    @Nullable EcoLogger ecoLogger,
    ConfigKeeper<MainSection> config
  ) {
    if (sellableItemBuckets.isEmpty() && unsellableItemBuckets.isEmpty())
      return false;

    if (!sellableItemBuckets.isEmpty()) {
      for (var bucket : sellableItemBuckets) {
        var totalValue = bucket.computeTotalValueAndSetFormatted(economy);

        economy.depositPlayer(player, bucket.shop.world.getName(), totalValue);
        economy.withdrawPlayer(Bukkit.getOfflinePlayer(bucket.shop.ownerId), bucket.shop.world.getName(), totalValue);

        if (ecoLogger != null) {
          ecoLogger.logTwoWayTransaction(
            bucket.shop.owner, bucket.shop.ownerId,
            player.getName(), player.getUniqueId(),
            totalValue, "ChestShop", "receiver sold " + bucket.count.value + "x " + bucket.item.getType().name()+ " at " + stringifyLocation(bucket.shop)
          );
        }
      }

      config.rootSection.sellGui.soldItems.sendMessage(
        player,
        new InterpretationEnvironment()
          .withVariable("items", sellableItemBuckets)
      );
    }

    if (!unsellableItemBuckets.isEmpty()) {
      config.rootSection.sellGui.unsellableItems.sendMessage(
        player,
        new InterpretationEnvironment()
          .withVariable("items", unsellableItemBuckets)
      );
    }

    return true;
  }

  private String stringifyLocation(ChestShopEntry shop) {
    var world = shop.signLocation.getWorld();
    return shop.signLocation.getBlockX() + " " + shop.signLocation.getBlockY() + " " + shop.signLocation.getBlockZ() + " " + (world == null ? "?" : world.getName());
  }

  private <T extends ItemAndCount> @Nullable T getOrCreateItemEntry(ItemStack item, List<T> list, Supplier<@Nullable T> creator) {
    return list.stream()
      .filter(it -> it.item.isSimilar(item))
      .findFirst()
      .orElseGet(() -> {
        var newEntry = creator.get();

        if (newEntry == null)
          return null;

        list.add(newEntry);

        return newEntry;
      });
  }
}
