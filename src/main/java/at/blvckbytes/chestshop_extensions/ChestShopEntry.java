package at.blvckbytes.chestshop_extensions;

import at.blvckbytes.component_markup.expression.interpreter.InterpretationEnvironment;
import com.Acrobot.Breeze.Utils.PriceUtil;
import com.Acrobot.ChestShop.Events.ItemParseEvent;
import com.Acrobot.ChestShop.Signs.ChestShopSign;
import com.Acrobot.ChestShop.UUIDs.NameManager;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Container;
import org.bukkit.block.Sign;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.block.sign.Side;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import javax.annotation.Nullable;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.MathContext;
import java.text.DecimalFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ChestShopEntry {

  private static final int MAX_INVENTORY_SIZE = 6 * 9;

  public static final long SHOP_UPDATE_INTERVAL_T = 20 * 5;

  private static final DecimalFormat DECIMAL_FORMAT = new DecimalFormat("0.##");

  public final ItemStack item;
  public final String owner;
  public final UUID ownerId;
  public final Location signLocation;
  public final Side side;
  public final World world;
  public final int quantity;
  public final double buyPrice;
  public final double sellPrice;
  public final BlockVector3 blockVector;

  public final BigDecimal normalizedBuyPrice;
  public final BigDecimal normalizedSellPrice;

  public int stock;
  public int containerSize;

  private long lastUpdateStamp;

  public ChestShopEntry(
    ItemStack item,
    String owner,
    UUID ownerId,
    Location signLocation,
    Side side,
    int quantity,
    double buyPrice,
    double sellPrice,
    int stock,
    int containerSize
  ) {
    this.item = item;
    this.owner = owner.trim();
    this.ownerId = ownerId;
    this.signLocation = signLocation;
    this.side = side;

    this.world = signLocation.getWorld();

    if (this.world == null)
      throw new IllegalStateException("Requiring shop-signs to reside at locations with loaded worlds");

    this.quantity = quantity;
    this.buyPrice = buyPrice;
    this.sellPrice = sellPrice;
    this.stock = stock;
    this.containerSize = containerSize;
    this.blockVector = BukkitAdapter.adapt(signLocation).toVector().toBlockPoint();

    this.normalizedBuyPrice = quantity <= 0 ? new BigDecimal(buyPrice) : new BigDecimal(buyPrice).divide(new BigDecimal(quantity), MathContext.DECIMAL128);
    this.normalizedSellPrice = quantity <= 0 ? new BigDecimal(sellPrice) : new BigDecimal(sellPrice).divide(new BigDecimal(quantity), MathContext.DECIMAL128);
  }

  public boolean updateAndGetIfRemove(long relativeTime, boolean force, Logger logger, Consumer<ChestShopEntry> updatedInstanceHandler) {
    // Never force-load chunks - only update if they're already loaded.
    if (!world.isChunkLoaded(signLocation.getBlockX() >> 4, signLocation.getBlockZ() >> 4))
      return false;

    if (!force && relativeTime - lastUpdateStamp < SHOP_UPDATE_INTERVAL_T)
      return false;

    ChestShopEntry newEntry;

    if (
      !(signLocation.getBlock().getState(false) instanceof Sign sign)
        || (newEntry = tryCreateFromSign(sign, side, ComponentUtil.getSignLines(sign.getSide(side)))) == null
    ) {
      logger.info("Removed no-longer-existing shop at " + signLocation.getBlockX() + " " + signLocation.getBlockY() + " " + signLocation.getBlockZ());
      return true;
    }

    lastUpdateStamp = relativeTime;

    if (!doParametersDifferFrom(newEntry))
      return false;

    updatedInstanceHandler.accept(newEntry);
    return false;
  }

  public boolean doParametersDifferFrom(ChestShopEntry other) {
    if (!item.isSimilar(other.item))
      return true;

    if (!owner.equalsIgnoreCase(other.owner) || !ownerId.equals(other.ownerId))
      return true;

    if (quantity != other.quantity)
      return true;

    if (buyPrice != other.buyPrice || sellPrice != other.sellPrice)
      return true;

    return stock != other.stock || containerSize != other.containerSize;
  }

  public InterpretationEnvironment getEnvironment() {
    return new InterpretationEnvironment()
      .withVariable("owner", owner)
      .withVariable("quantity", quantity)
      .withVariable("buy_price", DECIMAL_FORMAT.format(buyPrice))
      .withVariable("sell_price", DECIMAL_FORMAT.format(sellPrice))
      .withVariable("remaining_stock", this.stock)
      .withVariable("remaining_space", this.calculateSpace())
      .withVariable("can_buy", buyPrice >= 0)
      .withVariable("can_sell", sellPrice >= 0)
      .withVariable("loc_world", signLocation.getWorld().getName())
      .withVariable("loc_x", signLocation.getBlockX())
      .withVariable("loc_y", signLocation.getBlockY())
      .withVariable("loc_z", signLocation.getBlockZ());
  }

  public int calculateSpace() {
    if (containerSize < 0)
      return -1;

    var maximumCapacity = containerSize * item.getMaxStackSize();
    return Math.max(0, maximumCapacity - stock);
  }

  public @Nullable JsonElement toJson(Logger logger) {
    try {
      var yamlConfig = new YamlConfiguration();

      yamlConfig.set("item", item);
      yamlConfig.set("owner", owner);
      yamlConfig.set("ownerId", ownerId.toString());
      yamlConfig.set("signLocation", signLocation);
      yamlConfig.set("side", side.name());
      yamlConfig.set("quantity", quantity);
      yamlConfig.set("buyPrice", buyPrice);
      yamlConfig.set("sellPrice", sellPrice);
      yamlConfig.set("stock", stock);
      yamlConfig.set("containerSize", containerSize);

      return new JsonPrimitive(yamlConfig.saveToString());
    } catch (Throwable e) {
      logger.log(Level.WARNING, "An error occurred while trying to stringify a shop to it's YAML-representation", e);
      return null;
    }
  }

  public static @Nullable ChestShopEntry fromJson(JsonElement json, Logger logger, long relativeTime) {
    try {
      var yamlConfig = YamlConfiguration.loadConfiguration(new StringReader(json.getAsString()));

      var entry = new ChestShopEntry(
        Objects.requireNonNull(yamlConfig.getItemStack("item")),
        Objects.requireNonNull(yamlConfig.getString("owner")),
        UUID.fromString(Objects.requireNonNull(yamlConfig.getString("ownerId"))),
        Objects.requireNonNull(yamlConfig.getLocation("signLocation")),
        Side.valueOf(yamlConfig.getString("side", "FRONT").toUpperCase().trim()),
        yamlConfig.getInt("quantity"),
        yamlConfig.getDouble("buyPrice"),
        yamlConfig.getDouble("sellPrice"),
        yamlConfig.getInt("stock"),
        yamlConfig.getInt("containerSize", 0)
      );

      entry.lastUpdateStamp = relativeTime;

      return entry;
    } catch (Throwable e) {
      logger.log(Level.WARNING, "An error occurred while trying to parse a shop from it's YAML-representation", e);
      return null;
    }
  }

  public static SpaceAndStock countItems(Inventory inventory, ItemStack item) {
    // Fake admin-shop inventories have a size of Integer#MAX_VALUE, which can easily
    // crash the server if spammed... No idea why they did that.
    var inventorySize = Math.min(MAX_INVENTORY_SIZE, inventory.getSize());

    var maxStackSize = item.getMaxStackSize();

    var spaceCount = 0;
    var stockCount = 0;

    for (var slotIndex = 0; slotIndex < inventorySize; ++slotIndex) {
      var currentItem = inventory.getItem(slotIndex);

      if (currentItem == null) {
        spaceCount += maxStackSize;
        continue;
      }

      if (!item.isSimilar(currentItem))
        continue;

      var currentAmount = currentItem.getAmount();
      var remainingSpace = maxStackSize - currentAmount;

      if (remainingSpace > 0)
        spaceCount += remainingSpace;

      stockCount += currentAmount;
    }

    return new SpaceAndStock(spaceCount, stockCount);
  }

  public static @Nullable ChestShopEntry tryCreateFromSign(Sign shopSign, Side side, String[] signLines) {
    var signLocation = shopSign.getLocation();

    if (!ChestShopSign.isValid(signLines))
      return null;

    var itemParseEvent = new ItemParseEvent(ChestShopSign.getItem(signLines));
    Bukkit.getPluginManager().callEvent(itemParseEvent);
    var shopItem = itemParseEvent.getItem();

    if (shopItem == null || shopItem.getType() == Material.AIR)
      return null;

    var ownerShortName = ChestShopSign.getOwner(signLines);

    if (ownerShortName.isBlank())
      return null;

    // The name, stored on the first line of the sign, may in some cases be a shortened
    // version - ChestShop's NameManager is also used internally to resolve them to their
    // fully extended counterpart.

    //noinspection deprecation
    var ownerAccount = NameManager.getAccountFromShortName(ownerShortName);

    if (ownerAccount == null)
      return null;

    var priceLine = ChestShopSign.getPrice(signLines);
    var buyPrice = PriceUtil.getExactBuyPrice(priceLine).doubleValue();
    var sellPrice = PriceUtil.getExactSellPrice(priceLine).doubleValue();

    if (buyPrice < 0 && sellPrice < 0)
      return null;

    int stock = -1;
    int size = -1;

    // Manually look up the container, as ChestShop's utility disregards unloaded blocks, and
    // the container could be on an exact chunk-boundary; this will load said chunk if necessary.
    if (shopSign.getBlockData() instanceof WallSign wallSign) {
      var mountedOnFace = wallSign.getFacing().getOppositeFace();

      var mountedOnBlock = shopSign.getLocation()
        .add(mountedOnFace.getModX(), mountedOnFace.getModY(), mountedOnFace.getModZ())
        .getBlock();

      if (mountedOnBlock.getState(false) instanceof Container container) {
        stock = countItems(container.getInventory(), shopItem).stock();
        size = container.getInventory().getSize();
      }
    }

    var quantity = ChestShopSign.getQuantity(signLines);

    if (quantity <= 0)
      return null;

    return new ChestShopEntry(
      shopItem,
      ownerAccount.getName(),
      ownerAccount.getUuid(),
      signLocation,
      side,
      quantity,
      buyPrice,
      sellPrice,
      stock,
      size
    );
  }
}
