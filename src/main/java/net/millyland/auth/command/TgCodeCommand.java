package net.millyland.auth.command;

import net.millyland.auth.TgAuthPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class TgCodeCommand implements CommandExecutor {

    private final TgAuthPlugin plugin;

    public TgCodeCommand(TgAuthPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.lang().pget("commands.player-only"));
            return true;
        }

        var code = plugin.authManager().currentLinkCode(player.getUniqueId());
        if (code.isEmpty()) {
            player.sendMessage(plugin.lang().pget("commands.tgcode-not-needed"));
        } else {
            player.sendMessage(net.millyland.auth.ui.CopyCode.inText(plugin,
                    plugin.lang().pget("commands.tgcode-sent", "%code%", code.get()), code.get(), "/link " + code.get()));
        }
        return true;
    }
}
