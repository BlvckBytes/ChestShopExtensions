package at.blvckbytes.chestshop_extensions.transaction_log;

import at.blvckbytes.chestshop_extensions.config.MainSection;
import at.blvckbytes.chestshop_extensions.display.result.ResultDisplayHandler;
import at.blvckbytes.cm_mapper.ConfigKeeper;
import at.blvckbytes.component_markup.expression.interpreter.InterpretationEnvironment;
import me.blvckbytes.item_predicate_parser.syllables_matcher.NormalizedConstant;
import org.bukkit.Bukkit;
import org.bukkit.Tag;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.stream.IntStream;

public class TransactionLogCommand implements CommandExecutor, TabCompleter, Listener {

  private final PluginCommand command;
  private final Plugin plugin;
  private final ShopTransactionLogger transactionLogger;
  private final ConfigKeeper<MainSection> config;

  public TransactionLogCommand(
    PluginCommand command,
    Plugin plugin,
    ShopTransactionLogger transactionLogger,
    ConfigKeeper<MainSection> config
  ) {
    this.command = command;
    this.plugin = plugin;
    this.transactionLogger = transactionLogger;
    this.config = config;
  }

  @Override
  public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
    if (!(sender instanceof Player player)) {
      config.rootSection.transactionLog.playersOnly.sendMessage(sender);
      return true;
    }

    var environment = new InterpretationEnvironment()
      .withVariable("label", label);

    NormalizedConstant<CommandAction> normalizedAction;

    if (args.length == 0 || (normalizedAction = CommandAction.matcher.matchFirst(args[0])) == null || normalizedAction.constant == CommandAction.HELP) {
      config.rootSection.transactionLog.helpScreen.sendMessage(sender, environment);
      return true;
    }

    environment.withVariable("action", normalizedAction.getNormalizedName());

    if (normalizedAction.constant == CommandAction.CLEAR) {
      if (args.length != 1) {
        config.rootSection.transactionLog.helpScreen.sendMessage(player, environment);
        return true;
      }

      var transactions = transactionLogger.getTransactionsForOwner(player);

      if (transactions.isEmpty()) {
        config.rootSection.transactionLog.noHistoryToClear.sendMessage(player);
        return true;
      }

      transactionLogger.clearTransactionsForOwner(player);

      config.rootSection.transactionLog.historyCleared.sendMessage(
        player,
        environment
          .withVariable("count", transactions.size())
      );

      return true;
    }

    if (normalizedAction.constant == CommandAction.HISTORY) {
      if (args.length > 2) {
        config.rootSection.transactionLog.helpScreen.sendMessage(player, environment);
        return true;
      }

      handleDisplayingLogPage(player, label + " " + normalizedAction.getNormalizedName(), args.length == 2 ? args[1] : null);
      return true;
    }

    if (normalizedAction.constant == CommandAction.TELEPORT) {
      if (args.length != 2) {
        config.rootSection.transactionLog.helpScreen.sendMessage(player, environment);
        return true;
      }

      var transactionNumber = 1;

      try {
        transactionNumber = Integer.parseInt(args[1]);

        if (transactionNumber <= 0)
          throw new IllegalStateException();
      } catch (Throwable e) {
        config.rootSection.transactionLog.invalidTransactionNumber.sendMessage(
          player,
          environment
            .withVariable("input", args[1])
        );

        return true;
      }

      var transactions = transactionLogger.getTransactionsForOwner(player);

      if (transactions.isEmpty()) {
        config.rootSection.transactionLog.teleportHasNoTransactions.sendMessage(player, environment);
        return true;
      }

      if (transactionNumber > transactions.size()) {
        config.rootSection.transactionLog.teleportNumberNotFound.sendMessage(
          player,
          environment
            .withVariable("transaction_number", transactionNumber)
            .withVariable("transactions_count", transactions.size())
        );

        return true;
      }

      var transaction = transactions.get(transactionNumber - 1);
      var signBlock = transaction.signLocation.getBlock();

      if (!(Tag.WALL_SIGNS.isTagged(signBlock.getType()))) {
        config.rootSection.transactionLog.teleportFailedNoSign.sendMessage(
          player,
          environment
            .withVariable("transaction", transaction)
        );

        return true;
      }

      ResultDisplayHandler.teleportPlayerToSign(player, signBlock);

      config.rootSection.transactionLog.teleportSuccess.sendMessage(
        player,
        environment
          .withVariable("transaction", transaction)
      );

      return true;
    }

    throw new IllegalStateException("Unaccounted-for command-action: " + normalizedAction.constant.name());
  }

  @Override
  public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
    if (!(sender instanceof Player player) || args.length == 0)
      return List.of();

    if (args.length == 1)
      return CommandAction.matcher.createCompletions(args[0]);

    var normalizedAction = CommandAction.matcher.matchFirst(args[0]);

    if (normalizedAction == null)
      return List.of();

    if (normalizedAction.constant == CommandAction.HISTORY && args.length == 2)
      return buildHistoryPageSuggestions(player, args[1]);

    if (normalizedAction.constant == CommandAction.TELEPORT && args.length == 2) {
      var transactions = transactionLogger.getTransactionsForOwner(player);

      if (transactions.isEmpty())
        return List.of();

      return IntStream.range(1, transactions.size() + 1)
        .limit(15)
        .mapToObj(String::valueOf)
        .filter(it -> it.startsWith(args[1]))
        .toList();
    }

    return List.of();
  }

  public void handleDisplayingLogPage(Player player, String historyCommand, @Nullable String pageArgument) {
    var transactions = transactionLogger.getTransactionsForOwner(player);

    if (transactions.isEmpty()) {
      config.rootSection.transactionLog.noHistoryToShow.sendMessage(player);
      return;
    }

    var pageSize = config.rootSection.transactionLog.historyPageSize;
    var numberOfPages = (transactions.size() + (pageSize - 1)) / pageSize;

    var page = 1;

    if (pageArgument != null) {
      try {
        page = Integer.parseInt(pageArgument);

        if (page <= 0)
          throw new IllegalStateException();
      } catch (Throwable e) {
        config.rootSection.transactionLog.invalidPageNumber.sendMessage(
          player,
          new InterpretationEnvironment()
            .withVariable("input", pageArgument)
        );

        return;
      }
    }

    if (page > numberOfPages) {
      config.rootSection.transactionLog.outOfBoundsPageNumber.sendMessage(
        player,
        new InterpretationEnvironment()
          .withVariable("current_page", page)
          .withVariable("page_count", numberOfPages)
      );

      return;
    }

    var firstIndex = (page - 1) * pageSize;
    var pageContents = transactions.subList(firstIndex, Math.min(firstIndex + pageSize, transactions.size()));

    config.rootSection.transactionLog.historyPageScreen.sendMessage(
      player,
      new InterpretationEnvironment()
        .withVariable("history_command", historyCommand)
        .withVariable("teleport_command", command.getName() + " " + CommandAction.matcher.getNormalizedName(CommandAction.TELEPORT))
        .withVariable("transactions", pageContents)
        .withVariable("current_page", page)
        .withVariable("page_count", numberOfPages)
    );
  }

  public List<String> buildHistoryPageSuggestions(Player player, String input) {
    var transactions = transactionLogger.getTransactionsForOwner(player);

    if (transactions.isEmpty())
      return List.of();

    var pageSize = config.rootSection.transactionLog.historyPageSize;
    var numberOfPages = (transactions.size() + (pageSize - 1)) / pageSize;

    return IntStream.range(1, numberOfPages + 1)
      .limit(15)
      .mapToObj(String::valueOf)
      .filter(it -> it.startsWith(input))
      .toList();
  }

  @EventHandler
  public void onJoin(PlayerJoinEvent event) {
    var player = event.getPlayer();
    var transactions = transactionLogger.getTransactionsForOwner(player);

    if (transactions.isEmpty())
      return;

    Bukkit.getScheduler().runTaskLater(plugin, () -> {
      config.rootSection.transactionLog.joinNotification.sendMessage(
        player,
        new InterpretationEnvironment()
          .withVariable("count", transactions.size())
      );
    }, config.rootSection.transactionLog.joinNotifyDelayTicks);
  }
}
