package at.blvckbytes.chestshop_extensions.config;

import at.blvckbytes.chestshop_extensions.config.command.CommandsSection;
import at.blvckbytes.chestshop_extensions.config.result_display.ResultDisplaySection;
import at.blvckbytes.chestshop_extensions.transaction_log.TransactionLogSection;
import at.blvckbytes.chestshop_extensions.transaction_undo.TransactionUndoSection;
import at.blvckbytes.cm_mapper.mapper.section.CSAlways;
import at.blvckbytes.cm_mapper.mapper.section.ConfigSection;
import at.blvckbytes.component_markup.expression.interpreter.InterpretationEnvironment;
import at.blvckbytes.component_markup.util.logging.InterpreterLogger;

@CSAlways
public class MainSection extends ConfigSection {

  public CommandsSection commands;
  public PlayerMessagesSection playerMessages;
  public RegionFilterSection regionFilter;
  public ResultDisplaySection resultDisplay;
  public TransactionUndoSection transactionUndo;
  public ShopItemInfoSection shopItemInfo;
  public SellGuiSection sellGui;
  public TransactionLogSection transactionLog;

  public MainSection(InterpretationEnvironment baseEnvironment, InterpreterLogger interpreterLogger) {
    super(baseEnvironment, interpreterLogger);
  }
}
