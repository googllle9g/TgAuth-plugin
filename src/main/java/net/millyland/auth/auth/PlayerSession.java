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

    /** The login provider (addon) this player chose or linked, with its display name; null for Telegram. */
    public volatile String providerId;
    public volatile String providerName;

    /** True while the Telegram/provider choice is on screen. */
    public volatile boolean choosing;

    /** True if this player was offered a choice of methods (so the instruction screens get a "Back" button). */
    public volatile boolean choiceOffered;

    /** The provider link attempt in progress and the instructions it last showed (for reminders). */
    public volatile ProviderLinkSession linkSession;
    public volatile net.millyland.auth.api.LinkInstructions instructions;
    public volatile boolean providerReminderRunning;

    /** The pending provider confirmation, if this player's linked account belongs to a provider. */
    public volatile ProviderConfirmation confirmation;

    /** The links this player has in the database (set at join); a player may have both a Telegram and a provider link. */
    public volatile net.millyland.auth.storage.LinkedAccount telegramLink;
    public volatile java.util.List<net.millyland.auth.storage.ExternalLink> externalLinks = java.util.List.of();

    /** True while the player has to pick which of their two linked methods confirms this login. */
    public volatile boolean confirmChoosing;

    /** /add-auth-method: the method choice is on screen, an add attempt is running, and what the player already has. */
    public volatile boolean addChoosing;
    public volatile boolean addingMethod;
    public volatile boolean addingTelegram;
    public volatile boolean addHasTelegram;
    public volatile java.util.Set<String> addLinkedProviders = java.util.Set.of();

    public PlayerSession(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
        this.joinedAt = System.currentTimeMillis();
    }
}
