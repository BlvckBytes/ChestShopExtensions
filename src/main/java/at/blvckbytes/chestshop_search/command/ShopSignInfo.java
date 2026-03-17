package at.blvckbytes.chestshop_search.command;

import com.Acrobot.Breeze.Utils.PriceUtil;
import com.Acrobot.ChestShop.Events.ItemParseEvent;
import com.Acrobot.ChestShop.Signs.ChestShopSign;
import org.bukkit.Bukkit;
import org.bukkit.block.Sign;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.math.MathContext;

public record ShopSignInfo(
  Sign sign,
  boolean directlyTargeted,
  ItemStack item,
  BigDecimal normalizedBuyPrice,
  BigDecimal normalizedSellPrice
) {

  public static @Nullable ShopSignInfo tryParse(Sign sign, boolean directlyTargeted) {
    int quantity;

    try {
      quantity = ChestShopSign.getQuantity(sign);

      if (quantity <= 0)
        throw new IllegalArgumentException();
    } catch (Throwable e) {
      return null;
    }

    var priceLine = ChestShopSign.getPrice(sign);

    var buyPrice = PriceUtil.getExactBuyPrice(priceLine);
    var sellPrice = PriceUtil.getExactSellPrice(priceLine);

    if (PriceUtil.NO_PRICE.equals(buyPrice) && PriceUtil.NO_PRICE.equals(sellPrice))
      return null;

    var normalizedBuyPrice = buyPrice;

    if (!PriceUtil.NO_PRICE.equals(buyPrice))
      normalizedBuyPrice = buyPrice.divide(BigDecimal.valueOf(quantity), MathContext.DECIMAL128);

    var normalizedSellPrice = sellPrice;

    if (!PriceUtil.NO_PRICE.equals(sellPrice))
      normalizedSellPrice = sellPrice.divide(BigDecimal.valueOf(quantity), MathContext.DECIMAL128);

    var parseEvent = new ItemParseEvent(ChestShopSign.getItem(sign));
    Bukkit.getPluginManager().callEvent(parseEvent);

    var item = parseEvent.getItem();

    if (item == null)
      return null;

    return new ShopSignInfo(sign, directlyTargeted, item, normalizedBuyPrice, normalizedSellPrice);
  }

  public BigDecimal getNormalizedPrice(boolean doBuy) {
    return doBuy ? normalizedBuyPrice : normalizedSellPrice;
  }
}
