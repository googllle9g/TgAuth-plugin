package net.millyland.auth.command;

import net.millyland.auth.TgAuthPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * {@code /add-auth-method}: a logged-in player links one more login method. It is registered at runtime only
 * while at least one login provider (addon) is registered, see {@link TgAuthPlugin#refreshAddMethodCommand()}.
 */
public final class AddAuthMethodCommand extends Command {

    public static final String NAME = "add-auth-method";

    private final TgAuthPlugin plugin;

    public AddAuthMethodCommand(TgAuthPlugin plugin) {
        super(NAME, "Link one more login method (Telegram or an addon provider)", "/" + NAME, List.of());
        this.plugin = plugin;
        setPermission("tgauth.addmethod");
    }

    @Override
    public boolean execute(@NotNull CommandSender sender, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.lang().pget("commands.player-only"));
            return true;
        }
        if (!testPermissionSilent(sender)) {
            sender.sendMessage(plugin.lang().pget("commands.no-permission"));
            return true;
        }
        plugin.authManager().beginAddMethod(player);
        return true;
    }

    @Override
    public @NotNull List<String> tabComplete(@NotNull CommandSender sender, @NotNull String alias,
                                             @NotNull String[] args) {
        return List.of();
    }
}
