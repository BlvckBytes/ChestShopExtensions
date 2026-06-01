package at.blvckbytes.chestshop_extensions.transaction_log;

import at.blvckbytes.chestshop_extensions.config.MainSection;
import at.blvckbytes.cm_mapper.ConfigKeeper;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.stream.Stream;

public class TransactionLogHistoryCommand implements CommandExecutor, TabCompleter {

  private final ConfigKeeper<MainSection> config;
  private final TransactionLogCommand transactionLogCommand;

  public TransactionLogHistoryCommand(
    ConfigKeeper<MainSection> config,
    TransactionLogCommand transactionLogCommand
  ) {
    this.config = config;
    this.transactionLogCommand = transactionLogCommand;
  }

  @Override
  public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
    if (!(sender instanceof Player player)) {
      config.rootSection.transactionLog.playersOnly.sendMessage(sender);
      return true;
    }

    var arg = args.length == 0 ? null : args[0];

    if (arg != null && config.rootSection.transactionLog.historyShortcutCommand.clearSentinels.stream().anyMatch(it -> it.equalsIgnoreCase(arg))) {
      transactionLogCommand.handleClearingTransactions(player);
      return true;
    }

    transactionLogCommand.handleDisplayingLogPage(player, label, arg);
    return true;
  }

  @Override
  public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
    if (!(sender instanceof Player player))
      return List.of();

    if (args.length == 1) {
      return Stream.concat(
        config.rootSection.transactionLog.historyShortcutCommand.clearSentinels.stream(),
        transactionLogCommand.buildHistoryPageSuggestions(player, args[0])
      ).toList();
    }

    return List.of();
  }
}
