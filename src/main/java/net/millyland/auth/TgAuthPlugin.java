package net.millyland.auth;

import net.millyland.auth.admin.AdminPinService;
import net.millyland.auth.api.TgAuthAPI;
import net.millyland.auth.auth.AuthManager;
import net.millyland.auth.internal.AddonCleanupListener;
import net.millyland.auth.internal.TgAuthApiImpl;
import net.millyland.auth.command.TgAuthCommand;
import net.millyland.auth.command.TgChooseCommand;
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
    private net.millyland.auth.admin.AdminActions adminActions;
    private UpdateChecker updateChecker;
    private FastLoginHook fastLoginHook;
    private LuckPermsHook luckPermsHook;
    private volatile TelegramService telegramService;
    private volatile boolean telegramWanted;
    private net.millyland.auth.proxy.ProxyManager proxyManager;
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

        this.proxyManager = new net.millyland.auth.proxy.ProxyManager(this);
        this.api = new TgAuthApiImpl(this);
        this.authManager = new AuthManager(this);
        this.adminPinService = new AdminPinService(this);
        this.adminActions = new net.millyland.auth.admin.AdminActions(this);
        this.updateChecker = new UpdateChecker(this);
        this.fastLoginHook = new FastLoginHook(this);
        this.luckPermsHook = new LuckPermsHook(this);

        if (config.botToken() == null || config.botToken().isBlank()
                || config.botToken().equals("PUT_YOUR_BOT_TOKEN_HERE")) {
            if (config.telegramAuthEnabled()) {
                getLogger().severe("Telegram bot token is not configured in config.yml! The plugin will not work until you set telegram.bot-token.");
            } else {
                getLogger().info("No Telegram bot token set: running without the bot (telegram.auth-enabled is false).");
            }
        } else {
            this.telegramWanted = true;
            // Started on the first tick so that an addon providing a proxy (ProxyProvider) has registered by then.
            getServer().getScheduler().runTask(this, this::startTelegram);
        }

        getServer().getPluginManager().registerEvents(new PlayerJoinQuitListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerProtectListener(this), this);
        getServer().getPluginManager().registerEvents(new UuidMigrationListener(this), this);
        getServer().getPluginManager().registerEvents(new AddonCleanupListener(api), this);
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
        getCommand("tgchoose").setExecutor(new TgChooseCommand(this));

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

        if (!config.telegramAuthEnabled()) {
            // Runs on the first tick, after every plugin had the chance to register its login provider.
            getServer().getScheduler().runTask(this, () -> {
                if (api.providerCount() == 0) {
                    getLogger().severe("telegram.auth-enabled is false but NO login provider is registered. "
                            + "Telegram login is off, so nobody (except players with tgauth.bypass) can log in. "
                            + "Install an addon that adds a login method (for example DiscordTgAuth-addon) or set "
                            + "telegram.auth-enabled back to true.");
                } else {
                    getLogger().info("Telegram login is off; login providers: " + api.providerCount() + ".");
                }
            });
        }

        getLogger().info("TgAuth enabled.");
    }

    private net.millyland.auth.command.AddAuthMethodCommand addMethodCommand;

    /**
     * Registers {@code /add-auth-method} while at least one login provider exists and removes it when the last
     * one is gone. Safe to call from any thread.
     */
    public void refreshAddMethodCommand() {
        if (!Bukkit.isPrimaryThread()) {
            if (isEnabled()) Bukkit.getScheduler().runTask(this, this::refreshAddMethodCommand);
            return;
        }
        boolean want = api.providerCount() > 0 && isEnabled();
        var map = Bukkit.getCommandMap();
        if (want && addMethodCommand == null) {
            addMethodCommand = new net.millyland.auth.command.AddAuthMethodCommand(this);
            map.register("tgauth", addMethodCommand);
        } else if (!want && addMethodCommand != null) {
            addMethodCommand.unregister(map);
            map.getKnownCommands().values().removeIf(c -> c == addMethodCommand);
            addMethodCommand = null;
        } else {
            return;
        }
        syncCommandTree();
    }

    /** Makes the server and the connected clients pick up the added/removed command. */
    private void syncCommandTree() {
        try {
            Bukkit.getServer().getClass().getMethod("syncCommands").invoke(Bukkit.getServer());
        } catch (Throwable ignored) {
            // not available on this server: the command still runs through the Bukkit command map
        }
        for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) p.updateCommands();
    }

    /** Main thread: resolves the proxy and starts a new bot instance through it. */
    private void startTelegram() {
        if (!isEnabled()) return;
        net.millyland.auth.proxy.ProxyManager.Resolved proxy = proxyManager.resolve();
        net.millyland.auth.proxy.ProxyManager.useCredentialsFor(proxy == null ? null : proxy.proxy());
        if (proxy != null) {
            getLogger().info("The Telegram bot connects through the proxy " + proxy.proxy()
                    + " (from " + (proxy.provider() == null ? "config.yml" : "the addon provider \"" + proxy.source() + "\"") + ").");
        }
        this.telegramProxy = proxy;
        this.telegramService = new TelegramService(this, proxy == null ? null : proxy.proxy());
        startTelegramWithRetry(proxy);
    }

    private volatile net.millyland.auth.proxy.ProxyManager.Resolved telegramProxy;

    /**
     * Re-resolves the proxy (a provider appeared, went away or changed its answer) and reconnects the bot through the
     * new one if it differs from the proxy in use. Safe to call from any thread.
     */
    public void refreshProxy() {
        if (!Bukkit.isPrimaryThread()) {
            if (isEnabled()) Bukkit.getScheduler().runTask(this, this::refreshProxy);
            return;
        }
        if (!telegramWanted || telegramService == null) return; // not started yet: the first start resolves anyway
        net.millyland.auth.proxy.ProxyManager.Resolved now = proxyManager.resolve();
        if (net.millyland.auth.proxy.ProxyManager.same(now, telegramProxy)) return;
        getLogger().info("The proxy changed - reconnecting the Telegram bot"
                + (now == null ? " directly." : " through " + now.proxy() + "."));
        final TelegramService old = telegramService;
        getServer().getScheduler().runTaskAsynchronously(this, old::shutdown);
        startTelegram();
    }

    public net.millyland.auth.proxy.ProxyManager proxies() {
        return proxyManager;
    }

    /** The proxy TgAuth would use right now (null = direct). */
    public net.millyland.auth.proxy.ProxyManager.Resolved activeProxy() {
        return proxyManager.resolve();
    }

    /**
     * Starts the bot off the main thread and keeps retrying (every 30 s) if Telegram can't be
     * reached or rejects the token, logging the real cause instead of just "Error removing old webhook".
     * A proxy provider is told about each failure so it can rotate to another proxy.
     */
    private void startTelegramWithRetry(net.millyland.auth.proxy.ProxyManager.Resolved proxy) {
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
                    proxyManager.reportFailure(proxy, e);
                }
                if (service != this.telegramService) return; // a provider switched the proxy in onFailure
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
        if (addMethodCommand != null) {
            try {
                var map = Bukkit.getCommandMap();
                addMethodCommand.unregister(map);
                map.getKnownCommands().values().removeIf(c -> c == addMethodCommand);
            } catch (Throwable ignored) {
            }
            addMethodCommand = null;
        }
        getServer().getServicesManager().unregisterAll(this);
        if (updateChecker != null) {
            updateChecker.stop();
        }
        if (telegramService != null) {
            try {
                telegramService.shutdown();
            } catch (Throwable ignored) {
            }
        }
        net.millyland.auth.proxy.ProxyManager.releaseCredentials();
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

    public net.millyland.auth.admin.AdminActions adminActions() {
        return adminActions;
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
