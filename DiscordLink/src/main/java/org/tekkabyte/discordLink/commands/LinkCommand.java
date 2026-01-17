package org.tekkabyte.discordLink.commands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.tekkabyte.discordLink.DiscordLink;
import org.tekkabyte.discordLink.utils.SupabaseClient;

import java.util.concurrent.ThreadLocalRandom;

public class LinkCommand implements CommandExecutor {

    private final DiscordLink plugin;
    private final SupabaseClient supabaseClient;

    public LinkCommand(DiscordLink plugin) {
        this.plugin = plugin;
        this.supabaseClient = new SupabaseClient(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cThis command can only be used by players.");
            return true;
        }

        final String uuid = player.getUniqueId().toString();
        final String name = player.getName();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                if (supabaseClient.isAlreadyLinked(uuid)) {
                    Bukkit.getScheduler().runTask(plugin, () ->
                            player.sendMessage("§cYour account is already linked to Discord.")
                    );
                    return;
                }

                boolean success = false;
                String code = null;

                for (int attempt = 0; attempt < 3 && !success; attempt++) {
                    code = generateCode();
                    success = supabaseClient.createPendingLink(code, uuid, name);
                }

                final boolean finalSuccess = success;
                final String finalCode = code;

                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) return;

                    if (finalSuccess) {
                        player.sendMessage("§a§m                                                    ");
                        player.sendMessage("§6§lAccount Linking");
                        player.sendMessage("");
                        Component codeComponent = Component.text(finalCode)
                                .color(NamedTextColor.AQUA)
                                .decorate(TextDecoration.BOLD)
                                .clickEvent(ClickEvent.copyToClipboard(finalCode))
                                .hoverEvent(HoverEvent.showText(
                                        Component.text("Click to copy")
                                                .color(NamedTextColor.YELLOW)
                                ));

                        player.sendMessage(Component.text("Your verification code: ")
                                .color(NamedTextColor.GRAY)
                                .append(codeComponent));

                        player.sendMessage("");
                        player.sendMessage("§7Post this code in the Discord linking channel.");
                        player.sendMessage("§7This code expires in §c5 minutes§7.");
                        player.sendMessage("§a§m                                                    ");
                    } else {
                        player.sendMessage("§cFailed to generate linking code. Please try again later.");
                    }
                });
            } catch (Exception e) {
                plugin.getLogger().warning("LinkCommand failed: " + e.getMessage());
                e.printStackTrace();

                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) {
                        player.sendMessage("§cFailed to generate linking code. Please try again later.");
                    }
                });
            }
        });

        return true;
    }

    private String generateCode() {
        final String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder code = new StringBuilder(6);
        ThreadLocalRandom r = ThreadLocalRandom.current();

        for (int i = 0; i < 6; i++) {
            code.append(chars.charAt(r.nextInt(chars.length())));
        }
        return code.toString();
    }
}
