package org.tekkabyte.discordLink.utils;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.tekkabyte.discordLink.DiscordLink;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

public class SupabaseClient {

    private final DiscordLink plugin;
    private final String supabaseUrl;
    private final String supabaseKey;

    public SupabaseClient(DiscordLink plugin) {
        this.plugin = plugin;
        this.supabaseUrl = plugin.getConfig().getString("supabase.url");
        this.supabaseKey = plugin.getConfig().getString("supabase.service-key");
    }

    public String getLinkedDiscordId(String minecraftUuid) {
        try {
            String endpoint = supabaseUrl + "/rest/v1/account_links"
                    + "?minecraft_uuid=eq." + minecraftUuid
                    + "&select=discord_id"
                    + "&limit=1";

            URL url = new URL(endpoint);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("apikey", supabaseKey);
            conn.setRequestProperty("Authorization", "Bearer " + supabaseKey);

            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                try (BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = in.readLine()) != null) response.append(line);

                    JsonArray arr = JsonParser.parseString(response.toString()).getAsJsonArray();
                    if (arr.size() == 0) return null;

                    JsonObject obj = arr.get(0).getAsJsonObject();
                    if (!obj.has("discord_id") || obj.get("discord_id").isJsonNull()) return null;
                    String id = obj.get("discord_id").getAsString();
                    return (id == null || id.isBlank()) ? null : id.trim();
                }
            }
            conn.disconnect();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public boolean isAlreadyLinked(String minecraftUuid) {
        try {
            String endpoint = supabaseUrl + "/rest/v1/account_links?minecraft_uuid=eq." + minecraftUuid;
            URL url = new URL(endpoint);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("apikey", supabaseKey);
            conn.setRequestProperty("Authorization", "Bearer " + supabaseKey);

            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = in.readLine()) != null) {
                    response.append(line);
                }
                in.close();

                JsonArray jsonArray = JsonParser.parseString(response.toString()).getAsJsonArray();
                return jsonArray.size() > 0;
            }
            conn.disconnect();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    public boolean createPendingLink(String code, String minecraftUuid, String minecraftUsername) {
        try {
            String endpoint = supabaseUrl + "/rest/v1/pending_link_codes";
            URL url = new URL(endpoint);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("apikey", supabaseKey);
            conn.setRequestProperty("Authorization", "Bearer " + supabaseKey);
            conn.setRequestProperty("Prefer", "return=representation");
            conn.setDoOutput(true);

            String expiresAt = Instant.now().plus(5, ChronoUnit.MINUTES).toString();

            JsonObject json = new JsonObject();
            json.addProperty("code", code);
            json.addProperty("minecraft_uuid", minecraftUuid);
            json.addProperty("minecraft_username", minecraftUsername);
            json.addProperty("expires_at", expiresAt);

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = json.toString().getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = conn.getResponseCode();
            conn.disconnect();

            return responseCode >= 200 && responseCode < 300;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
}