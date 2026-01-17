package org.tekkabyte.discordLink;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.tekkabyte.discordLink.Bridge.BridgeClient;
import org.tekkabyte.discordLink.Bridge.BridgeEvent;
import org.tekkabyte.discordLink.commands.LinkCommand;
import org.tekkabyte.discordLink.commands.ReportCommand;
import org.tekkabyte.discordLink.listeners.ChatListener;
import org.tekkabyte.discordLink.listeners.PlayerListener;
import org.tekkabyte.discordLink.utils.DiscordWebhook;
import net.md_5.bungee.api.ChatColor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DiscordLink extends JavaPlugin {

    private DiscordWebhook chatWebhook;
    private DiscordWebhook reportsWebhook;

    private final Map<UUID, String> pendingLinks = new HashMap<>();

    private BridgeClient bridgeClient;
    private BukkitTask bridgePollTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();

        FileConfiguration cfg = getConfig();

        String chatWebhookUrl = cfg.getString("discord.chat-webhook", "").trim();
        String reportsWebhookUrl = cfg.getString("discord.reports-webhook", "").trim();

        String reportsThreadIdOverride = cfg.getString("reports.thread-id-override", "").trim();
        if (reportsThreadIdOverride.isBlank()) reportsThreadIdOverride = null;

        if (chatWebhookUrl.isBlank()) {
            getLogger().warning("[DiscordLink] discord.chat-webhook is missing. Chat/join/leave will NOT send to Discord.");
        } else {
            chatWebhook = new DiscordWebhook(chatWebhookUrl);
        }

        if (reportsWebhookUrl.isBlank()) {
            getLogger().warning("[DiscordLink] discord.reports-webhook is missing. Reports will NOT send to Discord.");
        } else {
            reportsWebhook = new DiscordWebhook(reportsWebhookUrl, reportsThreadIdOverride);
        }

        if (getCommand("link") != null) getCommand("link").setExecutor(new LinkCommand(this));
        if (getCommand("report") != null) getCommand("report").setExecutor(new ReportCommand(this));

        Bukkit.getPluginManager().registerEvents(new ChatListener(this), this);
        Bukkit.getPluginManager().registerEvents(new PlayerListener(this), this);

        String pollUrl = cfg.getString("bridge.poll-url", "").trim();
        String serverId = cfg.getString("bridge.server-id", "").trim();
        String secret = cfg.getString("bridge.secret", "").trim();
        int pollIntervalTicks = cfg.getInt("bridge.poll-interval-ticks", 40);
        boolean allowDiscordCommands = cfg.getBoolean("bridge.allow-discord-commands", false);

        if (!pollUrl.isBlank() && !serverId.isBlank() && !secret.isBlank() && pollIntervalTicks > 0) {
            bridgeClient = new BridgeClient(this, pollUrl, serverId, secret);
            bridgePollTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                    this,
                    () -> handleBridgePoll(allowDiscordCommands),
                    20L,
                    pollIntervalTicks
            );
            getLogger().info("[DiscordLink] Bridge polling enabled.");
        }

        getLogger().info("[DiscordLink] Enabled.");
    }

    @Override
    public void onDisable() {
        if (bridgePollTask != null) {
            bridgePollTask.cancel();
            bridgePollTask = null;
        }
        bridgeClient = null;

        getLogger().info("[DiscordLink] Disabled.");
    }

    public DiscordWebhook getChatWebhook() { return chatWebhook; }
    public DiscordWebhook getReportsWebhook() { return reportsWebhook; }
    public Map<UUID, String> getPendingLinks() { return pendingLinks; }

    public static String colorize(String message) {
        Pattern hexPattern = Pattern.compile("&#([A-Fa-f0-9]{6})");
        Matcher matcher = hexPattern.matcher(message);

        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String hex = matcher.group(1);
            matcher.appendReplacement(
                    buffer,
                    ChatColor.of("#" + hex).toString()
            );
        }
        matcher.appendTail(buffer);

        return ChatColor.translateAlternateColorCodes('&', buffer.toString());
    }

    private void sendOnlinePlayersNow() {
        if (chatWebhook == null) return;

        var players = Bukkit.getOnlinePlayers();
        java.util.List<String> names = players.stream()
                .map(p -> p.getName())
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();

        chatWebhook.sendOnlinePlayersEmbed(names.size(), names);
    }

    private void handleBridgePoll(boolean allowDiscordCommands) {
        try {
            if (bridgeClient == null) return;

            List<BridgeEvent> events = bridgeClient.poll();
            if (events == null || events.isEmpty()) return;

            Bukkit.getScheduler().runTask(this, () -> {
                for (BridgeEvent e : events) {
                    if (e == null) continue;

                    if (e.isChat()) {
                        String author = (e.author == null || e.author.isBlank()) ? "Discord" : e.author;
                        String content = (e.content == null) ? "" : e.content;
                        String msg = "&#29b6f6&lDISCORD &7- &f" + author + "&7: &f" + content;
                        Bukkit.broadcastMessage(colorize(msg));
                    } else if (e.isOnline()) {
                        sendOnlinePlayersNow();
                    } else if (allowDiscordCommands && e.isCommand()) {
                        String cmd = (e.command == null) ? "" : e.command.trim();
                        if (!cmd.isBlank()) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                    }
                }
            });
        } catch (Exception ex) {
            getLogger().warning("[DiscordLink] Bridge poll handling failed: " + ex.getMessage());
        }
    }
}