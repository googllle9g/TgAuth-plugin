package net.millyland.auth.internal;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;

/** Removes Telegram commands of addons that get disabled. */
public class AddonCleanupListener implements Listener {

    private final TelegramCommandRegistry registry;

    public AddonCleanupListener(TelegramCommandRegistry registry) {
        this.registry = registry;
    }

    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        registry.unregisterAll(event.getPlugin());
    }
}
