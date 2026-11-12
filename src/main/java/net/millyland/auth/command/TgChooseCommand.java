package net.millyland.auth.command;

import net.millyland.auth.TgAuthPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** {@code /tgchoose <method>}: what the clickable choice in chat runs. */
public class TgChooseCommand implements CommandExecutor {

    private final TgAuthPlugin plugin;

    public TgChooseCommand(TgAuthPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.lang().pget("commands.player-only"));
            return true;
        }
        plugin.authManager().choose(player, args.length > 0 ? args[0] : "");
        return true;
    }
}
