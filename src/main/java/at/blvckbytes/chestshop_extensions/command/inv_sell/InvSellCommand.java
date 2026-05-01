package at.blvckbytes.chestshop_extensions.command.inv_sell;

import at.blvckbytes.chestshop_extensions.ChestShopRegistry;
import at.blvckbytes.chestshop_extensions.eco_log.EcoLogger;
import at.blvckbytes.chestshop_extensions.command.sell_gui.SellGuiCommand;
import at.blvckbytes.chestshop_extensions.command.sell_gui.SellToShopSession;
import at.blvckbytes.chestshop_extensions.config.MainSection;
import at.blvckbytes.cm_mapper.ConfigKeeper;
import at.blvckbytes.component_markup.constructor.SlotType;
import at.blvckbytes.component_markup.expression.interpreter.InterpretationEnvironment;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
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
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class InvSellCommand implements CommandExecutor, TabCompleter, Listener {

  private final ChestShopRegistry shopRegistry;
  private final Economy economy;
  private final @Nullable EcoLogger ecoLogger;

  private final Logger logger;
  private final ConfigKeeper<MainSection> config;

  private final Map<UUID, Inventory> editedFiltersByPlayerId;

  private final NamespacedKey filtersItemDataKey;

  public InvSellCommand(
    Plugin plugin,
    ChestShopRegistry shopRegistry,
    Economy economy,
    @Nullable EcoLogger ecoLogger,
    ConfigKeeper<MainSection> config
  ) {
    this.logger = plugin.getLogger();
    this.shopRegistry = shopRegistry;
    this.economy = economy;
    this.ecoLogger = ecoLogger;
    this.config = config;

    this.editedFiltersByPlayerId = new HashMap<>();

    this.filtersItemDataKey = new NamespacedKey(plugin, "inv-sell-filters");
  }

  @Override
  public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
    if (!(sender instanceof Player player))
      return false;

    if (!(player.hasPermission("chestshopextensions.invsell"))) {
      config.rootSection.sellGui.invSell.noPermission.sendMessage(player);
      return true;
    }

    if (SellGuiCommand.isOutsideOfAllowedRegionAndSendMessages(player, config))
      return true;

    if (args.length == 0) {
      handleSellingInventory(player, label);
      return true;
    }

    var actionConstant = CommandAction.matcher.matchFirst(args[0]);

    if (actionConstant == null) {
      config.rootSection.sellGui.invSell.commandUsage.sendMessage(
        player,
        new InterpretationEnvironment()
          .withVariable("label", label)
          .withVariable("actions", CommandAction.matcher.createCompletions(null))
      );
      return true;
    }

    if (actionConstant.constant == CommandAction.FILTERS) {
      var filtersInventory = makeFiltersInventory();
      loadFiltersFromPlayerPDCAndGetIfEmpty(player, filtersInventory);

      player.openInventory(filtersInventory);
      editedFiltersByPlayerId.put(player.getUniqueId(), filtersInventory);

      config.rootSection.sellGui.invSell.openingFiltersInventory.sendMessage(player);
      return true;
    }

    return true;
  }

  @Override
  public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
    if (!(sender instanceof Player player) || !(player.hasPermission("chestshopextensions.invsell")) || args.length != 1)
      return List.of();

    return CommandAction.matcher.createCompletions(args[0]);
  }

  @EventHandler
  public void onInventoryClose(InventoryCloseEvent event) {
    if (!(event.getPlayer() instanceof Player player))
      return;

    var playerId = player.getUniqueId();
    var filtersInventory = editedFiltersByPlayerId.get(playerId);

    if (filtersInventory == null || !event.getInventory().equals(filtersInventory))
      return;

    editedFiltersByPlayerId.remove(playerId);

    storeFiltersToPlayerPDC(player, filtersInventory);

    config.rootSection.sellGui.invSell.savedFiltersInventory.sendMessage(
      player,
      new InterpretationEnvironment()
        .withVariable("filter_count", Arrays.stream(filtersInventory.getContents()).filter(this::isValidItem).count())
    );

    filtersInventory.clear();
  }

  private void handleSellingInventory(Player player, String commandLabel) {
    var environment = new InterpretationEnvironment()
      .withVariable("edit_command", "/" + commandLabel + " " + CommandAction.matcher.getNormalizedName(CommandAction.FILTERS));

    var filtersInventory = makeFiltersInventory();

    if (loadFiltersFromPlayerPDCAndGetIfEmpty(player, filtersInventory)) {
      config.rootSection.sellGui.invSell.noFiltersConfigured.sendMessage(player, environment);
      return;
    }

    var filterItems = filtersInventory.getContents();
    var sellSession = new SellToShopSession(shopRegistry, false);

    sellSession.removeItemsToSell(player.getInventory(), itemToSell -> {
      for (var filterItem : filterItems) {
        if (filterItem != null && filterItem.isSimilar(itemToSell))
          return true;
      }

      return false;
    });

    if (!sellSession.sendMessagesAndTransact(player, economy, ecoLogger, config))
      config.rootSection.sellGui.invSell.noMatchingItemsFound.sendMessage(player, environment);
  }

  private Inventory makeFiltersInventory() {
    return Bukkit.createInventory(
      null, 9 * 6,
      config.rootSection.sellGui.invSell.filtersInventoryTitle
        .interpret(SlotType.INVENTORY_TITLE, null)
        .getFirst()
    );
  }

  private void storeFiltersToPlayerPDC(Player player, Inventory filtersInventory) {
    byte[] itemDataBytes;

    try {
      itemDataBytes = ItemStack.serializeItemsAsBytes(filtersInventory.getContents());
    } catch (Throwable e) {
      logger.log(Level.SEVERE, "Could not serialize inv-sell filters of " + player.getName(), e);
      return;
    }

    player.getPersistentDataContainer().set(filtersItemDataKey, PersistentDataType.BYTE_ARRAY, itemDataBytes);
  }

  private boolean loadFiltersFromPlayerPDCAndGetIfEmpty(Player player, Inventory filtersInventory) {
    var itemDataBytes = player.getPersistentDataContainer().get(filtersItemDataKey, PersistentDataType.BYTE_ARRAY);

    if (itemDataBytes == null)
      return false;

    ItemStack[] filterItems;

    try {
      filterItems = ItemStack.deserializeItemsFromBytes(itemDataBytes);
    } catch (Throwable e) {
      logger.log(Level.SEVERE, "Could not deserialize inv-sell filters of " + player.getName(), e);
      return true;
    }

    var filterCount = 0;

    for (var itemIndex = 0; itemIndex < filterItems.length; ++itemIndex) {
      if (itemIndex >= filtersInventory.getSize())
        break;

      var filterItem = filterItems[itemIndex];

      if (!isValidItem(filterItem))
        continue;

      filtersInventory.setItem(itemIndex, filterItem);
      ++filterCount;
    }

    return filterCount == 0;
  }

  private boolean isValidItem(ItemStack item) {
    return item != null && item.getAmount() > 0 && !item.getType().isAir();
  }
}
