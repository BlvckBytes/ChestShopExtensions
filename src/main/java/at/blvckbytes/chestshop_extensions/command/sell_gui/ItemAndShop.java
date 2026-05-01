package at.blvckbytes.chestshop_extensions.command.sell_gui;

import at.blvckbytes.chestshop_extensions.ChestShopEntry;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class ItemAndShop extends ItemAndCount {

  public final ChestShopEntry shop;

  private @Nullable String formattedTotalValue;

  public ItemAndShop(ItemStack item, ChestShopEntry shop) {
    super(item);

    this.shop = shop;
  }

  @Override
  public @Nullable Object accessField(String rawIdentifier) {
    if (rawIdentifier.equals("value"))
      return formattedTotalValue;

    return super.accessField(rawIdentifier);
  }

  @Override
  public @Nullable Set<String> getAvailableFields() {
    return extendSet(super.getAvailableFields(), "value");
  }

  public double computeTotalValueAndSetFormatted(Economy economy) {
    var totalValue = shop.normalizedSellPrice.multiply(BigDecimal.valueOf(count.value)).doubleValue();
    this.formattedTotalValue = economy.format(totalValue);
    return totalValue;
  }

  private static Set<String> extendSet(Set<String> originalSet, String... values) {
    var newSet = new HashSet<>(originalSet);
    newSet.addAll(Arrays.asList(values));
    return newSet;
  }
}
