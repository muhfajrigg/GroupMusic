package me.muhfajrigg.music.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import me.muhfajrigg.music.GroupMusic;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI assistant helper for song identification ("title + artist").
 * Guessed tracks are verified and resolved via audio search providers (e.g. YouTube).
 * Untrusted web page metadata and player queries are treated strictly as data.
 */
public final class AiAssistant {

    public record Song(String title, String artist) {
        public String query() {
            return artist == null || artist.isBlank() ? title : title + " " + artist;
        }
    }

    private final GroupMusic plugin;
    private final AiClient client;
    private final Map<UUID, Long> lastSuggest = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastSearch = new ConcurrentHashMap<>();

    public AiAssistant(GroupMusic plugin, AiClient client) {
        this.plugin = plugin;
        this.client = client;
    }

    private YamlConfiguration cfg() {
        return plugin.cfg().ai();
    }

    public boolean available() {
        return client.available();
    }

    /** "off", "fallback", or "always". */
    public String linkMode() {
        String m = cfg().getString("link-cleanup", "always").trim().toLowerCase(Locale.ROOT);
        return m.equals("off") || m.equals("fallback") ? m : "always";
    }

    /** AI mode for regular search queries: "always" (AI-first), "fallback", or "off". */
    public String searchMode() {
        String m = cfg().getString("search.mode", "always").trim().toLowerCase(Locale.ROOT);
        return m.equals("off") || m.equals("fallback") ? m : "always";
    }

    /** @return true if player can use AI search now based on cooldown */
    public boolean trySearchSlot(UUID player) {
        long cooldown = Math.max(0, cfg().getLong("search.cooldown-seconds", 2)) * 1000L;
        long now = System.currentTimeMillis();
        Long last = lastSearch.get(player);
        if (last != null && now - last < cooldown) {
            return false;
        }
        lastSearch.put(player, now);
        return true;
    }

    public boolean retryEmptySearch() {
        return cfg().getBoolean("retry-empty-search", true);
    }

    public boolean suggestEnabled() {
        return cfg().getBoolean("suggest.enabled", true);
    }

    /** @return 0 if allowed (cooldown starts), otherwise remaining seconds */
    public long tryAcquire(UUID player) {
        long cooldown = Math.max(0, cfg().getLong("suggest.cooldown-seconds", 15)) * 1000L;
        long now = System.currentTimeMillis();
        Long last = lastSuggest.get(player);
        if (last != null && now - last < cooldown) {
            return (cooldown - (now - last) + 999) / 1000;
        }
        lastSuggest.put(player, now);
        return 0;
    }

    // ---------------------------------------------------------------- Tasks

    public CompletableFuture<List<Song>> suggest(String request) {
        int count = Math.max(1, Math.min(10, cfg().getInt("suggest.count", 3)));
        String user = fill(prompt("prompts.suggest"), "request", clip(request, 200), "count", String.valueOf(count));
        return ask(user, count);
    }

    public CompletableFuture<List<Song>> search(String query) {
        int count = Math.max(1, Math.min(5, cfg().getInt("search.count", 1)));
        String user = fill(prompt("prompts.search"), "query", clip(query, 200), "count", String.valueOf(count));
        return ask(user, count);
    }

    public CompletableFuture<List<Song>> identify(String url, String title, String description) {
        String user = fill(prompt("prompts.link"),
                "url", clip(url, 300), "title", clip(title, 200), "description", clip(description, 300));
        return ask(user, 1);
    }

    public CompletableFuture<List<Song>> correct(String query) {
        String user = fill(prompt("prompts.correct"), "query", clip(query, 200));
        return ask(user, 1);
    }

    private CompletableFuture<List<Song>> ask(String user, int max) {
        return client.complete(prompt("prompts.system"), user).thenApply(text -> parse(text, max));
    }

    // -------------------------------------------------------------- Helpers

    private String prompt(String path) {
        String p = cfg().getString(path, "");
        return p == null ? "" : p;
    }

    private static String fill(String template, String... kv) {
        String out = template;
        for (int i = 0; i + 1 < kv.length; i += 2) {
            out = out.replace("{" + kv[i] + "}", kv[i + 1]);
        }
        return out;
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("\\p{Cntrl}", " ").trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    /** Extracts first JSON object from model output (handles optional markdown code blocks). */
    static List<Song> parse(String text, int max) {
        List<Song> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        int a = text.indexOf('{');
        int b = text.lastIndexOf('}');
        if (a < 0 || b <= a) {
            return out;
        }
        try {
            JsonObject root = JsonParser.parseString(text.substring(a, b + 1)).getAsJsonObject();
            JsonElement songs = root.get("songs");
            if (songs == null || !songs.isJsonArray()) {
                return out;
            }
            JsonArray arr = songs.getAsJsonArray();
            for (JsonElement el : arr) {
                if (out.size() >= max) {
                    break;
                }
                if (!el.isJsonObject()) {
                    continue;
                }
                String title = field(el.getAsJsonObject(), "title");
                if (title.isEmpty()) {
                    continue;
                }
                out.add(new Song(title, field(el.getAsJsonObject(), "artist")));
            }
        } catch (JsonParseException | IllegalStateException e) {
            return new ArrayList<>();
        }
        return out;
    }

    private static String field(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? clip(e.getAsString(), 120) : "";
    }
}
