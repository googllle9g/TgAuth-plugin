package net.millyland.auth.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Supplies a proxy to TgAuth, for addons that manage proxies (a rotating list, a paid proxy API, ...).
 * Register it with {@link TgAuthAPI#registerProxyProvider}.
 *
 * <p>A proxy set by the server owner in TgAuth's {@code config.yml} ({@code proxy.enabled: true}) always wins.
 * Otherwise the first registered provider that returns a non-null proxy is used. When the proxy changes
 * (or a provider is added or removed) TgAuth reconnects the Telegram bot; call
 * {@link TgAuthAPI#refreshProxy()} when your provider's answer has changed.
 *
 * <p>Both methods may be called from any thread and must return quickly (no network calls).
 */
public interface ProxyProvider {

    /** Unique id: a-z, 0-9, underscore and dash, 1-24 characters. */
    @NotNull String getId();

    /** @return the proxy to use right now, or null for none (a direct connection, or the next provider) */
    @Nullable ProxyConfig getProxy();

    /**
     * Called when TgAuth could not start the Telegram bot through the proxy this provider returned. Use it to
     * rotate to another proxy and then call {@link TgAuthAPI#refreshProxy()}. Default: nothing.
     */
    default void onFailure(@NotNull ProxyConfig proxy, @NotNull Throwable error) {
    }
}
