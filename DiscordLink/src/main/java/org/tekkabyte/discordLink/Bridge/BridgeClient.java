package org.tekkabyte.discordLink.Bridge;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.List;

public class BridgeClient {

    private static final Gson GSON = new Gson();
    private static final Type LIST_TYPE = new TypeToken<List<BridgeEvent>>() {}.getType();

    private final JavaPlugin plugin;
    private final String pollUrl;
    private final String serverId;
    private final String secret;

    public BridgeClient(JavaPlugin plugin, String pollUrl, String serverId, String secret) {
        this.plugin = plugin;
        this.pollUrl = pollUrl;
        this.serverId = serverId;
        this.secret = secret;
    }

    public List<BridgeEvent> poll() {
        HttpURLConnection conn = null;
        try {
            String fullUrl = pollUrl
                    + "?serverId=" + URLEncoder.encode(serverId, StandardCharsets.UTF_8);

            conn = (HttpURLConnection) new URL(fullUrl).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "DiscordLink/1.0");

            conn.setRequestProperty("X-Server-Secret", secret);

            int code = conn.getResponseCode();
            if (code != 200) {
                Bukkit.getLogger().warning("[DiscordLink] Bridge poll failed: HTTP " + code);
                return Collections.emptyList();
            }

            try (InputStream in = conn.getInputStream()) {
                String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                if (json.isBlank()) return Collections.emptyList();
                return GSON.fromJson(json, LIST_TYPE);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Bridge poll error: " + e.getMessage());
            return Collections.emptyList();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}