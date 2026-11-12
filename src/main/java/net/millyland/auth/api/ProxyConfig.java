package net.millyland.auth.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * A proxy server TgAuth can route its outgoing connections through (the Telegram bot and, optionally, the
 * update check). Immutable; {@link #toString()} never contains the password.
 *
 * @param type     proxy protocol
 * @param host     host name or IP address
 * @param port     1-65535
 * @param username optional; null or blank means "no authentication"
 * @param password optional
 */
public record ProxyConfig(@NotNull Type type, @NotNull String host, int port,
                          @Nullable String username, @Nullable String password) {

    public enum Type {
        HTTP, SOCKS4, SOCKS5;

        /** Case-insensitive; also accepts "socks" (= SOCKS5) and "https" (= HTTP). Null if unknown. */
        public static @Nullable Type parse(@Nullable String text) {
            if (text == null) return null;
            return switch (text.trim().toUpperCase(Locale.ROOT)) {
                case "HTTP", "HTTPS" -> HTTP;
                case "SOCKS4" -> SOCKS4;
                case "SOCKS5", "SOCKS" -> SOCKS5;
                default -> null;
            };
        }
    }

    public ProxyConfig {
        if (type == null) throw new IllegalArgumentException("type");
        if (host == null || host.isBlank() || host.length() > 253 || !host.matches("[A-Za-z0-9._:\\-\\[\\]]+")) {
            throw new IllegalArgumentException("invalid proxy host: " + host);
        }
        if (port < 1 || port > 65535) throw new IllegalArgumentException("invalid proxy port: " + port);
        if (username != null && username.isBlank()) {
            username = null;
            password = null;
        }
    }

    /** A proxy without authentication. */
    public ProxyConfig(@NotNull Type type, @NotNull String host, int port) {
        this(type, host, port, null, null);
    }

    public boolean hasCredentials() {
        return username != null;
    }

    /** {@code socks5://host:port}, without credentials (safe for logs and chat). */
    public @NotNull String describe() {
        return type.name().toLowerCase(Locale.ROOT) + "://" + host + ":" + port;
    }

    @Override
    public String toString() {
        return describe() + (hasCredentials() ? " (with login)" : "");
    }
}
