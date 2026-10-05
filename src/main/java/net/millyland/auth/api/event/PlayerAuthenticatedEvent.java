package net.millyland.auth.api.event;

import net.millyland.auth.api.AuthMethod;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Fired on the main thread once a player is fully logged in and unfrozen (including players with
 * the {@code tgauth.bypass} permission). Use this to load player data, give items, teleport to the
 * spawn, and so on.
 */
public class PlayerAuthenticatedEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final AuthMethod method;

    public PlayerAuthenticatedEvent(@NotNull Player player, @NotNull AuthMethod method) {
        super(player);
        this.method = method;
    }

    public @NotNull AuthMethod getMethod() {
        return method;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
