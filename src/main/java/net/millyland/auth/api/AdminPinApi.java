package net.millyland.auth.api;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;

/**
 * The PIN of the admin panel, for addons with an admin panel of their own (see {@link AdminApi#pin()}). It is the
 * same protection the Telegram {@code /admin} panel has: the PIN is stored only as a salted hash, wrong guesses
 * are counted, entry is locked with growing delays after too many of them, the account owner and the other admins
 * are alerted, and an unlocked panel stays unlocked for {@code admin-pin.session-minutes} of inactivity. The
 * settings are TgAuth's {@code admin-pin} section.
 *
 * <p>A PIN belongs to a <em>subject</em> inside the addon's own {@code namespace}, normally the addon's provider
 * id and the external account id (for Discord: {@code "discord"} and the user id). It is independent of the
 * Telegram PIN. {@code /tgauth pinreset <player>} removes the PIN of every external account of that player, so the
 * admin is asked to set a new one. A namespace is {@code [a-z0-9_]{1,24}} and not {@code "telegram"}; an invalid
 * one throws {@link IllegalArgumentException}.
 *
 * <p>Methods returning a {@link CompletableFuture} may be called from any thread; the others are quick and
 * thread-safe.
 */
public interface AdminPinApi {

    enum Status {
        /** The PIN is right; the panel is now unlocked. */
        OK,
        /** Wrong PIN. */
        WRONG,
        /** Too many wrong attempts: PIN entry is locked, see {@link Check#lockSeconds()}. */
        LOCKED,
        /** No PIN is set for this subject yet. */
        NO_PIN
    }

    /**
     * @param status       the outcome
     * @param attemptsLeft with {@code WRONG}: how many guesses remain before the lockout
     * @param lockSeconds  with {@code LOCKED}: how long entry stays locked
     */
    record Check(@NotNull Status status, int attemptsLeft, long lockSeconds) {
    }

    /** @return false if the server owner turned the PIN off ({@code admin-pin.enabled}); the addon should then skip it */
    boolean isEnabled();

    /** Shortest allowed PIN (digits only). */
    int minLength();

    /** Longest allowed PIN (digits only). */
    int maxLength();

    /** @return true if the text is a valid PIN: only digits, between {@link #minLength()} and {@link #maxLength()} */
    boolean isValidFormat(@NotNull String pin);

    /** Whether a PIN is already set for the subject. */
    @NotNull CompletableFuture<Boolean> hasPin(@NotNull String namespace, @NotNull String subject);

    /**
     * Sets the first PIN of a subject (and unlocks the panel). It never replaces an existing PIN: completes with
     * false if one exists (a server admin removes it with {@code /tgauth pinreset}) or if the format is invalid.
     * Asking the person to type the PIN twice is the addon's job.
     */
    @NotNull CompletableFuture<Boolean> setPin(@NotNull String namespace, @NotNull String subject, @NotNull String pin);

    /** Checks a PIN. With {@code OK} the panel of this subject is unlocked. Counts wrong guesses and locks out. */
    @NotNull CompletableFuture<Check> verify(@NotNull String namespace, @NotNull String subject, @NotNull String pin);

    /**
     * @return true if this subject unlocked the panel recently enough; a true answer extends the session
     *         (sliding idle timeout)
     */
    boolean isUnlocked(@NotNull String namespace, @NotNull String subject);

    /** Locks the panel of this subject again. */
    void lock(@NotNull String namespace, @NotNull String subject);

    /** "1h 20m", "45s": a duration in the language of TgAuth's messages. */
    @NotNull String formatDuration(long seconds);
}
