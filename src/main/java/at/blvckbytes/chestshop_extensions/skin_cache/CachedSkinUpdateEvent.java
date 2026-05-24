package at.blvckbytes.chestshop_extensions.skin_cache;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

public class CachedSkinUpdateEvent extends Event {

  private static final HandlerList handlers = new HandlerList();

  public final CachedSkin cachedSkin;

  public CachedSkinUpdateEvent(CachedSkin cachedSkin) {
    this.cachedSkin = cachedSkin;
  }

  @Override
  public @NotNull HandlerList getHandlers() {
    return handlers;
  }

  @NotNull
  public static HandlerList getHandlerList() {
    return handlers;
  }
}
