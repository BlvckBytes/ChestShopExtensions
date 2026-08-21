package at.blvckbytes.chestshop_extensions;

import at.blvckbytes.chestshop_extensions.config.MainSection;
import at.blvckbytes.chestshop_extensions.transaction_log.ShopTransactionLogger;
import at.blvckbytes.cm_mapper.ConfigKeeper;
import at.blvckbytes.cm_mapper.ConfigKeeperReloadEvent;
import com.Acrobot.ChestShop.Events.ItemParseEvent;
import com.Acrobot.ChestShop.Events.ShopCreatedEvent;
import com.Acrobot.ChestShop.Events.ShopDestroyedEvent;
import com.Acrobot.ChestShop.Events.TransactionEvent;
import com.Acrobot.ChestShop.Signs.ChestShopSign;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Chest;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.block.sign.Side;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.DoubleChestInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ShopDataListener implements Listener {

  private static final BlockFace[] CONTAINER_SIGN_FACES = new BlockFace[] {
    BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST
  };

  private final Plugin plugin;
  private final ChestShopRegistry chestShopRegistry;
  private final ShopTransactionLogger transactionLogger;
  private final ConfigKeeper<MainSection> config;
  private final Logger logger;
  private final Set<ProtectedRegion> shopRegions;

  public ShopDataListener(
    Plugin plugin,
    ChestShopRegistry chestShopRegistry,
    ShopTransactionLogger transactionLogger,
    ConfigKeeper<MainSection> config,
    Logger logger
  ) {
    this.plugin = plugin;
    this.chestShopRegistry = chestShopRegistry;
    this.transactionLogger = transactionLogger;
    this.shopRegions = new HashSet<>();
    this.config = config;
    this.logger = logger;

    loadShopRegionsAndRemoveShopsOutside();
  }

  public boolean isShopRegion(ProtectedRegion region) {
    return shopRegions.contains(region);
  }

  @EventHandler
  public void onConfigReload(ConfigKeeperReloadEvent event) {
    if (event.configKeeper == config)
      loadShopRegionsAndRemoveShopsOutside();
  }

  @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
  public void onShopTransaction(TransactionEvent event) {
    var eventSign = event.getSign();

    if (ChestShopSign.isAdminShop(ComponentUtil.getSignLines(eventSign.getSide(event.getSide()))))
      return;

    var transactionItem = TransactionItem.of(event.getStock(), logger);

    if (transactionItem == null)
      return;

    var transactionType = event.getTransactionType();

    if (transactionType != TransactionEvent.TransactionType.BUY && transactionType != TransactionEvent.TransactionType.SELL) {
      logger.warning("Encountered unaccounted-for transaction-type: " + transactionType.name());
      return;
    }

    var newCounts = ChestShopEntry.countItems(event.getOwnerInventory(), transactionItem.itemClone);

    var wasBuy = transactionType == TransactionEvent.TransactionType.BUY;
    var didExhaust = wasBuy ? newCounts.stock() == 0 : newCounts.space() == 0;

    transactionLogger.onTransaction(
      event.getClient(),
      Bukkit.getOfflinePlayer(event.getOwnerAccount().getUuid()),
      eventSign.getLocation(),
      transactionItem,
      event.getExactPrice().doubleValue(),
      wasBuy,
      didExhaust
    );

    var shopSigns = new HashMap<Location, ItemStack>();

    shopSigns.put(eventSign.getLocation(), transactionItem.itemClone);

    var signBlock = eventSign.getBlock();

    // Sign-posts on top of containers are not supported, thus we only need to acknowledge wall-signs.
    if (signBlock.getBlockData() instanceof WallSign wallSign) {
      var signFacing = wallSign.getFacing();
      var possibleContainer = signBlock.getRelative(signFacing.getOppositeFace());
      addRemainingSignsOfShopContainer(getAllBlocksOfContainer(possibleContainer), shopSigns);
    }

    var containerSize = event.getOwnerInventory().getSize();

    for (var signEntry : shopSigns.entrySet()) {
      // There may be multiple different items sold/bought from/into the very same physical
      // container, so only relay the transaction to the shops that are affected by it.
      if (!transactionItem.itemClone.isSimilar(signEntry.getValue()))
        continue;

      chestShopRegistry.onStockChange(signEntry.getKey(), Side.FRONT, newCounts.stock(), containerSize);
    }
  }

  @EventHandler
  public void onInventoryClose(InventoryCloseEvent event) {
    var inventory = event.getInventory();
    List<Block> containerBlocks;

    if (inventory instanceof DoubleChestInventory doubleChestInventory) {
      containerBlocks = new ArrayList<>();

      if (doubleChestInventory.getLeftSide().getHolder() instanceof Container container)
        containerBlocks.add(container.getBlock());

      if (doubleChestInventory.getRightSide().getHolder() instanceof Container container)
        containerBlocks.add(container.getBlock());
    }

    else if (inventory.getHolder() instanceof BlockInventoryHolder blockInventoryHolder)
      containerBlocks = getAllBlocksOfContainer(blockInventoryHolder.getBlock());

    else
      return;

    var shopSigns = new HashMap<Location, ItemStack>();

    addRemainingSignsOfShopContainer(containerBlocks, shopSigns);

    var inventorySize = inventory.getSize();

    for (var signEntry : shopSigns.entrySet()) {
      // Relay an update to all attached shops, no matter their type, seeing how we specifically count
      // items for each individual sign; the user may have restocked multiple different items at once.
      var newCounts = ChestShopEntry.countItems(inventory, signEntry.getValue());
      chestShopRegistry.onStockChange(signEntry.getKey(), Side.FRONT, newCounts.stock(), inventorySize);
    }
  }

  @EventHandler
  public void onShopCreated(ShopCreatedEvent event) {
    Bukkit.getScheduler().runTask(plugin, () -> possiblyRegisterShop(event.getSign(), event.getSide(), event.getSignLines(), false));
  }

  @EventHandler
  public void onShopDestroyed(ShopDestroyedEvent event) {
    chestShopRegistry.onDestruction(event.getSign().getLocation(), event.getSide());
  }

  @EventHandler
  public void onChunkLoad(ChunkLoadEvent event) {
    var chunk = event.getChunk();

    if (!isChunkInAnyShopRegion(chunk))
      return;

    for (var tileEntity : chunk.getTileEntities()) {
      if (tileEntity instanceof Sign sign) {
        possiblyRegisterShop(sign, Side.FRONT, ComponentUtil.getSignLines(sign.getSide(Side.FRONT)), true);
        possiblyRegisterShop(sign, Side.BACK, ComponentUtil.getSignLines(sign.getSide(Side.BACK)), true);
      }
    }
  }

  private void possiblyRegisterShop(Sign shopSign, Side side, String[] signLines, boolean wasOnChunkLoad) {
    var signLocation = shopSign.getLocation();

    if (!isLocationInAnyShopRegion(signLocation))
      return;

    if (wasOnChunkLoad && chestShopRegistry.getShopAt(signLocation, side) != null)
      return;

    var shopEntry = ChestShopEntry.tryCreateFromSign(shopSign, side, signLines);

    if (shopEntry == null)
      return;

    chestShopRegistry.onCreation(shopEntry);
  }

  private boolean isLocationInAnyShopRegion(Location location) {
    var world = location.getWorld();

    if (world == null)
      return false;

    if (!config.rootSection.regionFilter.shopRegionWorlds.contains(world.getName()))
      return false;

    var x = location.getBlockX();
    var y = location.getBlockY();
    var z = location.getBlockZ();

    for (var region : shopRegions) {
      BlockVector3 regionMin = region.getMinimumPoint();
      BlockVector3 regionMax = region.getMaximumPoint();

      if (x > regionMax.x() || x < regionMin.x())
        continue;

      if (y > regionMax.y() || y < regionMin.y())
        continue;

      if (z > regionMax.z() || z < regionMin.z())
        continue;

      return true;
    }

    return false;
  }

  private boolean isChunkInAnyShopRegion(Chunk chunk) {
    if (!config.rootSection.regionFilter.shopRegionWorlds.contains(chunk.getWorld().getName()))
      return false;

    var chunkX = chunk.getX();
    var chunkZ = chunk.getZ();
    var minBlockX = chunkX << 4;
    var minBlockZ = chunkZ << 4;
    var maxBlockX = minBlockX + 15;
    var maxBlockZ = minBlockZ + 15;

    for (var region : shopRegions) {
      BlockVector3 regionMin = region.getMinimumPoint();
      BlockVector3 regionMax = region.getMaximumPoint();

      if (regionMax.x() < minBlockX || regionMin.x() > maxBlockX)
        continue;

      if (regionMax.z() < minBlockZ || regionMin.z() > maxBlockZ)
        continue;

      return true;
    }

    return false;
  }

  private void loadShopRegionsAndRemoveShopsOutside() {
    this.shopRegions.clear();

    var shopRegionPattern = config.rootSection.regionFilter.compiledShopRegionPattern;
    var shopRegionWorlds = config.rootSection.regionFilter.shopRegionWorlds;
    var regionContainer = WorldGuard.getInstance().getPlatform().getRegionContainer();

    for (var world : Bukkit.getWorlds()) {
      if (!shopRegionWorlds.contains(world.getName()))
        continue;

      var regionManager = regionContainer.get(BukkitAdapter.adapt(world));

      if (regionManager == null)
        continue;

      for (var regionEntry : regionManager.getRegions().entrySet()) {
        if (!shopRegionPattern.matcher(regionEntry.getKey()).matches())
          continue;

        this.shopRegions.add(regionEntry.getValue());
      }
    }

    if (this.shopRegions.isEmpty())
      logger.log(Level.WARNING, "Encountered zero matching shop-regions");
    else
      logger.log(Level.INFO, "Encountered " + this.shopRegions.size() + " matching shop-region(s)");

    var deletionCounter = new MutableInt();

    chestShopRegistry.deleteShopIf(shop -> {
      if (isLocationInAnyShopRegion(shop.signLocation))
        return false;

      deletionCounter.value += 1;
      return true;
    });

    if (deletionCounter.value > 0)
      logger.log(Level.INFO, "Deleted " + deletionCounter.value + " shops outside of the currently configured regions");
  }

  private void addRemainingSignsOfShopContainer(List<Block> containerBlocks, Map<Location, ItemStack> output) {
    for (var currentBlock : containerBlocks) {
      for (var currentFace : CONTAINER_SIGN_FACES) {
        var possibleSignBlock = currentBlock.getRelative(currentFace);
        var possibleSignLocation = possibleSignBlock.getLocation();

        if (output.containsKey(possibleSignLocation))
          continue;

        if (!Tag.WALL_SIGNS.isTagged(possibleSignBlock.getType()))
          continue;

        var signFacing = ((Directional) possibleSignBlock.getBlockData()).getFacing();

        if (signFacing != currentFace)
          continue;

        var sign = ((Sign) possibleSignBlock.getState(false));

        var itemLineContents = ChestShopSign.getItem(sign, null);

        if (itemLineContents.isBlank())
          continue;

        var itemParseEvent = new ItemParseEvent(itemLineContents);

        Bukkit.getPluginManager().callEvent(itemParseEvent);

        var shopItem = itemParseEvent.getItem();

        if (shopItem == null || shopItem.getType() == Material.AIR)
          continue;

        output.put(possibleSignLocation, shopItem);
      }
    }
  }

  public static @Nullable Block tryGetOtherChestHalf(Block block) {
    if (!(block.getBlockData() instanceof Chest chest))
      return null;

    var type = chest.getType();

    if (type == Chest.Type.SINGLE)
      return null;

    int dx = 0, dz = 0;

    // Left and right are relative to the chest itself, i.e. opposite to what
    // a player placing the appropriate block would see.

    switch (chest.getFacing()) {
      case NORTH: // -z
        dx = (type == Chest.Type.LEFT) ? 1 : -1;
        break;
      case SOUTH: // +z
        dx = (type == Chest.Type.LEFT) ? -1 : 1;
        break;
      case EAST: // +x
        dz = (type == Chest.Type.LEFT) ? 1 : -1;
        break;
      case WEST: // -x
        dz = (type == Chest.Type.LEFT) ? -1 : 1;
        break;
    }

    return block.getRelative(dx, 0, dz);
  }

  private List<Block> getAllBlocksOfContainer(Block containerBlock) {
    var result = new ArrayList<Block>();

    if (!(containerBlock.getState(false) instanceof Container))
      return Collections.emptyList();

    result.add(containerBlock);

    var otherBlock = tryGetOtherChestHalf(containerBlock);

    if (otherBlock != null)
      result.add(otherBlock);

    return result;
  }
}
