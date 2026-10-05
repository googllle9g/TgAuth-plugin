package net.millyland.auth.auth;

import java.util.UUID;

public class PlayerSession {

    public final UUID uuid;
    public final String name;
    public volatile AuthState state;

    public volatile String linkCode;
    public volatile long linkCodeExpireAt;

    public volatile String confirmToken;
    public volatile long confirmExpireAt;
    public volatile Integer telegramMessageId;
    public volatile Long telegramChatId;

    public volatile long joinedAt;
    public volatile boolean premium;

    public volatile String pendingIp;

    /** Message to show once login completes; kept while the admin is held back to set a PIN. */
    public volatile String postAuthMessageKey;

    /** How this player logged in; set when the login step succeeds. */
    public volatile net.millyland.auth.api.AuthMethod method;

    /** Name of the addon currently authenticating this player through the API, if any. */
    public volatile String externalAddon;

    public PlayerSession(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
        this.joinedAt = System.currentTimeMillis();
    }
}
