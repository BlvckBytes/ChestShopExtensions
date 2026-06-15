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
import at.blvckbytes.chestshop_extensions.skin_cache.SkinCache;
import at.blvckbytes.chestshop_extensions.transaction_log.*;
import at.blvckbytes.chestshop_extensions.transaction_undo.TransactionUndoListener;
import at.blvckbytes.cm_mapper.ConfigHandler;
import at.blvckbytes.cm_mapper.ConfigKeeper;
import at.blvckbytes.cm_mapper.ConfigKeeperReloadEvent;
import at.blvckbytes.cm_mapper.section.command.CommandUpdater;
import com.Acrobot.ChestShop.Events.PreTransactionEvent;
import com.cryptomorin.xseries.XMaterial;
import me.blvckbytes.item_predicate_parser.ItemPredicateParserPlugin;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.plugin.java.JavaPlugin;

import javax.annotation.Nullable;
import java.io.File;
import java.util.*;
import java.util.logging.Level;

public class ChestShopExtensionsPlugin extends JavaPlugin implements Listener {

  // TODO: This plugin should also be updated to the new and cleaner AutoWirer scheme.

  private @Nullable ChestShopRegistry chestShopRegistry;
  private @Nullable NameScopedKeyValueStore keyValueStore;
  private @Nullable SelectionStateStore selectionStateStore;
  private @Nullable ResultDisplayHandler resultDisplayHandler;
  private @Nullable OverviewDisplayHandler overviewDisplayHandler;
  private @Nullable SkinCache skinCache;
  private @Nullable ShopTransactionLogger transactionLogger;

  private @Nullable ConfigKeeper<MainSection> config;
  private @Nullable Runnable commandsUpdater;

  @Override
  public void onEnable() {
    var logger = getLogger();

    try {
      // First invocation is quite heavy - warm up cache
      XMaterial.matchXMaterial(Material.AIR);

      keyValueStore = new NameScopedKeyValueStore(getFileAndEnsureExistence("user-preferences.json"), logger);

      var configHandler = new ConfigHandler(this, "config");
      config = new ConfigKeeper<>(configHandler, "config.yml", MainSection.class);

      skinCache = new SkinCache(this, logger);

      selectionStateStore = new SelectionStateStore(this, logger);

      chestShopRegistry = new ChestShopRegistry(this, skinCache, keyValueStore, getFileAndEnsureExistence("known-shops.json"), config, logger);
      Bukkit.getServer().getPluginManager().registerEvents(chestShopRegistry, this);

      resultDisplayHandler = new ResultDisplayHandler(config, selectionStateStore, chestShopRegistry, this);
      Bukkit.getServer().getPluginManager().registerEvents(resultDisplayHandler, this);

      transactionLogger = new ShopTransactionLogger(this, config);

      var dataListener = new ShopDataListener(this, chestShopRegistry, transactionLogger, config, logger);
      getServer().getPluginManager().registerEvents(dataListener, this);

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
      var transactionLogCommand = Objects.requireNonNull(getCommand(TransactionLogCommandSection.INITIAL_NAME));
      var transactionLogHistoryCommand = Objects.requireNonNull(getCommand(TransactionLogHistoryCommandSection.INITIAL_NAME));

      setExecutorAndTabCompleter(shopSearchCommand, new ShopSearchCommand(chestShopRegistry, predicateHelper, resultDisplayHandler, config));
      setExecutorAndTabCompleter(shopSearchToggleCommand, new ShopSearchToggleCommand(keyValueStore, dataListener, config));
      setExecutorAndTabCompleter(shopOverviewCommand, new ShopOverviewCommand(chestShopRegistry, overviewDisplayHandler));
      setExecutorAndTabCompleter(shopSearchReloadCommand, new ShopSearchReloadCommand(config, logger));

      var transactionLogExecutor = new TransactionLogCommand(transactionLogCommand, this, transactionLogger, config);
      Bukkit.getServer().getPluginManager().registerEvents(transactionLogExecutor, this);

      setExecutorAndTabCompleter(transactionLogCommand, transactionLogExecutor);

      setExecutorAndTabCompleter(transactionLogHistoryCommand, new TransactionLogHistoryCommand(config, transactionLogExecutor));

      var shopItemExecutor = new ShopItemInfoCommand(config);
      Bukkit.getPluginManager().registerEvents(shopItemExecutor, this);

      shopItemInfoCommand.setExecutor(shopItemExecutor);

      commandsUpdater = () -> {
        config.rootSection.commands.shopSearch.apply(shopSearchCommand, commandUpdater);
        config.rootSection.commands.shopSearchToggle.apply(shopSearchToggleCommand, commandUpdater);
        config.rootSection.commands.shopOverview.apply(shopOverviewCommand, commandUpdater);
        config.rootSection.commands.shopSearchReload.apply(shopSearchReloadCommand, commandUpdater);
        config.rootSection.commands.shopItemInfo.apply(shopItemInfoCommand, commandUpdater);
        config.rootSection.transactionLog.mainCommand.apply(transactionLogCommand, commandUpdater);
        config.rootSection.transactionLog.historyShortcutCommand.apply(transactionLogHistoryCommand, commandUpdater);

        commandUpdater.trySyncCommands();
      };

      commandsUpdater.run();

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

      var offlinePlayerRegistry = new OfflinePlayerRegistry();
      Bukkit.getPluginManager().registerEvents(offlinePlayerRegistry, this);

      var invSellExecutor = new InvSellCommand(this, chestShopRegistry, economy, ecoLogger, offlinePlayerRegistry, config);
      Bukkit.getPluginManager().registerEvents(invSellExecutor, this);

      invSellCommand.setExecutor(invSellExecutor);

      var exportAdminshopsCommand = Objects.requireNonNull(getCommand("exportadminshops"));
      exportAdminshopsCommand.setExecutor(new ExportAdminshopsCommand(this, chestShopRegistry, parserPlugin.getTranslationLanguageRegistry()));

      Bukkit.getScheduler().runTaskLater(this, this::reorderPreTransactionEventHandlers, 1);

      Bukkit.getPluginManager().registerEvents(this, this);
    } catch (Throwable e) {
      logger.log(Level.SEVERE, "An error occurred while trying to enable the plugin", e);
      Bukkit.getPluginManager().disablePlugin(this);
    }
  }

  @Override
  public void onDisable() {
    if (chestShopRegistry != null) {
      chestShopRegistry.save(false);
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

    if (skinCache != null) {
      skinCache.onShutdown();
      skinCache = null;
    }

    if (transactionLogger != null) {
      transactionLogger.onShutdown();
      transactionLogger = null;
    }
  }

  @EventHandler
  public void onConfigReload(ConfigKeeperReloadEvent event) {
    if (commandsUpdater != null && event.configKeeper == config)
      commandsUpdater.run();
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

  private void setExecutorAndTabCompleter(PluginCommand command, CommandExecutor executor) {
    command.setExecutor(executor);

    if (executor instanceof TabCompleter tabCompleter)
      command.setTabCompleter(tabCompleter);
  }
}
