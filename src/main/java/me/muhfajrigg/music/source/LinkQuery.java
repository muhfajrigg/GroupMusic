package me.muhfajrigg.music.source;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds search queries ("title artist") from fetched page metadata without AI.
 */
public final class LinkQuery {

    private static final Pattern APPLE_1 = Pattern.compile(
            "^(.+?)\\s+-\\s+(?:Song|Single|EP|Album)\\s+by\\s+(.+?)(?:\\s+-\\s+Apple Music)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern BY_ON = Pattern.compile(
            "^(.+?)\\s+by\\s+(.+?)\\s+on\\s+(?:Apple Music|Amazon Music|TIDAL|Deezer)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SITE_SUFFIX = Pattern.compile(
            "\\s*[|\u2013\u2014-]\\s*(?:Spotify|Apple Music|Deezer|TIDAL|Amazon Music|Genius(?: Lyrics)?|Shazam"
                    + "|YouTube Music|Yandex Music)\\b.*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SPOTIFY_INTRO = Pattern.compile("(?i)^.*?\\bon Spotify\\.\\s*");
    private static final Pattern LYRICS_TAIL = Pattern.compile("(?i)\\s*\\(?\\b(?:lyrics?|lyric video)\\b\\)?\\s*$");

    private LinkQuery() {
    }

    /** @return search query string, or null if title could not be extracted */
    public static String build(LinkFetcher.Meta m) {
        String title = m.ogTitle().isBlank() ? m.title() : m.ogTitle();
        if (title.isBlank()) {
            return null;
        }
        String host = m.finalUri().getHost() == null ? "" : m.finalUri().getHost().toLowerCase(Locale.ROOT);
        String artist = "";

        Matcher a1 = APPLE_1.matcher(title);
        Matcher a2 = BY_ON.matcher(title);
        if (a1.matches()) {
            title = a1.group(1);
            artist = a1.group(2);
        } else if (a2.matches()) {
            title = a2.group(1);
            artist = a2.group(2);
        } else if (host.endsWith("spotify.com") || host.endsWith("spotify.link")) {
            // Spotify track page description: "Artist · Album · Song · Year"
            String[] parts = SPOTIFY_INTRO.matcher(m.description()).replaceFirst("").split(Pattern.quote(" · "));
            if (parts.length >= 2) {
                artist = parts[0];
            }
        }

        title = SITE_SUFFIX.matcher(title).replaceFirst("");
        title = LYRICS_TAIL.matcher(title).replaceFirst("").trim();
        artist = artist.trim();
        if (title.length() < 2) {
            return null;
        }
        String q = artist.isEmpty() || title.toLowerCase(Locale.ROOT).contains(artist.toLowerCase(Locale.ROOT))
                ? title : title + " " + artist;
        return q.length() > 120 ? q.substring(0, 120) : q;
    }
}
