package at.blvckbytes.chestshop_extensions.command.sell_gui;

import at.blvckbytes.chestshop_extensions.ChestShopRegistry;
import at.blvckbytes.chestshop_extensions.eco_log.EcoLogger;
import at.blvckbytes.chestshop_extensions.config.MainSection;
import at.blvckbytes.cm_mapper.ConfigKeeper;
import at.blvckbytes.component_markup.constructor.SlotType;
import at.blvckbytes.component_markup.expression.interpreter.InterpretationEnvironment;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import net.milkbowl.vault.economy.Economy;
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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class SellGuiCommand implements CommandExecutor, TabCompleter, Listener {

  private final ChestShopRegistry chestShopRegistry;
  private final Economy economy;
  private final @Nullable EcoLogger ecoLogger;
  private final ConfigKeeper<MainSection> config;

  private final Map<UUID, Inventory> sellInventoryByPlayerId;

  public SellGuiCommand(
    ChestShopRegistry chestShopRegistry,
    Economy economy,
    @Nullable EcoLogger ecoLogger,
    ConfigKeeper<MainSection> config
  ) {
    this.chestShopRegistry = chestShopRegistry;
    this.economy = economy;
    this.ecoLogger = ecoLogger;
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

    if (isOutsideOfAllowedRegionAndSendMessages(player, config))
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

    var sellSession = new SellToShopSession(chestShopRegistry, true);

    sellSession.removeItemsToSell(sellInventory, null);

    for (var slotIndex = 0; slotIndex < sellInventory.getSize(); ++slotIndex) {
      var currentItem = sellInventory.getItem(slotIndex);

      if (currentItem == null || currentItem.getType().isAir())
        continue;

      for (var remainder : player.getInventory().addItem(currentItem).values())
        player.getWorld().dropItem(player.getEyeLocation(), remainder);
    }

    sellInventory.clear();

    if (!sellSession.sendMessagesAndTransact(player, economy, ecoLogger, config))
      config.rootSection.sellGui.emptyInventory.sendMessage(player);
  }

  public static boolean isOutsideOfAllowedRegionAndSendMessages(Player player, ConfigKeeper<MainSection> config) {
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
}
