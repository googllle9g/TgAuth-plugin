package net.millyland.auth.internal;

import net.millyland.auth.TgAuthPlugin;
import net.millyland.auth.admin.AdminActions;
import net.millyland.auth.api.AdminApi;
import net.millyland.auth.api.AdminPlayerInfo;
import net.millyland.auth.api.AdminResult;
import net.millyland.auth.api.AdminResult.Outcome;
import net.millyland.auth.storage.ExternalLink;
import net.millyland.auth.storage.LinkedAccount;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public final class AdminApiImpl implements AdminApi {

    private final PinApi pinApi;

    @Override
    public @NotNull net.millyland.auth.api.AdminPinApi pin() {
        return pinApi;
    }

    private final TgAuthPlugin plugin;

    public AdminApiImpl(TgAuthPlugin plugin) {
        this.plugin = plugin;
        this.pinApi = new PinApi();
    }

    private AdminActions actions() {
        return plugin.adminActions();
    }

    private void async(Runnable task) {
        if (plugin.isEnabled()) Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
        else task.run();
    }

    // ---- lookups -----------------------------------------------------------------------------

    @Override
    public @NotNull CompletableFuture<Boolean> isAdmin(@NotNull UUID minecraftUuid) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        async(() -> {
            try {
                // adminPermissionReason blocks on the main thread, so it must run off it (this is async)
                String name = plugin.database().findByUuid(minecraftUuid).map(LinkedAccount::username)
                        .orElse(minecraftUuid.toString());
                future.complete(actions().adminPermissionReason(List.of(minecraftUuid), name) == null);
            } catch (Throwable t) {
                future.complete(false);
            }
        });
        return future;
    }

    @Override
    public @NotNull CompletableFuture<Optional<AdminPlayerInfo>> findPlayer(@NotNull String name) {
        CompletableFuture<Optional<AdminPlayerInfo>> future = new CompletableFuture<>();
        async(() -> {
            Optional<LinkedAccount> tg = plugin.database().findByUsername(name);
            UUID uuid = tg.map(LinkedAccount::uuid).orElseGet(() ->
                    plugin.database().findExternalsByUsername(name).stream().findFirst()
                            .map(ExternalLink::uuid).orElse(null));
            if (uuid == null) {
                future.complete(Optional.empty());
                return;
            }
            build(uuid).whenComplete((info, error) -> future.complete(error == null ? info : Optional.empty()));
        });
        return future;
    }

    @Override
    public @NotNull CompletableFuture<Optional<AdminPlayerInfo>> getPlayer(@NotNull UUID minecraftUuid) {
        CompletableFuture<Optional<AdminPlayerInfo>> future = new CompletableFuture<>();
        async(() -> build(minecraftUuid).whenComplete((info, error) -> future.complete(error == null ? info : Optional.empty())));
        return future;
    }

    /** Reads the links off the main thread, then the online and ban state on it. */
    private CompletableFuture<Optional<AdminPlayerInfo>> build(UUID uuid) {
        Optional<LinkedAccount> tg = plugin.database().findByUuid(uuid);
        List<ExternalLink> ext = plugin.database().findExternalsByUuid(uuid);
        if (tg.isEmpty() && ext.isEmpty()) return CompletableFuture.completedFuture(Optional.empty());

        String name = tg.map(LinkedAccount::username).orElseGet(() -> ext.get(0).username());
        CompletableFuture<Optional<AdminPlayerInfo>> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                boolean online = Bukkit.getPlayer(uuid) != null;
                boolean banned = Bukkit.getBanList(BanList.Type.NAME).isBanned(name);
                List<net.millyland.auth.api.ExternalLinkInfo> links = new ArrayList<>();
                for (ExternalLink l : ext) {
                    links.add(new net.millyland.auth.api.ExternalLinkInfo(l.provider(), l.uuid(), l.username(),
                            l.externalId(), l.externalName() == null || l.externalName().isBlank() ? null : l.externalName(),
                            l.linkedAt(), l.premium()));
                }
                future.complete(Optional.of(new AdminPlayerInfo(uuid, name, online, banned,
                        tg.map(TgAuthApiImpl::toInfo).orElse(null), links)));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    // ---- actions -----------------------------------------------------------------------------

    /** Resolves the stored name of a linked player, runs the action, logs it and tells the Telegram admins. */
    private CompletableFuture<AdminResult> act(UUID uuid, String actor, String verb,
                                               Function<String, CompletableFuture<AdminResult>> action,
                                               Function<AdminResult, Boolean> notifyWhen) {
        CompletableFuture<AdminResult> future = new CompletableFuture<>();
        async(() -> {
            String name = plugin.database().findByUuid(uuid).map(LinkedAccount::username)
                    .or(() -> plugin.database().findExternalsByUuid(uuid).stream().findFirst().map(ExternalLink::username))
                    .orElse(null);
            if (name == null) {
                future.complete(AdminResult.of(Outcome.NOT_FOUND));
                return;
            }
            action.apply(name).whenComplete((result, error) -> {
                AdminResult r = error == null ? result : AdminResult.of(Outcome.FAILED);
                if (notifyWhen.apply(r)) {
                    plugin.getLogger().info("[admin] " + actor + " " + verb.replace("%p", name));
                    if (plugin.telegram() != null) {
                        plugin.telegram().notifyAdmins(actor, -1L, verb.replace("%p", name));
                    }
                }
                future.complete(r);
            });
        });
        return future;
    }

    private static String durationText(@Nullable String duration) {
        return duration == null || AdminActions.parseDuration(duration) == AdminActions.PERMANENT
                ? "permanently" : "for " + duration;
    }

    @Override
    public @NotNull CompletableFuture<AdminResult> kick(@NotNull UUID uuid, @NotNull String reason, @NotNull String actor) {
        return act(uuid, actor, "kicked %p: " + reason, name -> actions().kick(uuid, name, reason), AdminResult::isDone);
    }

    @Override
    public @NotNull CompletableFuture<AdminResult> ban(@NotNull UUID uuid, @NotNull String reason,
                                                       @Nullable String duration, @NotNull String actor) {
        return act(uuid, actor, "banned %p " + durationText(duration) + ": " + reason,
                name -> actions().ban(uuid, name, reason, duration), AdminResult::isDone);
    }

    @Override
    public @NotNull CompletableFuture<AdminResult> warn(@NotNull UUID uuid, @NotNull String reason,
                                                        @Nullable String duration, @NotNull String actor) {
        return act(uuid, actor, "warned %p" + (duration == null || AdminActions.parseDuration(duration) == AdminActions.PERMANENT
                        ? "" : " for " + duration) + ": " + reason,
                name -> actions().warn(uuid, name, reason, duration),
                r -> r.isDone() || r.outcome() == Outcome.NOT_ONLINE);
    }

    @Override
    public @NotNull CompletableFuture<AdminResult> unban(@NotNull UUID uuid, @NotNull String actor) {
        return act(uuid, actor, "unbanned %p", name -> actions().unban(name), AdminResult::isDone);
    }

    @Override
    public @NotNull CompletableFuture<AdminResult> clearPremium(@NotNull UUID uuid, @NotNull String actor) {
        return act(uuid, actor, "removed premium from %p", name -> actions().clearPremium(uuid, name), AdminResult::isDone);
    }

    @Override
    public @NotNull CompletableFuture<AdminResult> unlinkTelegram(@NotNull UUID uuid, @NotNull String actor) {
        CompletableFuture<AdminResult> future = new CompletableFuture<>();
        async(() -> {
            Optional<LinkedAccount> acc = plugin.database().findByUuid(uuid);
            if (acc.isEmpty()) {
                future.complete(AdminResult.of(Outcome.NOT_FOUND));
                return;
            }
            boolean ok = plugin.database().unlink(uuid);
            if (ok) {
                plugin.api().fireUnlinked(acc.get());
                plugin.getLogger().info("[admin] " + actor + " unlinked " + acc.get().username() + " from Telegram");
                if (plugin.telegram() != null) {
                    plugin.telegram().notifyAdmins(actor, -1L, "unlinked " + acc.get().username() + " from Telegram");
                }
            }
            future.complete(AdminResult.of(ok ? Outcome.DONE : Outcome.FAILED));
        });
        return future;
    }

    @Override
    public @NotNull CompletableFuture<AdminResult> unlinkProvider(@NotNull UUID uuid, @NotNull String providerId,
                                                                  @NotNull String actor) {
        CompletableFuture<AdminResult> future = new CompletableFuture<>();
        async(() -> {
            Optional<ExternalLink> link = plugin.database().findExternal(uuid, providerId);
            if (link.isEmpty()) {
                future.complete(AdminResult.of(Outcome.NOT_FOUND));
                return;
            }
            boolean ok = plugin.database().unlinkExternal(uuid, providerId);
            if (ok) {
                String text = "unlinked " + link.get().username() + " from " + providerId;
                plugin.getLogger().info("[admin] " + actor + " " + text);
                if (plugin.telegram() != null) plugin.telegram().notifyAdmins(actor, -1L, text);
            }
            future.complete(AdminResult.of(ok ? Outcome.DONE : Outcome.FAILED));
        });
        return future;
    }

    private static final java.util.regex.Pattern NAMESPACE = java.util.regex.Pattern.compile("[a-z0-9_]{1,24}");

    private static void checkNamespace(String namespace) {
        if (!NAMESPACE.matcher(namespace).matches() || namespace.equals(net.millyland.auth.admin.AdminPinService.TELEGRAM)) {
            throw new IllegalArgumentException("Invalid PIN namespace \"" + namespace
                    + "\" (use a-z, 0-9, _ , 1-24 chars, not \"telegram\")");
        }
    }

    private final class PinApi implements net.millyland.auth.api.AdminPinApi {

        private net.millyland.auth.admin.AdminPinService service() {
            return plugin.adminPin();
        }

        @Override public boolean isEnabled() { return service().enabled(); }

        @Override public int minLength() { return plugin.cfg().adminPinMinLength(); }

        @Override public int maxLength() { return plugin.cfg().adminPinMaxLength(); }

        @Override public boolean isValidFormat(@NotNull String pin) { return service().validFormat(pin); }

        @Override
        public @NotNull CompletableFuture<Boolean> hasPin(@NotNull String namespace, @NotNull String subject) {
            checkNamespace(namespace);
            CompletableFuture<Boolean> future = new CompletableFuture<>();
            async(() -> future.complete(service().hasPin(namespace, subject)));
            return future;
        }

        @Override
        public @NotNull CompletableFuture<Boolean> setPin(@NotNull String namespace, @NotNull String subject,
                                                          @NotNull String pin) {
            checkNamespace(namespace);
            CompletableFuture<Boolean> future = new CompletableFuture<>();
            async(() -> {
                try {
                    if (!service().validFormat(pin) || service().hasPin(namespace, subject)) {
                        future.complete(false);
                        return;
                    }
                    boolean ok = service().savePin(namespace, subject, service().draft(pin));
                    if (ok) service().unlock(namespace, subject);
                    future.complete(ok);
                } catch (Throwable t) {
                    future.complete(false);
                }
            });
            return future;
        }

        @Override
        public @NotNull CompletableFuture<Check> verify(@NotNull String namespace, @NotNull String subject,
                                                        @NotNull String pin) {
            checkNamespace(namespace);
            CompletableFuture<Check> future = new CompletableFuture<>();
            async(() -> {
                try {
                    var result = service().verify(namespace, subject, pin);
                    if (result.status() == net.millyland.auth.admin.AdminPinService.Status.OK) {
                        service().unlock(namespace, subject);
                    }
                    future.complete(new Check(Status.valueOf(result.status().name()), result.attemptsLeft(),
                            result.lockSeconds()));
                } catch (Throwable t) {
                    plugin.getLogger().warning("PIN check failed: " + t);
                    future.complete(new Check(Status.WRONG, 0, 0));
                }
            });
            return future;
        }

        @Override
        public boolean isUnlocked(@NotNull String namespace, @NotNull String subject) {
            checkNamespace(namespace);
            return service().isUnlocked(namespace, subject);
        }

        @Override
        public void lock(@NotNull String namespace, @NotNull String subject) {
            checkNamespace(namespace);
            service().lock(namespace, subject);
        }

        @Override
        public @NotNull String formatDuration(long seconds) {
            return service().formatDuration(seconds);
        }
    }
}
