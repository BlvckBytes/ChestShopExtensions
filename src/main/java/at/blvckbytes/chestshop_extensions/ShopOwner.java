package at.blvckbytes.chestshop_extensions;

import at.blvckbytes.chestshop_extensions.skin_cache.CachedSkin;
import at.blvckbytes.component_markup.expression.interpreter.InterpretationEnvironment;
import org.jetbrains.annotations.Nullable;

public class ShopOwner {

  public final String name;

  private @Nullable String textures;

  public ShopOwner(String name) {
    this.name = name;
  }

  public InterpretationEnvironment getEnvironment() {
    return new InterpretationEnvironment()
      .withVariable("owner", name)
      .withVariable("textures", this.textures);
  }

  public void onCachedSkinUpdate(CachedSkin cachedSkin) {
    if (cachedSkin.playerName.equalsIgnoreCase(this.name))
      this.textures = cachedSkin.textures;
  }
}
