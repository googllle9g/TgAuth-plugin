package net.millyland.auth.api;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

/**
 * Handed to {@link LoginProvider#beginLink} when a new player chose that provider. The provider shows the
 * player what to do with {@link #showInstructions} and, once the external account proved that it belongs
 * to the player (for example the right code arrived), calls {@link #complete}. TgAuth then stores the link
 * in its database and logs the player in; the provider never logs anybody in by itself.
 * All methods may be called from any thread.
 */
public interface LinkSession {

    @NotNull Player getPlayer();

    /** Shows (or re-shows) the instructions to the player, in chat or in the dialog window. */
    void showInstructions(@NotNull LinkInstructions instructions);

    /**
     * The external account proved ownership.
     *
     * @param externalId   id of the account on the provider's side
     * @param externalName display name on the provider's side, may be {@code null}
     * @param maxAccounts  how many game accounts one external account may link (at least 1)
     */
    @NotNull CompletableFuture<LinkResult> complete(@NotNull String externalId, @Nullable String externalName,
                                                    int maxAccounts);

    /** @return true while the player still waits to link through this session */
    boolean isActive();
}
