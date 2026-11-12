package net.millyland.auth.hook;

import net.millyland.auth.TgAuthPlugin;
import net.millyland.auth.util.UuidUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

public class FastLoginHook {

    private final TgAuthPlugin plugin;
    private boolean hookRegistered;
    private boolean fastLoginPresent;

    public FastLoginHook(TgAuthPlugin plugin) {
        this.plugin = plugin;
        tryRegister();
    }

    private void tryRegister() {
        if (!plugin.cfg().fastLoginEnabled()) {
            return;
        }

        Plugin fastLogin = Bukkit.getPluginManager().getPlugin("FastLogin");
        if (fastLogin == null || !fastLogin.isEnabled()) {
            plugin.getLogger().info("FastLogin not found - premium detection will use the UUID-version heuristic only.");
            return;
        }
        fastLoginPresent = true;
        checkAutoRegisterSetting(fastLogin);

        String[] ifaceCandidates = {
                "com.github.games647.fastlogin.core.hooks.AuthPlugin",
                "com.github.games647.fastlogin.core.hooking.AuthPlugin",
                "com.github.games647.fastlogin.bukkit.hook.AuthPlugin"
        };

        Class<?> authPluginIface = null;
        for (String fqcn : ifaceCandidates) {
            try {
                authPluginIface = Class.forName(fqcn);
                break;
            } catch (ClassNotFoundException ignored) {

            }
        }

        if (authPluginIface == null) {
            plugin.getLogger().warning("Could not find FastLogin's AuthPlugin interface under any known package name "
                    + "(tried: " + String.join(", ", ifaceCandidates) + "). "
                    + "This usually means your FastLogin build renamed/moved it. "
                    + "TgAuth will keep working, but falls back to the UUID-version heuristic for premium detection.");
            return;
        }

        try {
            Object proxyHook = Proxy.newProxyInstance(
                    authPluginIface.getClassLoader(),
                    new Class<?>[]{authPluginIface},
                    new AuthPluginInvocationHandler()
            );

            Object core = invokeNoArgIfPresent(fastLogin, "getCore");
            if (core == null) core = fastLogin;

            Method setHook = findSingleArgMethod(core.getClass(), "setAuthPluginHook", authPluginIface);
            if (setHook == null && core != fastLogin) {

                setHook = findSingleArgMethod(fastLogin.getClass(), "setAuthPluginHook", authPluginIface);
                if (setHook != null) core = fastLogin;
            }

            if (setHook == null) {
                plugin.getLogger().warning("Found FastLogin's AuthPlugin interface (" + authPluginIface.getName()
                        + ") but no matching setAuthPluginHook(...) method on " + core.getClass().getName()
                        + ". Your FastLogin build likely uses a different registration API. "
                        + "TgAuth will keep working, but falls back to the UUID-version heuristic for premium detection.");
                return;
            }

            setHook.invoke(core, proxyHook);

            hookRegistered = true;
            plugin.getLogger().info("Registered TgAuth as FastLogin's auth-plugin hook ("
                    + authPluginIface.getName() + " via " + core.getClass().getSimpleName() + "#setAuthPluginHook). "
                    + "FastLogin premium verification is now active.");
        } catch (ReflectiveOperationException | RuntimeException e) {
            plugin.getLogger().warning("Could not hook into FastLogin's AuthPlugin API (version mismatch?): " + e);
            plugin.getLogger().warning("TgAuth will keep working, but falls back to the UUID-version heuristic "
                    + "for premium detection instead of a real FastLogin verification. "
                    + "Run /tgauth fastlogin for diagnostics.");
        }
    }

    private Object invokeNoArgIfPresent(Object target, String methodName) {
        try {
            Method m = target.getClass().getMethod(methodName);
            return m.invoke(target);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private Method findSingleArgMethod(Class<?> owner, String name, Class<?> paramType) {
        for (Method m : owner.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == 1
                    && m.getParameterTypes()[0].isAssignableFrom(paramType)) {
                return m;
            }
        }
        return null;
    }

    private void checkAutoRegisterSetting(Plugin fastLogin) {
        if (!(fastLogin instanceof org.bukkit.plugin.java.JavaPlugin javaPlugin)) return;
        try {
            var cfg = javaPlugin.getConfig();
            boolean autoRegister = cfg.getBoolean("autoRegister", false);
            boolean secondAttemptCracked = cfg.getBoolean("secondAttemptCracked", false);
            boolean premiumUuid = cfg.getBoolean("premiumUuid", false);

            if (!autoRegister) {
                plugin.getLogger().warning("FastLogin's own config.yml has autoRegister: false. "
                        + "This means FastLogin will never check a brand-new (never-before-registered) "
                        + "player's premium status automatically - only players TgAuth already knows about. "
                        + "If you turned this off because of password issues with LoginSecurity/AuthMe, that "
                        + "doesn't apply here: TgAuth ignores the generated password completely, it has no "
                        + "concept of passwords at all. Consider setting autoRegister: true in FastLogin's "
                        + "config.yml for reliable premium detection on new players.");
            } else if (!secondAttemptCracked) {
                plugin.getLogger().warning("FastLogin's own config.yml has autoRegister: true but "
                        + "secondAttemptCracked: false. With this combination, a genuinely cracked player "
                        + "using a name FastLogin decides to premium-check gets disconnected ('invalid "
                        + "session') and will keep getting disconnected on every reconnect attempt, since "
                        + "FastLogin doesn't remember the name already failed once. Set "
                        + "secondAttemptCracked: true in FastLogin's config.yml so cracked players can "
                        + "actually join a hybrid server.");
            }

            if (!premiumUuid) {
                plugin.getLogger().warning("FastLogin's own config.yml has premiumUuid: false. Without it, "
                        + "FastLogin does NOT switch a verified-premium player's effective UUID to their real "
                        + "Mojang UUID - they keep the same offline/cracked UUID regardless of verification. "
                        + "TgAuth's auth.migrate-link-by-username only has any effect when a UUID actually "
                        + "changes between a cracked and a premium login for the same name, so set "
                        + "premiumUuid: true in FastLogin's config.yml if you want that feature to do anything.");
            }
        } catch (Exception e) {
            plugin.getLogger().fine("Could not read FastLogin's config settings: " + e);
        }
    }

    public boolean isFastLoginPresent() {
        return fastLoginPresent;
    }

    public boolean isHookRegistered() {
        return hookRegistered;
    }

    public boolean isFastLoginAutoRegisterEnabled() {
        return readFastLoginBoolean("autoRegister");
    }

    public boolean isFastLoginSecondAttemptCrackedEnabled() {
        return readFastLoginBoolean("secondAttemptCracked");
    }

    public boolean isFastLoginPremiumUuidEnabled() {
        return readFastLoginBoolean("premiumUuid");
    }

    private boolean readFastLoginBoolean(String key) {
        Plugin fastLogin = Bukkit.getPluginManager().getPlugin("FastLogin");
        if (!(fastLogin instanceof org.bukkit.plugin.java.JavaPlugin javaPlugin)) return false;
        return javaPlugin.getConfig().getBoolean(key, false);
    }

    public boolean isPremium(Player player) {
        if (plugin.authManager().isFastLoginVerifiedPremium(player.getUniqueId())) {
            return true;
        }
        return UuidUtil.looksPremium(player.getUniqueId());
    }

    private class AuthPluginInvocationHandler implements InvocationHandler {
        @Override
        public Object invoke(Object proxyObj, Method method, Object[] args) {
            try {
                switch (method.getName()) {
                    case "isRegistered": {
                        String playerName = (String) args[0];
                        // "Registered" means linked with ANY method (Telegram or a login provider).
                        return plugin.database().findByUsername(playerName).isPresent()
                                || !plugin.database().findExternalsByUsername(playerName).isEmpty();
                    }
                    case "forceLogin": {
                        Player player = (Player) args[0];
                        plugin.authManager().markFastLoginVerifiedPremium(player);
                        return true;
                    }
                    case "forceRegister": {
                        Player player = (Player) args[0];
                        plugin.authManager().markFastLoginVerifiedPremium(player);
                        return true;
                    }
                    case "toString":
                        return "TgAuthFastLoginHook";
                    case "hashCode":
                        return System.identityHashCode(proxyObj);
                    case "equals":
                        return proxyObj == (args != null && args.length > 0 ? args[0] : null);
                    default:
                        return null;
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Error handling FastLogin hook call " + method.getName() + ": " + e);
                return method.getReturnType() == boolean.class ? Boolean.FALSE : null;
            }
        }
    }
}
