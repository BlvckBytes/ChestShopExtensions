package at.blvckbytes.chestshop_extensions;

import at.blvckbytes.chestshop_extensions.command.*;
import at.blvckbytes.chestshop_extensions.command.inv_sell.InvSellCommand;
import at.blvckbytes.chestshop_extensions.command.sell_gui.SellGuiCommand;
import at.blvckbytes.chestshop_extensions.config.MainSection;
import at.blvckbytes.chestshop_extensions.config.command.*;
import at.blvckbytes.chestshop_extensions.display.overview.OverviewDisplayHandler;
import at.blvckbytes.chestshop_extensions.display.result.ResultDisplayHandler;
import at.blvckbytes.chestshop_extensions.display.result.SelectionStateStore;
import at.blvckbytes.chestshop_extensions.eco_log.BBEcoLogLogger;
import at.blvckbytes.chestshop_extensions.eco_log.EcoLogger;
import at.blvckbytes.chestshop_extensions.transaction_undo.TransactionUndoListener;
import at.blvckbytes.cm_mapper.ConfigHandler;
import at.blvckbytes.cm_mapper.ConfigKeeper;
import at.blvckbytes.cm_mapper.section.command.CommandUpdater;
import com.Acrobot.ChestShop.Events.PreTransactionEvent;
import com.cryptomorin.xseries.XMaterial;
import me.blvckbytes.item_predicate_parser.ItemPredicateParserPlugin;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.plugin.java.JavaPlugin;

import javax.annotation.Nullable;
import java.io.File;
import java.util.*;
import java.util.logging.Level;

public class ChestShopExtensionsPlugin extends JavaPlugin {

  private @Nullable ChestShopRegistry chestShopRegistry;
  private @Nullable NameScopedKeyValueStore keyValueStore;
  private @Nullable SelectionStateStore selectionStateStore;
  private @Nullable ResultDisplayHandler resultDisplayHandler;
  private @Nullable OverviewDisplayHandler overviewDisplayHandler;

  @Override
  public void onEnable() {
    var logger = getLogger();

    try {
      // First invocation is quite heavy - warm up cache
      XMaterial.matchXMaterial(Material.AIR);

      keyValueStore = new NameScopedKeyValueStore(getFileAndEnsureExistence("user-preferences.json"), logger);

      var configHandler = new ConfigHandler(this, "config");
      var config = new ConfigKeeper<>(configHandler, "config.yml", MainSection.class);

      var texturesManager = new SkullTexturesManager(this, logger);

      selectionStateStore = new SelectionStateStore(this, logger);

      chestShopRegistry = new ChestShopRegistry(texturesManager, keyValueStore, getFileAndEnsureExistence("known-shops.json"), logger);
      Bukkit.getScheduler().runTaskAsynchronously(this, chestShopRegistry::load);

      resultDisplayHandler = new ResultDisplayHandler(config, selectionStateStore, chestShopRegistry, logger, this);
      Bukkit.getServer().getPluginManager().registerEvents(resultDisplayHandler, this);

      var dataListener = new ShopDataListener(this, chestShopRegistry, config, logger);
      getServer().getPluginManager().registerEvents(dataListener, this);

      Bukkit.getScheduler().runTaskTimerAsynchronously(this, chestShopRegistry::save, 20L * 30, 20L * 300);

      var parserPlugin = ItemPredicateParserPlugin.getInstance();

      if (parserPlugin == null)
        throw new IllegalStateException("Depending on ItemPredicateParser to be successfully loaded");

      var predicateHelper = parserPlugin.getPredicateHelper();

      EcoLogger ecoLogger = null;

      if (Bukkit.getPluginManager().isPluginEnabled("BBEcoLog"))
        ecoLogger = new BBEcoLogLogger();

      else
        logger.warning("Could not integrate with BBEcoLog, seeing how it is not loaded!");

      Bukkit.getScheduler().runTaskTimerAsynchronously(this, keyValueStore::saveToDisk, 20 * 60L, 20 * 60L);

      var transactionUndoListener = new TransactionUndoListener(this, config);
      Bukkit.getServer().getPluginManager().registerEvents(transactionUndoListener, this);

      overviewDisplayHandler = new OverviewDisplayHandler(resultDisplayHandler, chestShopRegistry, config, this);
      Bukkit.getServer().getPluginManager().registerEvents(overviewDisplayHandler, this);

      var commandUpdater = new CommandUpdater(this);

      var shopSearchCommand = Objects.requireNonNull(getCommand(ShopSearchCommandSection.INITIAL_NAME));
      var shopSearchToggleCommand = Objects.requireNonNull(getCommand(ShopSearchToggleCommandSection.INITIAL_NAME));
      var shopOverviewCommand = Objects.requireNonNull(getCommand(ShopOverviewCommandSection.INITIAL_NAME));
      var shopSearchReloadCommand = Objects.requireNonNull(getCommand(ShopSearchReloadCommandSection.INITIAL_NAME));
      var shopItemInfoCommand = Objects.requireNonNull(getCommand(ShopItemInfoCommandSection.INITIAL_NAME));

      shopSearchCommand.setExecutor(new ShopSearchCommand(chestShopRegistry, predicateHelper, resultDisplayHandler, config));
      shopSearchToggleCommand.setExecutor(new ShopSearchToggleCommand(keyValueStore, dataListener, config));
      shopOverviewCommand.setExecutor(new ShopOverviewCommand(chestShopRegistry, overviewDisplayHandler));
      shopSearchReloadCommand.setExecutor(new ShopSearchReloadCommand(config, logger));

      var shopItemExecutor = new ShopItemInfoCommand(config);
      Bukkit.getPluginManager().registerEvents(shopItemExecutor, this);

      shopItemInfoCommand.setExecutor(shopItemExecutor);

      Runnable updateCommands = () -> {
        config.rootSection.commands.shopSearch.apply(shopSearchCommand, commandUpdater);
        config.rootSection.commands.shopSearchToggle.apply(shopSearchToggleCommand, commandUpdater);
        config.rootSection.commands.shopOverview.apply(shopOverviewCommand, commandUpdater);
        config.rootSection.commands.shopSearchReload.apply(shopSearchReloadCommand, commandUpdater);
        config.rootSection.commands.shopItemInfo.apply(shopItemInfoCommand, commandUpdater);

        commandUpdater.trySyncCommands();
      };

      updateCommands.run();
      config.registerReloadListener(updateCommands);

      var buyCommand = Objects.requireNonNull(getCommand("buy"));
      var sellCommand = Objects.requireNonNull(getCommand("sell"));

      var buySellExecutor = new BuySellCommands(config, this, buyCommand, sellCommand);
      Bukkit.getPluginManager().registerEvents(buySellExecutor, this);

      buyCommand.setExecutor(buySellExecutor);
      sellCommand.setExecutor(buySellExecutor);

      var sellGuiCommand = Objects.requireNonNull(getCommand("sellgui"));

      var economyProvider = Bukkit.getServer().getServicesManager().getRegistration(Economy.class);

      if (economyProvider == null)
        throw new IllegalStateException("Could not locate an economy-provider");

      var economy = economyProvider.getProvider();

      var sellGuiExecutor = new SellGuiCommand(chestShopRegistry, economy, ecoLogger, config);
      Bukkit.getPluginManager().registerEvents(sellGuiExecutor, this);

      sellGuiCommand.setExecutor(sellGuiExecutor);

      var invSellCommand = Objects.requireNonNull(getCommand("invsell"));

      var invSellExecutor = new InvSellCommand(this, chestShopRegistry, economy, ecoLogger, config);
      Bukkit.getPluginManager().registerEvents(invSellExecutor, this);

      invSellCommand.setExecutor(invSellExecutor);

      var exportAdminshopsCommand = Objects.requireNonNull(getCommand("exportadminshops"));
      exportAdminshopsCommand.setExecutor(new ExportAdminshopsCommand(this, chestShopRegistry, parserPlugin.getTranslationLanguageRegistry()));

      Bukkit.getScheduler().runTaskLater(this, this::reorderPreTransactionEventHandlers, 1);
    } catch (Throwable e) {
      logger.log(Level.SEVERE, "An error occurred while trying to enable the plugin", e);
      Bukkit.getPluginManager().disablePlugin(this);
    }
  }

  @Override
  public void onDisable() {
    if (chestShopRegistry != null) {
      chestShopRegistry.save();
      chestShopRegistry = null;
    }

    if (keyValueStore != null) {
      keyValueStore.saveToDisk();
      keyValueStore = null;
    }

    if (selectionStateStore != null) {
      selectionStateStore.onShutdown();
      selectionStateStore = null;
    }

    if (resultDisplayHandler != null) {
      resultDisplayHandler.onShutdown();
      resultDisplayHandler = null;
    }

    if (overviewDisplayHandler != null) {
      overviewDisplayHandler.onShutdown();
      overviewDisplayHandler = null;
    }
  }

  private void reorderPreTransactionEventHandlers() {
    var handlerList = PreTransactionEvent.getHandlerList();
    var registeredListeners = handlerList.getRegisteredListeners();

    var ourListeners = new ArrayList<RegisteredListener>();

    for (var registeredListener : registeredListeners) {
      handlerList.unregister(registeredListener);

      if (registeredListener.getPlugin() == this)
        ourListeners.add(registeredListener);
    }

    for (var ourListener : ourListeners)
      handlerList.register(ourListener);

    for (var registeredListener : registeredListeners) {
      if (registeredListener.getPlugin() == this)
        continue;

      handlerList.register(registeredListener);
    }
  }

  private File getFileAndEnsureExistence(String name) throws Exception {
    var file = new File(getDataFolder(), name);

    if (!file.exists()) {
      var parentDirectory = file.getParentFile();

      if (!parentDirectory.exists() && !parentDirectory.mkdirs())
        throw new IllegalStateException("Could not create parent-directories of the file " + file);

      if (!file.createNewFile())
        throw new IllegalStateException("Could not create the file " + file);
    }

    return file;
  }
}
