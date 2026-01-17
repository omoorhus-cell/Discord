package org.tekkabyte.discordLink.commands;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.tekkabyte.discordLink.DiscordLink;
import org.tekkabyte.discordLink.utils.DiscordWebhook;
import org.tekkabyte.discordLink.utils.SupabaseClient;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ReportCommand implements CommandExecutor {

    private final DiscordLink plugin;
    private final SupabaseClient supabase;

    private static final Map<UUID, Deque<Long>> RECENT_REPORTS = new ConcurrentHashMap<>();

    public ReportCommand(DiscordLink plugin) {
        this.plugin = plugin;
        this.supabase = new SupabaseClient(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player reporter)) {
            sender.sendMessage("§cThis command can only be used by players.");
            return true;
        }

        if (args.length < 2) {
            reporter.sendMessage("§cUsage: /report <player> <reason...>");
            return true;
        }

        DiscordWebhook hook = plugin.getReportsWebhook();
        if (hook == null) {
            reporter.sendMessage("§cReports are not configured (missing discord.reports-webhook).");
            return true;
        }

        final int max = plugin.getConfig().getInt("reports.max-per-player", 3);
        final long windowSeconds = plugin.getConfig().getLong("reports.window-seconds", 86400L);
        final long windowMillis = Math.max(1L, windowSeconds) * 1000L;

        final UUID reporterUuidObj = reporter.getUniqueId();
        final long now = System.currentTimeMillis();

        Deque<Long> dq = RECENT_REPORTS.computeIfAbsent(reporterUuidObj, k -> new ArrayDeque<>());

        synchronized (dq) {
            while (!dq.isEmpty() && (now - dq.peekFirst()) > windowMillis) dq.pollFirst();

            if (dq.size() >= max) {
                long oldest = dq.peekFirst();
                long resetInMs = windowMillis - (now - oldest);
                long resetInSec = Math.max(1L, (resetInMs + 999) / 1000);

                reporter.sendMessage("§cYou have reached the report limit (" + max + " per " + windowSeconds + "s).");
                reporter.sendMessage("§7Try again in §f" + resetInSec + "s§7.");
                return true;
            }

            dq.addLast(now);
        }

        String targetName = args[0];
        String reason = String.join(" ", Arrays.copyOfRange(args, 1, args.length));

        final String reporterName = reporter.getName();
        final String reporterUuid = reporterUuidObj.toString();
        final long timestamp = now;

        String roleId = plugin.getConfig().getString("discord.staff-role-id", "").trim();
        final String staffMention = roleId.isEmpty() ? "" : "<@&" + roleId + ">";

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean success = false;

            try {
                String discordId = supabase.getLinkedDiscordId(reporterUuid);
                if (discordId == null) {
                    rollbackSlot(reporterUuidObj, timestamp);

                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (!reporter.isOnline()) return;
                        reporter.sendMessage("§cYou must link your Discord account before filing reports. Use §e/link§c.");
                    });
                    return;
                }

                success = hook.sendReport(
                        staffMention,
                        reporterName,
                        reporterUuid,
                        discordId,
                        targetName,
                        reason,
                        timestamp
                );

            } catch (Exception e) {
                rollbackSlot(reporterUuidObj, timestamp);
                plugin.getLogger().warning("Failed to send report webhook: " + e.getMessage());
                e.printStackTrace();
            }

            final boolean finalSuccess = success;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!reporter.isOnline()) return;

                if (finalSuccess) {
                    reporter.sendMessage("§aYour report has been submitted successfully.");
                } else {
                    reporter.sendMessage("§cFailed to submit report. Please try again later.");
                }
            });
        });

        return true;
    }

    private void rollbackSlot(UUID reporterUuid, long timestamp) {
        Deque<Long> dq = RECENT_REPORTS.get(reporterUuid);
        if (dq == null) return;
        synchronized (dq) {
            dq.removeFirstOccurrence(timestamp);
            if (dq.isEmpty()) RECENT_REPORTS.remove(reporterUuid);
        }
    }
}