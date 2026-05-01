package at.blvckbytes.chestshop_extensions.eco_log;

import java.util.UUID;

public interface EcoLogger {

  void logTwoWayTransaction(
    String moneySenderName, UUID moneySenderUuid,
    String moneyReceiverName, UUID moneyReceiverUuid,
    double amount, String category, String description
  );

}
