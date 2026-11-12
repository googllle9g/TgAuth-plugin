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
    private final java.util.Map<String, ProviderEntry> providers = new java.util.concurrent.ConcurrentHashMap<>();

    private record ProviderEntry(Plugin owner, LoginProvider provider) {
    }

    private final AdminApiImpl adminApi;

    @Override
    public @NotNull net.millyland.auth.api.AdminApi admin() {
        return adminApi;
    }

    public TgAuthApiImpl(TgAuthPlugin plugin) {
        this.plugin = plugin;
        this.adminApi = new AdminApiImpl(plugin);
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

    @Override
    public @NotNull CompletableFuture<List<LinkedAccountInfo>> getLinkedAccountsByTelegramId(long telegramId) {
        return CompletableFuture.supplyAsync(() -> plugin.database().findAllByTelegramId(telegramId).stream()
                .map(TgAuthApiImpl::toInfo).toList(), async);
    }

    // ---- login providers ---------------------------------------------------------------------

    private static final java.util.regex.Pattern PROVIDER_ID = java.util.regex.Pattern.compile("[a-z0-9_]{1,24}");

    @Override
    public boolean registerLoginProvider(@NotNull Plugin owner, @NotNull LoginProvider provider) {
        String id = provider.getId();
        if (!PROVIDER_ID.matcher(id).matches() || id.equals("telegram")) {
            plugin.getLogger().warning(owner.getName() + " tried to register a login provider with the invalid id \""
                    + id + "\" (use a-z, 0-9, _ , 1-24 chars, not \"telegram\").");
            return false;
        }
        boolean added = providers.putIfAbsent(id, new ProviderEntry(owner, provider)) == null;
        if (added) {
            plugin.getLogger().info("Login provider \"" + id + "\" registered by " + owner.getName() + ".");
            plugin.refreshAddMethodCommand();
        }
        return added;
    }

    @Override
    public boolean unregisterLoginProvider(@NotNull Plugin owner, @NotNull String providerId) {
        ProviderEntry entry = providers.get(providerId);
        boolean removed = entry != null && entry.owner().equals(owner) && providers.remove(providerId, entry);
        if (removed) plugin.refreshAddMethodCommand();
        return removed;
    }

    @Override
    public boolean registerProxyProvider(@NotNull Plugin owner, @NotNull net.millyland.auth.api.ProxyProvider provider) {
        boolean added = plugin.proxies().register(owner, provider);
        if (added) plugin.refreshProxy();
        return added;
    }

    @Override
    public boolean unregisterProxyProvider(@NotNull Plugin owner, @NotNull String providerId) {
        boolean removed = plugin.proxies().unregister(owner, providerId);
        if (removed) plugin.refreshProxy();
        return removed;
    }

    @Override
    public void refreshProxy() {
        plugin.refreshProxy();
    }

    @Override
    public @NotNull Optional<String> getActiveProxy() {
        var used = plugin.activeProxy();
        return used == null ? Optional.empty() : Optional.of(used.proxy().describe());
    }

    @Override
    public @NotNull Optional<net.millyland.auth.api.ProxyConfig> getProxyConfig() {
        var used = plugin.activeProxy();
        return used == null ? Optional.empty() : Optional.of(used.proxy());
    }

    /** The registered provider with this id, or null. */
    public LoginProvider provider(String id) {
        ProviderEntry entry = id == null ? null : providers.get(id);
        return entry == null ? null : entry.provider();
    }

    /** How many login providers are registered (available or not). */
    public int providerCount() {
        return providers.size();
    }

    /** Providers that can handle a new player right now, in registration-independent (alphabetical) order. */
    public List<LoginProvider> availableProviders() {
        List<LoginProvider> out = new java.util.ArrayList<>();
        for (ProviderEntry entry : providers.values()) {
            try {
                if (entry.provider().isAvailable()) out.add(entry.provider());
            } catch (Throwable t) {
                plugin.getLogger().warning("Login provider " + entry.provider().getId() + " failed in isAvailable(): " + t);
            }
        }
        out.sort(java.util.Comparator.comparing(LoginProvider::getId));
        return out;
    }

    private static ExternalLinkInfo toExternalInfo(net.millyland.auth.storage.ExternalLink l) {
        return new ExternalLinkInfo(l.provider(), l.uuid(), l.username(), l.externalId(),
                l.externalName() == null || l.externalName().isBlank() ? null : l.externalName(),
                l.linkedAt(), l.premium());
    }

    @Override
    public @NotNull CompletableFuture<List<ExternalLinkInfo>> getExternalLinks(@NotNull UUID minecraftUuid) {
        return CompletableFuture.supplyAsync(() -> plugin.database().findExternalsByUuid(minecraftUuid).stream()
                .map(TgAuthApiImpl::toExternalInfo).toList(), async);
    }

    @Override
    public @NotNull CompletableFuture<List<ExternalLinkInfo>> getExternalLinks(@NotNull String providerId,
                                                                                @NotNull String externalId) {
        return CompletableFuture.supplyAsync(() -> plugin.database().findAllExternal(providerId, externalId)
                .stream().map(TgAuthApiImpl::toExternalInfo).toList(), async);
    }

    @Override
    public @NotNull CompletableFuture<Boolean> unlinkExternal(@NotNull UUID minecraftUuid) {
        return CompletableFuture.supplyAsync(() -> plugin.database().unlinkExternal(minecraftUuid), async);
    }

    @Override
    public @NotNull CompletableFuture<Boolean> unlinkExternal(@NotNull UUID minecraftUuid, @NotNull String providerId) {
        return CompletableFuture.supplyAsync(
                () -> plugin.database().unlinkExternal(minecraftUuid, providerId), async);
    }

    /** Called when any plugin is disabled: drops everything it registered. */
    public void cleanup(Plugin disabled) {
        registry.unregisterAll(disabled);
        if (providers.values().removeIf(e -> e.owner().equals(disabled))) plugin.refreshAddMethodCommand();
        if (plugin.proxies().cleanup(disabled)) plugin.refreshProxy();
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
