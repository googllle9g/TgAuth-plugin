package net.millyland.auth.config;

import net.millyland.auth.TgAuthPlugin;
import net.millyland.auth.util.YamlMerger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

public class Config {

    private static final String FILE_NAME = "config.yml";

    private final TgAuthPlugin plugin;
    private FileConfiguration cfg;

    public Config(TgAuthPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }

        File configFile = new File(plugin.getDataFolder(), FILE_NAME);
        if (!configFile.exists()) {
            plugin.saveResource(FILE_NAME, false);
        } else {
            YamlMerger.mergeMissingKeys(plugin, FILE_NAME, configFile);
        }

        this.cfg = YamlConfiguration.loadConfiguration(configFile);
    }

    public String language() {
        return cfg.getString("language", "en");
    }

    public String botToken() {
        return cfg.getString("telegram.bot-token", "");
    }

    public String botUsername() {
        return cfg.getString("telegram.bot-username", "");
    }

    public java.util.List<Long> adminTelegramIds() {
        java.util.List<Long> list = new java.util.ArrayList<>();
        for (Object o : cfg.getList("telegram.admin-ids", java.util.List.of())) {
            try {
                list.add(Long.parseLong(String.valueOf(o)));
            } catch (NumberFormatException ignored) {

            }
        }
        return list;
    }

    public boolean adminPanelEnabled() {
        return cfg.getBoolean("telegram.admin-panel-enabled", true);
    }

    public int codeExpireSeconds() {
        return cfg.getInt("auth.code-expire-seconds", 300);
    }

    public int confirmTimeoutSeconds() {
        return cfg.getInt("auth.confirm-timeout-seconds", 90);
    }

    public int authTimeoutSeconds() {
        return cfg.getInt("auth.auth-timeout-seconds", 120);
    }

    public int reminderIntervalSeconds() {
        return cfg.getInt("auth.reminder-interval-seconds", 20);
    }

    public boolean applyBlindness() {
        return cfg.getBoolean("auth.apply-blindness", true);
    }

    public boolean applySlowness() {
        return cfg.getBoolean("auth.apply-slowness", true);
    }

    public boolean migrateLinkByUsername() {
        return cfg.getBoolean("auth.migrate-link-by-username", true);
    }

    public boolean migrationOverwriteExistingData() {
        return cfg.getBoolean("auth.migration-overwrite-existing-data", true);
    }

    public int crackedIpCooldownSeconds() {
        return cfg.getInt("auth.cracked-ip-cooldown-seconds", 0);
    }

    public boolean fastLoginEnabled() {
        return cfg.getBoolean("fastlogin.enabled", true);
    }

    public boolean premiumSkipConfirmation() {
        return cfg.getBoolean("fastlogin.premium-skip-confirmation", true);
    }

    public int premiumCheckWaitSeconds() {
        return cfg.getInt("fastlogin.premium-check-wait-seconds", 4);
    }

    public String storageFile() {
        return cfg.getString("storage.file", "database.db");
    }

    /** "sqlite" (default), "mysql" or "mariadb". */
    public String storageType() {
        return cfg.getString("storage.type", "sqlite");
    }

    public String mysqlHost() {
        return cfg.getString("storage.mysql.host", "localhost");
    }

    public int mysqlPort() {
        return Math.min(65535, Math.max(1, cfg.getInt("storage.mysql.port", 3306)));
    }

    public String mysqlDatabase() {
        return cfg.getString("storage.mysql.database", "tgauth");
    }

    public String mysqlUsername() {
        return cfg.getString("storage.mysql.username", "tgauth");
    }

    public String mysqlPassword() {
        return cfg.getString("storage.mysql.password", "");
    }

    public boolean mysqlUseSsl() {
        return cfg.getBoolean("storage.mysql.use-ssl", false);
    }

    public String mysqlTablePrefix() {
        return cfg.getString("storage.mysql.table-prefix", "tgauth_");
    }

    public int mysqlPoolMaxSize() {
        return Math.min(50, Math.max(1, cfg.getInt("storage.mysql.pool.maximum-pool-size", 10)));
    }

    public int mysqlPoolMinIdle() {
        return Math.min(mysqlPoolMaxSize(), Math.max(1, cfg.getInt("storage.mysql.pool.minimum-idle", 2)));
    }

    public long mysqlConnectionTimeoutMs() {
        return Math.max(1_000L, cfg.getLong("storage.mysql.pool.connection-timeout-ms", 10_000L));
    }

    public long mysqlMaxLifetimeMs() {
        return Math.max(30_000L, cfg.getLong("storage.mysql.pool.max-lifetime-ms", 1_800_000L));
    }

    /** Extra JDBC driver options from storage.mysql.properties (applied on top of the defaults). */
    public Map<String, String> mysqlProperties() {
        Map<String, String> result = new LinkedHashMap<>();
        ConfigurationSection section = cfg.getConfigurationSection("storage.mysql.properties");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                Object value = section.get(key);
                if (value != null) {
                    result.put(key, String.valueOf(value));
                }
            }
        }
        return result;
    }

    public int linkMaxAttempts() {
        return cfg.getInt("security.link-max-attempts", 5);
    }

    public int linkAttemptWindowSeconds() {
        return cfg.getInt("security.link-attempt-window-seconds", 60);
    }

    public int linkLockoutSeconds() {
        return cfg.getInt("security.link-lockout-seconds", 300);
    }

    public int globalLinkMaxAttempts() {
        return cfg.getInt("security.global-link-max-attempts", 20);
    }

    public int globalLinkAttemptWindowSeconds() {
        return cfg.getInt("security.global-link-attempt-window-seconds", 60);
    }

    public int globalLinkLockoutSeconds() {
        return cfg.getInt("security.global-link-lockout-seconds", 120);
    }

    public String kickCommand() {
        return cfg.getString("admin-commands.kick-command", "");
    }

    public String banCommand() {
        return cfg.getString("admin-commands.ban-command", "");
    }

    public String tempbanCommand() {
        return cfg.getString("admin-commands.tempban-command", "");
    }

    public String unbanCommand() {
        return cfg.getString("admin-commands.unban-command", "");
    }

    public String warnCommand() {
        return cfg.getString("admin-commands.warn-command", "");
    }

    public String tempwarnCommand() {
        return cfg.getString("admin-commands.tempwarn-command", "");
    }

    public boolean adminPinEnabled() {
        return cfg.getBoolean("admin-pin.enabled", true);
    }

    public int adminPinMinLength() {
        return Math.max(4, cfg.getInt("admin-pin.min-length", 4));
    }

    public int adminPinMaxLength() {
        return Math.max(adminPinMinLength(), Math.min(32, cfg.getInt("admin-pin.max-length", 12)));
    }

    public int adminPinMaxAttempts() {
        return Math.max(1, cfg.getInt("admin-pin.max-attempts", 3));
    }

    public int adminPinLockoutSeconds() {
        return Math.max(1, cfg.getInt("admin-pin.lockout-seconds", 300));
    }

    public int adminPinSessionMinutes() {
        return Math.max(1, cfg.getInt("admin-pin.session-timeout-minutes", 15));
    }

    public int adminPinSirenSeconds() {
        return Math.max(0, cfg.getInt("admin-pin.siren-seconds", 10));
    }

    /** Each consecutive lockout lasts this many times longer than the previous one (1.0 = no growth). */
    public double adminPinLockoutGrowth() {
        return Math.min(10.0, Math.max(1.0, cfg.getDouble("admin-pin.lockout-growth", 2.0)));
    }

    public int adminPinLockoutMaxSeconds() {
        return Math.max(adminPinLockoutSeconds(), cfg.getInt("admin-pin.lockout-max-seconds", 86400));
    }

    /** Lockout level drops back to the first step after this long without a new lockout. */
    public int adminPinLockoutLevelResetMinutes() {
        return Math.max(1, cfg.getInt("admin-pin.lockout-level-reset-minutes", 1440));
    }

    public boolean adminPinFreezeAdmins() {
        return cfg.getBoolean("admin-pin.freeze-admins-until-pin-set", true);
    }

    /** Seconds an admin may stay frozen waiting to set a PIN before being kicked; 0 = never kick. */
    public int adminPinSetupTimeoutSeconds() {
        return Math.max(0, cfg.getInt("admin-pin.setup-timeout-seconds", 300));
    }

    public boolean updateCheckerEnabled() {
        return cfg.getBoolean("update-checker.enabled", true);
    }

    public int updateCheckerIntervalHours() {
        return Math.max(1, cfg.getInt("update-checker.check-interval-hours", 12));
    }

    public boolean updateCheckerNotifyAdmins() {
        return cfg.getBoolean("update-checker.notify-admins", true);
    }

    /** How many game accounts may be linked to one Telegram account (default 1). */
    public int maxAccountsPerTelegram() {
        return Math.max(1, cfg.getInt("auth.max-accounts-per-telegram", 1));
    }

    public boolean apiAllowExternalAuthentication() {
        return cfg.getBoolean("api.allow-external-authentication", false);
    }
}
