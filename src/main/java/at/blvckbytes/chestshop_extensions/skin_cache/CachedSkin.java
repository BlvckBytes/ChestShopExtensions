package at.blvckbytes.chestshop_extensions.skin_cache;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.Base64;

public class CachedSkin {

  public static final long SHOULD_UPDATE_AFTER_MS = 1000 * 60 * 60 * 24 * 2;

  public final String playerName;
  public final String skinUrl;
  public final String textures;
  public final long fetchedAt;

  public CachedSkin(String playerName, String skinUrl, long fetchedAt) {
    this.playerName = playerName;
    this.skinUrl = skinUrl;
    this.textures = new String(Base64.getEncoder().encode(("{\"textures\":{\"SKIN\":{\"url\": \"" + skinUrl + "\"}}}").getBytes()));
    this.fetchedAt = fetchedAt;
  }

  public boolean shouldUpdate() {
    return System.currentTimeMillis() - fetchedAt >= SHOULD_UPDATE_AFTER_MS;
  }

  public JsonObject toJson() {
    var result = new JsonObject();

    result.addProperty("playerName", playerName);
    result.addProperty("url", skinUrl);
    result.addProperty("fetchedAt", fetchedAt);

    return result;
  }

  public static CachedSkin fromJson(JsonObject json) {
    if (!(json.get("playerName") instanceof JsonPrimitive playerNamePrimitive))
      throw new IllegalStateException("Expected \"playerName\" to be a json-primitive");

    if (!(json.get("url") instanceof JsonPrimitive urlPrimitive))
      throw new IllegalStateException("Expected \"url\" to be a json-primitive");

    if (!(json.get("fetchedAt") instanceof JsonPrimitive fetchedAtPrimitive))
      throw new IllegalStateException("Expected \"fetchedAt\" to be a json-primitive");

    if (!fetchedAtPrimitive.isNumber())
      throw new IllegalStateException("Expected \"fetchedAt\" to be a number");

    return new CachedSkin(
      playerNamePrimitive.getAsString(),
      urlPrimitive.getAsString(),
      fetchedAtPrimitive.getAsLong()
    );
  }
}
