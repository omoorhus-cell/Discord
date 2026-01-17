package org.tekkabyte.discordLink.listeners;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.tekkabyte.discordLink.DiscordLink;

public class ChatListener implements Listener {

    private final DiscordLink plugin;

    private static String sanitizeForDiscord(String s) {
        if (s == null) return "";
        s = ChatColor.stripColor(s);
        return s
                .replace("*", "\\*")
                .replace("_", "\\_")
                .replace("~", "\\~")
                .replace("`", "\\`");
    }

    public ChatListener(DiscordLink plugin) {
        this.plugin = plugin;
    }



    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        String playerName = event.getPlayer().getName();
        String message = event.getMessage();

        if (message.length() > 1900) {
            message = message.substring(0, 1897) + "...";
        }

        final String finalMessage = sanitizeForDiscord(message);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                plugin.getChatWebhook().sendMinecraftChat(playerName, finalMessage);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to forward chat to Discord: " + e.getMessage());
            }
        });
    }
}