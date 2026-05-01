package at.blvckbytes.chestshop_extensions.config;

import at.blvckbytes.cm_mapper.cm.ComponentMarkup;
import at.blvckbytes.cm_mapper.mapper.MappingError;
import at.blvckbytes.cm_mapper.mapper.section.CSAlways;
import at.blvckbytes.cm_mapper.mapper.section.ConfigSection;
import at.blvckbytes.component_markup.expression.interpreter.InterpretationEnvironment;
import at.blvckbytes.component_markup.util.logging.InterpreterLogger;

import java.lang.reflect.Field;
import java.util.List;

public class SellGuiSection extends ConfigSection {

  public @CSAlways RegionFilterSection regionFilter;

  public ComponentMarkup inventoryTitle;
  public int inventoryRowCount;

  public ComponentMarkup noPermission;
  public ComponentMarkup openingPrompt;
  public ComponentMarkup emptyInventory;
  public ComponentMarkup soldItems;
  public ComponentMarkup unsellableItems;
  public ComponentMarkup unallowedWorld;
  public ComponentMarkup unallowedRegion;

  public InvSellSection invSell;

  public SellGuiSection(InterpretationEnvironment baseEnvironment, InterpreterLogger interpreterLogger) {
    super(baseEnvironment, interpreterLogger);
  }

  @Override
  public void afterParsing(List<Field> fields) throws Exception {
    super.afterParsing(fields);

    if (inventoryRowCount <= 0)
      throw new MappingError("\"inventoryRowCount\" cannot be less than or equal to zero");
  }
}
