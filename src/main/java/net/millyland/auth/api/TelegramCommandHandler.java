package net.millyland.auth.api;

import org.jetbrains.annotations.NotNull;

/**
 * Handles a Telegram bot command registered by an addon.
 *
 * <p>Handlers run on an <b>asynchronous</b> thread (never the Minecraft main thread and never the
 * Telegram polling thread), so blocking I/O is fine, but Bukkit API calls that are not thread safe
 * must be scheduled onto the main thread with the Bukkit scheduler. Exceptions thrown by a handler
 * are caught and logged and never affect TgAuth.
 */
@FunctionalInterface
public interface TelegramCommandHandler {
    void handle(@NotNull TelegramCommandContext context);
}
