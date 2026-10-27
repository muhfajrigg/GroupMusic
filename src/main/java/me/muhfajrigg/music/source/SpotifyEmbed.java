package me.muhfajrigg.music.source;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses track listings from public Spotify embed pages (open.spotify.com/embed/...)
 * without requiring Spotify API credentials or Spotify Premium.
 */
public final class SpotifyEmbed {

    /** type = "playlist" or "album". */
    public record Ref(String type, String id) {
        public String embedUrl() {
            return "https://open.spotify.com/embed/" + type + "/" + id;
        }
    }

    private static final Pattern REF = Pattern.compile("/(playlist|album)/([A-Za-z0-9]{10,40})");
    private static final Pattern JSON_SCRIPT = Pattern.compile(
            "<script[^>]*type=\"application/json\"[^>]*>(.*?)</script>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private SpotifyEmbed() {
    }

    /** @return parsed Ref from link, or null if not a playlist/album */
    public static Ref parseRef(URI uri) {
        String path = uri.getPath() == null ? "" : uri.getPath();
        Matcher m = REF.matcher(path);
        return m.find() ? new Ref(m.group(1), m.group(2)) : null;
    }

    /** @return search query strings ("title artist"), limited by {@code max} */
    public static List<String> parseQueries(String html, int max) {
        List<String> out = new ArrayList<>();
        Matcher m = JSON_SCRIPT.matcher(html);
        while (m.find() && out.isEmpty()) {
            JsonArray tracks = findTrackList(parseJson(m.group(1)));
            if (tracks == null) {
                continue;
            }
            for (JsonElement e : tracks) {
                if (out.size() >= max) {
                    break;
                }
                if (!e.isJsonObject()) {
                    continue;
                }
                String q = queryOf(e.getAsJsonObject());
                if (q != null) {
                    out.add(q);
                }
            }
        }
        return out;
    }

    private static String queryOf(JsonObject o) {
        String title = clean(str(o, "title"));
        if (title.isEmpty()) {
            return null;
        }
        String artist = clean(str(o, "subtitle"));
        String q = artist.isEmpty() ? title : title + " " + artist;
        return q.length() > 120 ? q.substring(0, 120) : q;
    }

    private static String str(JsonObject o, String key) {
        JsonElement v = o.get(key);
        return v != null && v.isJsonPrimitive() ? v.getAsString() : "";
    }

    private static String clean(String s) {
        return s.replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
    }

    private static JsonElement parseJson(String raw) {
        String s = raw.trim();
        try {
            if (!s.startsWith("{") && !s.startsWith("[")) {
                s = URLDecoder.decode(s, StandardCharsets.UTF_8);
            }
            return JsonParser.parseString(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static JsonArray findTrackList(JsonElement e) {
        if (e == null) {
            return null;
        }
        if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            JsonElement t = o.get("trackList");
            if (t != null && t.isJsonArray()) {
                return t.getAsJsonArray();
            }
            for (var entry : o.entrySet()) {
                JsonArray r = findTrackList(entry.getValue());
                if (r != null) {
                    return r;
                }
            }
        } else if (e.isJsonArray()) {
            for (JsonElement c : e.getAsJsonArray()) {
                JsonArray r = findTrackList(c);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }
}
