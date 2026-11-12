package net.millyland.auth.admin;

import net.kyori.adventure.text.Component;
import net.millyland.auth.TgAuthPlugin;
import net.millyland.auth.api.AdminResult;
import net.millyland.auth.api.AdminResult.Outcome;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The punishment and account actions of the admin panel, shared by the Telegram panel and the public
 * {@link net.millyland.auth.api.AdminApi}. All of them run their server-side part on the main thread and
 * complete the returned future with the outcome.
 */
public final class AdminActions {

    public static final long PERMANENT = 0L;
    public static final long INVALID = -1L;

    private static final Pattern DURATION = Pattern.compile("^(\\d+)([smhdw])$");

    private final TgAuthPlugin plugin;

    public AdminActions(TgAuthPlugin plugin) {
        this.plugin = plugin;
    }

    /** @return milliseconds, {@link #PERMANENT} for p/perm/permanent (or null), {@link #INVALID} for bad input */
    public static long parseDuration(String input) {
        if (input == null) return PERMANENT;
        String s = input.trim().toLowerCase();
        if (s.equals("p") || s.equals("perm") || s.equals("permanent")) return PERMANENT;
        var m = DURATION.matcher(s);
        if (!m.matches()) return INVALID;
        long amount = Long.parseLong(m.group(1));
        long unit = switch (m.group(2)) {
            case "s" -> 1000L;
            case "m" -> 60_000L;
            case "h" -> 3_600_000L;
            case "d" -> 86_400_000L;
            case "w" -> 604_800_000L;
            default -> -1L;
        };
        long total = amount * unit;
        return total <= 0 ? INVALID : total;
    }

    private CompletableFuture<AdminResult> onMain(Supplier<AdminResult> work) {
        CompletableFuture<AdminResult> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                future.complete(work.get());
            } catch (Throwable t) {
                plugin.getLogger().warning("Admin action failed: " + t);
                future.complete(AdminResult.of(Outcome.FAILED));
            }
        });
        return future;
    }

    private boolean dispatchConfigured(String template, String player, String reason, String duration) {
        String cmd = template
                .replace("%player%", player)
                .replace("%reason%", reason == null ? "" : reason)
                .replace("%duration%", duration == null ? "" : duration);
        try {
            return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        } catch (Exception e) {
            plugin.getLogger().warning("Configured admin command '" + cmd + "' threw an error: " + e);
            return false;
        }
    }

    private static AdminResult viaCommand(boolean ok) {
        return new AdminResult(ok ? Outcome.DONE : Outcome.FAILED, true, null);
    }

    public CompletableFuture<AdminResult> kick(UUID uuid, String name, String reason) {
        return onMain(() -> {
            String custom = plugin.cfg().kickCommand();
            if (!custom.isBlank()) return viaCommand(dispatchConfigured(custom, name, reason, null));
            Player p = Bukkit.getPlayer(uuid);
            if (p == null || !p.isOnline()) return AdminResult.of(Outcome.NOT_ONLINE);
            p.kick(Component.text(reason));
            return AdminResult.of(Outcome.DONE);
        });
    }

    public CompletableFuture<AdminResult> ban(UUID uuid, String name, String reason, String durationInput) {
        long parsed = parseDuration(durationInput);
        if (parsed == INVALID) return CompletableFuture.completedFuture(AdminResult.of(Outcome.INVALID_DURATION));
        boolean permanent = parsed == PERMANENT;
        return onMain(() -> {
            String custom = permanent ? plugin.cfg().banCommand() : plugin.cfg().tempbanCommand();
            if (!custom.isBlank()) return viaCommand(dispatchConfigured(custom, name, reason, durationInput));

            Date expiry = permanent ? null : new Date(System.currentTimeMillis() + parsed);
            Bukkit.getBanList(BanList.Type.NAME).addBan(name, reason, expiry, null);
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) p.kick(Component.text("Banned: " + reason));
            return new AdminResult(Outcome.DONE, false, expiry == null ? null : Instant.ofEpochMilli(expiry.getTime()));
        });
    }

    public CompletableFuture<AdminResult> warn(UUID uuid, String name, String reason, String durationInput) {
        long parsed = parseDuration(durationInput);
        if (parsed == INVALID) return CompletableFuture.completedFuture(AdminResult.of(Outcome.INVALID_DURATION));
        boolean permanent = parsed == PERMANENT;
        return onMain(() -> {
            String custom = permanent ? plugin.cfg().warnCommand() : plugin.cfg().tempwarnCommand();
            if (!custom.isBlank()) return viaCommand(dispatchConfigured(custom, name, reason, durationInput));

            // The built-in warning has no storage and no expiry of its own: the duration is only shown to the
            // player. Configure tempwarn-command for real enforcement through a punishment plugin.
            Player p = Bukkit.getPlayer(uuid);
            if (p == null || !p.isOnline()) return AdminResult.of(Outcome.NOT_ONLINE);
            String note = permanent ? "" : " (expires in " + durationInput + ")";
            p.sendMessage(Component.text("Warning: " + reason + note));
            return AdminResult.of(Outcome.DONE);
        });
    }

    public CompletableFuture<AdminResult> unban(String name) {
        return onMain(() -> {
            String custom = plugin.cfg().unbanCommand();
            if (!custom.isBlank()) return viaCommand(dispatchConfigured(custom, name, null, null));
            Bukkit.getBanList(BanList.Type.NAME).pardon(name);
            return AdminResult.of(Outcome.DONE);
        });
    }

    /** Clears the premium flag in the database and runs FastLogin's /unpremium when it is installed. */
    public CompletableFuture<AdminResult> clearPremium(UUID uuid, String name) {
        CompletableFuture<AdminResult> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            plugin.database().setPremium(uuid, false);
            if (plugin.fastLoginHook().isFastLoginPresent()) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "unpremium " + name);
                    } catch (Exception e) {
                        plugin.getLogger().warning("Could not run FastLogin's '/unpremium " + name + "': " + e);
                    }
                });
            }
            future.complete(AdminResult.of(Outcome.DONE));
        });
        return future;
    }

    public boolean fastLoginPresent() {
        return plugin.fastLoginHook().isFastLoginPresent();
    }

    // ------------------------------------------------------------------ who is an admin

    /**
     * Checks the {@code tgauth.admin} permission of any of the accounts: online players directly, offline ones
     * through LuckPerms or operator status. Blocks (up to 5 s per step) - never call it from the main thread.
     *
     * @return null if one of them is an admin, otherwise a short explanation why not (for the console)
     */
    public String adminPermissionReason(List<UUID> uuids, String names) {
        java.util.Map<UUID, Boolean> online;
        try {
            online = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                java.util.Map<UUID, Boolean> result = new java.util.HashMap<>();
                for (UUID u : uuids) {
                    Player p = Bukkit.getPlayer(u);
                    if (p != null) result.put(u, p.hasPermission("tgauth.admin"));
                }
                return result;
            }).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "could not check the permissions of " + names + ": " + e;
        }
        if (online.containsValue(true)) return null;

        List<UUID> offline = uuids.stream().filter(u -> !online.containsKey(u)).toList();
        if (offline.isEmpty()) {
            return names + " is online but lacks the tgauth.admin permission";
        }

        if (plugin.luckPermsHook().isPresent()) {
            for (UUID u : offline) {
                if (plugin.luckPermsHook().hasPermission(u, "tgauth.admin")) return null;
            }
            return names + " (offline) has no tgauth.admin permission in LuckPerms";
        }

        try {
            boolean anyOp = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                for (UUID u : offline) {
                    if (Bukkit.getOfflinePlayer(u).isOp()) return true;
                }
                return false;
            }).get(5, TimeUnit.SECONDS);
            return anyOp ? null : names + " is offline and not an operator";
        } catch (Exception e) {
            return "could not check operator status of " + names + ": " + e;
        }
    }
}
