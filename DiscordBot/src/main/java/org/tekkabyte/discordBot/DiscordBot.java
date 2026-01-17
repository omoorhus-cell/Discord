package org.tekkabyte.discordBot;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

public class DiscordBot extends JavaPlugin {

    private volatile JDA jda;
    private SupabaseRest supabase;

    private String serverId;

    private String discordToken;
    private String clientId;
    private String guildId;

    private String chatChannelId;
    private String linkChannelId;
    private String adminChannelId;

    private boolean allowDiscordCommands;
    private boolean linkOneToOne;
    private String linkRoleId;

    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();

        shuttingDown.set(false);

        serverId = getConfig().getString("bridge.server-id", "").trim();

        discordToken = getConfig().getString("discord.token", "").trim();
        clientId = getConfig().getString("discord.client-id", "").trim();
        guildId = getConfig().getString("discord.guild-id", "").trim();

        chatChannelId = getConfig().getString("discord.mc-chat-channel-id", "").trim();
        linkChannelId = getConfig().getString("discord.mc-link-channel-id", "").trim();
        adminChannelId = getConfig().getString("discord.mc-admin-channel-id", "").trim();

        allowDiscordCommands = getConfig().getBoolean("discord.allow-discord-commands", false);
        linkOneToOne = getConfig().getBoolean("discord.link-one-to-one", true);
        linkRoleId = getConfig().getString("discord.link-role-id", "").trim();
        if (linkRoleId.isBlank()) linkRoleId = null;
        if (adminChannelId.isBlank()) adminChannelId = null;

        String supaUrl = getConfig().getString("supabase.url", "").trim();
        String supaKey = getConfig().getString("supabase.service-key", "").trim();

        if (serverId.isBlank()
                || discordToken.isBlank()
                || chatChannelId.isBlank()
                || linkChannelId.isBlank()
                || supaUrl.isBlank()
                || supaKey.isBlank()) {
            getLogger().severe("Missing config values. Need: bridge.server-id, discord.token, discord.mc-chat-channel-id, discord.mc-link-channel-id, supabase.url, supabase.service-key");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        supabase = new SupabaseRest(supaUrl, supaKey);

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                if (shuttingDown.get()) return;

                JDA built = JDABuilder.createDefault(discordToken)
                        .enableIntents(
                                GatewayIntent.GUILD_MESSAGES,
                                GatewayIntent.MESSAGE_CONTENT,
                                GatewayIntent.GUILD_MEMBERS // needed for addRoleToMember
                        )
                        .addEventListeners(new BotListener(this, supabase))
                        .build();

                if (shuttingDown.get()) {
                    try { built.shutdownNow(); } catch (Exception ignored) {}
                    return;
                }

                built.awaitReady();
                jda = built;

                getLogger().info("Discord bot connected as: " + jda.getSelfUser().getAsTag());

                if (!guildId.isBlank()) {
                    var guild = jda.getGuildById(guildId);
                    if (guild != null) {
                        guild.updateCommands()
                                .addCommands(Commands.slash("online", "Show online Minecraft players"))
                                .queue();
                        getLogger().info("Registered /online in guild " + guild.getName());
                    } else {
                        getLogger().warning("guild-id set but bot is not in that guild (or ID is wrong). /online not registered.");
                    }
                } else {
                    getLogger().warning("discord.guild-id not set. /online will only work if previously registered elsewhere.");
                }

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                getLogger().severe("Discord bot startup interrupted.");
                Bukkit.getScheduler().runTask(this, () -> Bukkit.getPluginManager().disablePlugin(this));
            } catch (Exception e) {
                getLogger().severe("Failed to start Discord bot: " + e.getMessage());
                e.printStackTrace();
                Bukkit.getScheduler().runTask(this, () -> Bukkit.getPluginManager().disablePlugin(this));
            }
        });
    }

    @Override
    public void onDisable() {
        shuttingDown.set(true);

        JDA local = this.jda;
        this.jda = null;

        if (local != null) {
            try {
                local.shutdown();
                try {
                    local.awaitShutdown(Duration.ofSeconds(5));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }

                if (local.getStatus() != JDA.Status.SHUTDOWN) {
                    local.shutdownNow();
                }
            } catch (Exception ignored) {
                try { local.shutdownNow(); } catch (Exception ignored2) {}
            }
        }
    }

    public String getServerId() { return serverId; }
    public String getChatChannelId() { return chatChannelId; }
    public String getLinkChannelId() { return linkChannelId; }
    public String getAdminChannelId() { return adminChannelId; }
    public boolean isAllowDiscordCommands() { return allowDiscordCommands; }
    public boolean isLinkOneToOne() { return linkOneToOne; }
    public String getLinkRoleId() { return linkRoleId; }
}
