package net.millyland.auth.api;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A pending "is this really you?" login confirmation of a player whose account is linked to a
 * {@link LoginProvider}. TgAuth already checked the licence (FastLogin) and the trusted IP; the provider only
 * has to ask its user and answer with {@link #approve()} or {@link #deny()}. Both methods can be called from
 * any thread and only the first answer counts.
 */
public interface ConfirmationRequest {

    @NotNull Player getPlayer();

    /** @return the IP address the player is connecting from, or {@code null} if unknown */
    @Nullable String getIp();

    /** @return how many seconds are left until TgAuth kicks the player for not confirming in time */
    int getSecondsLeft();

    /** The user confirmed. TgAuth logs the player in and remembers the IP as trusted. */
    void approve();

    /** The user rejected the login. TgAuth kicks the player. */
    void deny();
}
