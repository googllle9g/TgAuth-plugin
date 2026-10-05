package net.millyland.auth.command;

import net.millyland.auth.TgAuthPlugin;
import net.millyland.auth.storage.Database;
import net.millyland.auth.storage.LinkedAccount;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class TgAuthCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of(
            "reload", "unlink", "forcelink", "userinfo", "fastlogin", "pinreset", "update", "migrate");

    private final TgAuthPlugin plugin;

    public TgAuthCommand(TgAuthPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("tgauth.admin")) {
            sender.sendMessage(plugin.lang().pget("commands.no-permission"));
            return true;
        }

        // A player who hasn't passed TgAuth login yet must not be able to use admin commands
        // (e.g. an operator whose account is being impersonated).
        if (sender instanceof Player player && !plugin.authManager().isAuthenticated(player.getUniqueId())) {
            sender.sendMessage(plugin.lang().pget("commands.no-permission"));
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(plugin.lang().pget("commands.usage"));
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload" -> {
                plugin.cfg().load();
                plugin.lang().load();
                sender.sendMessage(plugin.lang().pget("commands.reload-success"));
            }
            case "unlink" -> {
                if (args.length < 2) {
                    sender.sendMessage(plugin.lang().pget("commands.usage"));
                    return true;
                }

                Optional<LinkedAccount> byName = plugin.database().findByUsername(args[1]);
                boolean removed = byName.isPresent() && plugin.database().unlink(byName.get().uuid());
                if (removed) plugin.api().fireUnlinked(byName.get());
                sender.sendMessage(removed
                        ? plugin.lang().pget("commands.unlink-success", "%player%", args[1])
                        : plugin.lang().pget("commands.unlink-not-linked"));
            }
            case "forcelink" -> {
                if (args.length < 3) {
                    sender.sendMessage(plugin.lang().pget("commands.usage"));
                    return true;
                }
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
                long telegramId;
                try {
                    telegramId = Long.parseLong(args[2]);
                } catch (NumberFormatException ex) {
                    sender.sendMessage(plugin.lang().pget("commands.usage"));
                    return true;
                }
                boolean linked = plugin.database().link(target.getUniqueId(), telegramId, args[1], null);
                if (!linked) {
                    sender.sendMessage(plugin.lang().pget("commands.forcelink-failed", "%player%", args[1]));
                    return true;
                }
                plugin.api().fireLinked(new LinkedAccount(target.getUniqueId(), telegramId, args[1],
                        System.currentTimeMillis(), null, false));
                sender.sendMessage(plugin.lang().pget("commands.forcelink-success",
                        "%player%", args[1], "%telegram%", String.valueOf(telegramId)));
            }
            case "userinfo" -> {
                if (args.length < 2) {
                    sender.sendMessage(plugin.lang().pget("commands.usage"));
                    return true;
                }

                Optional<LinkedAccount> acc = plugin.database().findByUsername(args[1]);
                if (acc.isEmpty()) {
                    sender.sendMessage(plugin.lang().pget("commands.userinfo-not-linked", "%player%", args[1]));
                    return true;
                }
                LinkedAccount a = acc.get();
                String tgUsername = (a.telegramUsername() == null || a.telegramUsername().isBlank())
                        ? plugin.lang().get("commands.userinfo-username-unset")
                        : "@" + a.telegramUsername();
                String premiumText = a.premium()
                        ? plugin.lang().get("commands.userinfo-premium-yes")
                        : plugin.lang().get("commands.userinfo-premium-no");

                sender.sendMessage(plugin.lang().pget("commands.userinfo-header", "%player%", args[1]));
                sender.sendMessage(plugin.lang().pget("commands.userinfo-telegram-id",
                        "%telegram%", String.valueOf(a.telegramId())));
                sender.sendMessage(plugin.lang().pget("commands.userinfo-telegram-username", "%tguser%", tgUsername));
                sender.sendMessage(plugin.lang().pget("commands.userinfo-premium", "%premium%", premiumText));
            }
            case "fastlogin" -> {
                var hook = plugin.fastLoginHook();
                sender.sendMessage("§7[TgAuth] FastLogin installed: " + (hook.isFastLoginPresent() ? "§ayes" : "§cno"));
                sender.sendMessage("§7[TgAuth] Hook registered: " + (hook.isHookRegistered()
                        ? "§ayes" : "§cno (check the console log at startup for the reason)"));
                if (hook.isFastLoginPresent()) {
                    sender.sendMessage("§7[TgAuth] FastLogin's autoRegister: " + (hook.isFastLoginAutoRegisterEnabled()
                            ? "§ayes"
                            : "§cno - new (never-registered) players won't be premium-checked automatically."));
                    sender.sendMessage("§7[TgAuth] FastLogin's secondAttemptCracked: " + (hook.isFastLoginSecondAttemptCrackedEnabled()
                            ? "§ayes"
                            : "§cno - cracked players may get stuck in a disconnect loop if autoRegister is on."));
                    sender.sendMessage("§7[TgAuth] FastLogin's premiumUuid: " + (hook.isFastLoginPremiumUuidEnabled()
                            ? "§ayes"
                            : "§cno - UUIDs never change on premium verification, so migrate-link-by-username "
                              + "has no effect."));
                }
                sender.sendMessage("§7[TgAuth] Premium-verified players this run: §f" + plugin.authManager().fastLoginVerifiedCount());
                sender.sendMessage("§7[TgAuth] config: fastlogin.enabled=" + plugin.cfg().fastLoginEnabled()
                        + ", premium-skip-confirmation=" + plugin.cfg().premiumSkipConfirmation());
            }
            case "migrate" -> {
                Database db = plugin.database();
                if (db.type() == Database.Type.SQLITE) {
                    sender.sendMessage(plugin.lang().pget("commands.migrate-not-needed"));
                    return true;
                }
                File source = new File(plugin.getDataFolder(), plugin.cfg().storageFile());
                if (!source.isFile()) {
                    sender.sendMessage(plugin.lang().pget("commands.migrate-no-file", "%file%", source.getName()));
                    return true;
                }

                sender.sendMessage(plugin.lang().pget("commands.migrate-start",
                        "%file%", source.getName(), "%type%", db.type().displayName()));
                // Network I/O: never on the server thread.
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    String result;
                    try {
                        Database.ImportResult r = db.importFromSqlite(source);
                        plugin.getLogger().info("Imported " + r.accounts() + " account(s) and " + r.pins()
                                + " PIN(s) from " + source.getName() + " (" + (r.accountsSkipped() + r.pinsSkipped())
                                + " already present).");
                        result = plugin.lang().pget("commands.migrate-done",
                                "%accounts%", String.valueOf(r.accounts()),
                                "%pins%", String.valueOf(r.pins()),
                                "%skipped%", String.valueOf(r.accountsSkipped() + r.pinsSkipped()));
                    } catch (Exception e) {
                        plugin.getLogger().warning("SQLite import failed: " + e);
                        result = plugin.lang().pget("commands.migrate-failed", "%error%", String.valueOf(e.getMessage()));
                    }
                    String message = result;
                    Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(message));
                });
            }
            case "update" -> {
                sender.sendMessage(plugin.lang().pget("update.checking"));
                plugin.updateChecker().check(result -> {
                    switch (result.outcome()) {
                        case UPDATE_AVAILABLE -> plugin.updateChecker().sendUpdateMessage(sender, result.release());
                        case UP_TO_DATE -> sender.sendMessage(plugin.lang().pget("update.up-to-date",
                                "%current%", plugin.updateChecker().currentVersion()));
                        case NO_RELEASES -> sender.sendMessage(plugin.lang().pget("update.no-releases"));
                        case FAILED -> sender.sendMessage(plugin.lang().pget("update.check-failed"));
                    }
                });
            }
            case "pinreset" -> {
                if (args.length < 2) {
                    sender.sendMessage(plugin.lang().pget("commands.usage"));
                    return true;
                }

                Long telegramId = null;
                Optional<LinkedAccount> linked = plugin.database().findByUsername(args[1]);
                if (linked.isPresent()) {
                    telegramId = linked.get().telegramId();
                } else {
                    try {
                        telegramId = Long.parseLong(args[1]);
                    } catch (NumberFormatException ignored) {
                    }
                }
                if (telegramId == null) {
                    sender.sendMessage(plugin.lang().pget("commands.userinfo-not-linked", "%player%", args[1]));
                    return true;
                }

                boolean existed = plugin.adminPin().resetPin(telegramId);
                if (!existed) {
                    sender.sendMessage(plugin.lang().pget("commands.pinreset-nothing", "%player%", args[1]));
                    return true;
                }
                sender.sendMessage(plugin.lang().pget("commands.pinreset-success", "%player%", args[1]));
                plugin.getLogger().info("Admin panel PIN of " + args[1] + " (Telegram ID " + telegramId
                        + ") was reset by " + sender.getName() + ".");
                plugin.adminPin().broadcastToAdmins(plugin.lang().rawGet("pin.reset-notify-telegram",
                        "%player%", args[1], "%by%", sender.getName()), null);
            }
            default -> sender.sendMessage(plugin.lang().pget("commands.usage"));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("tgauth.admin")) {
            return List.of();
        }

        if (args.length == 1) {
            String partial = args[0].toLowerCase();
            return SUBCOMMANDS.stream()
                    .filter(s -> s.startsWith(partial))
                    .collect(Collectors.toList());
        }

        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (sub.equals("unlink") || sub.equals("forcelink") || sub.equals("userinfo") || sub.equals("pinreset")) {
                String partial = args[1].toLowerCase();
                List<String> names = new ArrayList<>();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getName().toLowerCase().startsWith(partial)) {
                        names.add(p.getName());
                    }
                }
                return names;
            }
        }

        return List.of();
    }
}
