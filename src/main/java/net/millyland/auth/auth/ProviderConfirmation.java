package net.millyland.auth.auth;

import net.millyland.auth.api.ConfirmationRequest;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicBoolean;

/** The {@link ConfirmationRequest} handed to a provider; the answer is applied by {@link AuthManager}. */
public final class ProviderConfirmation implements ConfirmationRequest {

    private final AuthManager manager;
    private final Player player;
    private final String ip;
    private final long expiresAt;
    final AtomicBoolean answered = new AtomicBoolean();

    ProviderConfirmation(AuthManager manager, Player player, @Nullable String ip, long expiresAt) {
        this.manager = manager;
        this.player = player;
        this.ip = ip;
        this.expiresAt = expiresAt;
    }

    @Override
    public @NotNull Player getPlayer() {
        return player;
    }

    @Override
    public @Nullable String getIp() {
        return ip;
    }

    @Override
    public int getSecondsLeft() {
        return (int) Math.max(0, (expiresAt - System.currentTimeMillis()) / 1000L);
    }

    @Override
    public void approve() {
        manager.resolveProviderConfirmation(this, true);
    }

    @Override
    public void deny() {
        manager.resolveProviderConfirmation(this, false);
    }
}
