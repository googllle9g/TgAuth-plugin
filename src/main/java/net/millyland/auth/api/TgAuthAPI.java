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

    /**
     * Bumped on breaking API changes. Version 3 replaced {@code authenticate()}, {@code PlayerLinkRequiredEvent}
     * and the other v2 helpers with {@link LoginProvider}: TgAuth now keeps the links, checks licence and trusted
     * IP and logs players in itself, so an addon can no longer log a player in on its own. Version 4 added
     * {@link #admin()}, version 5 added proxy support ({@link ProxyProvider}); both are additive: addons written for
     * version 3 or 4 keep working.
     */
    int API_VERSION = 5;

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

    // ---- API v3: login providers (Discord, web, ...) ---------------------------------------------

    /**
     * Adds a way to log in next to Telegram. New players are offered the choice (TgAuth shows it, in chat or in
     * the vanilla dialog), TgAuth stores the links in its database, checks licence and trusted IP and logs
     * players in. See {@link LoginProvider}. Removed automatically when the registering plugin is disabled.
     *
     * @return false if the id is invalid or already taken
     */
    boolean registerLoginProvider(@NotNull Plugin owner, @NotNull LoginProvider provider);

    /** Removes a provider registered by this plugin. Existing links stay in the database. */
    boolean unregisterLoginProvider(@NotNull Plugin owner, @NotNull String providerId);

    /**
     * Lets an addon supply the proxy TgAuth connects to Telegram through. A proxy set in TgAuth's own config
     * ({@code proxy.enabled}) wins over providers; otherwise the first registered provider that returns a proxy is
     * used. TgAuth reconnects the bot when the effective proxy changes. Removed automatically when the registering
     * plugin is disabled. See {@link ProxyProvider}. Any thread.
     *
     * @return false if the id is invalid or already taken
     */
    boolean registerProxyProvider(@NotNull Plugin owner, @NotNull ProxyProvider provider);

    /** Removes a provider registered by this plugin. @return true if something was removed */
    boolean unregisterProxyProvider(@NotNull Plugin owner, @NotNull String providerId);

    /**
     * Asks TgAuth to ask the providers again; if the effective proxy changed, the Telegram bot reconnects through
     * the new one. Call it after your {@link ProxyProvider#getProxy()} answer changed. Any thread.
     */
    void refreshProxy();

    /**
     * @return the proxy TgAuth currently uses, as {@code type://host:port} (never with credentials);
     * empty for a direct connection
     */
    @NotNull Optional<String> getActiveProxy();

    /**
     * The proxy TgAuth currently uses, <b>including its login</b>, for addons that open their own connections
     * (for example a Discord bot) and want to follow the server's proxy. Empty for a direct connection. Treat the
     * password as a secret: never log or display it. Any thread.
     */
    @NotNull Optional<ProxyConfig> getProxyConfig();

    /**
     * Looks up the provider links of a Minecraft account (at most one per provider). Completes with an empty list
     * if there are none.
     */
    @NotNull CompletableFuture<List<ExternalLinkInfo>> getExternalLinks(@NotNull UUID minecraftUuid);

    /** Looks up every game account linked to one external account, oldest link first. */
    @NotNull CompletableFuture<List<ExternalLinkInfo>> getExternalLinks(@NotNull String providerId,
                                                                         @NotNull String externalId);

    /** Removes every provider link of a Minecraft account. Completes with true if a link was removed. */
    @NotNull CompletableFuture<Boolean> unlinkExternal(@NotNull UUID minecraftUuid);

    /** Removes the link of one provider. Completes with true if a link was removed. */
    @NotNull CompletableFuture<Boolean> unlinkExternal(@NotNull UUID minecraftUuid, @NotNull String providerId);

    // ---- API v4: admin tools ----------------------------------------------------------------------

    /** The actions of TgAuth's admin panel, for addons with an admin panel of their own. */
    @NotNull AdminApi admin();
}
