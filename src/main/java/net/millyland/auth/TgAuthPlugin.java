package net.millyland.auth;

import net.millyland.auth.admin.AdminPinService;
import net.millyland.auth.api.TgAuthAPI;
import net.millyland.auth.auth.AuthManager;
import net.millyland.auth.internal.AddonCleanupListener;
import net.millyland.auth.internal.TgAuthApiImpl;
import net.millyland.auth.command.TgAuthCommand;
import net.millyland.auth.command.TgCodeCommand;
import net.millyland.auth.config.Config;
import net.millyland.auth.hook.FastLoginHook;
import net.millyland.auth.hook.LuckPermsHook;
import net.millyland.auth.lang.Lang;
import net.millyland.auth.listener.PlayerJoinQuitListener;
import net.millyland.auth.listener.PlayerProtectListener;
import net.millyland.auth.listener.UuidMigrationListener;
import net.millyland.auth.storage.Database;
import net.millyland.auth.telegram.TelegramService;
import net.millyland.auth.update.UpdateChecker;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public class TgAuthPlugin extends JavaPlugin {

    private Config config;
    private Lang lang;
    private Database database;
    private AuthManager authManager;
    private AdminPinService adminPinService;
    private UpdateChecker updateChecker;
    private FastLoginHook fastLoginHook;
    private LuckPermsHook luckPermsHook;
    private TelegramService telegramService;
    private TgAuthApiImpl api;
    private File primaryWorldContainer;

    @Override
    public void onEnable() {
        this.config = new Config(this);
        this.lang = new Lang(this);

        this.database = new Database(this);
        try {
            database.connect();
        } catch (Exception e) {
            getLogger().severe("Could not connect to the database, disabling plugin: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.api = new TgAuthApiImpl(this);
        this.authManager = new AuthManager(this);
        this.adminPinService = new AdminPinService(this);
        this.updateChecker = new UpdateChecker(this);
        this.fastLoginHook = new FastLoginHook(this);
        this.luckPermsHook = new LuckPermsHook(this);

        if (config.botToken() == null || config.botToken().isBlank()
                || config.botToken().equals("PUT_YOUR_BOT_TOKEN_HERE")) {
            getLogger().severe("Telegram bot token is not configured in config.yml! The plugin will not work until you set telegram.bot-token.");
        } else {
            this.telegramService = new TelegramService(this);
            startTelegramWithRetry();
        }

        getServer().getPluginManager().registerEvents(new PlayerJoinQuitListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerProtectListener(this), this);
        getServer().getPluginManager().registerEvents(new UuidMigrationListener(this), this);
        getServer().getPluginManager().registerEvents(new AddonCleanupListener(api.registry()), this);
        getServer().getServicesManager().register(TgAuthAPI.class, api, this, ServicePriority.Normal);

        this.primaryWorldContainer = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0).getWorldFolder();
        if (primaryWorldContainer == null) {
            getLogger().warning("No world was loaded at plugin startup - cracked/premium player data migration "
                    + "will not work until the server is restarted with a world present.");
        }

        TgAuthCommand tgAuthCommand = new TgAuthCommand(this);
        getCommand("tgauth").setExecutor(tgAuthCommand);
        getCommand("tgauth").setTabCompleter(tgAuthCommand);
        getCommand("tgcode").setExecutor(new TgCodeCommand(this));

        if (config.migrateLinkByUsername()) {
            getLogger().info("auth.migrate-link-by-username is enabled - cracked/premium UUID switches for the "
                    + "same username will re-link automatically. This requires FastLogin's own premiumUuid: true "
                    + "AND secondAttemptCracked: true settings to be enabled to work correctly and safely - run "
                    + "/tgauth fastlogin to check them. See config.yml for details.");

            if (config.migrationOverwriteExistingData()) {
                getLogger().info("auth.migration-overwrite-existing-data is enabled (default) - migrations will "
                        + "overwrite any playerdata/advancements/stats already present for the destination UUID. "
                        + "Safe on a fresh server or one where TgAuth/FastLogin were set up from the start. If "
                        + "you added this setup to an ALREADY-RUNNING server with real players who had progress "
                        + "under their own premium UUID before this existed, turn this off in config.yml.");
            }
        }

        updateChecker.start();

        getLogger().info("TgAuth enabled.");
    }

    /**
     * Starts the bot off the main thread and keeps retrying (every 30 s) if Telegram can't be
     * reached or rejects the token, logging the real cause instead of just "Error removing old webhook".
     */
    private void startTelegramWithRetry() {
        final TelegramService service = this.telegramService;
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            int attempt = 0;
            while (isEnabled() && service == this.telegramService) {
                attempt++;
                try {
                    service.start();
                    getLogger().info("Telegram bot started.");
                    return;
                } catch (Throwable e) {
                    Throwable root = e;
                    while (root.getCause() != null && root.getCause() != root) root = root.getCause();
                    getLogger().severe("Failed to start Telegram bot (attempt " + attempt + "): " + e.getMessage()
                            + " | cause: " + root.getClass().getSimpleName() + ": " + root.getMessage()
                            + " | Check telegram.bot-token, that api.telegram.org is reachable from this server "
                            + "(firewall/DNS/proxy), and that no other program is polling this bot. Retrying in 30 s.");
                }
                try {
                    Thread.sleep(30_000L);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        });
    }

    @Override
    public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
        if (updateChecker != null) {
            updateChecker.stop();
        }
        if (telegramService != null) {
            try {
                telegramService.onClosing();
            } catch (Throwable ignored) {
            }
        }
        if (database != null) {
            database.close();
        }
    }

    public Config cfg() {
        return config;
    }

    public Lang lang() {
        return lang;
    }

    public Database database() {
        return database;
    }

    public AuthManager authManager() {
        return authManager;
    }

    public AdminPinService adminPin() {
        return adminPinService;
    }

    public UpdateChecker updateChecker() {
        return updateChecker;
    }

    public FastLoginHook fastLoginHook() {
        return fastLoginHook;
    }

    public LuckPermsHook luckPermsHook() {
        return luckPermsHook;
    }

    /** Internal implementation of the public {@link TgAuthAPI}. */
    public TgAuthApiImpl api() {
        return api;
    }

    public TelegramService telegram() {
        return telegramService;
    }

    public File primaryWorldContainer() {
        return primaryWorldContainer;
    }
}
