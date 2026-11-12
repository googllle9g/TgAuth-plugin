package net.millyland.auth.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * The actions of TgAuth's admin panel, for addons that offer an admin panel of their own (DiscordTgAuth-addon does).
 * They behave exactly like the buttons of the Telegram {@code /admin} panel: the {@code admin-commands} section of
 * TgAuth's config decides whether a punishment plugin's console command or the built-in behaviour is used.
 *
 * <p>Obtain it with {@link TgAuthAPI#admin()}. Every method may be called from any thread and completes
 * asynchronously. <b>TgAuth does not check who calls</b>: the addon must make sure the person is allowed to do
 * this (see {@link #isAdmin(UUID)}). The {@code actor} text names the person in the console log and in the
 * notification the other Telegram admins get (for example {@code "Discord: Steve"}).
 */
public interface AdminApi {

    Pattern DURATION = Pattern.compile("(?i)p|perm|permanent|\\d+[smhdw]");

    /**
     * Checks a duration text: {@code p}/{@code perm}/{@code permanent} or a number with a unit s, m, h, d or w
     * (for example {@code 30m}, {@code 7d}).
     */
    static boolean isValidDuration(@Nullable String text) {
        if (text == null) return false;
        String s = text.trim();
        return DURATION.matcher(s).matches() && !s.matches("(?i)0+[smhdw]");
    }

    /** The PIN protection of the admin panel, so an addon panel can ask for it before opening. */
    @NotNull AdminPinApi pin();

    /**
     * @return true if the Minecraft account has the {@code tgauth.admin} permission (checked online, through
     *         LuckPerms when offline, or by operator status)
     */
    @NotNull CompletableFuture<Boolean> isAdmin(@NotNull UUID minecraftUuid);

    /** Looks up a player by their Minecraft name among the linked accounts. */
    @NotNull CompletableFuture<Optional<AdminPlayerInfo>> findPlayer(@NotNull String name);

    /** Looks up a linked player by UUID. */
    @NotNull CompletableFuture<Optional<AdminPlayerInfo>> getPlayer(@NotNull UUID minecraftUuid);

    /** Kicks an online player. Completes with {@code NOT_ONLINE} if they are not online. */
    @NotNull CompletableFuture<AdminResult> kick(@NotNull UUID minecraftUuid, @NotNull String reason,
                                                 @NotNull String actor);

    /**
     * Bans a player.
     *
     * @param duration see {@link #isValidDuration(String)}; {@code null} means permanent
     */
    @NotNull CompletableFuture<AdminResult> ban(@NotNull UUID minecraftUuid, @NotNull String reason,
                                                @Nullable String duration, @NotNull String actor);

    /**
     * Warns a player. Without a punishment plugin configured the built-in warning is only a chat message to an
     * online player (the duration is just shown in it).
     *
     * @param duration see {@link #isValidDuration(String)}; {@code null} means it does not expire
     */
    @NotNull CompletableFuture<AdminResult> warn(@NotNull UUID minecraftUuid, @NotNull String reason,
                                                 @Nullable String duration, @NotNull String actor);

    /** Lifts a ban. */
    @NotNull CompletableFuture<AdminResult> unban(@NotNull UUID minecraftUuid, @NotNull String actor);

    /** Clears the premium flag of the player's links (and runs FastLogin's {@code /unpremium} if installed). */
    @NotNull CompletableFuture<AdminResult> clearPremium(@NotNull UUID minecraftUuid, @NotNull String actor);

    /** Removes the Telegram link of the player. */
    @NotNull CompletableFuture<AdminResult> unlinkTelegram(@NotNull UUID minecraftUuid, @NotNull String actor);

    /** Removes the link of one provider (for example {@code "discord"}). */
    @NotNull CompletableFuture<AdminResult> unlinkProvider(@NotNull UUID minecraftUuid, @NotNull String providerId,
                                                           @NotNull String actor);
}
