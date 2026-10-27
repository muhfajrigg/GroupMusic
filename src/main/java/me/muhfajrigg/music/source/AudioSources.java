package me.muhfajrigg.music.source;

import com.github.topi314.lavasrc.mirror.DefaultMirroringAudioTrackResolver;
import com.github.topi314.lavasrc.spotify.SpotifySourceManager;
import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers;
import dev.lavalink.youtube.YoutubeAudioSourceManager;
import dev.lavalink.youtube.YoutubeSourceOptions;
import dev.lavalink.youtube.clients.Android;
import dev.lavalink.youtube.clients.AndroidMusic;
import dev.lavalink.youtube.clients.AndroidVr;
import dev.lavalink.youtube.clients.Ios;
import dev.lavalink.youtube.clients.Music;
import dev.lavalink.youtube.clients.Tv;
import dev.lavalink.youtube.clients.TvHtml5Simply;
import dev.lavalink.youtube.clients.Web;
import dev.lavalink.youtube.clients.WebEmbedded;
import dev.lavalink.youtube.clients.skeleton.Client;
import me.muhfajrigg.music.GroupMusic;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Registers audio sources with Lavaplayer: YouTube (youtube.yml), Spotify (sources.yml),
 * and remote fallback providers (SoundCloud, Bandcamp, direct HTTP, etc.).
 */
public final class AudioSources {

    public record Setup(YoutubeAudioSourceManager youtube, boolean spotify) {
    }

    private AudioSources() {
    }

    public static Setup register(GroupMusic plugin, AudioPlayerManager playerManager) {
        playerManager.getConfiguration().setOutputFormat(StandardAudioDataFormats.COMMON_PCM_S16_BE);

        YoutubeAudioSourceManager youtube = registerYoutube(plugin, playerManager);
        boolean spotify = registerSpotify(plugin, playerManager);

        // Register default remote sources (SoundCloud, Bandcamp, etc.) without standard Lavaplayer YouTube
        AudioSourceManagers.registerRemoteSources(playerManager,
                com.sedmelluq.discord.lavaplayer.source.youtube.YoutubeAudioSourceManager.class);
        return new Setup(youtube, spotify);
    }

    private static YoutubeAudioSourceManager registerYoutube(GroupMusic plugin, AudioPlayerManager playerManager) {
        YamlConfiguration yt = plugin.cfg().youtube();

        YoutubeSourceOptions options = new YoutubeSourceOptions();
        String cipherUrl = yt.getString("remote-cipher.url", "");
        if (cipherUrl != null && !cipherUrl.isBlank()) {
            String pass = yt.getString("remote-cipher.password", "");
            options.setRemoteCipher(cipherUrl.trim(), pass == null ? "" : pass, "GroupMusic");
            plugin.getLogger().info("YouTube: remote cipher -> " + cipherUrl.trim());
        } else {
            plugin.getLogger().warning("YouTube: remote-cipher.url is EMPTY in youtube.yml. "
                    + "Playback may fail without a cipher server.");
        }

        List<Client> clients = new ArrayList<>();
        List<String> names = yt.getStringList("clients");
        if (names.isEmpty()) {
            names = List.of("MUSIC", "WEB", "IOS", "ANDROID_VR", "TV");
        }
        for (String name : names) {
            Client c = clientByName(name);
            if (c == null) {
                plugin.getLogger().warning("YouTube: unrecognized client name in youtube.yml: " + name);
            } else {
                clients.add(c);
            }
        }
        plugin.getLogger().info("YouTube: active clients -> " + names);

        YoutubeAudioSourceManager youtube = new YoutubeAudioSourceManager(options, clients.toArray(new Client[0]));
        String token = yt.getString("oauth-refresh-token", "");
        if (token != null && !token.isBlank()) {
            youtube.useOauth2(token.trim(), true);
            plugin.getLogger().info("YouTube: authenticated with OAuth refresh token from youtube.yml.");
        } else if (yt.getBoolean("oauth", false)) {
            plugin.getLogger().warning("YouTube: OAuth enabled without refresh token. Check console or run /musiclogin in-game.");
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> youtube.useOauth2(null, false));
        }
        playerManager.registerSourceManager(youtube);
        return youtube;
    }

    private static boolean registerSpotify(GroupMusic plugin, AudioPlayerManager playerManager) {
        YamlConfiguration src = plugin.cfg().sources();
        String id = src.getString("spotify.client-id", "");
        String secret = src.getString("spotify.client-secret", "");
        boolean on = id != null && !id.isBlank() && secret != null && !secret.isBlank();
        if (!on) {
            plugin.getLogger().info("Spotify API: disabled (single track links resolve via web lookup; "
                    + "set spotify.client-id & client-secret in sources.yml for full playlist/album support).");
            return false;
        }
        List<String> providers = src.getStringList("spotify.mirror-providers");
        if (providers.isEmpty()) {
            providers = List.of("ytsearch:\"%ISRC%\"", "ytsearch:%QUERY%", "scsearch:%QUERY%");
        }
        String country = src.getString("spotify.country-code", "US");
        SpotifySourceManager spotify = new SpotifySourceManager(
                id.trim(), secret.trim(), null, country,
                unused -> playerManager,
                new DefaultMirroringAudioTrackResolver(providers.toArray(new String[0])));
        playerManager.registerSourceManager(spotify);
        plugin.getLogger().info("Spotify API: enabled (country code: " + country + ").");
        return true;
    }

    private static Client clientByName(String name) {
        return switch (name.trim().toUpperCase(Locale.ROOT)) {
            case "MUSIC" -> new Music();
            case "WEB" -> new Web();
            case "WEBEMBEDDED" -> new WebEmbedded();
            case "IOS" -> new Ios();
            case "ANDROID" -> new Android();
            case "ANDROID_MUSIC" -> new AndroidMusic();
            case "ANDROID_VR" -> new AndroidVr();
            case "TV" -> new Tv();
            case "TVHTML5_SIMPLY" -> new TvHtml5Simply();
            default -> null;
        };
    }
}
