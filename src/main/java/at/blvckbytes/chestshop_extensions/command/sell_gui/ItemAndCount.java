package at.blvckbytes.chestshop_extensions.command.sell_gui;

import at.blvckbytes.chestshop_extensions.MutableInt;
import at.blvckbytes.component_markup.markup.interpreter.DirectFieldAccess;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

public class ItemAndCount implements DirectFieldAccess {

  public final ItemStack item;
  public final MutableInt count;

  public ItemAndCount(ItemStack item) {
    this.item = new ItemStack(item);
    this.count = new MutableInt();
  }

  @Override
  public @Nullable Object accessField(String rawIdentifier) {
    return switch (rawIdentifier) {
      case "key" -> item.getType().translationKey();
      case "count" -> count.value;
      default -> DirectFieldAccess.UNKNOWN_FIELD_SENTINEL;
    };
  }

  @Override
  public @Nullable Set<String> getAvailableFields() {
    return Set.of("key", "count");
  }
}