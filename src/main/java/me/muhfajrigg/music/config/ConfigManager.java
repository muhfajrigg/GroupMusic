package me.muhfajrigg.music.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Loads configuration files (config, sources, youtube, ai, messages).
 * Missing keys are automatically populated from the internal jar defaults.
 */
public final class ConfigManager {

    private final JavaPlugin plugin;

    private volatile YamlConfiguration general;
    private volatile YamlConfiguration sources;
    private volatile YamlConfiguration youtube;
    private volatile YamlConfiguration ai;
    private volatile YamlConfiguration messages;

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public synchronized void load() {
        plugin.getDataFolder().mkdirs();
        migrateLegacy();
        general = read("config.yml");
        sources = read("sources.yml");
        youtube = read("youtube.yml");
        ai = read("ai.yml");

        // Save Indonesian messages translation resource if missing
        File idFile = new File(plugin.getDataFolder(), "messages_id.yml");
        if (!idFile.exists()) {
            try {
                plugin.saveResource("messages_id.yml", false);
            } catch (IllegalArgumentException ignored) {
            }
        }

        String locale = general.getString("locale", "en").trim().toLowerCase(Locale.ROOT);
        String msgFile = locale.equals("id") || locale.equals("in") ? "messages_id.yml" : "messages.yml";
        messages = read(msgFile);
    }

    public YamlConfiguration general() {
        return general;
    }

    public YamlConfiguration sources() {
        return sources;
    }

    public YamlConfiguration youtube() {
        return youtube;
    }

    public YamlConfiguration ai() {
        return ai;
    }

    public YamlConfiguration messages() {
        return messages;
    }

    /** Saves youtube.yml to disk (used when new OAuth token is received). */
    public synchronized void saveYoutube() {
        try {
            youtube.save(new File(plugin.getDataFolder(), "youtube.yml"));
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save youtube.yml: " + e.getMessage());
        }
    }

    private YamlConfiguration read(String name) {
        File file = new File(plugin.getDataFolder(), name);
        if (!file.exists()) {
            plugin.saveResource(name, false);
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        try (InputStream in = plugin.getResource(name)) {
            if (in != null) {
                cfg.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
            }
        } catch (IOException ignored) {
        }
        return cfg;
    }

    // ------------------------------------------------------------ Migration

    /**
     * Migrates legacy v0.1 single config.yml into separate configuration files.
     */
    private void migrateLegacy() {
        File dir = plugin.getDataFolder();
        File gf = new File(dir, "config.yml");
        if (!gf.exists()) {
            return;
        }
        YamlConfiguration old = YamlConfiguration.loadConfiguration(gf);
        boolean legacy = old.contains("max-queue") || old.contains("max-track-minutes")
                || old.contains("idle-timeout-seconds") || old.contains("allowed-url-hosts")
                || old.contains("allow-any-url") || old.contains("soundcloud-fallback")
                || old.isConfigurationSection("youtube") || old.isConfigurationSection("spotify");
        if (!legacy) {
            return;
        }
        try {
            Files.copy(gf.toPath(), new File(dir, "config.yml.old").toPath(), StandardCopyOption.REPLACE_EXISTING);

            File yf = new File(dir, "youtube.yml");
            if (!yf.exists()) {
                plugin.saveResource("youtube.yml", false);
                YamlConfiguration y = YamlConfiguration.loadConfiguration(yf);
                copy(old, "youtube.clients", y, "clients");
                copy(old, "youtube.remote-cipher.url", y, "remote-cipher.url");
                copy(old, "youtube.remote-cipher.password", y, "remote-cipher.password");
                copy(old, "youtube.oauth", y, "oauth");
                copy(old, "youtube.oauth-refresh-token", y, "oauth-refresh-token");
                y.save(yf);
            }

            File sf = new File(dir, "sources.yml");
            if (!sf.exists()) {
                plugin.saveResource("sources.yml", false);
                YamlConfiguration s = YamlConfiguration.loadConfiguration(sf);
                copy(old, "allow-any-url", s, "links.allow-any-url");
                copy(old, "soundcloud-fallback", s, "search.soundcloud-fallback");
                copy(old, "spotify.client-id", s, "spotify.client-id");
                copy(old, "spotify.client-secret", s, "spotify.client-secret");
                copy(old, "spotify.country-code", s, "spotify.country-code");
                copy(old, "spotify.mirror-providers", s, "spotify.mirror-providers");

                Set<String> known = new HashSet<>();
                known.addAll(s.getStringList("links.direct-hosts"));
                known.addAll(s.getStringList("links.lookup-hosts"));
                List<String> direct = new ArrayList<>(s.getStringList("links.direct-hosts"));
                for (String h : old.getStringList("allowed-url-hosts")) {
                    String n = h.trim().toLowerCase(Locale.ROOT);
                    if (!n.isEmpty() && !known.contains(n)) {
                        direct.add(n);
                    }
                }
                s.set("links.direct-hosts", direct);
                s.save(sf);
            }

            plugin.saveResource("config.yml", true);
            YamlConfiguration g = YamlConfiguration.loadConfiguration(gf);
            copy(old, "max-queue", g, "limits.max-queue");
            copy(old, "max-track-minutes", g, "limits.max-track-minutes");
            copy(old, "idle-timeout-seconds", g, "limits.idle-timeout-seconds");
            copy(old, "bossbar.enabled", g, "bossbar.enabled");
            g.save(gf);

            plugin.getLogger().info("Migrated legacy configuration to config.yml, sources.yml, and youtube.yml. "
                    + "Backup saved as config.yml.old");
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to migrate legacy configuration: " + e.getMessage());
        }
    }

    private static void copy(YamlConfiguration from, String fromPath, YamlConfiguration to, String toPath) {
        if (from.contains(fromPath)) {
            to.set(toPath, from.get(fromPath));
        }
    }
}
