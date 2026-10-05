package net.millyland.auth.auth;

import net.millyland.auth.TgAuthPlugin;
import net.millyland.auth.api.AuthMethod;
import net.millyland.auth.api.event.PlayerAuthenticateEvent;
import net.millyland.auth.api.event.PlayerAuthenticatedEvent;
import net.millyland.auth.storage.LinkedAccount;
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
import java.util.concurrent.ConcurrentHashMap;

public class AuthManager {

    private final TgAuthPlugin plugin;
    private final SecureRandom random = new SecureRandom();

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

            Bukkit.getScheduler().runTask(plugin, () -> {
                Player p = Bukkit.getPlayer(uuid);
                if (p == null || !p.isOnline()) return;

                if (linked.isEmpty()) {
                    beginLinkFlow(p, session);
                    scheduleAuthTimeout(uuid);
                } else {
                    decideLinkedAccountFlow(p, session, linked.get(), hookActive);
                }
            });
        });
    }

    private void decideLinkedAccountFlow(Player player, PlayerSession session, LinkedAccount account, boolean hookActive) {
        UUID uuid = player.getUniqueId();
        boolean skipEnabled = plugin.cfg().premiumSkipConfirmation();

        if (skipEnabled && isFastLoginVerifiedPremium(uuid)) {
            authenticate(player, session, "join.premium-auto-login");
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
                    authenticate(p, session, "join.premium-auto-login");
                } else {
                    beginConfirmFlowOrSkipViaTrustedIp(p, session, account);
                }
            }, waitSeconds * 20L);
            return;
        }

        beginConfirmFlowOrSkipViaTrustedIp(player, session, account);
    }

    private void beginConfirmFlowOrSkipViaTrustedIp(Player player, PlayerSession session, LinkedAccount account) {
        int cooldownSeconds = plugin.cfg().crackedIpCooldownSeconds();
        String ip = ipOf(player);

        if (cooldownSeconds > 0 && ip != null) {
            UUID uuid = player.getUniqueId();
            plugin.database().revokeTrustedIpIfUsedByOtherAccount(uuid, ip);
            plugin.database().revokeTrustedIpIfMismatched(uuid, ip);

            if (plugin.database().matchesRecentIp(uuid, ip, cooldownSeconds)) {
                authenticate(player, session, "join.trusted-ip-skip");
                return;
            }
        }

        beginConfirmFlow(player, session, account);
        scheduleAuthTimeout(player.getUniqueId());
    }

    private String ipOf(Player player) {
        return player.getAddress() != null ? player.getAddress().getAddress().getHostAddress() : null;
    }

    public void handleQuit(UUID uuid) {
        PlayerSession s = sessions.remove(uuid);
        if (s != null) {
            if (s.linkCode != null) pendingCodes.remove(s.linkCode);
            if (s.confirmToken != null) pendingConfirms.remove(s.confirmToken);
        }
    }

    private void beginLinkFlow(Player player, PlayerSession session) {
        session.state = AuthState.AWAITING_LINK;
        String code = generateCode();
        session.linkCode = code;
        session.linkCodeExpireAt = System.currentTimeMillis() + plugin.cfg().codeExpireSeconds() * 1000L;
        pendingCodes.put(code, player.getUniqueId());

        String botUsername = plugin.cfg().botUsername();
        player.sendMessage(plugin.lang().pget("join.need-link-code",
                "%code%", code, "%bot%", "@" + botUsername));

        scheduleReminder(player.getUniqueId());
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
            p.sendMessage(plugin.lang().pget("join.need-link-reminder",
                    "%code%", s.linkCode, "%seconds%", String.valueOf(remaining)));
        }, interval * 20L, interval * 20L);
    }

    public void handleLinkAttempt(String code, long telegramId, String tgUsername, long chatId) {
        long now = System.currentTimeMillis();

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

            boolean codeValid = uuid != null && session != null && session.state == AuthState.AWAITING_LINK
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
            if (plugin.database().findByUuid(uuid).isPresent()) {
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
                authenticate(p, session, "link.success-ingame");

                int cooldownSeconds = plugin.cfg().crackedIpCooldownSeconds();
                String ip = ipOf(p);
                if (cooldownSeconds > 0 && ip != null) {
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {

                        plugin.database().revokeTrustedIpIfUsedByOtherAccount(uuid, ip);
                        plugin.database().recordConfirmedIp(uuid, ip);
                    });
                }
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

    private void beginConfirmFlow(Player player, PlayerSession session, LinkedAccount account) {
        session.state = AuthState.AWAITING_CONFIRM;
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
                authenticate(p, session, "confirm.confirmed-ingame");
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
    private void authenticate(Player player, PlayerSession session, String messageKeyOrNull) {
        if (session.state == AuthState.AWAITING_ADMIN_PIN || session.state == AuthState.AUTHENTICATED) return;

        AuthMethod method = session.externalAddon != null ? AuthMethod.EXTERNAL : methodForKey(messageKeyOrNull);
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

    private static AuthMethod methodForKey(String key) {
        if (key == null) return AuthMethod.EXTERNAL;
        return switch (key) {
            case "join.premium-auto-login" -> AuthMethod.PREMIUM;
            case "join.trusted-ip-skip" -> AuthMethod.TRUSTED_IP;
            case "link.success-ingame" -> AuthMethod.TELEGRAM_LINK;
            default -> AuthMethod.TELEGRAM_CONFIRM;
        };
    }

    /**
     * Used by {@code TgAuthAPI#authenticate}. Main thread only.
     *
     * @return true if the player was logged in (or moved on to the admin PIN step)
     */
    public boolean authenticateExternal(Player player, String addonName) {
        PlayerSession session = sessions.get(player.getUniqueId());
        if (session == null) return false;
        if (session.state != AuthState.AWAITING_LINK && session.state != AuthState.AWAITING_CONFIRM) return false;

        session.externalAddon = addonName;
        try {
            authenticate(player, session, null);
        } finally {
            session.externalAddon = null;
        }
        return session.state == AuthState.AUTHENTICATED || session.state == AuthState.AWAITING_ADMIN_PIN;
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

        if (session.premium) {
            UUID uuid = player.getUniqueId();
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> plugin.database().setPremium(uuid, true));
        }

        if (messageKeyOrNull != null) {
            player.sendMessage(plugin.lang().pget(messageKeyOrNull));
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
            authenticate(p, session, "join.premium-auto-login");
        });
    }
}
