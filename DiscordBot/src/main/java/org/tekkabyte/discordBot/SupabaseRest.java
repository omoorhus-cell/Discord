package org.tekkabyte.discordBot;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

public class SupabaseRest {

    private static final Gson GSON = new Gson();

    private final String url;
    private final String serviceKey;
    private final HttpClient http = HttpClient.newHttpClient();

    public SupabaseRest(String supabaseUrl, String serviceKey) {
        this.url = supabaseUrl.endsWith("/") ? supabaseUrl.substring(0, supabaseUrl.length() - 1) : supabaseUrl;
        this.serviceKey = serviceKey;
    }

    public PayloadBuilder payload() { return new PayloadBuilder(); }

    public void insertBridgeEvent(String serverId, String type, JsonObject payload) throws Exception {
        JsonObject row = new JsonObject();
        row.addProperty("server_id", serverId);
        row.addProperty("type", type);
        row.add("payload", payload);

        JsonArray arr = new JsonArray();
        arr.add(row);

        post("/rest/v1/bridge_events", arr.toString());
    }

    public PendingCode fetchPendingByCode(String code) throws Exception {
        String endpoint = "/rest/v1/pending_link_codes"
                + "?code=eq." + enc(code)
                + "&select=code,minecraft_uuid,minecraft_username,expires_at"
                + "&limit=1";

        String body = get(endpoint);
        JsonArray arr = GSON.fromJson(body, JsonArray.class);
        if (arr == null || arr.size() == 0) return null;

        JsonObject o = arr.get(0).getAsJsonObject();
        return new PendingCode(
                o.get("code").getAsString(),
                o.get("minecraft_uuid").getAsString(),
                o.get("minecraft_username").getAsString(),
                o.get("expires_at").getAsString()
        );
    }

    public void deletePendingCode(String code) throws Exception {
        delete("/rest/v1/pending_link_codes?code=eq." + enc(code));
    }

    public boolean isMinecraftAlreadyLinked(String minecraftUuid) throws Exception {
        String endpoint = "/rest/v1/account_links?minecraft_uuid=eq." + enc(minecraftUuid) + "&select=minecraft_uuid&limit=1";
        String body = get(endpoint);
        JsonArray arr = GSON.fromJson(body, JsonArray.class);
        return arr != null && arr.size() > 0;
    }

    public boolean isDiscordAlreadyLinked(String discordId) throws Exception {
        String endpoint = "/rest/v1/account_links?discord_id=eq." + enc(discordId) + "&select=discord_id&limit=1";
        String body = get(endpoint);
        JsonArray arr = GSON.fromJson(body, JsonArray.class);
        return arr != null && arr.size() > 0;
    }

    public void upsertAccountLink(String minecraftUuid, String minecraftUsername, String discordId, String discordTag) throws Exception {
        JsonObject row = new JsonObject();
        row.addProperty("minecraft_uuid", minecraftUuid);
        row.addProperty("minecraft_username", minecraftUsername);
        row.addProperty("discord_id", discordId);
        row.addProperty("discord_tag", discordTag);
        row.addProperty("linked_at", Instant.now().toString());

        JsonArray arr = new JsonArray();
        arr.add(row);

        post("/rest/v1/account_links?on_conflict=minecraft_uuid", arr.toString());
    }


    private String get(String path) throws Exception {
        HttpRequest req = base(path).GET().build();
        return send(req, "GET " + path);
    }

    private void post(String path, String json) throws Exception {
        HttpRequest req = base(path)
                .header("Content-Type", "application/json")
                .header("Prefer", "return=minimal")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        send(req, "POST " + path);
    }

    private void delete(String path) throws Exception {
        HttpRequest req = base(path).DELETE().build();
        send(req, "DELETE " + path);
    }

    private HttpRequest.Builder base(String path) {
        return HttpRequest.newBuilder(URI.create(url + path))
                .header("apikey", serviceKey)
                .header("Authorization", "Bearer " + serviceKey)
                .header("Accept", "application/json");
    }

    private String send(HttpRequest req, String label) throws Exception {
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        int code = res.statusCode();
        String body = res.body() == null ? "" : res.body();

        if (code < 200 || code >= 300) {
            throw new RuntimeException("Supabase " + label + " -> HTTP " + code + ": " + body);
        }
        return body;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }


    public record PendingCode(String code, String minecraftUuid, String minecraftUsername, String expiresAtIso) {
        public boolean isExpired() {
            try {
                long exp = Instant.parse(expiresAtIso).toEpochMilli();
                return exp <= System.currentTimeMillis();
            } catch (Exception e) {
                return true;
            }
        }
    }

    public static class PayloadBuilder {
        private final JsonObject obj = new JsonObject();
        public PayloadBuilder add(String k, String v) { obj.addProperty(k, v); return this; }
        public PayloadBuilder add(String k, long v) { obj.addProperty(k, v); return this; }
        public PayloadBuilder add(String k, boolean v) { obj.addProperty(k, v); return this; }
        public JsonObject build() { return obj; }
    }
}