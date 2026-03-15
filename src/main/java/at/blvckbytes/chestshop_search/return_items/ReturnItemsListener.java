package at.blvckbytes.chestshop_search.return_items;

import at.blvckbytes.chestshop_search.TransactionItem;
import at.blvckbytes.chestshop_search.config.MainSection;
import at.blvckbytes.cm_mapper.ConfigKeeper;
import com.Acrobot.ChestShop.Configuration.Properties;
import com.Acrobot.ChestShop.Containers.AdminInventory;
import com.Acrobot.ChestShop.Events.PreTransactionEvent;
import com.Acrobot.ChestShop.Events.TransactionEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.*;

public class ReturnItemsListener implements Listener {

  private final Plugin plugin;
  private final ConfigKeeper<MainSection> config;

  private final Map<UUID, TransactionHistory> historyByClientId;

  public ReturnItemsListener(Plugin plugin, ConfigKeeper<MainSection> config) {
    this.plugin = plugin;
    this.config = config;

    this.historyByClientId = new HashMap<>();
  }

  @EventHandler(priority = EventPriority.LOWEST)
  public void onPreTransaction(PreTransactionEvent event) {
    var transactionItem = TransactionItem.of(event.getStock(), plugin.getLogger());

    if (transactionItem == null)
      return;

    if (handleReturningItems(event, transactionItem))
      return;

    handleBuyingAllFromAdminshop(event, transactionItem);
  }

  @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
  public void onTransaction(TransactionEvent event) {
    if (!event.getClient().hasPermission("chestshopsearch.return-items") || config.rootSection.returnItems.returnWindowSeconds <= 0)
      return;

    var transactionItem = TransactionItem.of(event.getStock(), plugin.getLogger());

    if (transactionItem == null)
      return;

    var history = historyByClientId.computeIfAbsent(event.getClient().getUniqueId(), k -> new TransactionHistory(config));

    var lastCorrespondingTransaction = history.findLastCorrespondingTransaction(event.getSign(), event.getTransactionType(), transactionItem);

    if (lastCorrespondingTransaction != null) {
      lastCorrespondingTransaction.markUsedForReturning();
      config.rootSection.returnItems.returnMessage.sendMessage(event.getClient());
      return;
    }

    history.addTransaction(event.getSign(), event.getTransactionType(), transactionItem, event.getExactPrice());
  }

  @EventHandler
  public void onQuit(PlayerQuitEvent event) {
    historyByClientId.remove(event.getPlayer().getUniqueId());
  }

  private boolean handleReturningItems(PreTransactionEvent event, TransactionItem transactionItem) {
    if (!event.getClient().hasPermission("chestshopsearch.return-items") || config.rootSection.returnItems.returnWindowSeconds <= 0)
      return false;

    var history = historyByClientId.get(event.getClient().getUniqueId());

    if (history == null)
      return false;

    var lastCorrespondingTransaction = history.findLastCorrespondingTransaction(event.getSign(), event.getTransactionType(), transactionItem);

    if (lastCorrespondingTransaction == null)
      return false;

    event.setExactPrice(lastCorrespondingTransaction.exactPrice);

    if (lastCorrespondingTransaction.transactionItem.totalAmount != transactionItem.totalAmount)
      overrideStock(event, lastCorrespondingTransaction.transactionItem.recreateStock());

    return true;
  }

  private void handleBuyingAllFromAdminshop(PreTransactionEvent event, TransactionItem transactionItem) {
    if (event.getTransactionType() != TransactionEvent.TransactionType.BUY)
      return;

    if (!event.getClient().isSneaking() || !Properties.SHIFT_SELLS_EVERYTHING)
      return;

    if (!Properties.SHIFT_ALLOWS.equalsIgnoreCase("ALL") && !Properties.SHIFT_ALLOWS.equalsIgnoreCase("BUY"))
      return;

    overrideStockAndPriceToMaxInventoryCapacity(event, transactionItem);
  }

  public static void overrideStockAndPriceToMaxInventoryCapacity(PreTransactionEvent event, TransactionItem transactionItem) {
    var maxStackSize = transactionItem.itemClone.getMaxStackSize();

    var newStock = new ItemStack[event.getClientInventory().getSize()];

    // There's no need to compute the exact amount of fitting items, as the plugin will scale down accordingly by itself.

    for (var slot = 0; slot < newStock.length; ++slot) {
      var slotContents = new ItemStack(transactionItem.itemClone);

      slotContents.setAmount(maxStackSize);

      newStock[slot] = slotContents;
    }

    var newTotalAmount = maxStackSize * newStock.length;

    var scaledPrice = event.getExactPrice()
      .divide(BigDecimal.valueOf(transactionItem.totalAmount), MathContext.DECIMAL128)
      .multiply(BigDecimal.valueOf(newTotalAmount));

    event.setExactPrice(scaledPrice);

    overrideStock(event, newStock);
  }

  public static void overrideStock(PreTransactionEvent event, ItemStack[] newStock) {
    event.setStock(newStock);

    // They create a virtual admin-inventory for non-container-backed shops, so if we want to alter the
    // stock, we need to also update the contents of the inventory, as for the move to succeed later on.
    if (event.getOwnerInventory() instanceof AdminInventory) {
      // Also, let's create an independent stock-array, as I'm not sure on whether we'll run into
      // complications otherwise; it's cheap enough to do so, really.
      event.getOwnerInventory().setContents(deepCloneItemArray(newStock));
    }
  }

  private static ItemStack[] deepCloneItemArray(ItemStack[] input) {
    var result = new ItemStack[input.length];

    for (var index = 0; index < result.length; ++index)
      result[index] = new ItemStack(input[index]);

    return result;
  }
}
