package at.blvckbytes.chestshop_extensions.transaction_log;

import at.blvckbytes.cm_mapper.cm.ComponentMarkup;
import at.blvckbytes.cm_mapper.mapper.MappingError;
import at.blvckbytes.cm_mapper.mapper.section.ConfigSection;
import at.blvckbytes.component_markup.expression.interpreter.InterpretationEnvironment;
import at.blvckbytes.component_markup.util.logging.InterpreterLogger;

import java.lang.reflect.Field;
import java.util.List;

public class TransactionLogSection extends ConfigSection {

  public TransactionLogCommandSection mainCommand;
  public TransactionLogHistoryCommandSection historyShortcutCommand;

  public int maxAgeDays;
  public int historyPageSize;
  public int joinNotifyDelayTicks;
  public int transactionMergeMaxDeltaSeconds;

  public ComponentMarkup playersOnly;
  public ComponentMarkup helpScreen;
  public ComponentMarkup invalidPageNumber;
  public ComponentMarkup invalidTransactionNumber;
  public ComponentMarkup outOfBoundsPageNumber;
  public ComponentMarkup noHistoryToClear;
  public ComponentMarkup noHistoryToShow;
  public ComponentMarkup historyCleared;
  public ComponentMarkup historyPageScreen;
  public ComponentMarkup joinNotification;
  public ComponentMarkup teleportHasNoTransactions;
  public ComponentMarkup teleportNumberNotFound;
  public ComponentMarkup teleportFailedNoSign;
  public ComponentMarkup teleportSuccess;

  public TransactionLogSection(InterpretationEnvironment baseEnvironment, InterpreterLogger interpreterLogger) {
    super(baseEnvironment, interpreterLogger);
  }

  @Override
  public void afterParsing(List<Field> fields) throws Exception {
    super.afterParsing(fields);

    if (maxAgeDays <= 0)
      throw new MappingError("Property \"magAgeDays\" cannot be less than or equal to zero");

    if (historyPageSize <= 0)
      throw new MappingError("Property \"historyPageSize\" cannot be less than or equal to zero");

    if (joinNotifyDelayTicks <= 0)
      throw new MappingError("Property \"joinNotifyDelayTicks\" cannot be less than or equal to zero");

    if (transactionMergeMaxDeltaSeconds <= 0)
      throw new MappingError("Property \"transactionMergeMaxDeltaSeconds\" cannot be less than or equal to zero");
  }
}
