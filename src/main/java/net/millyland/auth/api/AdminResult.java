package net.millyland.auth.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;

/**
 * The outcome of an admin action ({@link AdminApi}).
 *
 * @param outcome    what happened
 * @param viaCommand true if the action was run through a console command from TgAuth's {@code admin-commands}
 *                   config (a punishment plugin) instead of the built-in behaviour
 * @param expiresAt  when a built-in temporary ban ends; {@code null} for permanent bans and everything else
 */
public record AdminResult(@NotNull Outcome outcome, boolean viaCommand, @Nullable Instant expiresAt) {

    public enum Outcome {
        /** The action was carried out. */
        DONE,
        /** The player has to be online for this action (kick, warn) and is not. */
        NOT_ONLINE,
        /** The account or player is unknown to TgAuth. */
        NOT_FOUND,
        /** The duration text is not valid, see {@link AdminApi#isValidDuration(String)}. */
        INVALID_DURATION,
        /** Something went wrong (a configured console command failed, a database error); see the server log. */
        FAILED
    }

    public boolean isDone() {
        return outcome == Outcome.DONE;
    }

    public static AdminResult of(@NotNull Outcome outcome) {
        return new AdminResult(outcome, false, null);
    }
}
