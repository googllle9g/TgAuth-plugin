package net.millyland.auth.internal;

import net.millyland.auth.api.LinkedAccountInfo;
import net.millyland.auth.api.TelegramCommandContext;
import net.millyland.auth.telegram.TelegramService;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;

final class TelegramContextImpl implements TelegramCommandContext {

    private final CommandLine line;
    private final long chatId;
    private final long telegramId;
    private final String username;
    private final List<LinkedAccountInfo> accounts;
    private final boolean admin;
    private final TelegramService telegram;

    TelegramContextImpl(CommandLine line, long chatId, long telegramId, String username,
                        List<LinkedAccountInfo> accounts, boolean admin, TelegramService telegram) {
        this.line = line;
        this.chatId = chatId;
        this.telegramId = telegramId;
        this.username = username;
        this.accounts = accounts;
        this.admin = admin;
        this.telegram = telegram;
    }

    @Override public @NotNull String command() { return line.name(); }
    @Override public @NotNull List<String> args() { return line.args(); }
    @Override public @NotNull String rawArgs() { return line.rawArgs(); }
    @Override public long chatId() { return chatId; }
    @Override public long telegramId() { return telegramId; }
    @Override public @NotNull Optional<String> telegramUsername() { return Optional.ofNullable(username); }
    @Override public @NotNull Optional<LinkedAccountInfo> linkedAccount() { return accounts.isEmpty() ? Optional.empty() : Optional.of(accounts.get(0)); }
    @Override public @NotNull List<LinkedAccountInfo> linkedAccounts() { return accounts; }
    @Override public boolean isAdmin() { return admin; }

    @Override
    public void reply(@NotNull String text) {
        telegram.send(chatId, text);
    }
}
