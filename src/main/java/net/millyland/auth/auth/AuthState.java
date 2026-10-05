package net.millyland.auth.auth;

public enum AuthState {

    AWAITING_LINK,

    AWAITING_CONFIRM,

    /**
     * Passed Telegram login, but the player is an admin who has not set a PIN for the bot's admin
     * panel yet. Treated exactly like an unauthenticated player (frozen) until the PIN is set.
     */
    AWAITING_ADMIN_PIN,

    AUTHENTICATED
}
