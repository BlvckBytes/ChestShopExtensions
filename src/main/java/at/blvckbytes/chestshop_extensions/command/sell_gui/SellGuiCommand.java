package at.blvckbytes.chestshop_extensions.command.sell_gui;

import at.blvckbytes.chestshop_extensions.ChestShopRegistry;
import at.blvckbytes.chestshop_extensions.MutableInt;
import at.blvckbytes.chestshop_extensions.command.BuySellCommands;
import at.blvckbytes.chestshop_extensions.config.MainSection;
import at.blvckbytes.cm_mapper.ConfigKeeper;
import at.blvckbytes.component_markup.constructor.SlotType;
import at.blvckbytes.component_markup.expression.interpreter.InterpretationEnvironment;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Supplier;

public class SellGuiCommand implements CommandExecutor, TabCompleter, Listener {

  private final BuySellCommands buySellCommands;
  private final ChestShopRegistry chestShopRegistry;
  private final ConfigKeeper<MainSection> config;

  private final Map<UUID, Inventory> sellInventoryByPlayerId;

  public SellGuiCommand(
    ChestShopRegistry chestShopRegistry,
    BuySellCommands buySellCommands,
    ConfigKeeper<MainSection> config
  ) {
    this.buySellCommands = buySellCommands;
    this.chestShopRegistry = chestShopRegistry;
    this.config = config;

    this.sellInventoryByPlayerId = new HashMap<>();
  }

  @Override
  public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
    if (!(sender instanceof Player player))
      return false;

    if (!(player.hasPermission("chestshopextensions.sellgui"))) {
      config.rootSection.sellGui.noPermission.sendMessage(player);
      return true;
    }

    if (isOutsideOfAllowedRegionAndSendMessages(player))
      return true;

    var inventoryTitle = config.rootSection.sellGui.inventoryTitle.interpret(
      SlotType.INVENTORY_TITLE,
      new InterpretationEnvironment()
    ).getFirst();

    var sellInventory = Bukkit.createInventory(null, 9 * config.rootSection.sellGui.inventoryRowCount, inventoryTitle);

    sellInventoryByPlayerId.put(player.getUniqueId(), sellInventory);

    player.openInventory(sellInventory);
    config.rootSection.sellGui.openingPrompt.sendMessage(player);
    return true;
  }

  @Override
  public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
    return List.of();
  }

  @EventHandler
  public void onInventoryClose(InventoryCloseEvent event) {
    if (!(event.getPlayer() instanceof Player player))
      return;

    var playerId = player.getUniqueId();
    var sellInventory = sellInventoryByPlayerId.get(playerId);

    if (sellInventory == null || !event.getInventory().equals(sellInventory))
      return;

    sellInventoryByPlayerId.remove(playerId);

    var itemsToSell = Arrays.asList(sellInventory.getContents());
    var sellBuckets = analyzeItemsToSell(itemsToSell);

    if (sellBuckets == null) {
      config.rootSection.sellGui.emptyInventory.sendMessage(player);
      return;
    }

    // Hand the items back out to the player, such that ChestShop can then take them from their
    // inventory, once we simulate the interactions as dictated by the previous count-analysis.
    for (var itemToSell : itemsToSell) {
      if (itemToSell == null)
        continue;

      for (var remainder : player.getInventory().addItem(itemToSell).values())
        player.getWorld().dropItem(player.getEyeLocation(), remainder);
    }

    sellInventory.clear();

    dispatchSellBuckets(player, sellBuckets);
  }

  public @Nullable SellBuckets analyzeItemsToSell(List<ItemStack> itemsToSell) {
    var sellableItems = new ArrayList<ItemAndShop>();
    var unsellableItems = new ArrayList<ItemAndCount>();

    for (var currentItem : itemsToSell) {
      if (currentItem == null || currentItem.getType().isAir() || currentItem.getAmount() <= 0)
        continue;

      var matchingSoldItem = getOrCreateItemEntry(currentItem, sellableItems, () -> {
        var shop = chestShopRegistry.locateValidatedAdminShopToSellItemTo(currentItem);
        return shop == null ? null : new ItemAndShop(currentItem, shop, new MutableInt());
      });

      if (matchingSoldItem != null) {
        matchingSoldItem.count().value += currentItem.getAmount();
        continue;
      }

      var matchingUnsoldItem = getOrCreateItemEntry(currentItem, unsellableItems, () -> new ItemAndCount(currentItem, new MutableInt()));

      if (matchingUnsoldItem != null)
        matchingUnsoldItem.count.value += currentItem.getAmount();
    }

    if (sellableItems.isEmpty() && unsellableItems.isEmpty())
      return null;

    return new SellBuckets(sellableItems, unsellableItems);
  }

  public void dispatchSellBuckets(Player player, SellBuckets sellBuckets) {
    for (var sellableItem : sellBuckets.sellableItems()) {
      var signBlock = sellableItem.shop().signLocation.getBlock();
      buySellCommands.simulateParameterizedInteraction(player, signBlock, false, sellableItem.count().value);
    }

    if (!sellBuckets.unsellableItems().isEmpty()) {
      config.rootSection.sellGui.unsellableItems.sendMessage(
        player,
        new InterpretationEnvironment()
          .withVariable("items", sellBuckets.unsellableItems())
      );
    }
  }

  public boolean isOutsideOfAllowedRegionAndSendMessages(Player player) {
    var regionContainer = WorldGuard.getInstance().getPlatform().getRegionContainer();
    var targetWorld = player.getWorld();

    if (!config.rootSection.sellGui.regionFilter.shopRegionWorlds.contains(targetWorld.getName())) {
      config.rootSection.sellGui.unallowedWorld.sendMessage(player);
      return true;
    }

    var regionManager = regionContainer.get(BukkitAdapter.adapt(player.getWorld()));

    if (regionManager == null) {
      config.rootSection.sellGui.unallowedRegion.sendMessage(player);
      return true;
    }

    var isWithinAllowedRegion = regionManager
      .getApplicableRegions(BlockVector3.at(player.getX(), player.getY(), player.getZ()))
      .getRegions().stream()
      .anyMatch(region -> config.rootSection.sellGui.regionFilter.compiledShopRegionPattern.matcher(region.getId()).matches());

    if (!isWithinAllowedRegion) {
      config.rootSection.sellGui.unallowedRegion.sendMessage(player);
      return true;
    }

    return false;
  }

  private <T extends ItemHolder> @Nullable T getOrCreateItemEntry(ItemStack item, List<T> list, Supplier<@Nullable T> creator) {
    return list.stream()
      .filter(it -> it.item().isSimilar(item))
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
