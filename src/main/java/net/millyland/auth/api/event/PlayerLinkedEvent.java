package net.millyland.auth.api.event;

import net.millyland.auth.api.LinkedAccountInfo;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Fired after a Minecraft account has just been linked to a Telegram account (first login, /tgauth forcelink or an addon).
 *
 * <p>The player may be offline. This event can be fired <b>asynchronously</b> (check
 * {@link #isAsynchronous()}); do not touch non thread safe Bukkit API directly in the handler.
 */
public class PlayerLinkedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final LinkedAccountInfo account;

    public PlayerLinkedEvent(boolean async, @NotNull LinkedAccountInfo account) {
        super(async);
        this.account = account;
    }

    /** @return the account as it was at the moment of the change */
    public @NotNull LinkedAccountInfo getAccount() {
        return account;
    }

    public @NotNull UUID getUniqueId() {
        return account.uuid();
    }

    public long getTelegramId() {
        return account.telegramId();
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
