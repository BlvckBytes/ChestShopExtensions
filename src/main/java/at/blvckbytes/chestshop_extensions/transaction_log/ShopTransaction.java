package at.blvckbytes.chestshop_extensions.transaction_log;

import at.blvckbytes.chestshop_extensions.config.MainSection;
import at.blvckbytes.cm_mapper.ConfigKeeper;
import at.blvckbytes.component_markup.markup.interpreter.DirectFieldAccess;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;

import javax.annotation.Nullable;
import java.util.*;

public class ShopTransaction implements DirectFieldAccess {

  public final String clientName;
  public final UUID clientId;
  public final UUID ownerId;
  public final Location signLocation;
  public final ItemStack item;
  public final int itemAmount;
  public final double price;
  public final boolean wasBuy;
  public final boolean didExhaust;
  public final long timestamp;

  public int indexInList;

  public ShopTransaction(
    String clientName,
    UUID clientId,
    UUID ownerId,
    Location signLocation,
    ItemStack item,
    int itemAmount,
    double price,
    boolean wasBuy,
    boolean didExhaust,
    long timestamp
  ) {
    this.clientName = clientName;
    this.clientId = clientId;
    this.ownerId = ownerId;
    this.signLocation = signLocation;
    this.item = item;
    this.itemAmount = itemAmount;
    this.price = price;
    this.wasBuy = wasBuy;
    this.didExhaust = didExhaust;
    this.timestamp = timestamp;
  }

  public long getAge() {
    return System.currentTimeMillis() - timestamp;
  }

  public JsonObject toJson() {
    var result = new JsonObject();

    result.addProperty("clientName", clientName);
    result.addProperty("clientId", clientId.toString());
    result.addProperty("ownerId", ownerId.toString());

    result.addProperty("sign", (
      signLocation.getBlockX()
        + " " + signLocation.getBlockY()
        + " " + signLocation.getBlockZ()
        + " " + (Objects.requireNonNull(signLocation.getWorld()).getName())
    ));

    result.addProperty("item", Base64.getEncoder().encodeToString(item.serializeAsBytes()));
    result.addProperty("itemAmount", itemAmount);
    result.addProperty("price", price);
    result.addProperty("wasBuy", wasBuy);
    result.addProperty("didExhaust", didExhaust);
    result.addProperty("timestamp", timestamp);

    return result;
  }

  @Override
  public Object accessField(String rawIdentifier) {
    return switch (rawIdentifier) {
      case "index" -> indexInList;
      case "client" -> clientName;
      case "x" -> signLocation.getBlockX();
      case "y" -> signLocation.getBlockY();
      case "z" -> signLocation.getBlockZ();
      case "world" -> Objects.requireNonNull(signLocation.getWorld()).getName();
      case "item_key" -> item.getType().translationKey();
      case "item" -> item;
      case "amount" -> itemAmount;
      case "price" -> price;
      case "was_buy" -> wasBuy;
      case "did_exhaust" -> didExhaust;
      case "timestamp" -> timestamp;
      case "age" -> getAge();
      default -> DirectFieldAccess.UNKNOWN_FIELD_SENTINEL;
    };
  }

  @Override
  public Set<String> getAvailableFields() {
    return Set.of("index", "client", "x", "y", "z", "world", "item_key", "item", "amount", "price", "was_buy", "did_exhaust", "timestamp", "age");
  }

  public @Nullable ShopTransaction mergeWithIfPossible(ShopTransaction other, ConfigKeeper<MainSection> config) {
    if (!ownerId.equals(other.ownerId) || !clientId.equals(other.clientId))
      return null;

    if (wasBuy != other.wasBuy)
      return null;

    var timeDelta = Math.abs(timestamp - other.timestamp);

    if (timeDelta / 1000 > config.rootSection.transactionLog.transactionMergeMaxDeltaSeconds)
      return null;

    if (!Objects.equals(signLocation.getWorld(), other.signLocation.getWorld()))
      return null;

    if (
      signLocation.getBlockX() != other.signLocation.getBlockX()
      || signLocation.getBlockY() != other.signLocation.getBlockY()
      || signLocation.getBlockZ() != other.signLocation.getBlockZ()
    ) {
      return null;
    }

    if (!item.isSimilar(other.item))
      return null;

    var thisUnitPrice = price / itemAmount;
    var otherUnitPrice = other.price / other.itemAmount;

    if (Math.abs(thisUnitPrice - otherUnitPrice) > .01)
      return null;

    return new ShopTransaction(
      clientName, clientId, ownerId, signLocation, item,
      itemAmount + other.itemAmount,
      price + other.price,
      wasBuy,
      didExhaust | other.didExhaust,
      // Keep the original timestamp, as to also prevent merging further than the max time-delta through "induction".
      timestamp
    );
  }

  public static ShopTransaction fromJson(JsonObject json) {
    if (!(json.get("clientName") instanceof JsonPrimitive clientNamePrimitive) || !clientNamePrimitive.isString())
      throw new IllegalStateException("Expected \"clientName\" to be a string");

    if (!(json.get("clientId") instanceof JsonPrimitive clientIdPrimitive) || !clientIdPrimitive.isString())
      throw new IllegalStateException("Expected \"clientId\" to be a string");

    if (!(json.get("ownerId") instanceof JsonPrimitive ownerPrimitive) || !ownerPrimitive.isString())
      throw new IllegalStateException("Expected \"ownerId\" to be a string");

    if (!(json.get("sign") instanceof JsonPrimitive signPrimitive) || !signPrimitive.isString())
      throw new IllegalStateException("Expected \"sign\" to be a string");

    var locationParts = signPrimitive.getAsString().split(" +");

    int x, y, z;
    String worldName;

    try {
      x = Integer.parseInt(locationParts[0]);
      y = Integer.parseInt(locationParts[1]);
      z = Integer.parseInt(locationParts[2]);
      worldName = locationParts[3];
    } catch (Throwable e) {
      throw new IllegalStateException("Encountered a malformed location-string");
    }

    var world = Bukkit.getWorld(worldName);

    if (world == null)
      throw new IllegalStateException("Encountered invalid world: \"" + worldName + "\"");

    if (!(json.get("item") instanceof JsonPrimitive itemPrimitive) || !itemPrimitive.isString())
      throw new IllegalStateException("Expected \"item\" to be a string");

    byte[] itemBytes;

    try {
      itemBytes = Base64.getDecoder().decode(itemPrimitive.getAsString());
    } catch (Throwable e) {
      throw new IllegalStateException("Encountered an invalid base64 item-string: " + itemPrimitive.getAsString(), e);
    }

    ItemStack item;

    try {
      item = ItemStack.deserializeBytes(itemBytes);
    } catch (Throwable e) {
      throw new IllegalStateException("Encountered invalid item-bytes in the base64-string", e);
    }

    if (!(json.get("itemAmount") instanceof JsonPrimitive itemAmountPrimitive) || !itemAmountPrimitive.isNumber())
      throw new IllegalStateException("Expected \"itemAmount\" to be a number");

    if (!(json.get("price") instanceof JsonPrimitive pricePrimitive) || !pricePrimitive.isNumber())
      throw new IllegalStateException("Expected \"price\" to be a number");

    if (!(json.get("wasBuy") instanceof JsonPrimitive wasBuyPrimitive) || !wasBuyPrimitive.isBoolean())
      throw new IllegalStateException("Expected \"wasBuy\" to be a boolean");

    if (!(json.get("didExhaust") instanceof JsonPrimitive didExhaustPrimitive) || !didExhaustPrimitive.isBoolean())
      throw new IllegalStateException("Expected \"didExhaust\" to be a boolean");

    if (!(json.get("timestamp") instanceof JsonPrimitive timestampPrimitive) || !timestampPrimitive.isNumber())
      throw new IllegalStateException("Expected \"timestamp\" to be a number");

    return new ShopTransaction(
      clientNamePrimitive.getAsString(),
      UUID.fromString(clientIdPrimitive.getAsString()),
      UUID.fromString(ownerPrimitive.getAsString()),
      new Location(world, x, y, z),
      item,
      itemAmountPrimitive.getAsInt(),
      pricePrimitive.getAsDouble(),
      wasBuyPrimitive.getAsBoolean(),
      didExhaustPrimitive.getAsBoolean(),
      timestampPrimitive.getAsLong()
    );
  }
}
