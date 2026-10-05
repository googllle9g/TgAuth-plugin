package net.millyland.auth.internal;

import net.millyland.auth.TgAuthPlugin;
import net.millyland.auth.api.*;
import net.millyland.auth.api.event.PlayerLinkedEvent;
import net.millyland.auth.api.event.PlayerUnlinkedEvent;
import net.millyland.auth.auth.AuthState;
import net.millyland.auth.auth.PlayerSession;
import net.millyland.auth.storage.LinkedAccount;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public class TgAuthApiImpl implements TgAuthAPI {

    private final TgAuthPlugin plugin;
    private final TelegramCommandRegistry registry = new TelegramCommandRegistry();
    private final Executor async;

    public TgAuthApiImpl(TgAuthPlugin plugin) {
        this.plugin = plugin;
        this.async = task -> {
            if (plugin.isEnabled()) {
                Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
            } else {
                task.run();
            }
        };
    }

    public TelegramCommandRegistry registry() {
        return registry;
    }

    public static LinkedAccountInfo toInfo(LinkedAccount a) {
        return new LinkedAccountInfo(a.uuid(), a.username(), a.telegramId(),
                a.telegramUsername() == null || a.telegramUsername().isBlank() ? null : a.telegramUsername(),
                a.linkedAt(), a.premium());
    }

    // ---- state -----------------------------------------------------------------------------

    @Override public int getApiVersion() { return API_VERSION; }

    @Override
    public @NotNull String getPluginVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public @NotNull AuthStatus getStatus(@NotNull Player player) {
        PlayerSession s = plugin.authManager().session(player.getUniqueId());
        if (s == null) return AuthStatus.AUTHENTICATED;
        return switch (s.state) {
            case AWAITING_LINK -> AuthStatus.AWAITING_LINK;
            case AWAITING_CONFIRM -> AuthStatus.AWAITING_CONFIRM;
            case AWAITING_ADMIN_PIN -> AuthStatus.AWAITING_ADMIN_PIN;
            case AUTHENTICATED -> AuthStatus.AUTHENTICATED;
        };
    }

    @Override
    public boolean isAuthenticated(@NotNull Player player) {
        return plugin.authManager().isAuthenticated(player.getUniqueId());
    }

    @Override
    public @NotNull Optional<AuthMethod> getAuthMethod(@NotNull Player player) {
        PlayerSession s = plugin.authManager().session(player.getUniqueId());
        if (s == null || s.state != AuthState.AUTHENTICATED) return Optional.empty();
        return Optional.ofNullable(s.method);
    }

    // ---- accounts --------------------------------------------------------------------------

    @Override
    public @NotNull CompletableFuture<Optional<LinkedAccountInfo>> getLinkedAccount(@NotNull UUID minecraftUuid) {
        return CompletableFuture.supplyAsync(
                () -> plugin.database().findByUuid(minecraftUuid).map(TgAuthApiImpl::toInfo), async);
    }

    @Override
    public @NotNull CompletableFuture<Optional<LinkedAccountInfo>> getLinkedAccountByTelegramId(long telegramId) {
        return CompletableFuture.supplyAsync(
                () -> plugin.database().findByTelegramId(telegramId).map(TgAuthApiImpl::toInfo), async);
    }

    // ---- authentication --------------------------------------------------------------------

    @Override
    public @NotNull CompletableFuture<List<LinkedAccountInfo>> getLinkedAccountsByTelegramId(long telegramId) {
        return CompletableFuture.supplyAsync(() -> plugin.database().findAllByTelegramId(telegramId).stream()
                .map(TgAuthApiImpl::toInfo).toList(), async);
    }

    @Override
    public boolean authenticate(@NotNull Plugin addon, @NotNull Player player) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("TgAuthAPI.authenticate must be called on the main thread");
        }
        if (!plugin.cfg().apiAllowExternalAuthentication()) {
            plugin.getLogger().warning(addon.getName() + " tried to authenticate " + player.getName()
                    + " but api.allow-external-authentication is false.");
            return false;
        }
        if (!player.isOnline()) return false;
        return plugin.authManager().authenticateExternal(player, addon.getName());
    }

    // ---- telegram --------------------------------------------------------------------------

    @Override
    public boolean registerTelegramCommand(@NotNull Plugin addon, @NotNull String name,
                                           @NotNull TelegramCommandHandler handler) {
        return registry.register(addon, name, handler);
    }

    @Override
    public boolean unregisterTelegramCommand(@NotNull Plugin addon, @NotNull String name) {
        return registry.unregister(addon, name);
    }

    @Override
    public boolean isTelegramAvailable() {
        return plugin.telegram() != null;
    }

    @Override
    public void sendTelegramMessage(long chatId, @NotNull String text) {
        if (plugin.telegram() != null) plugin.telegram().send(chatId, text);
    }

    @Override
    public @NotNull CompletableFuture<Boolean> sendTelegramMessage(@NotNull UUID minecraftUuid, @NotNull String text) {
        return CompletableFuture.supplyAsync(() -> {
            if (plugin.telegram() == null) return false;
            Optional<LinkedAccount> acc = plugin.database().findByUuid(minecraftUuid);
            if (acc.isEmpty()) return false;
            // In Telegram a private chat id equals the user id.
            plugin.telegram().send(acc.get().telegramId(), text);
            return true;
        }, async);
    }

    /**
     * Called by the Telegram service for every text message in a private chat. Runs the matching
     * addon handler asynchronously.
     *
     * @return true if the message was an addon command and has been taken care of
     */
    public boolean dispatchTelegramCommand(long chatId, long telegramId, String username, String text) {
        CommandLine line = CommandLine.parse(text);
        if (line == null) return false;
        TelegramCommandRegistry.Entry entry = registry.find(line.name());
        if (entry == null) return false;

        async.execute(() -> {
            try {
                List<LinkedAccountInfo> linked = plugin.database().findAllByTelegramId(telegramId).stream()
                        .map(TgAuthApiImpl::toInfo).toList();
                boolean admin = plugin.telegram() != null && plugin.telegram().isAdmin(telegramId);
                var ctx = new TelegramContextImpl(line, chatId, telegramId, username,
                        linked, admin, plugin.telegram());
                entry.handler().handle(ctx);
            } catch (Throwable t) {
                plugin.getLogger().warning("Telegram command /" + line.name() + " from addon "
                        + entry.owner().getName() + " failed: " + t);
            }
        });
        return true;
    }

    // ---- events ----------------------------------------------------------------------------

    public void fireLinked(LinkedAccount account) {
        fire(new PlayerLinkedEvent(!Bukkit.isPrimaryThread(), toInfo(account)));
    }

    public void fireUnlinked(LinkedAccount account) {
        fire(new PlayerUnlinkedEvent(!Bukkit.isPrimaryThread(), toInfo(account)));
    }

    private void fire(Event event) {
        try {
            Bukkit.getPluginManager().callEvent(event);
        } catch (Throwable t) {
            plugin.getLogger().warning("An addon failed while handling " + event.getEventName() + ": " + t);
        }
    }
}
