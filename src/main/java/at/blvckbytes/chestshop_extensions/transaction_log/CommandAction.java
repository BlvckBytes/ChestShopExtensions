package at.blvckbytes.chestshop_extensions.transaction_log;

import me.blvckbytes.item_predicate_parser.syllables_matcher.EnumMatcher;
import me.blvckbytes.item_predicate_parser.syllables_matcher.MatchableEnum;

public enum CommandAction implements MatchableEnum {
  HELP,
  HISTORY,
  CLEAR,
  TELEPORT,
  ;

  public static final EnumMatcher<CommandAction> matcher = new EnumMatcher<>(values());

}
