package net.millyland.auth.api;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;

/**
 * Everything a {@link TelegramCommandHandler} needs to know about one command invocation.
 * Only messages sent in a private chat with the bot are dispatched to addons.
 */
public interface TelegramCommandContext {

    /** The command name in lower case, without the slash and without any {@code @botname} suffix. */
    @NotNull String command();

    /** The whitespace-separated arguments after the command. Never null, may be empty. */
    @NotNull List<String> args();

    /** Everything after the command, unsplit and trimmed. Empty string if there is none. */
    @NotNull String rawArgs();

    /** The chat to reply to. */
    long chatId();

    /** Telegram user id of the sender. */
    long telegramId();

    /** Telegram @username of the sender without "@", if they have one. */
    @NotNull Optional<String> telegramUsername();

    /** The first (oldest) Minecraft account linked to the sender, if any. */
    @NotNull Optional<LinkedAccountInfo> linkedAccount();

    /** All Minecraft accounts linked to the sender, oldest first (may hold several, see auth.max-accounts-per-telegram). */
    @NotNull List<LinkedAccountInfo> linkedAccounts();

    /**
     * Whether the sender is a TgAuth admin (admin-ids, a linked account with {@code tgauth.admin},
     * LuckPerms or OP). Being admin does NOT mean they passed the admin PIN prompt; addons that do
     * sensitive things should check this and their own rules.
     */
    boolean isAdmin();

    /** Sends a plain text reply to the sender. Safe to call from the handler thread. */
    void reply(@NotNull String text);
}
