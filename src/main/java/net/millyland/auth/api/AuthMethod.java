package net.millyland.auth.api;

/** How a player got through TgAuth login. */
public enum AuthMethod {
    /** First-time login: the player entered the one-time code in the Telegram bot. */
    TELEGRAM_LINK,
    /** The player pressed "Yes, it's me" on the Telegram confirmation message. */
    TELEGRAM_CONFIRM,
    /** The player came from a recently confirmed IP address, so the confirmation was skipped. */
    TRUSTED_IP,
    /** The player is a verified premium account (FastLogin) and confirmation is skipped for them. */
    PREMIUM,
    /** The player has the {@code tgauth.bypass} permission. */
    BYPASS,
    /** First-time login through a {@link LoginProvider}: the player proved ownership of an external account. */
    EXTERNAL_LINK,
    /** The linked external account (a {@link LoginProvider}) confirmed the login. */
    EXTERNAL_CONFIRM
}
