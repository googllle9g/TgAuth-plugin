package net.millyland.auth.api.event;

import net.millyland.auth.api.AuthMethod;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Fired on the main thread when a player has passed a login step and is about to be authenticated,
 * before any state changes. Cancelling it denies the login and kicks the player.
 *
 * <p>For admins the admin PIN step may still follow after this event; use
 * {@link PlayerAuthenticatedEvent} to know when the player is really free to move.
 */
public class PlayerAuthenticateEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final AuthMethod method;
    private boolean cancelled;
    private String kickMessage;

    public PlayerAuthenticateEvent(@NotNull Player player, @NotNull AuthMethod method) {
        super(player);
        this.method = method;
    }

    public @NotNull AuthMethod getMethod() {
        return method;
    }

    /** @return the kick message in legacy {@code &} or {@code §} colors, or null to use TgAuth's default */
    public @Nullable String getKickMessage() {
        return kickMessage;
    }

    public void setKickMessage(@Nullable String kickMessage) {
        this.kickMessage = kickMessage;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
