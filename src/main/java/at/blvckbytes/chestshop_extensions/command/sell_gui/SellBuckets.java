package at.blvckbytes.chestshop_extensions.command.sell_gui;

import java.util.List;

public record SellBuckets(
  List<ItemAndShop> sellableItems,
  List<ItemAndCount> unsellableItems
) {}
