package at.blvckbytes.chestshop_extensions.eco_log;

import at.blvckbytes.bb_eco_log.BBEcoLogPlugin;
import at.blvckbytes.bb_eco_log.EcoLogAPI;
import at.blvckbytes.bb_eco_log.logger.PlayerId;
import org.bukkit.Bukkit;

import java.util.UUID;

public class BBEcoLogLogger implements EcoLogger {

  private final EcoLogAPI api;

  public BBEcoLogLogger() {
    var ecoLogPlugin = (BBEcoLogPlugin) Bukkit.getPluginManager().getPlugin("BBEcoLog");

    if (ecoLogPlugin == null || !ecoLogPlugin.isEnabled())
      throw new IllegalStateException("Depending on BBEcoLog to be successfully loaded");

    this.api = ecoLogPlugin.getAPI();
  }

  @Override
  public void logTwoWayTransaction(
    String moneySenderName, UUID moneySenderUuid,
    String moneyReceiverName, UUID moneyReceiverUuid,
    double amount, String category, String description
  ) {
    api.logTwoWayTransaction(
      new PlayerId(moneySenderName, moneySenderUuid),
      new PlayerId(moneyReceiverName, moneyReceiverUuid),
      amount, category, description
    );
  }
}
