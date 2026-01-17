package org.tekkabyte.discordBot;

import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;

public class BotListener extends ListenerAdapter {

    private final DiscordBot plugin;
    private final SupabaseRest supabase;

    public BotListener(DiscordBot plugin, SupabaseRest supabase) {
        this.plugin = plugin;
        this.supabase = supabase;
    }

    private static String sanitizeContent(String text) {
        String s = String.valueOf(text == null ? "" : text);

        s = s.replace("@everyone", "@\u200Beveryone")
                .replace("@here", "@\u200Bhere");

        s = s.replace("<@&", "<@\u200B&")
                .replace("<@!", "<@\u200B!")
                .replace("<@", "<@\u200B");

        return s.trim();
    }

    private static boolean isSixCharCode(String s) {
        return s != null && s.matches("^[A-Z0-9]{6}$");
    }

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        try {
            if (!event.isFromGuild()) return;
            if (!event.getName().equalsIgnoreCase("online")) return;

            supabase.insertBridgeEvent(plugin.getServerId(), "online",
                    supabase.payload()
                            .add("by", event.getMember() != null ? event.getMember().getEffectiveName() : event.getUser().getName())
                            .add("discord_id", event.getUser().getId())
                            .add("ts", System.currentTimeMillis())
                            .build()
            );

            event.reply("👥 Requested online player list.").setEphemeral(true).queue();
        } catch (Exception e) {
            plugin.getLogger().warning("interactionCreate(/online) failed: " + e.getMessage());
            try {
                event.reply("❌ Failed to request online list.").setEphemeral(true).queue();
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void onMessageReceived(@NotNull MessageReceivedEvent msg) {
        try {
            if (msg.getAuthor().isBot()) return;
            if (!msg.isFromGuild()) return;

            String channelId = msg.getChannel().getId();

            plugin.getLogger().info("[Discord] msg channel=" + channelId +
                    " author=" + msg.getAuthor().getAsTag() +
                    " raw=" + msg.getMessage().getContentRaw());

            // LINK CHANNEL: paste 6-char code
            if (channelId.equals(plugin.getLinkChannelId())) {
                String code = msg.getMessage().getContentRaw().trim().toUpperCase();
                if (!isSixCharCode(code)) return;

                msg.getMessage().delete().queue(ok -> {}, err -> {});

                var pending = supabase.fetchPendingByCode(code);
                if (pending == null) {
                    msg.getChannel().sendMessage("❌ That code is invalid or already used.").queue();
                    return;
                }

                if (pending.isExpired()) {
                    supabase.deletePendingCode(code);
                    msg.getChannel().sendMessage("⌛ That code expired. Run `/link` in Minecraft again.").queue();
                    return;
                }

                if (plugin.isLinkOneToOne()) {
                    if (supabase.isMinecraftAlreadyLinked(pending.minecraftUuid())) {
                        supabase.deletePendingCode(code);
                        msg.getChannel().sendMessage("❌ That Minecraft account is already linked.").queue();
                        return;
                    }
                    if (supabase.isDiscordAlreadyLinked(msg.getAuthor().getId())) {
                        msg.getChannel().sendMessage("❌ Your Discord account is already linked to a Minecraft account.").queue();
                        return;
                    }
                }

                supabase.upsertAccountLink(
                        pending.minecraftUuid(),
                        pending.minecraftUsername(),
                        msg.getAuthor().getId(),
                        msg.getAuthor().getName() + "#" + msg.getAuthor().getDiscriminator()
                );
                supabase.deletePendingCode(code);

                String roleId = plugin.getLinkRoleId();
                if (roleId != null) {
                    Member member = msg.getMember();
                    Role role = msg.getGuild().getRoleById(roleId);

                    if (member != null && role != null) {
                        msg.getGuild().addRoleToMember(member, role)
                                .reason("Minecraft account linked")
                                .queue(ok -> {}, err -> plugin.getLogger().warning("Failed to grant role: " + err.getMessage()));
                    }
                }
                String roleText = (roleId != null) ? " and granted the linked role." : ".";
                msg.getChannel().sendMessage("✅ Linked **" + pending.minecraftUsername() + "** to <@" + msg.getAuthor().getId() + ">" + roleText).queue();
                return;
            }

            if (channelId.equals(plugin.getChatChannelId())) {
                String raw = msg.getMessage().getContentRaw().trim();

                if (raw.equalsIgnoreCase("!online")) {
                    supabase.insertBridgeEvent(plugin.getServerId(), "online",
                            supabase.payload()
                                    .add("by", msg.getMember() != null ? msg.getMember().getEffectiveName() : msg.getAuthor().getName())
                                    .add("discord_id", msg.getAuthor().getId())
                                    .add("ts", System.currentTimeMillis())
                                    .build()
                    );
                    msg.getMessage().addReaction(Emoji.fromUnicode("👥")).queue();
                    return;
                }

                StringBuilder attachmentText = new StringBuilder();
                msg.getMessage().getAttachments().forEach(a -> attachmentText.append("\n").append(a.getUrl()));

                String content = sanitizeContent(raw + attachmentText);
                if (content.isEmpty()) return;

                String author = msg.getMember() != null ? msg.getMember().getEffectiveName() : msg.getAuthor().getName();

                supabase.insertBridgeEvent(plugin.getServerId(), "chat",
                        supabase.payload()
                                .add("author", author)
                                .add("content", content)
                                .add("ts", System.currentTimeMillis())
                                .add("discord_id", msg.getAuthor().getId())
                                .add("discord_tag", msg.getAuthor().getName() + "#" + msg.getAuthor().getDiscriminator())
                                .build()
                );
                return;
            }

            if (plugin.isAllowDiscordCommands()
                    && plugin.getAdminChannelId() != null
                    && channelId.equals(plugin.getAdminChannelId())) {

                String text = msg.getMessage().getContentRaw();
                if (!text.startsWith("!mc ")) return;

                Member member = msg.getMember();
                if (member == null || !member.hasPermission(Permission.ADMINISTRATOR)) {
                    msg.getMessage().reply("❌ You must have Administrator to run Minecraft commands.").queue();
                    return;
                }

                String command = text.substring(4).trim();
                if (command.isEmpty()) return;

                supabase.insertBridgeEvent(plugin.getServerId(), "command",
                        supabase.payload()
                                .add("command", command)
                                .add("by", member.getEffectiveName())
                                .add("ts", System.currentTimeMillis())
                                .add("discord_id", msg.getAuthor().getId())
                                .build()
                );
                msg.getMessage().addReaction(Emoji.fromUnicode("✅")).queue();
            }

        } catch (Exception e) {
            plugin.getLogger().warning("messageCreate handler failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
}