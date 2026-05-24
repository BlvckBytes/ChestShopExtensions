package at.blvckbytes.chestshop_extensions.skin_cache;

import com.google.gson.*;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.geysermc.floodgate.api.FloodgateApi;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SkinCache {

  private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder().build();
  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

  private final ConcurrentMap<String, UUID> playerIdByNameLower;
  private final ConcurrentMap<String, CachedSkin> cachedSkinByNameLower;

  private final File cacheFile;
  private final AtomicBoolean needsSaving;

  // Only request one skin at a time, seeing how it looks like we're sometimes running
  // into a sort of spam-detection (or rate-limiting, but who's to know).
  private final Queue<String> nameRequestQueue;
  private final AtomicBoolean isCurrentlyRequesting;

  private final Plugin plugin;
  private final Logger logger;
  private final boolean hasFloodgate;

  private long relativeTime;

  public SkinCache(Plugin plugin, Logger logger) throws Exception {
    this.playerIdByNameLower = new ConcurrentHashMap<>();
    this.cachedSkinByNameLower = new ConcurrentHashMap<>();

    this.cacheFile = new File(plugin.getDataFolder(), "cached-skins.json");
    this.needsSaving = new AtomicBoolean(false);

    if (!cacheFile.exists()) {
      if (!cacheFile.createNewFile())
        throw new IllegalStateException("Could not create cache-file " + cacheFile);
    }

    else if (!cacheFile.isFile())
      throw new IllegalStateException("Expected file at " + cacheFile);

    this.nameRequestQueue = new ArrayDeque<>();
    this.isCurrentlyRequesting = new AtomicBoolean(false);

    this.plugin = plugin;
    this.logger = logger;
    this.hasFloodgate = Bukkit.getServer().getPluginManager().isPluginEnabled("floodgate");

    Bukkit.getScheduler().runTaskTimer(plugin, () -> {
      ++relativeTime;

      if (relativeTime % 20 == 0)
        updateSkinsFromOnlinePlayers();

      if (needsSaving.get() && relativeTime % 20 == 0)
        Bukkit.getScheduler().runTaskAsynchronously(plugin, this::saveCachedSkins);

      if (!isCurrentlyRequesting.get()) {
        var requestToProcess = nameRequestQueue.poll();

        if (requestToProcess != null)
          getOrTryUpdateSkin(requestToProcess);
      }
    }, 0, 1);

    Bukkit.getScheduler().runTaskAsynchronously(plugin, this::loadCachedSkins);
  }

  public @Nullable CachedSkin getOrTryUpdateSkin(String ownerName) {
    var nameLower = ownerName.toLowerCase();
    var cachedSkin = cachedSkinByNameLower.get(nameLower);

    if (cachedSkin != null && !cachedSkin.shouldUpdate())
      return cachedSkin;

    if (isCurrentlyRequesting.get()) {
      nameRequestQueue.add(ownerName);
      return null;
    }

    isCurrentlyRequesting.set(true);

    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
      tryFetchAndCacheSkin(ownerName);
      isCurrentlyRequesting.set(false);
    });

    return null;
  }

  public void onShutdown() {
    saveCachedSkins();
  }

  private void saveCachedSkins() {
    var cacheItems = new JsonArray();

    for (var item : cachedSkinByNameLower.values())
      cacheItems.add(item.toJson());

    try (var writer = new FileWriter(cacheFile)) {
      writer.write(GSON.toJson(cacheItems));
      needsSaving.set(false);
    } catch (Throwable e) {
      logger.log(Level.SEVERE, "An error occurred while trying to save the skin-cache to file", e);
    }
  }

  private void addCacheEntry(CachedSkin cachedSkin, boolean callUpdateEvent) {
    cachedSkinByNameLower.put(cachedSkin.playerName.toLowerCase(), cachedSkin);
    needsSaving.set(true);

    if (callUpdateEvent)
      Bukkit.getScheduler().runTask(plugin, () -> callUpdateEvent(cachedSkin));
  }

  private void loadCachedSkins() {
    if (cacheFile.length() == 0)
      return;

    try (var reader = new FileReader(cacheFile)) {
      var cacheItems = GSON.fromJson(reader, JsonArray.class);

      for (var cacheItem : cacheItems) {
        if (!(cacheItem instanceof JsonObject jsonObject))
          continue;

        CachedSkin cachedSkin;

        try {
          cachedSkin = CachedSkin.fromJson(jsonObject);
        } catch (Throwable e) {
          logger.log(Level.WARNING, "An error occurred while trying to load a skin-cache entry", e);
          continue;
        }

        addCacheEntry(cachedSkin, false);
      }

      logger.info("Loaded " + cachedSkinByNameLower.size() + " cached skins");

      needsSaving.set(false);
    } catch (Throwable e) {
      logger.log(Level.SEVERE, "An error occurred while trying to load the skin-cache from file", e);
    }
  }

  private void updateSkinsFromOnlinePlayers() {
    for (var player : Bukkit.getOnlinePlayers()) {
      var skinUrl = player.getPlayerProfile().getTextures().getSkin();

      if (skinUrl == null)
        continue;

      var urlString = skinUrl.toString();
      var playerName = player.getName();
      var playerNameLower = playerName.toLowerCase();

      var existingSkin = cachedSkinByNameLower.get(playerNameLower);

      if (existingSkin != null && existingSkin.skinUrl.equals(urlString))
        continue;

      addCacheEntry(new CachedSkin(playerName, urlString, System.currentTimeMillis()), true);
    }
  }

  private void tryFetchAndCacheSkin(String ownerName) {
    // Floodgate-skins are a pain to access, so we only update and cache their skins when they are online.
    if (hasFloodgate && ownerName.startsWith(FloodgateApi.getInstance().getPlayerPrefix()))
      return;

    var playerId = tryGetPlayerUUID(ownerName);

    if (playerId == null)
      return;

    try {
      var url = "https://sessionserver.mojang.com/session/minecraft/profile/" + playerId + "?unsigned=false";

      var request = HttpRequest.newBuilder(URI.create(url)).build();
      var response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() != 200)
        throw new IllegalStateException("Non-200 status-code for profile-resolve: " + response.statusCode());

      var jsonElement = JsonParser.parseString(response.body());

      if (jsonElement == null || !jsonElement.isJsonObject())
        throw new IllegalStateException("Non-json response");

      var json = jsonElement.getAsJsonObject();

      if (!(json.get("properties") instanceof JsonArray properties))
        throw new IllegalStateException("Missing key \"properties\" (or is not an array)");

      for (JsonElement propertyElement : properties) {
        if (!(propertyElement instanceof JsonObject property))
          continue;

        if (!(property.get("name") instanceof JsonPrimitive name))
          continue;

        if (!"textures".equals(name.getAsString()))
          continue;

        if (!(property.get("value") instanceof JsonPrimitive value))
          continue;

        var base64Value = value.getAsString();

        String decodedValue;

        try {
          decodedValue = new String(Base64.getDecoder().decode(base64Value));
        } catch (Throwable e) {
          throw new IllegalStateException("Received invalid base64: " + base64Value, e);
        }

        if (!(GSON.fromJson(decodedValue, JsonElement.class) instanceof JsonObject valueObject))
          throw new IllegalStateException("Expected decoded base64 \"" + base64Value + "\" to be a json-object");

        if (!(valueObject.get("textures") instanceof JsonObject texturesObject))
          throw new IllegalStateException("Expected decoded base64 \"" + base64Value + "\" to contain a \"textures\" json-object");

        if (!(texturesObject.get("SKIN") instanceof JsonObject skinObject))
          throw new IllegalStateException("Expected decoded base64 \"" + base64Value + "\" to contain a \"textures\".\"SKIN\" json-object");

        if (!(skinObject.get("url") instanceof JsonPrimitive urlPrimitive))
          throw new IllegalStateException("Expected decoded base64 \"" + base64Value + "\" to contain a \"textures\".\"SKIN\".\"url\" json-primitive");

        var skinUrl = urlPrimitive.getAsString();

        addCacheEntry(new CachedSkin(ownerName, skinUrl, System.currentTimeMillis()), true);

        return;
      }

      throw new IllegalStateException("Missing properties.textures");
    } catch (Exception e) {
      logger.log(Level.WARNING, "An error occurred while trying to fetch Java skull-data for " + ownerName + ": " + e.getMessage());
    }
  }

  private @Nullable UUID tryGetPlayerUUID(String ownerName) {
    var nameLower = ownerName.toLowerCase();

    UUID id;

    if ((id = playerIdByNameLower.get(nameLower)) != null)
      return id;

    var cachedPlayer = Bukkit.getOfflinePlayerIfCached(ownerName);

    if (cachedPlayer != null) {
      id = cachedPlayer.getUniqueId();

      playerIdByNameLower.put(nameLower, id);

      return id;
    }

    try {
      var url = "https://api.mojang.com/users/profiles/minecraft/" + ownerName;

      var request = HttpRequest.newBuilder(URI.create(url)).build();
      var response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() != 200)
        throw new IllegalStateException("Non-200 status-code for UUID-resolve: " + response.statusCode());

      var jsonElement = JsonParser.parseString(response.body());

      if (jsonElement == null || !jsonElement.isJsonObject())
        throw new IllegalStateException("Non-json response");

      var json = jsonElement.getAsJsonObject();

      if (!(json.get("id") instanceof JsonPrimitive idPrimitive))
        throw new IllegalStateException("Missing key \"id\"");

      id = fromDashLessString(idPrimitive.getAsString());
    } catch (Throwable e) {
      logger.log(Level.WARNING, "An error occurred while trying to resolve the UUID of " + ownerName, e);
      return null;
    }

    playerIdByNameLower.put(nameLower, id);

    return id;
  }

  private static UUID fromDashLessString(String input) {
    if (input == null || input.length() != 32)
      throw new IllegalArgumentException("Invalid dash-less UUID: " + input);

    var sb = new StringBuilder(input);
    sb.insert(8, '-');
    sb.insert(13, '-');
    sb.insert(18, '-');
    sb.insert(23, '-');

    return UUID.fromString(sb.toString());
  }

  private void callUpdateEvent(CachedSkin cachedSkin) {
    Bukkit.getPluginManager().callEvent(new CachedSkinUpdateEvent(cachedSkin));
  }
}
