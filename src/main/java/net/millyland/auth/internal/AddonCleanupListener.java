package net.millyland.auth.internal;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;

/** Removes Telegram commands of addons that get disabled. */
public class AddonCleanupListener implements Listener {

    private final TgAuthApiImpl api;

    public AddonCleanupListener(TgAuthApiImpl api) {
        this.api = api;
    }

    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        api.cleanup(event.getPlugin());
    }
}
