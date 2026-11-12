package net.millyland.auth.proxy;

import net.millyland.auth.TgAuthPlugin;
import net.millyland.auth.api.ProxyConfig;
import net.millyland.auth.api.ProxyProvider;
import org.bukkit.plugin.Plugin;

import java.net.Authenticator;
import java.net.InetAddress;
import java.net.PasswordAuthentication;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/**
 * Decides which proxy TgAuth uses: the one in config.yml if {@code proxy.enabled}, otherwise the first
 * addon-supplied {@link ProxyProvider} that returns one.
 */
public final class ProxyManager {

    private static final Pattern PROVIDER_ID = Pattern.compile("[a-z0-9_\\-]{1,24}");

    /** The proxy in use and where it came from ("config" or a provider id). */
    public record Resolved(ProxyConfig proxy, String source, ProxyProvider provider) {
    }

    private record Entry(Plugin owner, ProxyProvider provider) {
    }

    private final TgAuthPlugin plugin;
    private final List<Entry> providers = new CopyOnWriteArrayList<>();
    private volatile boolean warnedBadConfig;

    public ProxyManager(TgAuthPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean register(Plugin owner, ProxyProvider provider) {
        String id = provider.getId();
        if (id == null || !PROVIDER_ID.matcher(id).matches()) {
            plugin.getLogger().warning(owner.getName() + " tried to register a proxy provider with the invalid id \""
                    + id + "\" (use a-z, 0-9, _ and -, 1-24 chars).");
            return false;
        }
        synchronized (providers) {
            for (Entry e : providers) {
                if (e.provider().getId().equals(id)) return false;
            }
            providers.add(new Entry(owner, provider));
        }
        plugin.getLogger().info("Proxy provider \"" + id + "\" registered by " + owner.getName() + ".");
        return true;
    }

    public boolean unregister(Plugin owner, String id) {
        return providers.removeIf(e -> e.owner().equals(owner) && e.provider().getId().equals(id));
    }

    /** @return true if anything was removed */
    public boolean cleanup(Plugin disabled) {
        return providers.removeIf(e -> e.owner().equals(disabled));
    }

    /** The proxy to use right now, or null for a direct connection. */
    public Resolved resolve() {
        ProxyConfig fromConfig = plugin.cfg().configuredProxy();
        if (fromConfig != null) return new Resolved(fromConfig, "config", null);

        if (plugin.cfg().proxyEnabled() && !warnedBadConfig) {
            warnedBadConfig = true;
            plugin.getLogger().warning("proxy.enabled is true but proxy.type / host / port in config.yml are not valid - "
                    + "ignoring them.");
        }
        for (Entry e : providers) {
            try {
                ProxyConfig p = e.provider().getProxy();
                if (p != null) return new Resolved(p, e.provider().getId(), e.provider());
            } catch (Throwable t) {
                plugin.getLogger().warning("Proxy provider " + e.provider().getId() + " failed in getProxy(): " + t);
            }
        }
        return null;
    }

    /** Same proxy (ignoring where it came from)? */
    public static boolean same(Resolved a, Resolved b) {
        if (a == null || b == null) return a == b;
        return a.proxy().equals(b.proxy());
    }

    /** Tells the provider that supplied this proxy that it did not work. */
    public void reportFailure(Resolved used, Throwable error) {
        if (used == null || used.provider() == null) return;
        try {
            used.provider().onFailure(used.proxy(), error);
        } catch (Throwable t) {
            plugin.getLogger().warning("Proxy provider " + used.source() + " failed in onFailure(): " + t);
        }
    }

    public int providerCount() {
        return providers.size();
    }

    // ---- credentials -----------------------------------------------------------------------

    /**
     * Makes the JVM answer authentication requests of THIS proxy (host + port) with its credentials and leaves
     * every other request to whatever authenticator was installed before. No-op without credentials.
     */
    public static void useCredentialsFor(ProxyConfig proxy) {
        ScopedAuthenticator.set(proxy != null && proxy.hasCredentials() ? proxy : null);
    }

    /** Puts the previous default authenticator back (plugin disable). */
    public static void releaseCredentials() {
        ScopedAuthenticator.uninstall();
    }

    private static final class ScopedAuthenticator extends Authenticator {

        private static ScopedAuthenticator installed;

        private final Authenticator previous;
        private volatile ProxyConfig proxy;

        private ScopedAuthenticator(Authenticator previous) {
            this.previous = previous;
        }

        static synchronized void set(ProxyConfig proxy) {
            if (proxy == null) {
                if (installed != null) installed.proxy = null;
                return;
            }
            if (installed == null || Authenticator.getDefault() != installed) {
                installed = new ScopedAuthenticator(Authenticator.getDefault());
                Authenticator.setDefault(installed);
            }
            installed.proxy = proxy;
        }

        static synchronized void uninstall() {
            if (installed != null && Authenticator.getDefault() == installed) {
                Authenticator.setDefault(installed.previous);
            }
            installed = null;
        }

        private boolean isOurProxy(ProxyConfig p) {
            if (p == null || getRequestingPort() != p.port()) return false;
            String host = getRequestingHost();
            if (host != null && host.equalsIgnoreCase(p.host())) return true;
            InetAddress site = getRequestingSite();
            return site != null && (p.host().equalsIgnoreCase(site.getHostAddress())
                    || p.host().equalsIgnoreCase(site.getHostName()));
        }

        @Override
        protected PasswordAuthentication getPasswordAuthentication() {
            ProxyConfig p = proxy;
            if (isOurProxy(p)) {
                String pass = p.password() == null ? "" : p.password();
                return new PasswordAuthentication(p.username(), pass.toCharArray());
            }
            if (previous != null) {
                return Authenticator.requestPasswordAuthentication(previous, getRequestingHost(), getRequestingSite(),
                        getRequestingPort(), getRequestingProtocol(), getRequestingPrompt(),
                        getRequestingScheme(), getRequestingURL(), getRequestorType());
            }
            return null;
        }
    }
}
