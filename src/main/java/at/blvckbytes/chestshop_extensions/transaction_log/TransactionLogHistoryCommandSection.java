package at.blvckbytes.chestshop_extensions.transaction_log;

import at.blvckbytes.cm_mapper.section.command.CommandSection;
import at.blvckbytes.component_markup.expression.interpreter.InterpretationEnvironment;
import at.blvckbytes.component_markup.util.logging.InterpreterLogger;

public class TransactionLogHistoryCommandSection extends CommandSection {

  public static final String INITIAL_NAME = "shoptransactionloghistory";

  public TransactionLogHistoryCommandSection(InterpretationEnvironment baseEnvironment, InterpreterLogger interpreterLogger) {
    super(INITIAL_NAME, baseEnvironment, interpreterLogger);
  }
}
