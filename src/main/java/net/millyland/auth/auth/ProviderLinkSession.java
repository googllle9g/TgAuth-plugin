package net.millyland.auth.auth;

import net.millyland.auth.api.LinkInstructions;
import net.millyland.auth.api.LinkResult;
import net.millyland.auth.api.LinkSession;
import net.millyland.auth.api.LoginProvider;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

/** The {@link LinkSession} handed to a provider; all real work happens in {@link AuthManager}. */
public final class ProviderLinkSession implements LinkSession {

    private final net.millyland.auth.TgAuthPlugin plugin;
    private final AuthManager manager;
    private final Player player;
    final PlayerSession session;
    final LoginProvider provider;
    volatile boolean active = true;

    ProviderLinkSession(net.millyland.auth.TgAuthPlugin plugin, AuthManager manager, Player player,
                        PlayerSession session, LoginProvider provider) {
        this.plugin = plugin;
        this.manager = manager;
        this.player = player;
        this.session = session;
        this.provider = provider;
    }

    void deactivate() {
        active = false;
    }

    @Override
    public @NotNull Player getPlayer() {
        return player;
    }

    @Override
    public void showInstructions(@NotNull LinkInstructions instructions) {
        if (Bukkit.isPrimaryThread()) {
            manager.showProviderInstructions(this, instructions);
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> manager.showProviderInstructions(this, instructions));
        }
    }

    @Override
    public @NotNull CompletableFuture<LinkResult> complete(@NotNull String externalId, @Nullable String externalName,
                                                           int maxAccounts) {
        CompletableFuture<LinkResult> result = new CompletableFuture<>();
        manager.completeProviderLink(this, externalId, externalName, maxAccounts, result);
        return result;
    }

    @Override
    public boolean isActive() {
        return active && (session.state == AuthState.AWAITING_LINK || session.addingMethod);
    }
}
