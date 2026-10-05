package net.millyland.auth.api;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Public API of TgAuth. Obtain an instance with {@link TgAuth#get()} or from Bukkit's
 * {@code ServicesManager}. Everything outside the {@code net.millyland.auth.api} package is
 * internal and may change without notice.
 *
 * <p>Threading: methods returning plain values must be called from the main server thread unless
 * documented otherwise; methods returning a {@link CompletableFuture} may be called from any
 * thread and complete on an asynchronous thread.
 */
public interface TgAuthAPI {

    /** Bumped on breaking API changes. The current version is 1. */
    int API_VERSION = 1;

    /** @return the API version of the installed TgAuth */
    int getApiVersion();

    /** @return the installed TgAuth plugin version */
    @NotNull String getPluginVersion();

    /** @return the login state of the player, or {@link AuthStatus#AUTHENTICATED} if TgAuth does not track them */
    @NotNull AuthStatus getStatus(@NotNull Player player);

    /** @return true if the player is fully logged in (main thread) */
    boolean isAuthenticated(@NotNull Player player);

    /** @return how the player logged in; empty while they are not authenticated yet */
    @NotNull Optional<AuthMethod> getAuthMethod(@NotNull Player player);

    /** Looks up the Telegram link of a Minecraft account. Completes with empty if not linked. */
    @NotNull CompletableFuture<Optional<LinkedAccountInfo>> getLinkedAccount(@NotNull UUID minecraftUuid);

    /** Looks up the (first) Minecraft account linked to a Telegram user id. Completes with empty if none. */
    @NotNull CompletableFuture<Optional<LinkedAccountInfo>> getLinkedAccountByTelegramId(long telegramId);

    /**
     * Looks up every Minecraft account linked to a Telegram user id (the server owner can allow several via
     * {@code auth.max-accounts-per-telegram}), oldest link first. Completes with an empty list if none.
     */
    @NotNull CompletableFuture<List<LinkedAccountInfo>> getLinkedAccountsByTelegramId(long telegramId);

    /**
     * Logs a player in on behalf of another plugin (for example a custom SSO or 2FA addon).
     * Fires {@link net.millyland.auth.api.event.PlayerAuthenticateEvent} with {@link AuthMethod#EXTERNAL},
     * and the admin PIN rules still apply to admins.
     *
     * <p>Disabled unless the server owner sets {@code api.allow-external-authentication: true} in
     * TgAuth's config.yml, because it lets plugins skip the Telegram login.
     *
     * @return true if the player was authenticated or moved on to the admin PIN step; false if the
     *         option is disabled, the player is already authenticated or the player is offline
     */
    boolean authenticate(@NotNull Plugin addon, @NotNull Player player);

    /**
     * Registers a bot command (for example {@code "balance"} for {@code /balance}). Built-in
     * commands ({@code start, admin, link}) cannot be overridden. The command is removed
     * automatically when the registering plugin is disabled.
     *
     * @param name the command name, letters/digits/underscore, 1-32 chars, with or without the leading slash
     * @return true if registered; false if the name is invalid, reserved or already taken by another plugin
     */
    boolean registerTelegramCommand(@NotNull Plugin addon, @NotNull String name,
                                    @NotNull TelegramCommandHandler handler);

    /** Removes a command registered by this plugin. @return true if something was removed */
    boolean unregisterTelegramCommand(@NotNull Plugin addon, @NotNull String name);

    /** @return true if the Telegram bot is running (a token is configured and it started) */
    boolean isTelegramAvailable();

    /** Sends a plain text message to a Telegram chat. Any thread; no-op if the bot is not running. */
    void sendTelegramMessage(long chatId, @NotNull String text);

    /**
     * Sends a plain text message to the Telegram account linked to the Minecraft account.
     * Completes with false if the account is not linked or the bot is not running.
     */
    @NotNull CompletableFuture<Boolean> sendTelegramMessage(@NotNull UUID minecraftUuid, @NotNull String text);
}
