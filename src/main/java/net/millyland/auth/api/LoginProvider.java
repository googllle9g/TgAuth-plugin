package net.millyland.auth.api;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * An additional way to log in next to Telegram (Discord, a website, ...). Register it with
 * {@link TgAuthAPI#registerLoginProvider}. TgAuth does everything that is the same for every method: it keeps
 * the links in its database, offers the choice to new players, checks the licence (FastLogin) and the trusted
 * IP, applies the admin PIN rules and finally logs the player in. A provider only supplies the part that is
 * specific to it: proving that a player owns an external account, and asking that account's owner to confirm
 * a login.
 *
 * <p>All methods are called on the main server thread.
 */
public interface LoginProvider {

    /** @return a unique id, {@code [a-z0-9_]{1,24}}; it is stored with every link, so never change it */
    @NotNull String getId();

    /** @return the plain name used in messages, e.g. {@code "Discord"} */
    @NotNull String getDisplayName();

    /** @return the label of this method in the choice menu, e.g. a coloured {@code [Discord]} */
    @NotNull Component getChoiceLabel();

    /** @return the tooltip of the choice label */
    @NotNull Component getChoiceHover();

    /**
     * @return false while the provider cannot work (for example the bot is offline). It is then not offered to
     *         new players and players who linked it get a message instead of a confirmation request.
     */
    boolean isAvailable();

    /**
     * A new player (no Telegram link, no link at all) chose this provider. Show the instructions through the
     * session and call {@link LinkSession#complete} when the external account proved ownership.
     */
    void beginLink(@NotNull Player player, @NotNull LinkSession session);

    /**
     * A player whose account is linked to this provider joined. TgAuth already decided that a confirmation is
     * needed (the player is not a verified licence holder and comes from an IP that is not trusted). Ask the
     * linked external account and answer through the request.
     */
    void requestConfirmation(@NotNull Player player, @NotNull ExternalLinkInfo link,
                             @NotNull ConfirmationRequest request);

    /**
     * The pending link or confirmation of this player ended without an answer from the provider (the player
     * left, was kicked, logged in as a licensed player, or switched to another method). Clean up here.
     */
    default void onSessionEnded(@NotNull UUID playerUuid) {
    }
}
