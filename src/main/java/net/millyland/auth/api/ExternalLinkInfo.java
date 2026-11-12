package net.millyland.auth.api;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Immutable snapshot of a Minecraft account linked through a {@link LoginProvider} (Discord, a website, ...).
 * The link itself is stored in TgAuth's database.
 *
 * @param provider      the id of the {@link LoginProvider} that owns the link
 * @param uuid          the Minecraft UUID
 * @param minecraftName the Minecraft name stored at link time
 * @param externalId    the account id on the provider's side (for Discord the numeric user id as text)
 * @param externalName  a display name on the provider's side, or {@code null}
 * @param linkedAt      link time in epoch milliseconds
 * @param premium       whether the account was verified as a premium (licensed) account
 */
public record ExternalLinkInfo(String provider, UUID uuid, String minecraftName, String externalId,
                               @Nullable String externalName, long linkedAt, boolean premium) {
}
