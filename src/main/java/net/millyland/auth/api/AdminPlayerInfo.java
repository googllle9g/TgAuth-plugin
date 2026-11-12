package net.millyland.auth.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * What TgAuth knows about a player for the admin tools.
 *
 * @param uuid      the Minecraft UUID
 * @param name      the Minecraft name stored at link time
 * @param online    whether the player is online right now
 * @param banned    whether the name is on the built-in (vanilla) ban list; bans made through a punishment plugin
 *                  are not visible here
 * @param telegram  the Telegram link, or {@code null}
 * @param external  the provider links (Discord, ...), possibly empty
 */
public record AdminPlayerInfo(@NotNull UUID uuid, @NotNull String name, boolean online, boolean banned,
                              @Nullable LinkedAccountInfo telegram, @NotNull List<ExternalLinkInfo> external) {

    /** @return true if any of the links is flagged as a premium (licensed) account */
    public boolean premium() {
        return (telegram != null && telegram.premium()) || external.stream().anyMatch(ExternalLinkInfo::premium);
    }
}
