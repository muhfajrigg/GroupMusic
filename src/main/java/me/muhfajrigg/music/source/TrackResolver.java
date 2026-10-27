package me.muhfajrigg.music.source;

import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import me.muhfajrigg.music.GroupMusic;
import me.muhfajrigg.music.ai.AiAssistant;
import org.bukkit.configuration.file.YamlConfiguration;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * Resolves user inputs (titles, links, natural language queries) into playable audio tracks.
 * Callbacks are invoked asynchronously (off the main server thread).
 */
public final class TrackResolver {

    public interface Callback {
        void found(List<AudioTrack> tracks);

        void none();

        void failed(String reason);
    }

    private final GroupMusic plugin;
    private final AudioPlayerManager manager;
    private final LinkFetcher fetcher;
    private final AiAssistant ai;
    private volatile boolean spotifyEnabled;

    private static final String BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
    /** Number of parallel search queries when resolving collections/playlists. */
    private static final int SEARCH_PARALLEL = 4;

    public TrackResolver(GroupMusic plugin, AudioPlayerManager manager, LinkFetcher fetcher, AiAssistant ai) {
        this.plugin = plugin;
        this.manager = manager;
        this.fetcher = fetcher;
        this.ai = ai;
    }

    public void setSpotifyEnabled(boolean enabled) {
        this.spotifyEnabled = enabled;
    }

    private YamlConfiguration sources() {
        return plugin.cfg().sources();
    }

    private String reason(String key) {
        return plugin.messages().plain("reason." + key);
    }

    // ------------------------------------------------------------ Entry Points

    public void resolve(String input, UUID player, Callback cb) {
        String text = input.trim();
        if (text.startsWith("http://") || text.startsWith("https://")) {
            resolveLink(text, cb);
            return;
        }
        String mode = ai.available() ? ai.searchMode() : "off";
        if (mode.equals("always") && ai.trySearchSlot(player)) {
            // AI-first: AI guesses title + artist, then searches YouTube. Fallback to raw text if empty.
            searchFromAi(ai.search(text), cb, text);
            return;
        }
        Callback wrapped = !mode.equals("off") && ai.retryEmptySearch()
                ? onEmpty(cb, () -> searchFromAi(ai.correct(text), cb, null))
                : cb;
        searchOnce(text, wrapped);
    }

    /** Suggests tracks using AI natural language description. */
    public void suggest(String request, Callback cb) {
        if (!ai.available()) {
            cb.failed("AI assistant is disabled");
            return;
        }
        searchFromAi(ai.suggest(request), cb, null);
    }

    // ---------------------------------------------------------------- Links

    private void resolveLink(String url, Callback cb) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            cb.failed(reason("link-invalid"));
            return;
        }
        String host = hostOf(uri);
        if (host == null) {
            cb.failed(reason("link-invalid"));
            return;
        }
        if (!hostAllowed(host)) {
            cb.failed(reason("link-blocked"));
            return;
        }
        if (isSpotify(host) && spotifyEnabled) {
            loadSpotifyApi(uri, cb); // LavaSrc: tracks, albums, and playlists (falls back to embed/web scraping on failure)
            return;
        }
        if (matches(host, list("links.lookup-hosts"))) {
            resolveByLookup(uri, cb, false);
            return;
        }
        load(url, false, cb, null);
    }

    /**
     * Resolves Spotify link via LavaSrc API. If failing (e.g. 403 or quota),
     * automatically falls back to web lookup or public embed parsing.
     */
    private void loadSpotifyApi(URI uri, Callback cb) {
        load(uri.toString(), false, onFailed(cb, () -> {
            plugin.getLogger().warning("Spotify API failed for this link. Using fallback method (web/embed page).");
            resolveByLookup(uri, cb, true);
        }), null);
    }

    private void resolveByLookup(URI uri, Callback cb, boolean skipApi) {
        if (isCollection(uri)) {
            resolveCollection(uri, cb);
            return;
        }
        String ua = sources().getString("links.user-agent", "Mozilla/5.0 (compatible; GroupMusic/1.0.0)");
        int timeout = sources().getInt("links.lookup-timeout-seconds", 8);
        String mode = ai.available() ? ai.linkMode() : "off";

        fetcher.fetch(uri, u -> {
            String h = hostOf(u);
            return h != null && hostAllowed(h);
        }, ua, timeout).whenComplete((meta, err) -> {
            if (err != null) {
                plugin.getLogger().log(Level.WARNING, "Failed to open link " + uri.getHost() + ": " + describe(err));
                if (!mode.equals("off")) {
                    searchFromAi(ai.identify(uri.toString(), "", ""), cb, null);
                } else {
                    cb.failed(reason("lookup-failed"));
                }
                return;
            }

            URI fin = meta.finalUri();
            String fh = hostOf(fin);
            if (fh != null && isSpotify(fh) && spotifyEnabled && !skipApi) {
                loadSpotifyApi(fin, cb);
                return;
            }
            if (fh != null && matches(fh, list("links.direct-hosts"))) {
                load(fin.toString(), false, cb, null);
                return;
            }
            if (isCollection(fin)) {
                resolveCollection(fin, cb);
                return;
            }

            String query = LinkQuery.build(meta);
            if (mode.equals("always") || (query == null && !mode.equals("off"))) {
                searchFromAi(ai.identify(fin.toString(), meta.ogTitle().isBlank() ? meta.title() : meta.ogTitle(),
                        meta.description()), cb, query);
                return;
            }
            if (query == null) {
                cb.failed(reason("lookup-failed"));
                return;
            }
            Callback wrapped = mode.equals("fallback")
                    ? onEmpty(cb, () -> searchFromAi(ai.identify(fin.toString(), meta.title(), meta.description()), cb, null))
                    : cb;
            searchOnce(query, wrapped);
        });
    }

    /**
     * Resolves Spotify playlist/album without API: parses track list from public embed page,
     * then searches each track on YouTube (SoundCloud fallback).
     */
    private void resolveCollection(URI uri, Callback cb) {
        String host = hostOf(uri);
        SpotifyEmbed.Ref ref = host != null && isSpotify(host) ? SpotifyEmbed.parseRef(uri) : null;
        if (ref == null) {
            cb.failed(reason("collection-unsupported"));
            return;
        }
        String ua = sources().getString("links.embed-user-agent", BROWSER_UA);
        int timeout = Math.max(10, sources().getInt("links.lookup-timeout-seconds", 8));
        int max = Math.max(1, plugin.cfg().general().getInt("limits.max-queue", 50));
        String failMsg = reasonOr("collection-failed",
                "Could not read track list. Ensure the playlist or album is PUBLIC, then try again.");

        fetcher.fetchHtml(URI.create(ref.embedUrl()), u -> {
            String h = hostOf(u);
            return h != null && hostAllowed(h);
        }, ua, timeout, 3_000_000).whenComplete((html, err) -> {
            if (err != null) {
                plugin.getLogger().log(Level.WARNING, "Failed to open Spotify embed: " + describe(err));
                cb.failed(failMsg);
                return;
            }
            List<String> queries = SpotifyEmbed.parseQueries(html, max);
            if (queries.isEmpty()) {
                plugin.getLogger().warning("Spotify embed does not contain track list (private playlist or page structure changed).");
                cb.failed(failMsg);
                return;
            }
            plugin.getLogger().info("Spotify embed: " + queries.size() + " tracks parsed (" + ref.type() + "), searching on YouTube...");
            searchAll(queries, cb);
        });
    }

    /** Searches all queries in parallel, preserving original collection order. */
    private void searchAll(List<String> queries, Callback cb) {
        AudioTrack[] results = new AudioTrack[queries.size()];
        AtomicInteger next = new AtomicInteger();
        int workers = Math.min(SEARCH_PARALLEL, queries.size());
        AtomicInteger active = new AtomicInteger(workers);
        for (int w = 0; w < workers; w++) {
            searchWorker(queries, results, next, active, cb);
        }
    }

    private void searchWorker(List<String> queries, AudioTrack[] results, AtomicInteger next,
                              AtomicInteger active, Callback cb) {
        int i = next.getAndIncrement();
        if (i >= queries.size()) {
            if (active.decrementAndGet() == 0) {
                List<AudioTrack> out = new ArrayList<>();
                for (AudioTrack t : results) {
                    if (t != null) {
                        out.add(t);
                    }
                }
                if (out.isEmpty()) {
                    cb.none();
                } else {
                    cb.found(out);
                }
            }
            return;
        }
        searchOnce(queries.get(i), new Callback() {
            @Override
            public void found(List<AudioTrack> tracks) {
                if (!tracks.isEmpty()) {
                    results[i] = tracks.get(0);
                }
                searchWorker(queries, results, next, active, cb);
            }

            @Override
            public void none() {
                searchWorker(queries, results, next, active, cb);
            }

            @Override
            public void failed(String reason) {
                searchWorker(queries, results, next, active, cb);
            }
        });
    }

    private String reasonOr(String key, String fallback) {
        return plugin.cfg().messages().getString("reason." + key) == null ? fallback : reason(key);
    }

    private static boolean isCollection(URI uri) {
        String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
        String q = uri.getQuery() == null ? "" : uri.getQuery();
        if (path.contains("/playlist/") || path.contains("/artist/") || path.contains("/show/")
                || path.contains("/episode/") || path.contains("/podcast/")) {
            return true;
        }
        boolean isAlbum = path.contains("/album/");
        boolean trackInAlbum = path.contains("/track/") || path.contains("/song/") || q.startsWith("i=") || q.contains("&i=");
        return isAlbum && !trackInAlbum;
    }

    // ------------------------------------------------------------- Searching

    /** Searches a single query across providers chain: ytsearch -> ytmsearch -> scsearch. */
    private void searchOnce(String query, Callback cb) {
        List<String> ids = new ArrayList<>();
        for (String prefix : searchProviders()) {
            ids.add(prefix + query);
        }
        loadChain(ids, 0, cb, null);
    }

    private List<String> searchProviders() {
        List<String> out = new ArrayList<>();
        boolean sc = sources().getBoolean("search.soundcloud-fallback", true);
        List<String> cfg = sources().getStringList("search.providers");
        if (cfg.isEmpty()) {
            cfg = List.of("ytsearch:", "ytmsearch:", "scsearch:");
        }
        for (String p : cfg) {
            String prefix = p.trim();
            if (prefix.isEmpty()) {
                continue;
            }
            if (!prefix.endsWith(":")) {
                prefix = prefix + ":";
            }
            if (prefix.equals("scsearch:") && !sc) {
                continue;
            }
            out.add(prefix);
        }
        return out;
    }

    /** Finds a SoundCloud replacement if a YouTube track fails playback. */
    public void findSoundCloudReplacement(String title, String author, Callback cb) {
        String clean = cleanTitle(title);
        List<String> ids = new ArrayList<>();
        if (!clean.isBlank()) {
            ids.add("scsearch:" + clean);
        }
        if (author != null && !author.isBlank() && !clean.toLowerCase(Locale.ROOT).contains(author.toLowerCase(Locale.ROOT))) {
            ids.add("scsearch:" + clean + " " + author);
        }
        if (ids.isEmpty()) {
            cb.none();
            return;
        }
        loadChain(ids, 0, cb, null);
    }

    private static String cleanTitle(String title) {
        if (title == null) {
            return "";
        }
        String t = title.replaceAll("\\(.*?\\)|\\[.*?\\]", " ");
        t = t.replaceAll("(?i)\\b(official|music video|video clip|lyrics?|lirik|visualizer|audio|hd|4k)\\b", " ");
        return t.replaceAll("\\s+", " ").trim();
    }

    private void loadChain(List<String> ids, int i, Callback cb, String lastError) {
        if (i >= ids.size()) {
            if (lastError != null && ids.size() == 1) {
                cb.failed(lastError);
            } else {
                cb.none();
            }
            return;
        }
        String identifier = ids.get(i);
        manager.loadItem(identifier, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                cb.found(List.of(track));
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                List<AudioTrack> tracks = playlist.getTracks();
                if (tracks.isEmpty()) {
                    next("empty", null);
                } else {
                    cb.found(List.of(tracks.get(0)));
                }
            }

            @Override
            public void noMatches() {
                next("empty", null);
            }

            @Override
            public void loadFailed(FriendlyException e) {
                plugin.getLogger().warning("Failed to load '" + identifier + "': " + describe(e));
                next("error", shortReason(e));
            }

            private void next(String why, String error) {
                if (i + 1 < ids.size()) {
                    plugin.getLogger().info("Source '" + identifier + "' returned " + why + ", attempting: " + ids.get(i + 1));
                }
                loadChain(ids, i + 1, cb, error != null ? error : lastError);
            }
        });
    }

    private void searchFromAi(CompletableFuture<List<AiAssistant.Song>> future, Callback cb, String backupQuery) {
        future.whenComplete((songs, err) -> {
            if (err != null) {
                plugin.getLogger().log(Level.WARNING, "AI assistant failed: " + describe(err));
            }
            if (err != null || songs == null || songs.isEmpty()) {
                if (backupQuery != null) {
                    searchOnce(backupQuery, cb);
                } else {
                    cb.none();
                }
                return;
            }
            searchNext(songs, 0, Collections.synchronizedList(new ArrayList<>()), cb, backupQuery);
        });
    }

    private void searchNext(List<AiAssistant.Song> songs, int i, List<AudioTrack> out, Callback cb, String backupQuery) {
        if (i >= songs.size()) {
            if (out.isEmpty()) {
                if (backupQuery != null) {
                    searchOnce(backupQuery, cb);
                } else {
                    cb.none();
                }
            } else {
                cb.found(new ArrayList<>(out));
            }
            return;
        }
        searchOnce(songs.get(i).query(), new Callback() {
            @Override
            public void found(List<AudioTrack> tracks) {
                out.addAll(tracks);
                searchNext(songs, i + 1, out, cb, backupQuery);
            }

            @Override
            public void none() {
                searchNext(songs, i + 1, out, cb, backupQuery);
            }

            @Override
            public void failed(String reason) {
                searchNext(songs, i + 1, out, cb, backupQuery);
            }
        });
    }

    private static Callback onFailed(Callback cb, Runnable whenFailed) {
        return new Callback() {
            @Override
            public void found(List<AudioTrack> tracks) {
                cb.found(tracks);
            }

            @Override
            public void none() {
                cb.none();
            }

            @Override
            public void failed(String reason) {
                whenFailed.run();
            }
        };
    }

    private static Callback onEmpty(Callback cb, Runnable whenEmpty) {
        return new Callback() {
            @Override
            public void found(List<AudioTrack> tracks) {
                cb.found(tracks);
            }

            @Override
            public void none() {
                whenEmpty.run();
            }

            @Override
            public void failed(String reason) {
                cb.failed(reason);
            }
        };
    }

    private void load(String identifier, boolean search, Callback cb, String fallback) {
        manager.loadItem(identifier, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                cb.found(List.of(track));
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                List<AudioTrack> tracks = playlist.getTracks();
                if (tracks.isEmpty()) {
                    noResult();
                } else if (playlist.isSearchResult()) {
                    cb.found(List.of(tracks.get(0)));
                } else if (playlist.getSelectedTrack() != null) {
                    cb.found(List.of(playlist.getSelectedTrack()));
                } else {
                    cb.found(tracks);
                }
            }

            @Override
            public void noMatches() {
                noResult();
            }

            @Override
            public void loadFailed(FriendlyException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to load '" + identifier + "': " + describe(e));
                if (fallback != null) {
                    plugin.getLogger().info("Attempting fallback: " + fallback);
                    load(fallback, true, cb, null);
                } else {
                    cb.failed(shortReason(e));
                }
            }

            private void noResult() {
                if (fallback != null) {
                    plugin.getLogger().info("Empty result for '" + identifier + "', attempting fallback: " + fallback);
                    load(fallback, true, cb, null);
                } else {
                    cb.none();
                }
            }
        });
    }

    // ---------------------------------------------------------- Hosts & Utils

    private List<String> list(String path) {
        return sources().getStringList(path);
    }

    private boolean hostAllowed(String host) {
        return sources().getBoolean("links.allow-any-url", false)
                || matches(host, list("links.direct-hosts"))
                || matches(host, list("links.lookup-hosts"));
    }

    private static boolean matches(String host, List<String> allowed) {
        for (String a : allowed) {
            String n = a.trim().toLowerCase(Locale.ROOT);
            if (!n.isEmpty() && (host.equals(n) || host.endsWith("." + n))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSpotify(String host) {
        return host.equals("spotify.com") || host.endsWith(".spotify.com");
    }

    private static String hostOf(URI uri) {
        String h = uri.getHost();
        return h == null ? null : h.toLowerCase(Locale.ROOT);
    }

    private String shortReason(FriendlyException e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? reason("unknown") : m;
    }

    /** Formats full causal chain exception description for server log output. */
    public static String describe(Throwable t) {
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        for (Throwable c = t; c != null && depth < 6; c = c.getCause(), depth++) {
            if (depth > 0) {
                sb.append(" <- ");
            }
            sb.append(c.getClass().getSimpleName()).append(": ").append(c.getMessage());
        }
        return sb.toString();
    }
}
