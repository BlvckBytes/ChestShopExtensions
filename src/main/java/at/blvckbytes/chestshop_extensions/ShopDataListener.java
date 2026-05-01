package at.blvckbytes.chestshop_extensions;

import at.blvckbytes.chestshop_extensions.config.MainSection;
import at.blvckbytes.cm_mapper.ConfigKeeper;
import com.Acrobot.Breeze.Utils.InventoryUtil;
import com.Acrobot.Breeze.Utils.PriceUtil;
import com.Acrobot.ChestShop.Events.ItemParseEvent;
import com.Acrobot.ChestShop.Events.ShopCreatedEvent;
import com.Acrobot.ChestShop.Events.ShopDestroyedEvent;
import com.Acrobot.ChestShop.Events.TransactionEvent;
import com.Acrobot.ChestShop.Signs.ChestShopSign;
import com.Acrobot.ChestShop.UUIDs.NameManager;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Chest;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.event.EventHandler;
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
  private final ConfigKeeper<MainSection> config;
  private final Logger logger;
  private final Set<ProtectedRegion> shopRegions;

  public ShopDataListener(
    Plugin plugin,
    ChestShopRegistry chestShopRegistry,
    ConfigKeeper<MainSection> config,
    Logger logger
  ) {
    this.plugin = plugin;
    this.chestShopRegistry = chestShopRegistry;
    this.shopRegions = new HashSet<>();
    this.config = config;
    this.logger = logger;

    loadShopRegions();
    config.registerReloadListener(this::loadShopRegions);
  }

  public boolean isShopRegion(ProtectedRegion region) {
    return shopRegions.contains(region);
  }

  @EventHandler
  public void onShopTransaction(TransactionEvent event) {
    var transactionItem = TransactionItem.of(event.getStock(), logger);

    if (transactionItem == null)
      return;

    var eventSign = event.getSign();
    var shopSigns = new HashMap<Location, ItemStack>();

    shopSigns.put(eventSign.getLocation(), transactionItem.itemClone);

    var signBlock = eventSign.getBlock();

    // Sign-posts on top of containers are not supported, thus we only need to acknowledge wall-signs.
    if (signBlock.getBlockData() instanceof WallSign wallSign) {
      var signFacing = wallSign.getFacing();
      var possibleContainer = signBlock.getRelative(signFacing.getOppositeFace());
      addRemainingSignsOfShopContainer(getAllBlocksOfContainer(possibleContainer), shopSigns);
    }

    var wasBuy = event.getTransactionType() == TransactionEvent.TransactionType.BUY;

    for (var signEntry : shopSigns.entrySet()) {
      // There may be multiple different items sold/bought from/into the very same physical
      // container, so only relay the transaction to the shops that are affected by it.
      if (!transactionItem.itemClone.isSimilar(signEntry.getValue()))
        continue;

      chestShopRegistry.onTransaction(signEntry.getKey(), transactionItem.totalAmount, wasBuy);
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
      var stock = InventoryUtil.getAmount(signEntry.getValue(), inventory);
      chestShopRegistry.onInventoryClose(signEntry.getKey(), stock, inventorySize);
    }
  }

  @EventHandler
  public void onShopCreated(ShopCreatedEvent event) {
    Bukkit.getScheduler().runTask(plugin, () -> possiblyRegisterShop(event.getSign(), event.getSignLines(), false));
  }

  @EventHandler
  public void onShopDestroyed(ShopDestroyedEvent event) {
    chestShopRegistry.onDestruction(event.getSign().getLocation());
  }

  @EventHandler
  public void onChunkLoad(ChunkLoadEvent event) {
    var chunk = event.getChunk();

    if (!isChunkInAnyShopRegion(chunk))
      return;

    for (var tileEntity : chunk.getTileEntities()) {
      if (tileEntity instanceof Sign sign) {
        if (!ChestShopSign.isValid(sign))
          continue;

        //noinspection deprecation
        possiblyRegisterShop(sign, sign.getLines(), true);
      }
    }
  }

  private void possiblyRegisterShop(Sign shopSign, String[] signLines, boolean wasOnChunkLoad) {
    var signLocation = shopSign.getLocation();

    if (wasOnChunkLoad && chestShopRegistry.getShopAtLocation(signLocation) != null)
      return;

    var itemParseEvent = new ItemParseEvent(ChestShopSign.getItem(signLines));
    Bukkit.getPluginManager().callEvent(itemParseEvent);
    var shopItem = itemParseEvent.getItem();

    if (shopItem == null || shopItem.getType() == Material.AIR) {
      logger.log(Level.WARNING, "Item-response was null/AIR for shop at " + signLocation + " while registering, sign=[" + joinStrings(signLines) + "]");
      return;
    }

    var ownerShortName = ChestShopSign.getOwner(signLines);

    if (ownerShortName.isBlank()) {
      logger.log(Level.WARNING, "Owner was blank for shop at " + signLocation + ", sign=[" + joinStrings(signLines) + "]");
      return;
    }

    // The name, stored on the first line of the sign, may in some cases be a shortened
    // version - ChestShop's NameManager is also used internally to resolve them to their
    // fully extended counterpart.

    //noinspection deprecation
    var ownerAccount = NameManager.getAccountFromShortName(ownerShortName);

    if (ownerAccount == null) {
      logger.log(Level.WARNING, "Owner-account was null for short-name " + ownerShortName + " for shop at " + signLocation + ", sign=[" + joinStrings(signLines) + "]");
      return;
    }

    var priceLine = ChestShopSign.getPrice(signLines);
    var buyPrice = PriceUtil.getExactBuyPrice(priceLine).doubleValue();
    var sellPrice = PriceUtil.getExactSellPrice(priceLine).doubleValue();

    if (buyPrice < 0 && sellPrice < 0) {
      logger.log(Level.WARNING, "No prices for shop at " + signLocation + ", sign=[" + joinStrings(signLines) + "]");
      return;
    }

    int stock = -1;
    int size = -1;

    // Manually look up the container, as ChestShop's utility disregards unloaded blocks, and
    // the container could be on an exact chunk-boundary; this will load said chunk if necessary.
    if (shopSign.getBlockData() instanceof WallSign wallSign) {
      var mountedOnFace = wallSign.getFacing().getOppositeFace();

      var mountedOnBlock = shopSign.getLocation()
        .add(mountedOnFace.getModX(), mountedOnFace.getModY(), mountedOnFace.getModZ())
        .getBlock();

      if (mountedOnBlock.getState() instanceof Container container) {
        stock = InventoryUtil.getAmount(shopItem, container.getInventory());
        size = container.getInventory().getSize();
      }
    }

    var quantity = ChestShopSign.getQuantity(signLines);

    if (quantity <= 0) {
      logger.log(Level.WARNING, "No quantity for shop at " + signLocation + ", sign=[" + joinStrings(signLines) + "]");
      return;
    }

    var shopEntry = new ChestShopEntry(
      shopItem,
      ownerAccount.getName(),
      ownerAccount.getUuid(),
      signLocation,
      quantity,
      buyPrice,
      sellPrice,
      stock,
      size
    );

    chestShopRegistry.onCreation(shopEntry);
  }

  private boolean isChunkInAnyShopRegion(Chunk chunk) {
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

  private static String joinStrings(String[] values) {
    var result = new StringBuilder();
    for (var value : values) {
      if (!result.isEmpty())
        result.append(',');

      result.append('"').append(value).append('"');
    }
    return result.toString();
  }

  private void loadShopRegions() {
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

        var sign = ((Sign) possibleSignBlock.getState());

        var itemLineContents = ChestShopSign.getItem(sign);

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

    if (!(containerBlock.getState() instanceof Container))
      return Collections.emptyList();

    result.add(containerBlock);

    var otherBlock = tryGetOtherChestHalf(containerBlock);

    if (otherBlock != null)
      result.add(otherBlock);

    return result;
  }
}
