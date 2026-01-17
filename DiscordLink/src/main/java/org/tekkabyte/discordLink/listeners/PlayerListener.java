package org.tekkabyte.discordLink.listeners;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.tekkabyte.discordLink.DiscordLink;

public class PlayerListener implements Listener {

    private final DiscordLink plugin;

    public PlayerListener(DiscordLink plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        final String playerName = event.getPlayer().getName();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                plugin.getChatWebhook().sendPlayerJoin(playerName);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to send join webhook: " + e.getMessage());
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        final String playerName = event.getPlayer().getName();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                plugin.getChatWebhook().sendPlayerLeave(playerName);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to send leave webhook: " + e.getMessage());
            }
        });
    }
}