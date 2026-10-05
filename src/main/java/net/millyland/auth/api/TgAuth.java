package net.millyland.auth.api;

import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

/** Static entry point to the TgAuth API. */
public final class TgAuth {

    private TgAuth() {
    }

    /**
     * @return the API
     * @throws IllegalStateException if TgAuth is not enabled; declare {@code depend: [TgAuth]}
     *                               (or softdepend and check) in your plugin.yml
     */
    public static @NotNull TgAuthAPI get() {
        var registration = Bukkit.getServicesManager().getRegistration(TgAuthAPI.class);
        if (registration == null) {
            throw new IllegalStateException("TgAuth is not enabled");
        }
        return registration.getProvider();
    }
}
