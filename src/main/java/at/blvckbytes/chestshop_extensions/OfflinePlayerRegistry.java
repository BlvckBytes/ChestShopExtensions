package at.blvckbytes.chestshop_extensions;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.stream.Stream;

public class OfflinePlayerRegistry implements Listener {

  private final List<String> knownNames;
  private final Map<String, UUID> idByNameLower;

  public OfflinePlayerRegistry() {
    this.knownNames = new ArrayList<>();
    this.idByNameLower = new HashMap<>();

    for (var offlinePlayer : Bukkit.getOfflinePlayers()) {
      var name = offlinePlayer.getName();

      if (name != null)
        addKnownName(name, offlinePlayer.getUniqueId());
    }
  }

  public Stream<String> streamKnownNames() {
    return knownNames.stream();
  }

  public @Nullable OfflinePlayer getPlayerByName(String name) {
    var playerId = idByNameLower.get(name.toLowerCase());

    if (playerId == null)
      return null;

    return Bukkit.getOfflinePlayer(playerId);
  }

  @EventHandler
  public void onJoin(PlayerJoinEvent event) {
    var player = event.getPlayer();

    addKnownName(player.getName(), player.getUniqueId());
    addKnownName(ComponentUtil.componentToText(player.displayName()), player.getUniqueId());
  }

  private void addKnownName(String name, UUID playerId) {
    var trimmedName = name.trim();

    if (trimmedName.isBlank())
      return;

    if (knownNames.stream().anyMatch(it -> it.equalsIgnoreCase(trimmedName)))
      return;

    knownNames.add(trimmedName);
    idByNameLower.put(trimmedName.toLowerCase(), playerId);
  }
}
