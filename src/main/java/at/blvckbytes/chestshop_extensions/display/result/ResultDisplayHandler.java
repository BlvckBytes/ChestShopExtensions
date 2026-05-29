package at.blvckbytes.chestshop_extensions.display.result;

import at.blvckbytes.chestshop_extensions.ChestShopEntry;
import at.blvckbytes.chestshop_extensions.ChestShopRegistry;
import at.blvckbytes.chestshop_extensions.config.MainSection;
import at.blvckbytes.chestshop_extensions.display.DisplayHandler;
import at.blvckbytes.cm_mapper.ConfigKeeper;
import at.blvckbytes.component_markup.expression.interpreter.InterpretationEnvironment;
import com.Acrobot.ChestShop.Signs.ChestShopSign;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Rotatable;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.plugin.Plugin;

import java.util.logging.Logger;

public class ResultDisplayHandler extends DisplayHandler<ResultDisplay, ResultDisplayData> {

  private final SelectionStateStore stateStore;
  private final ChestShopRegistry shopRegistry;
  private final Logger logger;

  public ResultDisplayHandler(
    ConfigKeeper<MainSection> config,
    SelectionStateStore stateStore,
    ChestShopRegistry shopRegistry,
    Logger logger,
    Plugin plugin
  ) {
    super(config, plugin);

    this.stateStore = stateStore;
    this.shopRegistry = shopRegistry;
    this.logger = logger;

    shopRegistry.registerStockChangeListener(shop -> forEachDisplay(display -> display.onShopStockChange(shop)));
  }

  @Override
  public ResultDisplay instantiateDisplay(Player player, ResultDisplayData displayData) {
    var selectionState = stateStore.loadState(player);
    return new ResultDisplay(config, plugin, player, displayData, selectionState);
  }

  @Override
  protected void handleClick(Player player, ResultDisplay display, ClickType clickType, int slot) {
    var targetShop = display.getShopCorrespondingToSlot(slot);

    if (clickType == ClickType.LEFT) {
      if (config.rootSection.resultDisplay.items.previousPage.getDisplaySlots().contains(slot)) {
        display.previousPage();
        return;
      }

      if (config.rootSection.resultDisplay.items.backButton.getDisplaySlots().contains(slot)) {
        var overviewInfo = display.displayData.overviewDisplayInfo();

        if (overviewInfo != null)
          overviewInfo.overviewDisplay().reopen();

        return;
      }

      if (config.rootSection.resultDisplay.items.nextPage.getDisplaySlots().contains(slot)) {
        display.nextPage();
        return;
      }

      if (config.rootSection.resultDisplay.items.sorting.getDisplaySlots().contains(slot)) {
        display.nextSortingOrder();
        return;
      }

      if (config.rootSection.resultDisplay.items.filtering.getDisplaySlots().contains(slot)) {
        display.nextFilteringState();
        return;
      }

      if (targetShop != null) {
        player.closeInventory();
        teleportPlayer(player, targetShop);
        return;
      }

      return;
    }

    if (clickType == ClickType.RIGHT) {
      if (config.rootSection.resultDisplay.items.previousPage.getDisplaySlots().contains(slot)) {
        display.firstPage();
        return;
      }

      if (config.rootSection.resultDisplay.items.nextPage.getDisplaySlots().contains(slot)) {
        display.lastPage();
        return;
      }

      if (config.rootSection.resultDisplay.items.sorting.getDisplaySlots().contains(slot)) {
        display.resetSortingState();
        return;
      }

      if (config.rootSection.resultDisplay.items.filtering.getDisplaySlots().contains(slot)) {
        display.resetFilteringState();
        return;
      }

      // Used to open a preview at this point; don't think that's necessary for our server

      return;
    }

    if (clickType == ClickType.DROP) {
      if (config.rootSection.resultDisplay.items.sorting.getDisplaySlots().contains(slot))
        display.nextSortingSelection();

      if (config.rootSection.resultDisplay.items.filtering.getDisplaySlots().contains(slot))
        display.nextFilteringCriterion();

      return;
    }

    if (clickType == ClickType.CONTROL_DROP) {
      if (config.rootSection.resultDisplay.items.sorting.getDisplaySlots().contains(slot))
        display.moveSortingSelectionDown();
    }
  }

  public static void teleportPlayerToSign(Player player, Block signBlock) {
    var blockData = signBlock.getBlockData();

    BlockFace signFacing;

    if (blockData instanceof Directional directional)
      signFacing = directional.getFacing();

    else if (blockData instanceof Rotatable rotatable)
      signFacing = rotatable.getRotation();

    else
      return;

    var signCenter = signBlock.getLocation().clone();
    var footLocation = signBlock.getLocation().clone();

    switch (toCardinalFacing(signFacing)) {
      case NORTH -> {
        footLocation.add(.5, 0, -.1);
        signCenter.add(.5, .5, .9);
      }

      case SOUTH -> {
        footLocation.add(.5, 0, 1);
        signCenter.add(.5, .5, 0);
      }

      case WEST -> {
        footLocation.add(-.1, 0, .5);
        signCenter.add(.9, .5, .5);
      }

      case EAST -> {
        footLocation.add(1, 0, .5);
        signCenter.add(0, .5, .5);
      }

      default -> { return; }
    }

    var eyeLocation = footLocation.clone().add(0, 1.6, 0);
    var direction = signCenter.toVector().subtract(eyeLocation.toVector()).normalize();
    footLocation.setDirection(direction);

    player.teleport(footLocation);
  }

  private void teleportPlayer(Player player, ChestShopEntry targetShop) {
    var signBlock = targetShop.signLocation.getBlock();
    var coordinates =  targetShop.signLocation.getBlockX() + " " + targetShop.signLocation.getBlockY() + " " + targetShop.signLocation.getBlockZ();
    var worldName = targetShop.signLocation.getWorld().getName();

    if (!(signBlock.getState() instanceof Sign sign) || !ChestShopSign.isValid(sign)) {
      config.rootSection.playerMessages.shopTeleportShopGone.sendMessage(
        player,
        new InterpretationEnvironment()
          .withVariable("coordinates", coordinates)
          .withVariable("world", worldName)
          .withVariable("owner", targetShop.owner)
      );

      shopRegistry.onDestruction(targetShop.signLocation);
      return;
    }

    teleportPlayerToSign(player, signBlock);

    config.rootSection.playerMessages.shopTeleportTeleported.sendMessage(
      player,
      new InterpretationEnvironment()
        .withVariable("coordinates", coordinates)
        .withVariable("world", worldName)
        .withVariable("owner", targetShop.owner)
    );
  }

  private static BlockFace toCardinalFacing(BlockFace face) {
    return switch (face) {
      case EAST, EAST_NORTH_EAST, EAST_SOUTH_EAST, SOUTH_EAST -> BlockFace.EAST;
      case SOUTH, SOUTH_SOUTH_EAST, SOUTH_SOUTH_WEST, SOUTH_WEST -> BlockFace.SOUTH;
      case WEST, WEST_NORTH_WEST, WEST_SOUTH_WEST, NORTH_WEST -> BlockFace.WEST;
      default -> BlockFace.NORTH;
    };
  }
}
