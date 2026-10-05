package net.millyland.auth.api;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Immutable snapshot of a Minecraft account linked to a Telegram account.
 *
 * @param uuid             the Minecraft UUID
 * @param minecraftName    the Minecraft name stored at link time
 * @param telegramId       the numeric Telegram user id
 * @param telegramUsername the Telegram @username without the "@", or {@code null} if the user has none
 * @param linkedAt         link time in epoch milliseconds
 * @param premium          whether the account was verified as a premium (licensed) account
 */
public record LinkedAccountInfo(UUID uuid, String minecraftName, long telegramId,
                                @Nullable String telegramUsername, long linkedAt, boolean premium) {
}
