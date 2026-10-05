package net.millyland.auth.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.millyland.auth.TgAuthPlugin;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Checks the GitHub repository's latest release and tells admins when a newer version exists.
 *
 * <p>Threading: the HTTP request is made with {@link HttpClient#sendAsync} (connect timeout 5s,
 * request timeout 10s), so neither the server thread nor a Bukkit worker is ever blocked on the
 * network. The response is processed on the HttpClient's own thread; the only things that
 * touch the Bukkit API (sending chat messages, running command callbacks) are handed back to the
 * main thread via the scheduler.
 *
 * <p>Failures (offline server, GitHub down, rate limit) are intentionally silent: they are logged
 * at FINE level only, so a flaky connection never spams the console.
 */
public class UpdateChecker {

    private static final String REPOSITORY = "googllle9g/TgAuth-plugin";
    private static final String LATEST_RELEASE_API = "https://api.github.com/repos/" + REPOSITORY + "/releases/latest";
    private static final String RELEASES_PAGE = "https://github.com/" + REPOSITORY + "/releases";
    private static final long INITIAL_DELAY_TICKS = 20L * 15;

    /** Release tags end up in chat, so accept only plain version-looking characters. */
    private static final Pattern SAFE_TAG = Pattern.compile("[A-Za-z0-9._+\\-]{1,40}");

    public enum Outcome { UPDATE_AVAILABLE, UP_TO_DATE, NO_RELEASES, FAILED }

    public record ReleaseInfo(String version, String url) {
    }

    public record CheckResult(Outcome outcome, ReleaseInfo release) {
    }

    private final TgAuthPlugin plugin;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Newest release that is newer than the running version; null if we're up to date / unknown. */
    private volatile ReleaseInfo latest;
    private BukkitTask periodicTask;

    public UpdateChecker(TgAuthPlugin plugin) {
        this.plugin = plugin;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public void start() {
        if (!plugin.cfg().updateCheckerEnabled()) return;
        long periodTicks = plugin.cfg().updateCheckerIntervalHours() * 60L * 60L * 20L;
        // The scheduled task only fires off a non-blocking request, it never waits for it.
        periodicTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> check(null),
                INITIAL_DELAY_TICKS, periodTicks);
    }

    public void stop() {
        if (periodicTask != null) {
            periodicTask.cancel();
            periodicTask = null;
        }
    }

    public String currentVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    /**
     * Starts a check without blocking the caller. The optional callback is invoked later on the
     * main server thread.
     */
    public void check(Consumer<CheckResult> callback) {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(LATEST_RELEASE_API))
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .header("User-Agent", "TgAuth-UpdateChecker/" + currentVersion())
                    .GET()
                    .build();
        } catch (RuntimeException e) {
            handle(new CheckResult(Outcome.FAILED, null), callback);
            return;
        }

        http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete((response, error) -> {
            CheckResult result;
            try {
                if (error != null) {
                    plugin.getLogger().fine("Update check failed: " + error);
                    result = new CheckResult(Outcome.FAILED, null);
                } else {
                    result = interpret(response);
                }
            } catch (Throwable t) {
                plugin.getLogger().fine("Update check failed: " + t);
                result = new CheckResult(Outcome.FAILED, null);
            }
            handle(result, callback);
        });
    }

    private CheckResult interpret(HttpResponse<String> response) throws IOException {
        int status = response.statusCode();
        if (status == 404) {
            return new CheckResult(Outcome.NO_RELEASES, null);
        }
        if (status != 200) {
            plugin.getLogger().fine("Update check: GitHub answered HTTP " + status);
            return new CheckResult(Outcome.FAILED, null);
        }

        JsonNode root = mapper.readTree(response.body());
        if (root.path("draft").asBoolean(false) || root.path("prerelease").asBoolean(false)) {
            return new CheckResult(Outcome.NO_RELEASES, null);
        }

        String tag = root.path("tag_name").asText("").trim();
        if (!SAFE_TAG.matcher(tag).matches()) {
            return new CheckResult(Outcome.FAILED, null);
        }
        String version = (tag.startsWith("v") || tag.startsWith("V")) ? tag.substring(1) : tag;

        String url = root.path("html_url").asText("");
        if (!url.startsWith("https://github.com/")) {
            url = RELEASES_PAGE;
        }

        if (Versions.isNewer(version, currentVersion())) {
            return new CheckResult(Outcome.UPDATE_AVAILABLE, new ReleaseInfo(version, url));
        }
        return new CheckResult(Outcome.UP_TO_DATE, null);
    }

    private void handle(CheckResult result, Consumer<CheckResult> callback) {
        if (result.outcome() == Outcome.UPDATE_AVAILABLE) {
            ReleaseInfo release = result.release();
            ReleaseInfo previous = this.latest;
            this.latest = release;

            // Announce each new version once, not on every periodic check.
            if (previous == null || !previous.version().equals(release.version())) {
                plugin.getLogger().info("A new version is available: " + release.version()
                        + " (running " + currentVersion() + ") - " + release.url());
                runOnMainThread(() -> announceToOnlineAdmins(release));
            }
        } else if (result.outcome() == Outcome.UP_TO_DATE) {
            this.latest = null;
        }

        if (callback != null) {
            runOnMainThread(() -> callback.accept(result));
        }
    }

    private void announceToOnlineAdmins(ReleaseInfo release) {
        if (!plugin.cfg().updateCheckerNotifyAdmins()) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            // Players still going through Telegram login get the message once they authenticate.
            if (player.hasPermission("tgauth.admin") && plugin.authManager().isAuthenticated(player.getUniqueId())) {
                sendUpdateMessage(player, release);
            }
        }
    }

    /** Called when a player has just finished TgAuth login: tells admins about a pending update. */
    public void notifyAdminOnLogin(Player player) {
        if (!plugin.cfg().updateCheckerNotifyAdmins()) return;
        if (!Bukkit.isPrimaryThread()) {
            runOnMainThread(() -> notifyAdminOnLogin(player));
            return;
        }
        ReleaseInfo release = this.latest;
        if (release == null || !player.isOnline() || !player.hasPermission("tgauth.admin")) return;
        sendUpdateMessage(player, release);
    }

    /** Main thread only. */
    public void sendUpdateMessage(CommandSender target, ReleaseInfo release) {
        String text = plugin.lang().pget("update.available",
                "%latest%", release.version(), "%current%", currentVersion());
        Component message = LegacyComponentSerializer.legacySection().deserialize(text)
                .clickEvent(ClickEvent.openUrl(release.url()))
                .hoverEvent(HoverEvent.showText(Component.text(release.url())));
        target.sendMessage(message);
    }

    private void runOnMainThread(Runnable task) {
        if (!plugin.isEnabled()) return;
        try {
            Bukkit.getScheduler().runTask(plugin, task);
        } catch (IllegalPluginAccessException ignored) {
            // Plugin was disabled between the check and now.
        }
    }
}
