package net.millyland.auth.auth;

import net.millyland.auth.TgAuthPlugin;
import net.millyland.auth.api.AuthMethod;
import net.millyland.auth.api.ExternalLinkInfo;
import net.millyland.auth.api.LinkInstructions;
import net.millyland.auth.api.LinkResult;
import net.millyland.auth.api.LoginProvider;
import net.millyland.auth.api.event.PlayerAuthenticateEvent;
import net.millyland.auth.api.event.PlayerAuthenticatedEvent;
import net.millyland.auth.storage.ExternalLink;
import net.millyland.auth.storage.LinkedAccount;
import net.millyland.auth.ui.ChoiceMenu;
import net.millyland.auth.ui.CopyCode;
import net.millyland.auth.util.UuidUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.security.SecureRandom;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class AuthManager {

    private final TgAuthPlugin plugin;
    private final SecureRandom random = new SecureRandom();
    private final ChoiceMenu menu;
    /** Serialises "count the links of an external account, then insert", so a limit cannot be overrun. */
    private final Object externalLinkLock = new Object();

    private final Map<UUID, PlayerSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, UUID> pendingCodes = new ConcurrentHashMap<>();
    private final Map<String, UUID> pendingConfirms = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> fastLoginPremiumVerified = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Map<Long, LinkAttemptThrottle> linkAttemptThrottles = new ConcurrentHashMap<>();

    private final java.util.concurrent.atomic.AtomicInteger globalFailedLinkAttempts = new java.util.concurrent.atomic.AtomicInteger();
    private volatile long globalFailedWindowStart = System.currentTimeMillis();
    private volatile long globalLockoutUntil;

    private static final class LinkAttemptThrottle {
        int failedAttempts;
        long windowStart;
        long blockedUntil;
    }

    public AuthManager(TgAuthPlugin plugin) {
        this.plugin = plugin;
        this.menu = new ChoiceMenu(plugin);
    }

    public PlayerSession session(UUID uuid) {
        return sessions.get(uuid);
    }

    public boolean isAuthenticated(UUID uuid) {
        PlayerSession s = sessions.get(uuid);
        return s == null || s.state == AuthState.AUTHENTICATED;
    }

    public void handleJoin(Player player) {
        UUID uuid = player.getUniqueId();
        String name = player.getName();

        PlayerSession session = new PlayerSession(uuid, name);
        sessions.put(uuid, session);

        if (player.hasPermission("tgauth.bypass")) {
            session.state = AuthState.AUTHENTICATED;
            session.method = AuthMethod.BYPASS;
            Bukkit.getPluginManager().callEvent(new PlayerAuthenticatedEvent(player, AuthMethod.BYPASS));
            return;
        }

        boolean hookActive = plugin.fastLoginHook().isHookRegistered();
        if (!hookActive && UuidUtil.looksPremium(uuid)) {

            fastLoginPremiumVerified.add(uuid);
        }
        session.premium = fastLoginPremiumVerified.contains(uuid);

        applyFreezeEffects(player);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {

            Optional<LinkedAccount> linked = plugin.database().findByUuid(uuid);
            List<ExternalLink> external = plugin.database().findExternalsByUuid(uuid);

            Bukkit.getScheduler().runTask(plugin, () -> {
                Player p = Bukkit.getPlayer(uuid);
                if (p == null || !p.isOnline()) return;

                if (linked.isPresent() || !external.isEmpty()) {
                    session.telegramLink = linked.orElse(null);
                    session.externalLinks = external;
                    decideLinkedFlow(p, session, hookActive, () -> beginConfirm(p, session));
                } else {
                    beginUnlinkedFlow(p, session);
                }
            });
        });
    }

    /**
     * The player has a link (Telegram or a provider). The licence and trusted-IP checks are the same for every
     * method and happen here; only the confirmation itself differs ({@code startConfirm}).
     */
    private void decideLinkedFlow(Player player, PlayerSession session, boolean hookActive, Runnable startConfirm) {
        UUID uuid = player.getUniqueId();
        boolean skipEnabled = plugin.cfg().premiumSkipConfirmation();

        if (skipEnabled && isFastLoginVerifiedPremium(uuid)) {
            authenticate(player, session, "join.premium-auto-login", AuthMethod.PREMIUM);
            return;
        }

        if (skipEnabled && hookActive) {

            int waitSeconds = plugin.cfg().premiumCheckWaitSeconds();
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Player p = Bukkit.getPlayer(uuid);
                if (p == null || !p.isOnline()) return;
                if (session.state == AuthState.AUTHENTICATED
                        || session.state == AuthState.AWAITING_ADMIN_PIN) return;

                if (isFastLoginVerifiedPremium(uuid)) {
                    authenticate(p, session, "join.premium-auto-login", AuthMethod.PREMIUM);
                } else {
                    confirmOrTrustedIp(p, session, startConfirm);
                }
            }, waitSeconds * 20L);
            return;
        }

        confirmOrTrustedIp(player, session, startConfirm);
    }

    private void confirmOrTrustedIp(Player player, PlayerSession session, Runnable startConfirm) {
        int cooldownSeconds = plugin.cfg().crackedIpCooldownSeconds();
        String ip = ipOf(player);

        if (cooldownSeconds > 0 && ip != null) {
            UUID uuid = player.getUniqueId();
            plugin.database().revokeTrustedIpIfUsedByOtherAccount(uuid, ip);
            plugin.database().revokeTrustedIpIfMismatched(uuid, ip);

            if (plugin.database().matchesRecentIp(uuid, ip, cooldownSeconds)) {
                authenticate(player, session, "join.trusted-ip-skip", AuthMethod.TRUSTED_IP);
                return;
            }
        }

        startConfirm.run();
        scheduleAuthTimeout(player.getUniqueId());
    }

    /** Remembers the player's IP as trusted after a successful link, like the Telegram link does. */
    private void trustCurrentIp(Player player) {
        int cooldownSeconds = plugin.cfg().crackedIpCooldownSeconds();
        String ip = ipOf(player);
        if (cooldownSeconds > 0 && ip != null) {
            UUID uuid = player.getUniqueId();
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                plugin.database().revokeTrustedIpIfUsedByOtherAccount(uuid, ip);
                plugin.database().recordConfirmedIp(uuid, ip);
            });
        }
    }

    private String ipOf(Player player) {
        return player.getAddress() != null ? player.getAddress().getAddress().getHostAddress() : null;
    }

    public void handleQuit(UUID uuid) {
        PlayerSession s = sessions.remove(uuid);
        if (s != null) {
            if (s.linkCode != null) pendingCodes.remove(s.linkCode);
            if (s.confirmToken != null) pendingConfirms.remove(s.confirmToken);
            endProviderAttempt(s);
        }
    }

    private void beginLinkFlow(Player player, PlayerSession session) {
        session.state = AuthState.AWAITING_LINK;
        String code = generateCode();
        session.linkCode = code;
        session.linkCodeExpireAt = System.currentTimeMillis() + plugin.cfg().codeExpireSeconds() * 1000L;
        pendingCodes.put(code, player.getUniqueId());

        String botUsername = plugin.cfg().botUsername();
        player.sendMessage(CopyCode.inText(plugin, plugin.lang().pget("join.need-link-code",
                "%code%", code, "%bot%", "@" + botUsername), code, "/link " + code));

        scheduleReminder(player.getUniqueId());
    }

    // ------------------------------------------------------------------ new player: choice and providers

    /**
     * A player without any link joined. With only Telegram they get the code at once; when addons registered
     * login providers they choose first (a single provider and no Telegram skips the choice).
     */
    private void beginUnlinkedFlow(Player player, PlayerSession session) {
        session.state = AuthState.AWAITING_LINK;
        List<LoginProvider> providers = plugin.api().availableProviders();
        boolean telegram = telegramLoginOn();

        if (providers.isEmpty()) {
            if (!telegram) {
                // Telegram login is off and no provider can take the player right now: nothing to wait for.
                kick(player, plugin.lang().get("join.no-method"));
                return;
            }
            beginLinkFlow(player, session);
        } else if (!telegram && providers.size() == 1) {
            beginProviderLink(player, session, providers.get(0));
        } else {
            session.choosing = true;
            session.choiceOffered = true;
            menu.showChoice(player, telegram, providers, ChoiceMenu.Mode.LOGIN);
        }
        scheduleAuthTimeout(player.getUniqueId());
    }

    /** Telegram is a usable login method: not switched off in the config and the bot is running. */
    public boolean telegramLoginOn() {
        return plugin.cfg().telegramAuthEnabled() && plugin.telegram() != null;
    }

    /** The player picked a method in the chat or dialog menu ({@code /tgchoose <id>}). Main thread. */
    public void choose(Player player, String methodId) {
        PlayerSession session = sessions.get(player.getUniqueId());
        String id = methodId == null ? "" : methodId.trim().toLowerCase(java.util.Locale.ROOT);
        if (session != null && session.addChoosing && session.state == AuthState.AUTHENTICATED) {
            chooseToAdd(player, session, id);
            return;
        }
        if (session != null && session.confirmChoosing && session.state == AuthState.AWAITING_CONFIRM) {
            chooseToConfirm(player, session, id);
            return;
        }
        if (session == null || session.state != AuthState.AWAITING_LINK) {
            player.sendMessage(plugin.lang().pget("choose.not-needed"));
            return;
        }
        if (id.equals("back") || id.isEmpty()) {
            backToChoice(player);
            return;
        }

        if (id.equals("telegram")) {
            if (!telegramLoginOn()) {
                menu.closeDialog(player);
                player.sendMessage(plugin.lang().pget("choose.unavailable"));
                return;
            }
            endProviderAttempt(session);
            session.choosing = false;
            if (session.linkCode == null) beginLinkFlow(player, session);
            menu.showTelegramInstructions(player, plugin.cfg().botUsername(), session.linkCode, session.choiceOffered);
            return;
        }

        LoginProvider provider = plugin.api().provider(id);
        if (provider == null || !provider.isAvailable()) {
            menu.closeDialog(player);
            player.sendMessage(plugin.lang().pget("choose.unavailable"));
            return;
        }
        endProviderAttempt(session);
        beginProviderLink(player, session, provider);
    }

    /** The "Back" button of an instruction screen: drop the attempt and show the method choice again. Main thread. */
    public void backToChoice(Player player) {
        PlayerSession session = sessions.get(player.getUniqueId());
        if (session == null || !session.choiceOffered) return;
        if (session.state == AuthState.AUTHENTICATED && session.addingMethod) {
            showAddChoice(player, session);
            return;
        }
        if (session.state != AuthState.AWAITING_LINK) return;
        endProviderAttempt(session);
        session.choosing = true;
        menu.showChoice(player, telegramLoginOn(), plugin.api().availableProviders(), ChoiceMenu.Mode.LOGIN);
    }

    private void beginProviderLink(Player player, PlayerSession session, LoginProvider provider) {
        session.choosing = false;
        session.providerId = provider.getId();
        session.providerName = provider.getDisplayName();
        ProviderLinkSession linkSession = new ProviderLinkSession(plugin, this, player, session, provider);
        session.linkSession = linkSession;
        try {
            provider.beginLink(player, linkSession);
        } catch (Throwable t) {
            plugin.getLogger().warning("Login provider " + provider.getId() + " failed to start linking: " + t);
            linkSession.deactivate();
            session.linkSession = null;
            menu.closeDialog(player);
            player.sendMessage(plugin.lang().pget("join.provider-unavailable", "%provider%", provider.getDisplayName()));
        }
    }

    /** Called by {@link ProviderLinkSession}. Main thread. */
    void showProviderInstructions(ProviderLinkSession linkSession, LinkInstructions instructions) {
        PlayerSession session = linkSession.session;
        Player player = Bukkit.getPlayer(session.uuid);
        if (player == null || !player.isOnline() || !linkSession.isActive() || session.linkSession != linkSession) return;

        session.instructions = instructions;
        menu.showInstructions(player, instructions, session.choiceOffered);
        if (instructions.reminder() != null && !session.providerReminderRunning) {
            session.providerReminderRunning = true;
            scheduleProviderReminder(session, linkSession);
        }
    }

    private void scheduleProviderReminder(PlayerSession session, ProviderLinkSession linkSession) {
        int interval = plugin.cfg().reminderIntervalSeconds();
        UUID uuid = session.uuid;
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            Player p = Bukkit.getPlayer(uuid);
            LinkInstructions current = session.instructions;
            if (p == null || !p.isOnline() || sessions.get(uuid) != session || !linkSession.isActive()
                    || session.linkSession != linkSession || current == null || current.reminder() == null) {
                session.providerReminderRunning = false;
                task.cancel();
                return;
            }
            p.sendMessage(current.reminder());
        }, interval * 20L, interval * 20L);
    }

    /** Drops a provider link attempt or confirmation that did not finish and tells the provider. */
    private void endProviderAttempt(PlayerSession session) {
        ProviderLinkSession linkSession = session.linkSession;
        ProviderConfirmation confirmation = session.confirmation;
        String providerId = session.providerId;

        boolean pending = false;
        if (linkSession != null) {
            linkSession.deactivate();
            session.linkSession = null;
            session.instructions = null;
            pending = true;
        }
        if (confirmation != null) {
            pending |= confirmation.answered.compareAndSet(false, true);
            session.confirmation = null;
        }
        if (pending && providerId != null) {
            LoginProvider provider = plugin.api().provider(providerId);
            if (provider != null) {
                try {
                    provider.onSessionEnded(session.uuid);
                } catch (Throwable t) {
                    plugin.getLogger().warning("Login provider " + providerId + " failed in onSessionEnded: " + t);
                }
            }
        }
        if (linkSession != null) session.providerId = null;
    }

    /**
     * The external account proved ownership ({@code LinkSession#complete}). Saves the link in the database and
     * logs the player in. Any thread.
     */
    void completeProviderLink(ProviderLinkSession linkSession, String externalId, String externalName,
                              int maxAccounts, CompletableFuture<LinkResult> result) {
        PlayerSession session = linkSession.session;
        UUID uuid = session.uuid;
        String providerId = linkSession.provider.getId();
        int limit = Math.max(1, maxAccounts);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                LinkResult outcome;
                synchronized (externalLinkLock) {
                    if (!linkSession.isActive() || sessions.get(uuid) != session) {
                        outcome = LinkResult.PLAYER_GONE;
                    } else if (plugin.database().countExternal(providerId, externalId) >= limit) {
                        outcome = LinkResult.LIMIT_REACHED;
                    } else if (session.addingMethod
                            ? plugin.database().findExternal(uuid, providerId).isPresent()
                            : (plugin.database().findByUuid(uuid).isPresent()
                                    || !plugin.database().findExternalsByUuid(uuid).isEmpty())) {
                        outcome = LinkResult.ALREADY_LINKED;
                    } else if (plugin.database().linkExternal(uuid, providerId, externalId, externalName, session.name)) {
                        outcome = LinkResult.LINKED;
                    } else {
                        outcome = LinkResult.ERROR;
                    }
                }
                result.complete(outcome);
                if (outcome != LinkResult.LINKED) return;

                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player p = Bukkit.getPlayer(uuid);
                    if (p == null || !p.isOnline() || sessions.get(uuid) != session) return;
                    linkSession.deactivate();
                    session.linkSession = null;
                    if (session.addingMethod) {
                        // an already logged-in player added this method: no login, just tell them
                        session.addingMethod = false;
                        session.providerId = null;
                        menu.closeDialog(p);
                        p.sendMessage(plugin.lang().pget("addmethod.success-provider",
                                "%provider%", linkSession.provider.getDisplayName()));
                        return;
                    }
                    authenticate(p, session, "link.success-external", AuthMethod.EXTERNAL_LINK);
                    trustCurrentIp(p);
                });
            } catch (Throwable t) {
                plugin.getLogger().warning("Could not complete the " + providerId + " link of " + session.name + ": " + t);
                result.complete(LinkResult.ERROR);
            }
        });
    }

    /** A linked-to-provider player needs confirmation: hand them to the provider ("player joined, here is the data"). */
    private void beginExternalConfirm(Player player, PlayerSession session, ExternalLink link) {
        session.state = AuthState.AWAITING_CONFIRM;
        long expiresAt = System.currentTimeMillis() + plugin.cfg().confirmTimeoutSeconds() * 1000L;
        session.confirmExpireAt = expiresAt;
        session.pendingIp = ipOf(player);

        LoginProvider provider = plugin.api().provider(link.provider());
        session.providerId = link.provider();
        session.providerName = provider != null ? provider.getDisplayName() : link.provider();
        if (provider == null || !provider.isAvailable()) {
            player.sendMessage(plugin.lang().pget("join.provider-unavailable", "%provider%", session.providerName));
            return; // stays frozen; the normal confirmation timeout kicks the player
        }

        ProviderConfirmation request = new ProviderConfirmation(this, player, session.pendingIp, expiresAt);
        session.confirmation = request;
        player.sendMessage(plugin.lang().pget("join.need-confirm-external", "%provider%", provider.getDisplayName()));
        try {
            provider.requestConfirmation(player, new ExternalLinkInfo(link.provider(), link.uuid(), link.username(),
                    link.externalId(), link.externalName(), link.linkedAt(), link.premium()), request);
        } catch (Throwable t) {
            plugin.getLogger().warning("Login provider " + provider.getId() + " failed to request a confirmation: " + t);
            player.sendMessage(plugin.lang().pget("join.provider-unavailable", "%provider%", provider.getDisplayName()));
        }
    }

    /** The provider answered a {@link ProviderConfirmation}. Any thread. */
    void resolveProviderConfirmation(ProviderConfirmation request, boolean approve) {
        if (!request.answered.compareAndSet(false, true)) return;
        UUID uuid = request.getPlayer().getUniqueId();
        Bukkit.getScheduler().runTask(plugin, () -> {
            PlayerSession session = sessions.get(uuid);
            Player p = Bukkit.getPlayer(uuid);
            if (session == null || p == null || !p.isOnline() || session.confirmation != request
                    || session.state != AuthState.AWAITING_CONFIRM) return;
            session.confirmation = null;

            if (approve) {
                authenticate(p, session, "confirm.confirmed-external", AuthMethod.EXTERNAL_CONFIRM);
                if (plugin.cfg().crackedIpCooldownSeconds() > 0 && session.pendingIp != null) {
                    String ip = session.pendingIp;
                    Bukkit.getScheduler().runTaskAsynchronously(plugin,
                            () -> plugin.database().recordConfirmedIp(uuid, ip));
                }
            } else {
                kick(p, plugin.lang().get("confirm.rejected-external", "%provider%", session.providerName));
            }
        });
    }

    private void scheduleReminder(UUID uuid) {
        int interval = plugin.cfg().reminderIntervalSeconds();
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            PlayerSession s = sessions.get(uuid);
            Player p = Bukkit.getPlayer(uuid);
            if (s == null || p == null || !p.isOnline() || s.state != AuthState.AWAITING_LINK) {
                task.cancel();
                return;
            }
            long remaining = (s.linkCodeExpireAt - System.currentTimeMillis()) / 1000L;
            if (remaining <= 0) {
                task.cancel();
                return;
            }
            p.sendMessage(CopyCode.inText(plugin, plugin.lang().pget("join.need-link-reminder",
                    "%code%", s.linkCode, "%seconds%", String.valueOf(remaining)), s.linkCode,
                    "/link " + s.linkCode));
        }, interval * 20L, interval * 20L);
    }

    public void handleLinkAttempt(String code, long telegramId, String tgUsername, long chatId) {
        long now = System.currentTimeMillis();

        if (!plugin.cfg().telegramAuthEnabled()) {
            plugin.telegram().send(chatId, plugin.lang().rawGet("link.telegram-disabled"));
            return;
        }

        if (globalLockoutUntil > now) {
            long secondsLeft = (globalLockoutUntil - now) / 1000L + 1;
            plugin.telegram().send(chatId, plugin.lang().rawGet("link.too-many-attempts", "%seconds%", String.valueOf(secondsLeft)));
            return;
        }

        LinkAttemptThrottle throttle = linkAttemptThrottles.computeIfAbsent(telegramId, k -> new LinkAttemptThrottle());

        synchronized (throttle) {
            if (throttle.blockedUntil > now) {
                long secondsLeft = (throttle.blockedUntil - now) / 1000L + 1;
                plugin.telegram().send(chatId, plugin.lang().rawGet("link.too-many-attempts", "%seconds%", String.valueOf(secondsLeft)));
                return;
            }

            UUID uuid = pendingCodes.get(code);
            PlayerSession session = uuid == null ? null : sessions.get(uuid);

            boolean codeValid = uuid != null && session != null
                    && (session.state == AuthState.AWAITING_LINK || session.addingTelegram)
                    && now <= session.linkCodeExpireAt;

            if (!codeValid) {
                registerFailedAttempt(throttle, now);
                plugin.telegram().send(chatId, plugin.lang().rawGet("link.invalid-code"));
                return;
            }

            int limit = plugin.cfg().maxAccountsPerTelegram();
            if (plugin.database().countByTelegramId(telegramId) >= limit) {
                plugin.telegram().send(chatId, limit == 1
                        ? plugin.lang().rawGet("link.already-linked-telegram")
                        : plugin.lang().rawGet("link.limit-reached", "%limit%", String.valueOf(limit)));
                return;
            }
            boolean adding = session.addingTelegram;
            if (adding ? plugin.database().findByUuid(uuid).isPresent()
                    : (plugin.database().findByUuid(uuid).isPresent()
                            || !plugin.database().findExternalsByUuid(uuid).isEmpty())) {
                plugin.telegram().send(chatId, plugin.lang().rawGet("link.already-linked-player"));
                return;
            }

            boolean ok = plugin.database().link(uuid, telegramId, session.name, tgUsername);
            pendingCodes.remove(code);

            if (!ok) {
                plugin.telegram().send(chatId, plugin.lang().rawGet("link.invalid-code"));
                return;
            }

            linkAttemptThrottles.remove(telegramId);

            plugin.telegram().send(chatId, plugin.lang().rawGet("link.success-telegram", "%player%", session.name));
            plugin.api().fireLinked(new LinkedAccount(uuid, telegramId, session.name, System.currentTimeMillis(),
                    tgUsername, session.premium));

            Bukkit.getScheduler().runTask(plugin, () -> {
                Player p = Bukkit.getPlayer(uuid);
                if (p == null || !p.isOnline()) return;
                if (adding) {
                    session.addingTelegram = false;
                    session.addingMethod = false;
                    session.linkCode = null;
                    menu.closeDialog(p);
                    p.sendMessage(plugin.lang().pget("addmethod.success-telegram"));
                    return;
                }
                authenticate(p, session, "link.success-ingame", AuthMethod.TELEGRAM_LINK);
                trustCurrentIp(p);
            });
        }
    }

    private void registerFailedAttempt(LinkAttemptThrottle throttle, long now) {
        int maxAttempts = plugin.cfg().linkMaxAttempts();
        int windowSeconds = plugin.cfg().linkAttemptWindowSeconds();
        int lockoutSeconds = plugin.cfg().linkLockoutSeconds();

        if (now - throttle.windowStart > windowSeconds * 1000L) {

            throttle.windowStart = now;
            throttle.failedAttempts = 0;
        }

        throttle.failedAttempts++;
        if (throttle.failedAttempts >= maxAttempts) {
            throttle.blockedUntil = now + lockoutSeconds * 1000L;
            throttle.failedAttempts = 0;
            throttle.windowStart = now;
        }

        registerGlobalFailedAttempt(now);
    }

    private synchronized void registerGlobalFailedAttempt(long now) {
        int maxAttempts = plugin.cfg().globalLinkMaxAttempts();
        int windowSeconds = plugin.cfg().globalLinkAttemptWindowSeconds();
        int lockoutSeconds = plugin.cfg().globalLinkLockoutSeconds();

        if (now - globalFailedWindowStart > windowSeconds * 1000L) {
            globalFailedWindowStart = now;
            globalFailedLinkAttempts.set(0);
        }

        int attempts = globalFailedLinkAttempts.incrementAndGet();
        if (attempts >= maxAttempts) {
            globalLockoutUntil = now + lockoutSeconds * 1000L;
            globalFailedLinkAttempts.set(0);
            globalFailedWindowStart = now;
            plugin.getLogger().warning("Too many failed /link attempts across " + attempts
                    + " requests in a short window - temporarily locking out ALL /link attempts "
                    + "for " + lockoutSeconds + "s. This usually means someone is brute-forcing "
                    + "link codes using multiple Telegram accounts.");
        }
    }

    /**
     * The player has a link. With a Telegram link and a provider link that are both usable they pick which one
     * confirms this login (chat click or dialog button); otherwise the single usable method starts at once.
     */
    private void beginConfirm(Player player, PlayerSession session) {
        session.state = AuthState.AWAITING_CONFIRM;
        LinkedAccount tg = session.telegramLink;
        boolean tgOk = tg != null && telegramLoginOn();

        // provider links whose addon is installed and ready right now
        List<LoginProvider> usable = new java.util.ArrayList<>();
        for (ExternalLink link : session.externalLinks) {
            LoginProvider provider = plugin.api().provider(link.provider());
            if (provider != null && providerAvailable(provider)) usable.add(provider);
        }

        if (usable.size() + (tgOk ? 1 : 0) > 1) {
            session.confirmChoosing = true;
            menu.showChoice(player, tgOk, usable, ChoiceMenu.Mode.CONFIRM);
        } else if (tgOk) {
            beginConfirmFlow(player, session, tg);
        } else if (usable.size() == 1) {
            beginExternalConfirm(player, session, linkOf(session, usable.get(0).getId()));
        } else if (!session.externalLinks.isEmpty()) {
            beginExternalConfirm(player, session, session.externalLinks.get(0)); // prints "provider unavailable"
        } else {
            beginConfirmFlow(player, session, tg);      // prints "Telegram is off"
        }
    }

    private static ExternalLink linkOf(PlayerSession session, String providerId) {
        for (ExternalLink link : session.externalLinks) {
            if (link.provider().equals(providerId)) return link;
        }
        return null;
    }

    private boolean providerAvailable(LoginProvider provider) {
        try {
            return provider.isAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    /** The choice of a confirmation method was clicked. Main thread. */
    private void chooseToConfirm(Player player, PlayerSession session, String id) {
        LinkedAccount tg = session.telegramLink;
        if (id.equals("telegram") && tg != null && telegramLoginOn()) {
            session.confirmChoosing = false;
            menu.closeDialog(player);
            beginConfirmFlow(player, session, tg);
            return;
        }
        ExternalLink ext = linkOf(session, id);
        if (ext != null) {
            LoginProvider provider = plugin.api().provider(id);
            if (provider != null && providerAvailable(provider)) {
                session.confirmChoosing = false;
                menu.closeDialog(player);
                beginExternalConfirm(player, session, ext);
                return;
            }
        }
        player.sendMessage(plugin.lang().pget("choose.unavailable"));
    }

    // ------------------------------------------------------------------ /add-auth-method

    /** {@code /add-auth-method}: an authenticated player links one more login method. Main thread. */
    public void beginAddMethod(Player player) {
        UUID uuid = player.getUniqueId();
        PlayerSession session = sessions.get(uuid);
        if (session == null || session.state != AuthState.AUTHENTICATED) {
            player.sendMessage(plugin.lang().pget("addmethod.not-authenticated"));
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean hasTelegram = plugin.database().findByUuid(uuid).isPresent();
            java.util.Set<String> linkedProviders = new java.util.HashSet<>();
            for (ExternalLink link : plugin.database().findExternalsByUuid(uuid)) linkedProviders.add(link.provider());
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player p = Bukkit.getPlayer(uuid);
                if (p == null || !p.isOnline() || sessions.get(uuid) != session) return;
                session.addHasTelegram = hasTelegram;
                session.addLinkedProviders = linkedProviders;
                showAddChoice(p, session);
            });
        });
    }

    /** Shows the methods the player can still add; a single one starts at once. */
    private void showAddChoice(Player player, PlayerSession session) {
        cancelAdd(session);
        boolean telegram = !session.addHasTelegram && telegramLoginOn();
        List<LoginProvider> available = plugin.api().availableProviders();
        List<LoginProvider> providers = new java.util.ArrayList<>();
        for (LoginProvider candidate : available) {
            if (!session.addLinkedProviders.contains(candidate.getId())) providers.add(candidate);
        }
        int count = (telegram ? 1 : 0) + providers.size();

        if (count == 0) {
            boolean everythingLinked = !available.isEmpty() && (session.addHasTelegram || !telegramLoginOn());
            player.sendMessage(plugin.lang().pget(everythingLinked ? "addmethod.all-linked" : "addmethod.none-available"));
        } else if (count == 1) {
            session.choiceOffered = false;
            if (telegram) beginAddTelegram(player, session);
            else beginAddProvider(player, session, providers.get(0));
        } else {
            session.choiceOffered = true;
            session.addChoosing = true;
            menu.showChoice(player, telegram, providers, ChoiceMenu.Mode.ADD);
        }
    }

    private void chooseToAdd(Player player, PlayerSession session, String id) {
        if (id.equals("back") || id.isEmpty()) {
            showAddChoice(player, session);
            return;
        }
        if (id.equals("telegram")) {
            if (session.addHasTelegram || !telegramLoginOn()) {
                menu.closeDialog(player);
                player.sendMessage(plugin.lang().pget("choose.unavailable"));
                return;
            }
            cancelAdd(session);
            beginAddTelegram(player, session);
            return;
        }
        LoginProvider provider = plugin.api().provider(id);
        if (provider == null || session.addLinkedProviders.contains(id) || !providerAvailable(provider)) {
            menu.closeDialog(player);
            player.sendMessage(plugin.lang().pget("choose.unavailable"));
            return;
        }
        cancelAdd(session);
        beginAddProvider(player, session, provider);
    }

    private void beginAddTelegram(Player player, PlayerSession session) {
        session.addChoosing = false;
        session.addingMethod = true;
        session.addingTelegram = true;
        String code = generateCode();
        session.linkCode = code;
        session.linkCodeExpireAt = System.currentTimeMillis() + plugin.cfg().codeExpireSeconds() * 1000L;
        pendingCodes.put(code, player.getUniqueId());

        String bot = plugin.cfg().botUsername();
        player.sendMessage(CopyCode.inText(plugin, plugin.lang().pget("addmethod.telegram-code",
                "%code%", code, "%bot%", "@" + bot), code, "/link " + code));
        menu.showTelegramInstructions(player, bot, code, session.choiceOffered);
    }

    private void beginAddProvider(Player player, PlayerSession session, LoginProvider provider) {
        session.addChoosing = false;
        session.addingMethod = true;
        beginProviderLink(player, session, provider);
    }

    /** Drops an unfinished add attempt (a provider session or a Telegram code). */
    private void cancelAdd(PlayerSession session) {
        endProviderAttempt(session);
        if (session.addingTelegram && session.linkCode != null) {
            pendingCodes.remove(session.linkCode);
            session.linkCode = null;
        }
        session.addingTelegram = false;
        session.addingMethod = false;
        session.addChoosing = false;
    }

    private void beginConfirmFlow(Player player, PlayerSession session, LinkedAccount account) {
        session.state = AuthState.AWAITING_CONFIRM;
        if (!telegramLoginOn()) {
            // Telegram login is off (or the bot is down) and the player has no other usable method. They stay
            // frozen (a licence or trusted IP was already handled) until the confirmation timeout kicks them.
            player.sendMessage(plugin.cfg().telegramAuthEnabled()
                    ? plugin.lang().pget("join.provider-unavailable", "%provider%", "Telegram")
                    : plugin.lang().pget("join.telegram-disabled"));
            return;
        }
        String token = generateToken();
        session.confirmToken = token;
        session.confirmExpireAt = System.currentTimeMillis() + plugin.cfg().confirmTimeoutSeconds() * 1000L;
        pendingConfirms.put(token, player.getUniqueId());

        String ip = ipOf(player);
        session.pendingIp = ip;
        String text = plugin.lang().rawGet("confirm.message", "%player%", session.name, "%ip%", ip == null ? "?" : ip);

        plugin.telegram().sendConfirmRequest(account.telegramId(), text, token);
        player.sendMessage(plugin.lang().pget("join.need-confirm"));
    }

    public void registerTelegramMessage(String token, long chatId, int messageId) {
        UUID uuid = pendingConfirms.get(token);
        if (uuid == null) return;
        PlayerSession session = sessions.get(uuid);
        if (session == null) return;
        session.telegramChatId = chatId;
        session.telegramMessageId = messageId;
    }

    public void handleConfirmCallback(String token, boolean approve, long chatId, int messageId) {
        UUID uuid = pendingConfirms.remove(token);
        if (uuid == null) return;
        PlayerSession session = sessions.get(uuid);
        if (session == null || session.state != AuthState.AWAITING_CONFIRM) return;

        String resultText = approve
                ? plugin.lang().rawGet("confirm.confirmed-telegram")
                : plugin.lang().rawGet("confirm.rejected-telegram");
        plugin.telegram().editConfirmResult(chatId, messageId, resultText);

        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null || !p.isOnline()) return;

            if (approve) {
                authenticate(p, session, "confirm.confirmed-ingame", AuthMethod.TELEGRAM_CONFIRM);
                if (plugin.cfg().crackedIpCooldownSeconds() > 0 && session.pendingIp != null) {
                    UUID sessionUuid = p.getUniqueId();
                    String ip = session.pendingIp;
                    Bukkit.getScheduler().runTaskAsynchronously(plugin,
                            () -> plugin.database().recordConfirmedIp(sessionUuid, ip));
                }
            } else {
                kick(p, plugin.lang().get("confirm.rejected-kick"));
            }
        });
    }

    /**
     * Called whenever the Telegram login step has succeeded. Admins who have no panel PIN yet are
     * not let through: they stay frozen (state AWAITING_ADMIN_PIN) until they set one in the bot.
     */
    private void authenticate(Player player, PlayerSession session, String messageKeyOrNull, AuthMethod method) {
        if (session.state == AuthState.AWAITING_ADMIN_PIN || session.state == AuthState.AUTHENTICATED) return;

        PlayerAuthenticateEvent event = new PlayerAuthenticateEvent(player, method);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            String custom = event.getKickMessage();
            kick(player, custom != null ? custom.replace('&', '§') : plugin.lang().get("api.authentication-denied"));
            return;
        }
        session.method = method;

        if (adminPinGateApplies(player)) {
            beginAdminPinGate(player, session, messageKeyOrNull);
            return;
        }
        completeAuthentication(player, session, messageKeyOrNull);
    }

    private boolean adminPinGateApplies(Player player) {
        return plugin.cfg().adminPinEnabled()
                && plugin.cfg().adminPinFreezeAdmins()
                && plugin.cfg().adminPanelEnabled()
                && plugin.telegram() != null          // without the bot nobody could ever set a PIN
                && player.hasPermission("tgauth.admin");
    }

    private void beginAdminPinGate(Player player, PlayerSession session, String messageKeyOrNull) {
        // isAuthenticated() is false in this state, so every protection listener keeps the player
        // frozen, and the blindness/slowness effects simply stay on.
        session.state = AuthState.AWAITING_ADMIN_PIN;
        session.postAuthMessageKey = messageKeyOrNull;
        UUID uuid = player.getUniqueId();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Optional<LinkedAccount> account = plugin.database().findByUuid(uuid);
            boolean pinMissing = account.isPresent() && !plugin.adminPin().hasPin(account.get().telegramId());

            Bukkit.getScheduler().runTask(plugin, () -> {
                Player p = Bukkit.getPlayer(uuid);
                if (p == null || !p.isOnline() || session.state != AuthState.AWAITING_ADMIN_PIN) return;

                if (!pinMissing) {
                    completeAuthentication(p, session, session.postAuthMessageKey);
                    return;
                }

                p.sendMessage(plugin.lang().pget("pin.ingame-setup-required",
                        "%bot%", "@" + plugin.cfg().botUsername()));
                scheduleAdminPinReminder(uuid);
                scheduleAdminPinTimeout(uuid);
            });
        });
    }

    private void scheduleAdminPinReminder(UUID uuid) {
        int interval = plugin.cfg().reminderIntervalSeconds();
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            PlayerSession s = sessions.get(uuid);
            Player p = Bukkit.getPlayer(uuid);
            if (s == null || p == null || !p.isOnline() || s.state != AuthState.AWAITING_ADMIN_PIN) {
                task.cancel();
                return;
            }
            p.sendMessage(plugin.lang().pget("pin.ingame-setup-reminder",
                    "%bot%", "@" + plugin.cfg().botUsername()));
        }, interval * 20L, interval * 20L);
    }

    private void scheduleAdminPinTimeout(UUID uuid) {
        int timeout = plugin.cfg().adminPinSetupTimeoutSeconds();
        if (timeout <= 0) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            PlayerSession s = sessions.get(uuid);
            Player p = Bukkit.getPlayer(uuid);
            if (s == null || p == null || !p.isOnline() || s.state != AuthState.AWAITING_ADMIN_PIN) return;
            kick(p, plugin.lang().get("pin.ingame-setup-timeout-kick"));
        }, timeout * 20L);
    }

    /**
     * Called (from the Telegram thread) right after an admin has saved their panel PIN: releases
     * the matching player if they are online and frozen waiting for exactly that.
     */
    public void onAdminPinSet(long telegramId) {
        List<LinkedAccount> accounts = plugin.database().findAllByTelegramId(telegramId);
        if (accounts.isEmpty()) return;

        Bukkit.getScheduler().runTask(plugin, () -> {
            for (LinkedAccount account : accounts) {
                UUID uuid = account.uuid();
                PlayerSession s = sessions.get(uuid);
                Player p = Bukkit.getPlayer(uuid);
                if (s == null || p == null || !p.isOnline() || s.state != AuthState.AWAITING_ADMIN_PIN) continue;

                p.sendMessage(plugin.lang().pget("pin.ingame-setup-done"));
                completeAuthentication(p, s, s.postAuthMessageKey);
            }
        });
    }

    private void completeAuthentication(Player player, PlayerSession session, String messageKeyOrNull) {
        session.state = AuthState.AUTHENTICATED;
        session.postAuthMessageKey = null;
        removeFreezeEffects(player);
        menu.closeDialog(player); // the choice / instruction window is not needed any more

        if (session.premium) {
            UUID uuid = player.getUniqueId();
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> plugin.database().setPremium(uuid, true));
        }

        // A pending provider confirmation that was answered by licence/IP instead: tell the provider.
        if (session.confirmation != null || session.linkSession != null) endProviderAttempt(session);

        if (messageKeyOrNull != null) {
            player.sendMessage(plugin.lang().pget(messageKeyOrNull,
                    "%provider%", session.providerName == null ? "" : session.providerName));
        }

        plugin.updateChecker().notifyAdminOnLogin(player);

        Bukkit.getPluginManager().callEvent(new PlayerAuthenticatedEvent(player,
                session.method != null ? session.method : AuthMethod.TELEGRAM_CONFIRM));
    }

    private void scheduleAuthTimeout(UUID uuid) {
        int timeout = plugin.cfg().authTimeoutSeconds();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            PlayerSession s = sessions.get(uuid);
            Player p = Bukkit.getPlayer(uuid);
            if (s == null || p == null || !p.isOnline()) return;
            // Waiting for the admin PIN has its own timeout (admin-pin.setup-timeout-seconds).
            if (s.state == AuthState.AUTHENTICATED || s.state == AuthState.AWAITING_ADMIN_PIN) return;

            String key = s.state == AuthState.AWAITING_LINK ? "link.code-expired-kick" : "confirm.timeout-kick";
            kick(p, plugin.lang().get(key));
        }, timeout * 20L);
    }

    private void kick(Player player, String legacyColoredText) {
        Component component = LegacyComponentSerializer.legacySection().deserialize(legacyColoredText);
        player.kick(component);
    }

    private void applyFreezeEffects(Player player) {

        player.setFallDistance(0f);

        if (plugin.cfg().applyBlindness()) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, Integer.MAX_VALUE, 1, false, false, false));
        }
        if (plugin.cfg().applySlowness()) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, Integer.MAX_VALUE, 5, false, false, false));
        }
    }

    private void removeFreezeEffects(Player player) {
        player.removePotionEffect(PotionEffectType.BLINDNESS);
        player.removePotionEffect(PotionEffectType.SLOWNESS);

        player.setFallDistance(0f);
    }

    private String generateCode() {
        int code = 100000 + random.nextInt(900000);
        return String.valueOf(code);
    }

    private String generateToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public Optional<String> currentLinkCode(UUID uuid) {
        PlayerSession s = sessions.get(uuid);
        if (s == null || s.state != AuthState.AWAITING_LINK) return Optional.empty();
        return Optional.ofNullable(s.linkCode);
    }

    public boolean isFastLoginVerifiedPremium(UUID uuid) {
        return fastLoginPremiumVerified.contains(uuid);
    }

    public int fastLoginVerifiedCount() {
        return fastLoginPremiumVerified.size();
    }

    public void markFastLoginVerifiedPremium(Player player) {
        UUID uuid = player.getUniqueId();
        fastLoginPremiumVerified.add(uuid);

        PlayerSession session = sessions.get(uuid);
        if (session == null) {

            return;
        }
        session.premium = true;

        if (!plugin.cfg().premiumSkipConfirmation()) return;
        if (session.state != AuthState.AWAITING_CONFIRM) return;

        if (session.confirmToken != null) pendingConfirms.remove(session.confirmToken);

        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null || !p.isOnline()) return;
            if (session.state == AuthState.AUTHENTICATED
                    || session.state == AuthState.AWAITING_ADMIN_PIN) return;
            authenticate(p, session, "join.premium-auto-login", AuthMethod.PREMIUM);
        });
    }
}
