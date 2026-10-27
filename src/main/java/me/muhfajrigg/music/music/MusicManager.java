package me.muhfajrigg.music.music;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.VolumeCategory;
import me.muhfajrigg.music.GroupMusic;
import me.muhfajrigg.music.ai.AiAssistant;
import me.muhfajrigg.music.ai.AiClient;
import me.muhfajrigg.music.auth.YoutubeLogin;
import me.muhfajrigg.music.source.AudioSources;
import me.muhfajrigg.music.source.LinkFetcher;
import me.muhfajrigg.music.source.TrackResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Manages Lavaplayer instance, Simple Voice Chat integration,
 * and active playback sessions (one per voice chat group).
 */
public final class MusicManager {

    private final GroupMusic plugin;
    private final AudioPlayerManager playerManager = new DefaultAudioPlayerManager();
    private final ExecutorService io;
    private final AiAssistant ai;
    private final TrackResolver resolver;
    private final YoutubeLogin login;
    private final Map<UUID, GroupSession> sessions = new ConcurrentHashMap<>();

    /** Simple Voice Chat volume category ID (lowercase/underscore, max 16 chars). */
    public static final String VOLUME_CATEGORY = "groupmusic";

    private volatile VoicechatServerApi voice;
    private volatile VolumeCategory category;
    /** Group volume cache (0..100) in memory. */
    private final Map<UUID, Integer> volumes = new ConcurrentHashMap<>();
    private BukkitTask syncTask;
    /** Players who toggled bossbar off via /musicbar (memory only). */
    private final Set<UUID> barOff = ConcurrentHashMap.newKeySet();

    public MusicManager(GroupMusic plugin) {
        this.plugin = plugin;

        AudioSources.Setup setup = AudioSources.register(plugin, playerManager);
        this.io = Executors.newFixedThreadPool(3, r -> {
            Thread t = new Thread(r, "GroupMusic-IO");
            t.setDaemon(true);
            return t;
        });
        this.ai = new AiAssistant(plugin, new AiClient(plugin));
        this.resolver = new TrackResolver(plugin, playerManager, new LinkFetcher(io), ai);
        this.resolver.setSpotifyEnabled(setup.spotify());
        this.login = new YoutubeLogin(plugin, setup.youtube());

        plugin.getLogger().info(ai.available() ? "AI assistant: enabled (ai.yml)." : "AI assistant: disabled (see ai.yml).");
    }

    public void start() {
        // Synchronize group members and close idle/empty sessions every second (20 ticks)
        syncTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void shutdown() {
        login.stop();
        if (syncTask != null) {
            syncTask.cancel();
        }
        for (GroupSession s : sessions.values()) {
            remove(s);
        }
        playerManager.shutdown();
        io.shutdownNow();
    }

    public TrackResolver resolver() {
        return resolver;
    }

    public AiAssistant ai() {
        return ai;
    }

    public void startLogin(CommandSender sender) {
        login.start(sender);
    }

    /** Toggles the bossbar for a player. @return true if bossbar is now visible */
    public boolean toggleBar(UUID playerId) {
        boolean nowOff = barOff.add(playerId);
        if (!nowOff) {
            barOff.remove(playerId);
        }
        for (GroupSession s : sessions.values()) {
            s.updateBossBar(barOff);
        }
        return !nowOff;
    }

    // -------------------------------------------------------- Voice Chat API

    public void onVoiceReady(VoicechatServerApi api) {
        this.voice = api;
        // Volume category in Simple Voice Chat GUI
        try {
            VolumeCategory cat = api.volumeCategoryBuilder()
                    .setId(VOLUME_CATEGORY)
                    .setName(plugin.messages().plain("volume.category-name"))
                    .setDescription(plugin.messages().plain("volume.category-description"))
                    .build();
            api.registerVolumeCategory(cat);
            this.category = cat;
        } catch (RuntimeException e) {
            this.category = null;
            plugin.getLogger().warning("Failed to register volume category with Voice Chat: " + e);
        }
    }

    public void onVoiceStopped() {
        VoicechatServerApi v = this.voice;
        VolumeCategory cat = this.category;
        this.voice = null;
        this.category = null;
        for (GroupSession s : sessions.values()) {
            remove(s);
        }
        volumes.clear();
        if (v != null && cat != null) {
            try {
                v.unregisterVolumeCategory(cat);
            } catch (RuntimeException ignored) {
                // voice server stopped
            }
        }
    }

    // ---------------------------------------------------------------- Volume

    /** Current volume (0..100) for group; defaults to volume.default in config.yml. */
    public int volumeOf(UUID groupId) {
        Integer v = volumes.get(groupId);
        return v != null ? v : defaultVolume();
    }

    private int defaultVolume() {
        return Math.max(0, Math.min(100, plugin.cfg().general().getInt("volume.default", 100)));
    }

    /** Sets group volume level (persisted during server runtime). */
    public void setVolume(UUID groupId, int percent) {
        int v = Math.max(0, Math.min(100, percent));
        volumes.put(groupId, v);
        GroupSession s = sessions.get(groupId);
        if (s != null) {
            s.setVolume(v);
        }
    }

    public boolean ready() {
        return voice != null;
    }

    /** Returns voice chat connection for player, or null if disconnected. */
    public VoicechatConnection connectionOf(Player player) {
        VoicechatServerApi v = voice;
        return v == null ? null : v.getConnectionOf(player.getUniqueId());
    }

    // ---------------------------------------------------------------- Sessions

    public GroupSession existing(UUID groupId) {
        return sessions.get(groupId);
    }

    /** Returns existing session for group or creates a new one. */
    public GroupSession sessionFor(UUID groupId) {
        return sessions.computeIfAbsent(groupId, id -> new GroupSession(plugin, playerManager, voice, id, resolver,
                volumeOf(id), category == null ? null : VOLUME_CATEGORY));
    }

    public void remove(GroupSession session) {
        if (sessions.remove(session.groupId(), session)) {
            session.close();
        }
    }

    /** Refreshes members for a single session. @return false if group is empty */
    public boolean refresh(GroupSession session) {
        VoicechatServerApi v = voice;
        if (v == null) {
            return false;
        }
        List<VoicechatConnection> members = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            VoicechatConnection c = v.getConnectionOf(p.getUniqueId());
            if (c == null) {
                continue;
            }
            Group g = c.getGroup();
            if (g != null && g.getId().equals(session.groupId())) {
                members.add(c);
            }
        }
        session.setMembers(members);
        return !members.isEmpty();
    }

    /** Periodic sync: single-pass mapping of online players to groups for optimal performance. */
    private void tick() {
        long idleLimit = plugin.cfg().general().getLong("limits.idle-timeout-seconds", 120) * 1000L;
        VoicechatServerApi v = voice;
        if (v == null || sessions.isEmpty()) {
            return;
        }

        Map<UUID, List<VoicechatConnection>> groupMap = new HashMap<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            VoicechatConnection c = v.getConnectionOf(p.getUniqueId());
            if (c != null) {
                Group g = c.getGroup();
                if (g != null) {
                    groupMap.computeIfAbsent(g.getId(), k -> new ArrayList<>()).add(c);
                }
            }
        }

        for (GroupSession s : sessions.values()) {
            List<VoicechatConnection> members = groupMap.getOrDefault(s.groupId(), List.of());
            s.setMembers(members);
            if (members.isEmpty()) {
                remove(s);
                continue;
            }
            if (idleLimit > 0 && s.idleFor() > idleLimit) {
                s.broadcast(plugin.messages().get("announce.idle-stop"));
                remove(s);
                continue;
            }
            s.updateBossBar(barOff);
        }
    }
}
