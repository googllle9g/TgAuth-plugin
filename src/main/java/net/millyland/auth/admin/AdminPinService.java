package net.millyland.auth.admin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import net.millyland.auth.TgAuthPlugin;
import net.millyland.auth.storage.AdminPinRecord;
import net.millyland.auth.storage.LinkedAccount;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PIN protection for the Telegram admin panel: PIN storage (salted PBKDF2), unlocked-session
 * tracking, brute-force lockout and the alerts that fire when a lockout is triggered.
 *
 * <p>The PIN is only ever kept as a salted hash. Verification of one admin's attempts is
 * serialized so parallel guesses can't slip past the attempt counter.
 */
public class AdminPinService {

    private static final int PBKDF2_ITERATIONS = 120_000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;
    private static final String PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256";

    private final TgAuthPlugin plugin;
    private final SecureRandom random = new SecureRandom();

    /** The Telegram bot's own PIN namespace; addon panels use their provider id (for example "discord"). */
    public static final String TELEGRAM = "telegram";

    /** "namespace:subject" -> epoch millis until which the panel stays unlocked (sliding idle timeout). */
    private final Map<String, Long> unlockedUntil = new ConcurrentHashMap<>();
    private final Map<String, Throttle> throttles = new ConcurrentHashMap<>();

    private static String key(String namespace, String subject) {
        return namespace + ":" + subject;
    }

    private static String tg(long telegramId) {
        return String.valueOf(telegramId);
    }

    private static final class Throttle {
        int failed;
        long lastFailureAt;
        long lockedUntil;
        /** Number of lockouts in a row so far; drives the escalating lockout duration. */
        int level;
    }

    public enum Status { OK, WRONG, LOCKED, NO_PIN }

    public record VerifyResult(Status status, int attemptsLeft, long lockSeconds) {
    }

    /** A freshly hashed PIN that has not been saved yet (used for the "repeat to confirm" step). */
    public record PinDraft(byte[] salt, byte[] hash) {
    }

    public AdminPinService(TgAuthPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean enabled() {
        return plugin.cfg().adminPinEnabled();
    }

    // ------------------------------------------------------------------ PIN storage

    public boolean hasPin(long telegramId) {
        return hasPin(TELEGRAM, tg(telegramId));
    }

    public boolean hasPin(String namespace, String subject) {
        return record(namespace, subject).isPresent();
    }

    private Optional<AdminPinRecord> record(String namespace, String subject) {
        if (namespace.equals(TELEGRAM)) {
            try {
                return plugin.database().findAdminPin(Long.parseLong(subject));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
        return plugin.database().findExtPin(namespace, subject);
    }

    public boolean validFormat(String pin) {
        if (pin == null) return false;
        int len = pin.length();
        if (len < plugin.cfg().adminPinMinLength() || len > plugin.cfg().adminPinMaxLength()) return false;
        for (int i = 0; i < len; i++) {
            char c = pin.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    public PinDraft draft(String pin) {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        return new PinDraft(salt, hash(pin, salt, PBKDF2_ITERATIONS));
    }

    public boolean matches(PinDraft draft, String pin) {
        return MessageDigest.isEqual(hash(pin, draft.salt(), PBKDF2_ITERATIONS), draft.hash());
    }

    public boolean savePin(long telegramId, PinDraft draft) {
        return savePin(TELEGRAM, tg(telegramId), draft);
    }

    public boolean savePin(String namespace, String subject, PinDraft draft) {
        String salt = Base64.getEncoder().encodeToString(draft.salt());
        String hash = Base64.getEncoder().encodeToString(draft.hash());
        boolean ok;
        if (namespace.equals(TELEGRAM)) {
            ok = plugin.database().saveAdminPin(Long.parseLong(subject), salt, hash, PBKDF2_ITERATIONS);
        } else {
            ok = plugin.database().saveExtPin(namespace, subject, salt, hash, PBKDF2_ITERATIONS);
        }
        if (ok) {
            throttles.remove(key(namespace, subject));
        }
        return ok;
    }

    /** Removes the PIN (the admin will be asked to set a new one) and drops any unlocked session. */
    public boolean resetPin(long telegramId) {
        return resetPin(TELEGRAM, tg(telegramId));
    }

    public boolean resetPin(String namespace, String subject) {
        boolean existed = namespace.equals(TELEGRAM)
                ? plugin.database().deleteAdminPin(Long.parseLong(subject))
                : plugin.database().deleteExtPin(namespace, subject);
        unlockedUntil.remove(key(namespace, subject));
        throttles.remove(key(namespace, subject));
        return existed;
    }

    private byte[] hash(String pin, byte[] salt, int iterations) {
        char[] chars = pin.toCharArray();
        PBEKeySpec spec = new PBEKeySpec(chars, salt, iterations, HASH_BITS);
        try {
            return SecretKeyFactory.getInstance(PBKDF2_ALGORITHM).generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 is not available in this JVM", e);
        } finally {
            spec.clearPassword();
            java.util.Arrays.fill(chars, '\0');
        }
    }

    // ------------------------------------------------------------------ unlocked sessions

    /** True if the admin's panel is unlocked; a valid session is extended (sliding idle timeout). */
    public boolean isUnlocked(long telegramId) {
        return isUnlocked(TELEGRAM, tg(telegramId));
    }

    public boolean isUnlocked(String namespace, String subject) {
        String k = key(namespace, subject);
        Long until = unlockedUntil.get(k);
        if (until == null) return false;
        long now = System.currentTimeMillis();
        if (until < now) {
            unlockedUntil.remove(k, until);
            return false;
        }
        unlockedUntil.put(k, now + plugin.cfg().adminPinSessionMinutes() * 60_000L);
        return true;
    }

    public void unlock(long telegramId) {
        unlock(TELEGRAM, tg(telegramId));
    }

    public void unlock(String namespace, String subject) {
        unlockedUntil.put(key(namespace, subject),
                System.currentTimeMillis() + plugin.cfg().adminPinSessionMinutes() * 60_000L);
    }

    public void lock(long telegramId) {
        lock(TELEGRAM, tg(telegramId));
    }

    public void lock(String namespace, String subject) {
        unlockedUntil.remove(key(namespace, subject));
    }

    // ------------------------------------------------------------------ verification / brute-force lockout

    /** Seconds left of an active lockout for this admin, or 0 if entry isn't locked. */
    public long lockRemainingSeconds(long telegramId) {
        return lockRemainingSeconds(TELEGRAM, tg(telegramId));
    }

    public long lockRemainingSeconds(String namespace, String subject) {
        Throttle t = throttles.get(key(namespace, subject));
        if (t == null) return 0;
        synchronized (t) {
            long left = t.lockedUntil - System.currentTimeMillis();
            return left > 0 ? left / 1000L + 1 : 0;
        }
    }

    public VerifyResult verify(long telegramId, String pin) {
        return verify(TELEGRAM, tg(telegramId), pin);
    }

    public VerifyResult verify(String namespace, String subject, String pin) {
        Optional<AdminPinRecord> record = record(namespace, subject);
        if (record.isEmpty()) {
            return new VerifyResult(Status.NO_PIN, 0, 0);
        }

        int maxAttempts = plugin.cfg().adminPinMaxAttempts();
        long baseLockoutMs = plugin.cfg().adminPinLockoutSeconds() * 1000L;
        long levelResetMs = plugin.cfg().adminPinLockoutLevelResetMinutes() * 60_000L;
        long lockoutSeconds = 0;

        Throttle t = throttles.computeIfAbsent(key(namespace, subject), k -> new Throttle());
        VerifyResult result;
        int failedAtLockout = 0;

        synchronized (t) {
            long now = System.currentTimeMillis();

            if (t.lockedUntil > now) {
                return new VerifyResult(Status.LOCKED, 0, (t.lockedUntil - now) / 1000L + 1);
            }
            // Old failures stop counting once a full base lockout period has passed without a new one.
            if (t.failed > 0 && now - t.lastFailureAt > baseLockoutMs) {
                t.failed = 0;
            }
            // The escalation level falls back to the first step after a long quiet period.
            if (t.level > 0 && now - t.lockedUntil > levelResetMs) {
                t.level = 0;
            }

            if (matchesRecord(record.get(), pin, key(namespace, subject))) {
                t.failed = 0;
                t.lockedUntil = 0;
                t.level = 0;
                return new VerifyResult(Status.OK, maxAttempts, 0);
            }

            t.failed++;
            t.lastFailureAt = now;
            if (t.failed >= maxAttempts) {
                failedAtLockout = t.failed;
                t.failed = 0;
                lockoutSeconds = LockoutPolicy.lockoutSeconds(
                        plugin.cfg().adminPinLockoutSeconds(),
                        plugin.cfg().adminPinLockoutGrowth(),
                        plugin.cfg().adminPinLockoutMaxSeconds(),
                        t.level);
                t.level = Math.min(t.level + 1, 64);
                t.lockedUntil = now + lockoutSeconds * 1000L;
                result = new VerifyResult(Status.LOCKED, 0, lockoutSeconds);
            } else {
                result = new VerifyResult(Status.WRONG, maxAttempts - t.failed, 0);
            }
        }

        if (result.status() == Status.LOCKED) {
            onBruteForce(namespace, subject, failedAtLockout, lockoutSeconds);
        }
        return result;
    }

    private boolean matchesRecord(AdminPinRecord record, String pin, String owner) {
        try {
            byte[] salt = Base64.getDecoder().decode(record.salt());
            byte[] expected = Base64.getDecoder().decode(record.hash());
            return MessageDigest.isEqual(hash(pin, salt, record.iterations()), expected);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Stored admin PIN for " + owner
                    + " is corrupted - reset it with /tgauth pinreset: " + e.getMessage());
            return false;
        }
    }

    /** "5m", "1h 20m", "45s" - units come from the language file. */
    public String formatDuration(long seconds) {
        return LockoutPolicy.format(seconds,
                plugin.lang().rawGet("pin.unit-hour"),
                plugin.lang().rawGet("pin.unit-minute"),
                plugin.lang().rawGet("pin.unit-second"));
    }

    // ------------------------------------------------------------------ alerts

    private void onBruteForce(String namespace, String subject, int attempts, long lockoutSeconds) {
        // The game accounts behind this PIN: linked to the Telegram ID, or to the external account (Discord, ...)
        List<UUID> uuids = new ArrayList<>();
        List<String> names = new ArrayList<>();
        if (namespace.equals(TELEGRAM)) {
            for (LinkedAccount a : plugin.database().findAllByTelegramId(Long.parseLong(subject))) {
                uuids.add(a.uuid());
                names.add(a.username());
            }
        } else {
            for (var l : plugin.database().findAllExternal(namespace, subject)) {
                uuids.add(l.uuid());
                names.add(l.username());
            }
        }
        String label = namespace.equals(TELEGRAM) ? "Telegram ID " + subject : namespace + " ID " + subject;
        String name = names.isEmpty() ? label : String.join(", ", names);

        plugin.getLogger().warning("Admin panel PIN brute-force detected for " + name + " (" + label + "): "
                + attempts + " wrong attempts in a row, PIN entry locked for " + lockoutSeconds + "s.");

        String attemptsText = String.valueOf(attempts);
        String timeText = formatDuration(lockoutSeconds);

        Bukkit.getScheduler().runTask(plugin, () -> {
            List<Player> victims = new ArrayList<>();
            for (UUID u : uuids) {
                Player online = Bukkit.getPlayer(u);
                if (online != null && online.isOnline()) victims.add(online);
            }

            if (!victims.isEmpty()) {
                for (Player victim : victims) alertVictimInGame(victim, attemptsText, timeText);
            } else {
                String telegramText = plugin.lang().rawGet("pin.alert-telegram",
                        "%player%", name, "%attempts%", attemptsText, "%time%", timeText);
                String chatText = plugin.lang().pget("pin.alert-admins-chat",
                        "%player%", name, "%attempts%", attemptsText, "%time%", timeText);
                broadcastToAdmins(telegramText, chatText);
            }
        });
    }

    private void alertVictimInGame(Player victim, String attempts, String time) {
        victim.sendMessage(plugin.lang().pget("pin.alert-victim-chat",
                "%attempts%", attempts, "%time%", time));

        Component title = LegacyComponentSerializer.legacySection()
                .deserialize(plugin.lang().get("pin.alert-victim-title"));
        Component subtitle = LegacyComponentSerializer.legacySection()
                .deserialize(plugin.lang().get("pin.alert-victim-subtitle"));
        victim.showTitle(Title.title(title, subtitle,
                Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(5), Duration.ofMillis(500))));

        startSiren(victim);
    }

    /** Two-tone alarm played at the player's own position; runs for admin-pin.siren-seconds. */
    private void startSiren(Player victim) {
        int sirenSeconds = plugin.cfg().adminPinSirenSeconds();
        if (sirenSeconds <= 0) return;

        final int period = 8;
        final int totalTicks = sirenSeconds * 20;
        final int[] elapsed = {0};
        final UUID uuid = victim.getUniqueId();

        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null || !p.isOnline() || elapsed[0] >= totalTicks) {
                task.cancel();
                return;
            }
            boolean high = (elapsed[0] / period) % 2 == 0;
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, SoundCategory.MASTER, 1.0f, high ? 1.7f : 0.8f);
            elapsed[0] += period;
        }, 0L, period);
    }

    /**
     * Tells every admin: a chat line for each online player with tgauth.admin, and a Telegram
     * message to everyone in telegram.admin-ids plus the linked Telegram of each online admin.
     * Must be called on the main thread; Telegram I/O is moved off it.
     *
     * @param inGameText chat line for online admins, or null to skip the in-game part
     */
    public void broadcastToAdmins(String telegramText, String inGameText) {
        List<UUID> onlineAdmins = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("tgauth.admin")) {
                onlineAdmins.add(p.getUniqueId());
                if (inGameText != null) {
                    p.sendMessage(inGameText);
                }
            }
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (plugin.telegram() == null) return;
            Set<Long> recipients = new HashSet<>(plugin.cfg().adminTelegramIds());
            for (UUID uuid : onlineAdmins) {
                plugin.database().findByUuid(uuid).ifPresent(acc -> recipients.add(acc.telegramId()));
            }
            for (long id : recipients) {
                plugin.telegram().send(id, telegramText);
            }
        });
    }
}
