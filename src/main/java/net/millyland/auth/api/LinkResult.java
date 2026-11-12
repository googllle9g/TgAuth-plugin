package net.millyland.auth.api;

/** Outcome of {@link LinkSession#complete}. */
public enum LinkResult {
    /** The link was saved in TgAuth's database and the player is being logged in. */
    LINKED,
    /** The external account already has the maximum number of linked game accounts. */
    LIMIT_REACHED,
    /** The game account already has a link (Telegram or another provider). */
    ALREADY_LINKED,
    /** The player left, or is not waiting to link any more. */
    PLAYER_GONE,
    /** The database failed; see the server log. */
    ERROR
}
