package at.blvckbytes.chestshop_search.command;

import at.blvckbytes.chestshop_search.ShopDataListener;
import at.blvckbytes.chestshop_search.config.MainSection;
import at.blvckbytes.cm_mapper.ConfigKeeper;
import at.blvckbytes.component_markup.constructor.SlotType;
import at.blvckbytes.component_markup.expression.interpreter.InterpretationEnvironment;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.block.Sign;
import org.bukkit.block.data.Directional;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class ShopItemInfoCommand implements CommandExecutor, TabCompleter, Listener {

  private static final BlockFace[] SIGN_MOUNT_FACES = {
    BlockFace.UP, BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST
  };

  private final ConfigKeeper<MainSection> config;
  private final Map<UUID, Inventory> previewInventoryByPlayerId;

  public ShopItemInfoCommand(ConfigKeeper<MainSection> config) {
    this.config = config;
    this.previewInventoryByPlayerId = new HashMap<>();
  }

  @Override
  public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
    if (!(sender instanceof Player player))
      return false;

    var signInfos = getTargetedSignInfos(player);

    if (signInfos.isEmpty()) {
      config.rootSection.playerMessages.notLookingAtShopSign.sendMessage(player);
      return true;
    }

    var firstInfo = signInfos.getFirst();

    if (!firstInfo.directlyTargeted()) {
      for (var currentInfo : signInfos) {
        if (!firstInfo.item().isSimilar(currentInfo.item())) {
          config.rootSection.playerMessages.multipleSignsToChooseFrom.sendMessage(player);
          return true;
        }
      }
    }

    var inventory = Bukkit.createInventory(
      null,
      9 * 3,
      config.rootSection.shopItemInfo.previewTitle.interpret(
        SlotType.INVENTORY_TITLE,
        new InterpretationEnvironment()
          .withVariable("command_label", label)
          .withVariable("x", firstInfo.sign().getX())
          .withVariable("y", firstInfo.sign().getY())
          .withVariable("z", firstInfo.sign().getZ())
      ).getFirst()
    );

    inventory.setItem(13, firstInfo.item());

    player.closeInventory();

    previewInventoryByPlayerId.put(player.getUniqueId(), inventory);

    player.openInventory(inventory);

    return true;
  }

  @Override
  public @Nullable List<String> onTabComplete(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String @NotNull [] strings) {
    return List.of();
  }

  @EventHandler
  public void onClick(InventoryClickEvent event) {
    handleInventoryInteraction(event);
  }

  @EventHandler
  public void onDrag(InventoryDragEvent event) {
    handleInventoryInteraction(event);
  }

  private void handleInventoryInteraction(InventoryInteractEvent event) {
    if (!(event.getWhoClicked() instanceof Player player))
      return;

    var topInventory = player.getOpenInventory().getTopInventory();
    var previewInventory = previewInventoryByPlayerId.get(player.getUniqueId());

    if (previewInventory != null && previewInventory.equals(topInventory))
      event.setCancelled(true);
  }

  @EventHandler
  public void onClose(InventoryCloseEvent event) {
    var playerId = event.getPlayer().getUniqueId();
    var previewInventory = previewInventoryByPlayerId.get(playerId);

    if (previewInventory != null && previewInventory.equals(event.getInventory()))
      previewInventoryByPlayerId.remove(playerId);
  }

  @EventHandler
  public void onQuit(PlayerQuitEvent event) {
    previewInventoryByPlayerId.remove(event.getPlayer().getUniqueId());
  }

  public static List<ShopSignInfo> getTargetedSignInfos(Player player) {
    var signInfos = new ArrayList<ShopSignInfo>();
    var seenSignLocations = new HashSet<Location>();

    var hitResult = player.getWorld().rayTraceBlocks(
      player.getEyeLocation(),
      player.getEyeLocation().getDirection(),
      5, FluidCollisionMode.NEVER, false
    );

    Block block;

    if (hitResult == null || (block = hitResult.getHitBlock()) == null)
      return signInfos;

    if (block.getState() instanceof Sign sign) {
      var signInfo = ShopSignInfo.tryParse(sign, true);

      if (signInfo != null) {
        seenSignLocations.add(block.getLocation());
        signInfos.add(signInfo);
      }

      var blockData = block.getBlockData();

      if (!Tag.WALL_SIGNS.isTagged(blockData.getMaterial()))
        return signInfos;

      var mountBlock = block.getRelative(((Directional) blockData).getFacing().getOppositeFace());

      if (!(mountBlock.getState() instanceof Container))
        return signInfos;

      // Continue to find all signs of the corresponding container, as the consumer may have a use
      // for them and they do count as indirectly targeted, seeing how the container represents a shop.
      block = mountBlock;
    }

    findAttachedSigns(block, signInfos, seenSignLocations, null);

    var otherHalf = ShopDataListener.tryGetOtherChestHalf(block);

    if (otherHalf != null)
      findAttachedSigns(otherHalf, signInfos, seenSignLocations, block);

    // Directly targeted signs come first, such that we can later use #getFirst to access it, if available.
    signInfos.sort(Comparator.comparing(ShopSignInfo::directlyTargeted).reversed());

    return signInfos;
  }

  private static void findAttachedSigns(
    Block origin,
    List<ShopSignInfo> output,
    Set<Location> seenSignLocations,
    @Nullable Block ignoredBlock
  ) {
    for (var mountFace : SIGN_MOUNT_FACES) {
      var currentBlock = origin.getRelative(mountFace);

      if (ignoredBlock != null && ignoredBlock.equals(currentBlock))
        continue;

      var blockData = currentBlock.getBlockData();

      if (!Tag.ALL_SIGNS.isTagged(blockData.getMaterial()))
        continue;

      if (mountFace == BlockFace.UP) {
        if (!Tag.STANDING_SIGNS.isTagged(blockData.getMaterial()))
          continue;
      }

      else {
        if (!Tag.WALL_SIGNS.isTagged(blockData.getMaterial()))
          continue;

        if (((Directional) blockData).getFacing() != mountFace)
          continue;
      }

      var signInfo = ShopSignInfo.tryParse((Sign) currentBlock.getState(), false);

      if (signInfo == null)
        continue;

      if (!seenSignLocations.add(currentBlock.getLocation()))
        continue;

      output.add(signInfo);
    }
  }
}
