package net.millyland.auth.lang;

import net.millyland.auth.TgAuthPlugin;
import net.millyland.auth.util.YamlMerger;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class Lang {

    private final TgAuthPlugin plugin;
    private FileConfiguration messages;
    private FileConfiguration fallback;

    public Lang(TgAuthPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        String code = plugin.cfg().language();
        File langDir = new File(plugin.getDataFolder(), "lang");
        if (!langDir.exists()) {
            langDir.mkdirs();
        }

        syncBundledLangFile("en.yml");
        syncBundledLangFile("ru.yml");

        File target = new File(langDir, code + ".yml");
        if (!target.exists()) {
            plugin.getLogger().warning("Language file lang/" + code + ".yml not found, falling back to en.yml");
            target = new File(langDir, "en.yml");
        }

        this.messages = YamlConfiguration.loadConfiguration(target);

        File fallbackFile = new File(langDir, "en.yml");
        this.fallback = YamlConfiguration.loadConfiguration(fallbackFile);
    }

    private void syncBundledLangFile(String fileName) {
        File out = new File(new File(plugin.getDataFolder(), "lang"), fileName);
        if (!out.exists()) {
            extractDefault(fileName, out);
        } else {
            YamlMerger.mergeMissingKeys(plugin, "lang/" + fileName, out);
        }
    }

    private void extractDefault(String fileName, File out) {
        try (InputStream in = plugin.getResource("lang/" + fileName)) {
            if (in == null) return;
            try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                YamlConfiguration yc = YamlConfiguration.loadConfiguration(reader);
                yc.save(out);
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Could not extract default language file " + fileName + ": " + e.getMessage());
        }
    }

    public String prefix() {
        return color(raw("prefix", ""));
    }

    private String raw(String path, String def) {
        String v = messages.getString(path);
        if (v == null && fallback != null) v = fallback.getString(path);
        return v == null ? def : v;
    }

    public String get(String path) {
        return color(raw(path, path));
    }

    public String get(String path, Object... placeholdersKV) {
        String msg = raw(path, path);
        for (int i = 0; i + 1 < placeholdersKV.length; i += 2) {
            msg = msg.replace(String.valueOf(placeholdersKV[i]), String.valueOf(placeholdersKV[i + 1]));
        }
        return color(msg);
    }

    public String pget(String path, Object... placeholdersKV) {
        return prefix() + get(path, placeholdersKV);
    }

    public String rawGet(String path, Object... placeholdersKV) {
        String msg = raw(path, path);
        for (int i = 0; i + 1 < placeholdersKV.length; i += 2) {
            msg = msg.replace(String.valueOf(placeholdersKV[i]), String.valueOf(placeholdersKV[i + 1]));
        }
        return ChatColor.stripColor(color(msg));
    }

    /** A list message (for example the lines of a dialog), coloured, with placeholders replaced. */
    public java.util.List<String> getList(String path, Object... placeholdersKV) {
        java.util.List<String> lines = messages.getStringList(path);
        if (lines.isEmpty() && fallback != null) lines = fallback.getStringList(path);
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String line : lines) {
            for (int i = 0; i + 1 < placeholdersKV.length; i += 2) {
                line = line.replace(String.valueOf(placeholdersKV[i]), String.valueOf(placeholdersKV[i + 1]));
            }
            out.add(color(line));
        }
        return out;
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}
