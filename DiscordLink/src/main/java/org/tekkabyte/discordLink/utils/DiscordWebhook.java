package org.tekkabyte.discordLink.utils;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class DiscordWebhook {

    private final String webhookUrl;
    private final String threadIdOverride;

    private volatile long blockedUntilMillis = 0L;

    public DiscordWebhook(String webhookUrl) {
        this(webhookUrl, null);
    }

    public DiscordWebhook(String webhookUrl, String threadIdOverride) {
        String cleaned = webhookUrl.endsWith("/") ? webhookUrl.substring(0, webhookUrl.length() - 1) : webhookUrl;
        this.webhookUrl = cleaned;
        this.threadIdOverride = (threadIdOverride == null || threadIdOverride.isBlank()) ? null : threadIdOverride.trim();
    }

    public void sendMinecraftChat(String playerName, String message) {
        String content = "**" + safe(playerName) + "**: " + safe(message);
        sendWebhookMessage(content);
    }

    public void sendPlayerJoin(String playerName) {
        sendSimpleEmbed("Player Joined", "✅ **" + safe(playerName) + "** joined the server.");
    }

    public void sendPlayerLeave(String playerName) {
        sendSimpleEmbed("Player Left", "👋 **" + safe(playerName) + "** left the server.");
    }

    public boolean sendReport(String staffRoleId,
                              String reporter,
                              String reporterUuid,
                              String reporterDiscordId,
                              String target,
                              String reason,
                              long ignoredTimestamp) {

        String reporterSafe = safe(reporter);
        String reporterUuidSafe = safe(reporterUuid);
        String targetSafe = safe(target);
        String reasonSafe = (reason == null || reason.isBlank()) ? "No reason provided." : safe(reason);

        String roleId = normalizeSnowflake(staffRoleId);
        String userId = normalizeSnowflake(reporterDiscordId);

        String staffMention = roleId.isEmpty() ? "" : "<@&" + roleId + "> ";

        JsonObject embed = new JsonObject();
        embed.addProperty("title", "🚩 New Report");
        embed.addProperty("description", "A player report was submitted.");

        JsonArray fields = new JsonArray();
        fields.add(field("Reporter", reporterSafe + " (`" + reporterUuidSafe + "`)", true));
        fields.add(field("Target", targetSafe, true));
        fields.add(field("Reason", reasonSafe, false));
        embed.add("fields", fields);

        JsonObject payload = new JsonObject();
        payload.addProperty("content", staffMention + "Report filed by <@" + userId + ">");
        payload.add("embeds", arr(embed));

        payload.add("allowed_mentions", allowRoleAndUserMention(roleId, userId));

        if (threadIdOverride == null) {
            payload.addProperty("thread_name", "Report: " + truncate(targetSafe, 70));
        }

        return sendPostRequest(buildUrlWithThreadIdIfNeeded(), payload.toString());
    }

    private static JsonObject allowRoleAndUserMention(String roleId, String userId) {
        JsonObject allowed = new JsonObject();

        allowed.add("parse", new JsonArray());

        JsonArray roles = new JsonArray();
        if (roleId != null && !roleId.isBlank()) roles.add(roleId);
        allowed.add("roles", roles);

        JsonArray users = new JsonArray();
        if (userId != null && !userId.isBlank()) users.add(userId);
        allowed.add("users", users);

        return allowed;
    }

    private static String normalizeSnowflake(String s) {
        if (s == null) return "";
        String digits = s.replaceAll("[^0-9]", "");
        if (digits.length() < 16) return "";
        return digits;
    }

    public boolean sendOnlinePlayersMessage(String content) {
        return sendWebhookMessage(content);
    }

    public boolean sendOnlinePlayersEmbed(int count, java.util.Collection<String> playerNames) {
        JsonObject embed = new JsonObject();
        embed.addProperty("title", "👥 Online Players (" + count + ")");
        embed.addProperty("description",
                playerNames == null || playerNames.isEmpty()
                        ? "*No players online*"
                        : String.join("\n", playerNames)
        );

        JsonObject payload = new JsonObject();
        payload.add("embeds", arr(embed));
        payload.add("allowed_mentions", noMentions());

        return sendPostRequest(buildUrlWithThreadIdIfNeeded(), payload.toString());
    }

    private boolean sendWebhookMessage(String content) {
        long now = System.currentTimeMillis();
        if (now < blockedUntilMillis) return false;

        content = sanitizeMentions(content);

        if (content == null) content = "";
        if (content.length() > 2000) content = content.substring(0, 1997) + "...";

        JsonObject payload = new JsonObject();
        payload.addProperty("content", content);

        payload.add("allowed_mentions", noMentions());

        return sendPostRequest(buildUrlWithThreadIdIfNeeded(), payload.toString());
    }

    private boolean sendSimpleEmbed(String title, String description) {
        JsonObject embed = new JsonObject();
        embed.addProperty("title", safe(title));
        embed.addProperty("description", safe(description));

        JsonObject payload = new JsonObject();
        payload.add("embeds", arr(embed));

        payload.add("allowed_mentions", noMentions());

        return sendPostRequest(buildUrlWithThreadIdIfNeeded(), payload.toString());
    }

    private String buildUrlWithThreadIdIfNeeded() {
        String url = webhookUrl;

        if (threadIdOverride != null) {
            String sep = url.contains("?") ? "&" : "?";
            url = url + sep + "thread_id=" + urlEncode(threadIdOverride);
        }

        return url;
    }

    private boolean sendPostRequest(String urlString, String jsonData) {
        HttpURLConnection conn = null;
        try {
            long now = System.currentTimeMillis();
            if (now < blockedUntilMillis) return false;

            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();

            conn.setRequestMethod("POST");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(8000);
            conn.setDoOutput(true);

            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "DiscordLink/1.0");

            byte[] input = jsonData.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(input.length);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(input);
            }

            int responseCode = conn.getResponseCode();

            if (responseCode == 429) {
                blockedUntilMillis = System.currentTimeMillis() + 5000L;
                Bukkit.getLogger().warning("[DiscordLink] Rate limited by Discord (429). Backing off for 5 seconds.");
                return false;
            }

            if (responseCode < 200 || responseCode >= 300) {
                Bukkit.getLogger().warning("[DiscordLink] Webhook POST failed with HTTP " + responseCode + " (URL=" + urlString + ")");

                try {
                    var is = conn.getErrorStream();
                    if (is != null) {
                        String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                        if (!body.isBlank()) {
                            if (body.length() > 1500) body = body.substring(0, 1500) + "...";
                            Bukkit.getLogger().warning("[DiscordLink] Discord error body: " + body);
                        }
                    }
                } catch (Exception ignored) {}
            }

            return responseCode >= 200 && responseCode < 300;
        } catch (Exception e) {
            Bukkit.getLogger().warning("[DiscordLink] Webhook POST failed.");
            e.printStackTrace();
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String sanitizeMentions(String s) {
        if (s == null) return "";
        return s
                .replace("@everyone", "@\u200Beveryone")
                .replace("@here", "@\u200Bhere")
                .replaceAll("<@&\\d+>", "[role]")
                .replaceAll("<@!?\\d+>", "[user]");
    }

    private static JsonObject noMentions() {
        JsonObject allowed = new JsonObject();
        allowed.add("parse", new JsonArray());
        allowed.add("users", new JsonArray());
        allowed.add("roles", new JsonArray());
        return allowed;
    }

    private static JsonObject onlyUserMention(String discordId) {
        JsonObject allowed = new JsonObject();

        allowed.add("parse", new JsonArray());

        JsonArray users = new JsonArray();
        if (discordId != null && !discordId.isBlank()) users.add(discordId);
        allowed.add("users", users);

        allowed.add("roles", new JsonArray());

        return allowed;
    }

    private static JsonArray arr(JsonObject... objs) {
        JsonArray a = new JsonArray();
        for (JsonObject o : objs) if (o != null) a.add(o);
        return a;
    }

    private static JsonObject field(String name, String value, boolean inline) {
        JsonObject f = new JsonObject();
        f.addProperty("name", name);
        f.addProperty("value", value);
        f.addProperty("inline", inline);
        return f;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, Math.max(0, max - 1)) + "…";
    }

    private static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    public String fetchDiscordMessages() {
        return null;
    }
}