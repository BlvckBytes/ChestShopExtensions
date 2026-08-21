package at.blvckbytes.chestshop_extensions.display.result;

import at.blvckbytes.chestshop_extensions.ChestShopEntry;
import at.blvckbytes.chestshop_extensions.config.MainSection;
import at.blvckbytes.chestshop_extensions.display.Display;
import at.blvckbytes.cm_mapper.ConfigKeeper;
import at.blvckbytes.component_markup.expression.interpreter.InterpretationEnvironment;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ResultDisplay extends Display<ResultDisplayData> {

  private final AsyncTaskQueue asyncQueue;

  private List<ChestShopEntry> filteredUnSortedShops;
  private List<ChestShopEntry> filteredSortedShops;

  private final ChestShopEntry[] slotMap;
  private final Set<Location> currentlyRenderedSignLocations;
  private int numberOfPages;
  public final SelectionState selectionState;

  private InterpretationEnvironment pageEnvironment;
  private InterpretationEnvironment sortingEnvironment;
  private InterpretationEnvironment filteringEnvironment;

  private int currentPage = 1;

  private boolean isFirstRender = true;

  public ResultDisplay(
    ConfigKeeper<MainSection> config,
    Plugin plugin,
    Player player,
    ResultDisplayData displayData,
    SelectionState selectionState
  ) {
    super(player, displayData, config, plugin);

    this.asyncQueue = new AsyncTaskQueue(plugin);
    this.slotMap = new ChestShopEntry[9 * 6];
    this.currentlyRenderedSignLocations = new HashSet<>();
    this.selectionState = selectionState;

    // Within async context already, see corresponding command
    applyFiltering();
    applySorting();
    show();
  }

  private void setupEnvironments() {
    pageEnvironment = config.rootSection.resultDisplay.inventoryEnvironment.copy()
      .withVariable("current_page", this.currentPage)
      .withVariable("number_of_pages", this.numberOfPages);

    if (displayData.overviewDisplayInfo() != null)
      pageEnvironment.withVariable("owner", displayData.overviewDisplayInfo().owner().name);

    sortingEnvironment = pageEnvironment.copy();
    selectionState.extendSortingEnvironment(sortingEnvironment);

    filteringEnvironment = pageEnvironment.copy();
    selectionState.extendFilteringEnvironment(filteringEnvironment);
  }

  @Override
  public void onConfigReload() {
    applyFiltering();
    applySorting();
    show();
  }

  public @Nullable ChestShopEntry getShopCorrespondingToSlot(int slot) {
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

  public void onShopStockChange(ChestShopEntry entry) {
    if (currentlyRenderedSignLocations.contains(entry.signLocation))
      renderItems();
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

  public void nextSortingSelection() {
    asyncQueue.enqueue(() -> {
      this.selectionState.nextSortingSelection();
      setupEnvironments();
      renderSortingItem();
    });
  }

  public void nextSortingOrder() {
    asyncQueue.enqueue(() -> {
      this.selectionState.nextSortingOrder();
      setupEnvironments();
      applySorting();
      renderItems();
    });
  }

  public void moveSortingSelectionDown() {
    asyncQueue.enqueue(() -> {
      this.selectionState.moveSortingSelectionDown();
      setupEnvironments();
      applySorting();
      renderItems();
    });
  }

  public void resetSortingState() {
    asyncQueue.enqueue(() -> {
      this.selectionState.resetSorting();
      setupEnvironments();
      applySorting();
      renderItems();
    });
  }

  public void nextFilteringCriterion() {
    asyncQueue.enqueue(() -> {
      this.selectionState.nextFilteringCriterion();
      setupEnvironments();
      renderFilteringItem();
    });
  }

  public void nextFilteringState() {
    asyncQueue.enqueue(() -> {
      this.selectionState.nextFilteringState();
      afterFilterChange();
    });
  }

  public void resetFilteringState() {
    asyncQueue.enqueue(() -> {
      this.selectionState.resetFiltering();
      afterFilterChange();
    });
  }

  private void afterFilterChange() {
    int pageCountDelta = applyFiltering();
    applySorting();
    setupEnvironments();

    // Need to update the UI-title
    if (pageCountDelta != 0)
      show();
    else
      renderItems();
  }

  private int applyFiltering() {
    this.filteredUnSortedShops = this.selectionState.applyFilter(displayData.shops());

    var oldNumberOfPages = this.numberOfPages;
    var numberOfDisplaySlots = config.rootSection.resultDisplay.getPaginationSlots().size();

    this.numberOfPages = Math.max(1, (int) Math.ceil(filteredUnSortedShops.size() / (double) numberOfDisplaySlots));

    var pageCountDelta = this.numberOfPages - oldNumberOfPages;

    // Try to stay on the current page, if possible
    if (pageCountDelta < 0)
      this.currentPage = 1;

    return pageCountDelta;
  }

  private void applySorting() {
    this.filteredSortedShops = new ArrayList<>(this.filteredUnSortedShops);
    this.selectionState.applySort(this.filteredSortedShops);
  }

  @Override
  public void show() {
    setupEnvironments();
    clearSlotMap();
    super.show();
    isFirstRender = false;
  }

  private void renderSortingItem() {
    config.rootSection.resultDisplay.items.sorting.renderInto(inventory::setItem, sortingEnvironment);
  }

  private void renderFilteringItem() {
    config.rootSection.resultDisplay.items.filtering.renderInto(inventory::setItem, filteringEnvironment);
  }

  @Override
  protected void renderItems() {
    var displaySlots = config.rootSection.resultDisplay.getPaginationSlots();
    var itemsIndex = (currentPage - 1) * displaySlots.size();
    var numberOfItems = filteredSortedShops.size();

    currentlyRenderedSignLocations.clear();

    for (var slot : displaySlots) {
      var currentSlot = itemsIndex++;

      if (currentSlot >= numberOfItems) {
        slotMap[slot] = null;
        inventory.setItem(slot, null);
        continue;
      }

      var cachedShop = filteredSortedShops.get(currentSlot);
      var representativeItem = cachedShop.item.clone();

      config.rootSection.resultDisplay.items.representativePatch.patch(representativeItem, cachedShop.getEnvironment());

      inventory.setItem(slot, representativeItem);

      slotMap[slot] = cachedShop;
      currentlyRenderedSignLocations.add(cachedShop.signLocation);
    }

    // Render filler first, such that it may be overridden by conditionally displayed items
    config.rootSection.resultDisplay.items.filler.renderInto(inventory::setItem, pageEnvironment);

    if (displayData.overviewDisplayInfo() != null)
      config.rootSection.resultDisplay.items.backButton.renderInto(inventory::setItem, pageEnvironment);

    config.rootSection.resultDisplay.items.previousPage.renderInto(inventory::setItem, pageEnvironment);
    config.rootSection.resultDisplay.items.nextPage.renderInto(inventory::setItem, pageEnvironment);
    renderSortingItem();
    renderFilteringItem();

    if (isFirstRender && !displayData.shops().isEmpty() && filteredSortedShops.isEmpty()) {
      config.rootSection.playerMessages.searchCommandBlankUi.sendMessage(
        player,
        new InterpretationEnvironment()
          .withVariable("result_count", displayData.shops().size())
      );
    }
  }

  @Override
  protected Inventory makeInventory() {
    return config.rootSection.resultDisplay.createInventory(pageEnvironment);
  }
}
