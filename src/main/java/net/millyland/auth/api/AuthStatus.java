package net.millyland.auth.api;

/** The login state of an online player. */
public enum AuthStatus {
    /** A new player who has not linked a Telegram account yet and must enter the link code. */
    AWAITING_LINK,
    /** A linked player who must confirm the login in Telegram. */
    AWAITING_CONFIRM,
    /** An admin who passed Telegram login but is still frozen until they set the admin panel PIN. */
    AWAITING_ADMIN_PIN,
    /** The player is fully logged in. */
    AUTHENTICATED
}
