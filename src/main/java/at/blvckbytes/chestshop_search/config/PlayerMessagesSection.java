package at.blvckbytes.chestshop_search.config;

import at.blvckbytes.cm_mapper.cm.ComponentMarkup;
import at.blvckbytes.cm_mapper.mapper.section.ConfigSection;
import at.blvckbytes.component_markup.expression.interpreter.InterpretationEnvironment;
import at.blvckbytes.component_markup.util.logging.InterpreterLogger;

public class PlayerMessagesSection extends ConfigSection {

  public ComponentMarkup searchCommandInvalidSearch;
  public ComponentMarkup searchCommandNoResults;
  public ComponentMarkup searchCommandResponse;
  public ComponentMarkup searchCommandBlankUi;
  public ComponentMarkup shopTeleportShopGone;
  public ComponentMarkup shopTeleportTeleported;
  public ComponentMarkup shopSearchToggleNotInARegion;
  public ComponentMarkup shopSearchToggleNowVisible;
  public ComponentMarkup shopSearchToggleNowInvisible;
  public ComponentMarkup pluginReloadedSuccess;
  public ComponentMarkup pluginReloadedError;
  public ComponentMarkup notLookingAtShopSign;
  public ComponentMarkup multipleSignsToChooseFrom;
  public ComponentMarkup buySellNoPermission;
  public ComponentMarkup buySellUsage;
  public ComponentMarkup buySellMalformedAmount;
  public ComponentMarkup buySellAmountInvalid;

  public PlayerMessagesSection(InterpretationEnvironment baseEnvironment, InterpreterLogger interpreterLogger) {
    super(baseEnvironment, interpreterLogger);
  }
}
