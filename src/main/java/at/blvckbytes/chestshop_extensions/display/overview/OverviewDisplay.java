package at.blvckbytes.chestshop_extensions.display.overview;

import at.blvckbytes.chestshop_extensions.ShopOwner;
import at.blvckbytes.chestshop_extensions.config.MainSection;
import at.blvckbytes.chestshop_extensions.display.Display;
import at.blvckbytes.chestshop_extensions.display.result.AsyncTaskQueue;
import at.blvckbytes.cm_mapper.ConfigKeeper;
import at.blvckbytes.component_markup.expression.interpreter.InterpretationEnvironment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

public class OverviewDisplay extends Display<OverviewDisplayData> {

  private final OverviewDisplayHandler overviewDisplayHandler;
  private final AsyncTaskQueue asyncQueue;

  private final ShopOwner[] slotMap;
  private int numberOfPages;

  private InterpretationEnvironment pageEnvironment;

  private int currentPage = 1;

  public OverviewDisplay(
    OverviewDisplayHandler overviewDisplayHandler,
    ConfigKeeper<MainSection> config,
    Plugin plugin,
    Player player,
    OverviewDisplayData displayData
  ) {
    super(player, displayData, config, plugin);

    this.overviewDisplayHandler = overviewDisplayHandler;
    this.asyncQueue = new AsyncTaskQueue(plugin);
    this.slotMap = new ShopOwner[9 * 6];

    setupEnvironments();

    // Within async context already, see corresponding command
    show();
  }

  private void setupEnvironments() {
    var numberOfDisplaySlots = config.rootSection.resultDisplay.getPaginationSlots().size();
    this.numberOfPages = Math.max(1, (int) Math.ceil(displayData.owners().size() / (double) numberOfDisplaySlots));

    this.pageEnvironment = config.rootSection.resultDisplay.inventoryEnvironment.copy()
      .withVariable("current_page", this.currentPage)
      .withVariable("number_of_pages", this.numberOfPages);
  }

  @Override
  public void onConfigReload() {
    show();
  }

  public @Nullable ShopOwner getOwnerCorrespondingToSlot(int slot) {
    return slotMap[slot];
  }

  @Override
  public void onInventoryClose() {
    clearSlotMap();
    super.onInventoryClose();
  }

  @Override
  public void onShutdown() {
    clearSlotMap();
    super.onShutdown();
  }

  public void clearSlotMap() {
    for (var i = 0; i < slotMap.length; ++i)
      this.slotMap[i] = null;
  }

  public void nextPage() {
    asyncQueue.enqueue(() -> {
      if (currentPage == numberOfPages)
        return;

      ++currentPage;
      show();
    });
  }

  public void previousPage() {
    asyncQueue.enqueue(() -> {
      if (currentPage == 1)
        return;

      --currentPage;
      show();
    });
  }

  public void firstPage() {
    asyncQueue.enqueue(() -> {
      if (currentPage == 1)
        return;

      currentPage = 1;
      show();
    });
  }

  public void lastPage() {
    asyncQueue.enqueue(() -> {
      if (currentPage == numberOfPages)
        return;

      currentPage = numberOfPages;
      show();
    });
  }

  @Override
  public void show() {
    setupEnvironments();
    clearSlotMap();
    super.show();
  }

  @Override
  protected void renderItems() {
    var displaySlots = config.rootSection.resultDisplay.getPaginationSlots();
    var itemsIndex = (currentPage - 1) * displaySlots.size();
    var numberOfItems = displayData.owners().size();

    for (var slot : displaySlots) {
      var currentSlot = itemsIndex++;

      if (currentSlot >= numberOfItems) {
        slotMap[slot] = null;
        inventory.setItem(slot, null);
        continue;
      }

      var entry = displayData.owners().get(currentSlot);
      var item = config.rootSection.resultDisplay.items.shopOwner.build(entry.getEnvironment());

      inventory.setItem(slot, item);

      slotMap[slot] = entry;
    }

    // Render filler first, such that it may be overridden by conditionally displayed items
    config.rootSection.resultDisplay.items.filler.renderInto(inventory::setItem, pageEnvironment);

    config.rootSection.resultDisplay.items.previousPage.renderInto(inventory::setItem, pageEnvironment);
    config.rootSection.resultDisplay.items.nextPage.renderInto(inventory::setItem, pageEnvironment);
  }

  @Override
  protected Inventory makeInventory() {
    return config.rootSection.resultDisplay.createInventory(pageEnvironment);
  }

  public void reopen() {
    overviewDisplayHandler.reopen(this);
  }
}
