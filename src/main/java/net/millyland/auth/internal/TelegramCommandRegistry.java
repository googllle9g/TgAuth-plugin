package net.millyland.auth.internal;

import net.millyland.auth.api.TelegramCommandHandler;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Thread-safe store of addon-registered Telegram commands. */
public class TelegramCommandRegistry {

    /** Commands handled by TgAuth itself that addons may not take over. */
    public static final Set<String> RESERVED = Set.of("start", "admin", "link", "help", "cancel");

    public record Entry(Plugin owner, TelegramCommandHandler handler) {
    }

    private final Map<String, Entry> commands = new ConcurrentHashMap<>();

    public boolean register(Plugin owner, String name, TelegramCommandHandler handler) {
        String n = CommandLine.normalizeName(name);
        if (n == null || RESERVED.contains(n)) return false;
        return commands.putIfAbsent(n, new Entry(owner, handler)) == null;
    }

    public boolean unregister(Plugin owner, String name) {
        String n = CommandLine.normalizeName(name);
        if (n == null) return false;
        Entry e = commands.get(n);
        return e != null && e.owner().equals(owner) && commands.remove(n, e);
    }

    public Entry find(String name) {
        return commands.get(name);
    }

    public int unregisterAll(Plugin owner) {
        int before = commands.size();
        commands.values().removeIf(e -> e.owner().equals(owner));
        return before - commands.size();
    }

    public int size() {
        return commands.size();
    }
}
