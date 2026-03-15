package at.blvckbytes.chestshop_search.command;

import at.blvckbytes.chestshop_search.MutableInt;
import at.blvckbytes.chestshop_search.TransactionItem;
import at.blvckbytes.chestshop_search.config.MainSection;
import at.blvckbytes.chestshop_search.return_items.ReturnItemsListener;
import at.blvckbytes.cm_mapper.ConfigKeeper;
import at.blvckbytes.component_markup.expression.ast.ExpressionNode;
import at.blvckbytes.component_markup.expression.interpreter.ExpressionInterpreter;
import at.blvckbytes.component_markup.expression.interpreter.InterpretationEnvironment;
import at.blvckbytes.component_markup.expression.interpreter.ValueInterpreter;
import at.blvckbytes.component_markup.expression.parser.ExpressionParseException;
import at.blvckbytes.component_markup.expression.parser.ExpressionParser;
import at.blvckbytes.component_markup.expression.tokenizer.ExpressionTokenizeException;
import at.blvckbytes.component_markup.util.InputView;
import at.blvckbytes.component_markup.util.logging.InterpreterLogger;
import com.Acrobot.ChestShop.Configuration.Properties;
import com.Acrobot.ChestShop.Events.PreTransactionEvent;
import com.Acrobot.ChestShop.Events.TransactionEvent;
import com.Acrobot.ChestShop.Signs.ChestShopSign;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.*;
import java.util.function.BiFunction;
import java.util.logging.Logger;

public class BuySellCommands implements CommandExecutor, TabCompleter, Listener {

  private record AmountChoice(Block signBlock, int amount, boolean doBuy, int time) {}

  private final ConfigKeeper<MainSection> config;
  private final Logger logger;
  private final PluginCommand buyCommand;
  private final PluginCommand sellCommand;

  private final Map<UUID, AmountChoice> amountChoiceByPlayerId;
  private int currentTime;

  public BuySellCommands(
    ConfigKeeper<MainSection> config,
    Plugin plugin,
    PluginCommand buyCommand,
    PluginCommand sellCommand
  ) {
    this.config = config;
    this.logger = plugin.getLogger();
    this.buyCommand = buyCommand;
    this.sellCommand = sellCommand;

    this.amountChoiceByPlayerId = new HashMap<>();

    Bukkit.getScheduler().runTaskTimer(plugin, () -> ++currentTime, 1, 1);
  }

  @Override
  public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
    if (!(sender instanceof Player player))
      return false;

    if (!(player.hasPermission("chestshopsearch.buy-sell"))) {
      config.rootSection.playerMessages.buySellNoPermission.sendMessage(player);
      return true;
    }

    boolean doBuy;

    if (command.equals(buyCommand))
      doBuy = true;
    else if (command.equals(sellCommand))
      doBuy = false;
    else {
      logger.warning("Encountered unaccounted-for buy/sell command-instance: " + command.getName());
      return false;
    }

    if (args.length == 0) {
      int colonIndex;

      if ((colonIndex = label.indexOf(':')) > 0)
        label = label.substring(colonIndex + 1);

      config.rootSection.playerMessages.buySellUsage.sendMessage(
        player,
        new InterpretationEnvironment()
          .withVariable("label", label)
      );

      return true;
    }

    if (args.length == 1 && args[0].equalsIgnoreCase("all")) {
      simulateInteractionAndStoreAmount(player, -1, doBuy);
      return true;
    }

    var amountString = String.join(" ", args);
    var amountResult = parseExpression(amountString, ValueInterpreter::asLong);

    if (amountResult.isEmpty()) {
      config.rootSection.playerMessages.buySellMalformedAmount.sendMessage(
        player,
        new InterpretationEnvironment()
          .withVariable("amount", amountString)
      );

      return true;
    }

    var amount = amountResult.get().intValue();

    if (amount <= 0) {
      config.rootSection.playerMessages.buySellAmountInvalid.sendMessage(
        player,
        new InterpretationEnvironment()
          .withVariable("amount", amount)
      );

      return true;
    }

    simulateInteractionAndStoreAmount(player, amount, doBuy);
    return true;
  }

  @Override
  public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
    return List.of("all");
  }

  @EventHandler(priority = EventPriority.LOWEST)
  public void onPreTransaction(PreTransactionEvent event) {
    var amountChoice = amountChoiceByPlayerId.remove(event.getClient().getUniqueId());

    if (amountChoice == null)
      return;

    if (currentTime - amountChoice.time > 2)
      return;

    var signBlock = event.getSign().getBlock();

    if (!signBlock.getLocation().equals(amountChoice.signBlock.getLocation()))
      return;

    if (amountChoice.doBuy != (event.getTransactionType() == TransactionEvent.TransactionType.BUY))
      return;

    var transactionItem = TransactionItem.of(event.getStock(), logger);

    if (transactionItem == null)
      return;

    if (amountChoice.amount < 0) {
      ReturnItemsListener.overrideStockAndPriceToMaxInventoryCapacity(event, transactionItem);
      return;
    }

    var maxStackSize = transactionItem.itemClone.getMaxStackSize();
    var requiredStacks = (amountChoice.amount + (maxStackSize - 1)) / maxStackSize;

    var newStock = new ItemStack[requiredStacks];
    var remainingAmount = amountChoice.amount;

    for (var slot = 0; slot < newStock.length; ++slot) {
      var slotContents = new ItemStack(transactionItem.itemClone);
      var currentAmount = Math.min(maxStackSize, remainingAmount);

      slotContents.setAmount(currentAmount);
      remainingAmount -= currentAmount;

      newStock[slot] = slotContents;
    }

    ReturnItemsListener.overrideStock(event, newStock);

    var scaledPrice = event.getExactPrice()
      .divide(BigDecimal.valueOf(transactionItem.totalAmount), MathContext.DECIMAL128)
      .multiply(BigDecimal.valueOf(amountChoice.amount));

    event.setExactPrice(scaledPrice);
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onPreProcess(PlayerCommandPreprocessEvent event) {
    var message = event.getMessage();

    // Quite the spaghetti-code, :')

    var firstSpaceIndex = message.indexOf(' ');

    if (firstSpaceIndex < 0) {
      if (message.equals("/" + buyCommand.getName()))
        event.setMessage("/" + buyCommand.getPlugin().getName().toLowerCase() + ":" + buyCommand.getName());

      if (message.equals("/" + sellCommand.getName()))
        event.setMessage("/" + sellCommand.getPlugin().getName().toLowerCase() + ":" + sellCommand.getName());

      return;
    }

    var commandToken = message.substring(0, firstSpaceIndex);

    if (commandToken.equals("/" + buyCommand.getName()))
      event.setMessage("/" + buyCommand.getPlugin().getName().toLowerCase() + ":" + buyCommand.getName() + message.substring(firstSpaceIndex));

    if (commandToken.equals("/" + sellCommand.getName()))
      event.setMessage("/" + sellCommand.getPlugin().getName().toLowerCase() + ":" + sellCommand.getName() + message.substring(firstSpaceIndex));
  }

  @EventHandler
  public void onQuit(PlayerQuitEvent event) {
    amountChoiceByPlayerId.remove(event.getPlayer().getUniqueId());
  }

  private void simulateInteractionAndStoreAmount(Player player, int amount, boolean doBuy) {
    //noinspection UnstableApiUsage
    var rayTraceResult = player.getWorld().rayTraceBlocks(
      player.getEyeLocation(),
      player.getEyeLocation().getDirection(),
      4.0,
      FluidCollisionMode.NEVER,
      false,
      block -> Tag.ALL_SIGNS.isTagged(block.getType())
    );

    if (
      rayTraceResult == null
        || rayTraceResult.getHitBlock() == null
        || rayTraceResult.getHitBlockFace() == null
        || !ChestShopSign.isValid(rayTraceResult.getHitBlock())
    ) {
      config.rootSection.playerMessages.notLookingAtShopSign.sendMessage(player);
      return;
    }

    var signBlock = rayTraceResult.getHitBlock();

    //noinspection UnstableApiUsage
    var fakeInteractionEvent = new PlayerInteractEvent(
      player,
      getActionForInteraction(doBuy),
      player.getInventory().getItemInMainHand(),
      signBlock,
      rayTraceResult.getHitBlockFace()
    );

    amountChoiceByPlayerId.put(player.getUniqueId(), new AmountChoice(signBlock, amount, doBuy, currentTime));

    Bukkit.getServer().getPluginManager().callEvent(fakeInteractionEvent);
  }

  private Action getActionForInteraction(boolean doBuy) {
    if (doBuy)
      return Properties.REVERSE_BUTTONS ? Action.LEFT_CLICK_BLOCK : Action.RIGHT_CLICK_BLOCK;

    return Properties.REVERSE_BUTTONS ? Action.RIGHT_CLICK_BLOCK : Action.LEFT_CLICK_BLOCK;
  }

  protected <T> Optional<T> parseExpression(String expression, BiFunction<ValueInterpreter, Object, T> resultMapper) {
    var inputView = InputView.of(expression);

    ExpressionNode expressionNode;

    try {
      expressionNode = ExpressionParser.parse(inputView, null);
    } catch (ExpressionTokenizeException | ExpressionParseException e) {
      return Optional.empty();
    }

    var environment = new InterpretationEnvironment();

    var logCount = new MutableInt();

    var value = ExpressionInterpreter.interpret(expressionNode, environment, makeCountingLogger(logCount));

    if (logCount.value != 0)
      return Optional.empty();

    return Optional.of(resultMapper.apply(environment.getValueInterpreter(), value));
  }

  private InterpreterLogger makeCountingLogger(MutableInt output) {
    return (view, position, message, e) -> ++output.value;
  }
}
