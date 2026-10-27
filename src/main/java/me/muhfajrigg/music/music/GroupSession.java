package me.muhfajrigg.music.music;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import me.muhfajrigg.music.GroupMusic;
import me.muhfajrigg.music.config.Messages;
import me.muhfajrigg.music.source.TrackResolver;
import me.muhfajrigg.music.util.TimeFmt;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Manages playback, queue, Lavaplayer instance, and static audio channel
 * for an individual Simple Voice Chat group.
 */
public final class GroupSession {

    private static final int FRAME_SAMPLES = 960; // 20 ms @ 48 kHz
    /** Maximum track history stored for queue loop mode. */
    private static final int HISTORY_MAX = 200;

    /** Loop modes: OFF = no loop, TRACK = loop single track, QUEUE = loop entire queue. */
    public enum LoopMode {
        OFF, TRACK, QUEUE;

        public LoopMode next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    private final GroupMusic plugin;
    private final UUID groupId;

    private final AudioPlayer player;
    private final StaticAudioChannel channel;
    private final OpusEncoder encoder;
    private final de.maxhenkel.voicechat.api.audiochannel.AudioPlayer output;

    // Queue state: synchronized via (this)
    private final Deque<AudioTrack> pending = new ArrayDeque<>();
    private AudioTrack current;
    private LoopMode loopMode = LoopMode.OFF;
    /** Track history for queue looping (clones). */
    private final Deque<AudioTrack> history = new ArrayDeque<>();
    private boolean paused;
    /** Volume 0..100, read by SVC audio thread. */
    private volatile int volume = 100;
    private boolean closed;
    private final TrackResolver resolver;
    /** YouTube track that failed and is searching for a SoundCloud replacement. */
    private AudioTrack retrying;
    /** Tracks that have already attempted fallback to prevent infinite loops. */
    private final Set<String> fallbackTried = new HashSet<>();

    private volatile long idleSince = System.currentTimeMillis();

    // Now playing Bossbar: touched only by main server thread
    private final BossBar bar = BossBar.bossBar(Component.empty(), 0f, BossBar.Color.PINK, BossBar.Overlay.PROGRESS);
    private final Set<UUID> barViewers = new HashSet<>();

    // Group members: written by main thread, read by SVC audio thread
    private volatile List<VoicechatConnection> members = List.of();
    private List<UUID> memberIds = List.of(); // main thread only
    private List<VoicechatConnection> appliedMembers = List.of(); // audio thread only

    /** @param category SVC volume category ID (slider in voice chat menu), nullable */
    GroupSession(GroupMusic plugin, AudioPlayerManager manager, VoicechatServerApi voice, UUID groupId,
                 TrackResolver resolver, int volume, String category) {
        this.plugin = plugin;
        this.resolver = resolver;
        this.groupId = groupId;
        this.volume = Math.max(0, Math.min(100, volume));
        this.bar.overlay(enumOr(BossBar.Overlay.class, plugin.cfg().general().getString("bossbar.overlay", "PROGRESS"), BossBar.Overlay.PROGRESS));

        this.player = manager.createPlayer();
        this.player.addListener(new AudioEventAdapter() {
            @Override
            public void onTrackEnd(AudioPlayer p, AudioTrack track, AudioTrackEndReason reason) {
                if (!reason.mayStartNext) {
                    return;
                }
                synchronized (GroupSession.this) {
                    if (closed || current != track) {
                        return; // replaced by skip or stop
                    }
                    if (retrying == track) {
                        return; // failed playback; currently finding SoundCloud replacement
                    }
                    if (loopMode == LoopMode.TRACK && reason == AudioTrackEndReason.FINISHED) {
                        start(copyOf(track), false);
                    } else {
                        advance();
                    }
                }
            }

            @Override
            public void onTrackException(AudioPlayer p, AudioTrack track, FriendlyException ex) {
                plugin.getLogger().warning("Failed to play " + track.getInfo().title + ": " + TrackResolver.describe(ex));
                if (tryFallback(track)) {
                    return;
                }
                synchronized (GroupSession.this) {
                    forget(track);
                }
                broadcast(plugin.messages().get("announce.play-error", "title", track.getInfo().title));
            }
        });

        this.channel = voice.createStaticAudioChannel(UUID.randomUUID());
        // Required for isolated group members to hear audio (SVC >= 2.6.0)
        this.channel.setBypassGroupIsolation(true);
        // Custom volume category slider in Simple Voice Chat GUI
        if (category != null) {
            this.channel.setCategory(category);
        }
        this.encoder = voice.createEncoder();
        this.output = voice.createAudioPlayer(channel, encoder, this::nextFrame);
        this.output.startPlaying();
    }

    public UUID groupId() {
        return groupId;
    }

    // ---------------------------------------------------------------- Audio

    /** Called by SVC audio thread every 20 ms. Must return 960 mono samples. */
    private short[] nextFrame() {
        List<VoicechatConnection> m = members;
        if (m != appliedMembers) {
            channel.clearTargets();
            for (VoicechatConnection c : m) {
                channel.addTarget(c);
            }
            appliedMembers = m;
        }

        AudioFrame frame = player.provide();
        short[] pcm = new short[FRAME_SAMPLES];
        if (frame == null) {
            return pcm; // silence during pause / empty queue
        }

        // COMMON_PCM_S16_BE = stereo, 16-bit big-endian. Average down to mono.
        byte[] d = frame.getData();
        if (d.length < FRAME_SAMPLES * 4) {
            return pcm;
        }
        // Human hearing is logarithmic; apply a quadratic curve for natural volume perception
        float normalized = volume / 100f;
        float gain = normalized * normalized;
        for (int i = 0; i < FRAME_SAMPLES; i++) {
            int l = (short) (((d[i * 4] & 0xFF) << 8) | (d[i * 4 + 1] & 0xFF));
            int r = (short) (((d[i * 4 + 2] & 0xFF) << 8) | (d[i * 4 + 3] & 0xFF));
            pcm[i] = (short) (((l + r) / 2) * gain);
        }
        return pcm;
    }

    private static boolean isYoutube(AudioTrack t) {
        String uri = t.getInfo().uri;
        return uri != null && (uri.contains("youtube.com") || uri.contains("youtu.be"));
    }

    /**
     * If YouTube track playback fails -> find the same track on SoundCloud and stream it.
     * @return true if fallback search started
     */
    private boolean tryFallback(AudioTrack failed) {
        synchronized (this) {
            if (closed || current != failed || !isYoutube(failed)
                    || !plugin.cfg().sources().getBoolean("search.soundcloud-fallback", true)
                    || !fallbackTried.add(failed.getIdentifier())) {
                return false;
            }
            retrying = failed;
        }
        plugin.getLogger().info("YouTube playback failed for '" + failed.getInfo().title + "', searching fallback on SoundCloud...");
        broadcast(Component.text("YouTube playback failed, attempting SoundCloud fallback: " + failed.getInfo().title, NamedTextColor.GRAY));
        resolver.findSoundCloudReplacement(failed.getInfo().title, failed.getInfo().author, new TrackResolver.Callback() {
            @Override
            public void found(List<AudioTrack> tracks) {
                synchronized (GroupSession.this) {
                    if (retrying != failed) {
                        return;
                    }
                    retrying = null;
                    if (closed || current != failed) {
                        return; // skipped or stopped during search
                    }
                    AudioTrack replacement = tracks.get(0);
                    replacement.setUserData(failed.getUserData());
                    forget(failed);
                    record(replacement);
                    start(replacement, false);
                }
                broadcast(Component.text("Playing from SoundCloud: " + tracks.get(0).getInfo().title, NamedTextColor.GRAY));
            }

            @Override
            public void none() {
                giveUp("not found on SoundCloud");
            }

            @Override
            public void failed(String reason) {
                giveUp(reason);
            }

            private void giveUp(String why) {
                plugin.getLogger().warning("SoundCloud fallback for '" + failed.getInfo().title + "' failed: " + why);
                boolean stillCurrent;
                synchronized (GroupSession.this) {
                    if (retrying != failed) {
                        return;
                    }
                    retrying = null;
                    forget(failed);
                    stillCurrent = !closed && current == failed;
                    if (stillCurrent) {
                        advance();
                    }
                }
                if (stillCurrent) {
                    broadcast(plugin.messages().get("announce.play-error", "title", failed.getInfo().title));
                }
            }
        });
        return true;
    }

    void setMembers(List<VoicechatConnection> list) {
        List<UUID> ids = new ArrayList<>();
        for (VoicechatConnection c : list) {
            ids.add(c.getPlayer().getUuid());
        }
        if (ids.equals(memberIds)) {
            return;
        }
        this.memberIds = ids;
        this.members = List.copyOf(list);
    }

    // --------------------------------------------------------------- Queue

    /** @return 0 if played immediately, otherwise position in queue (1-based). */
    public synchronized int enqueue(AudioTrack track) {
        if (current == null) {
            record(track);
            start(track, true);
            return 0;
        }
        pending.add(track);
        return pending.size();
    }

    public synchronized boolean skip() {
        if (current == null) {
            return false;
        }
        advance();
        return true;
    }

    /** @return true if paused, false if resumed, null if nothing playing. */
    public synchronized Boolean togglePause() {
        if (current == null) {
            return null;
        }
        paused = !paused;
        player.setPaused(paused);
        return paused;
    }

    /** OFF -> TRACK -> QUEUE -> OFF. @return new loop mode */
    public synchronized LoopMode cycleLoop() {
        loopMode = loopMode.next();
        return loopMode;
    }

    public synchronized void setLoopMode(LoopMode mode) {
        loopMode = mode;
    }

    public synchronized LoopMode loopMode() {
        return loopMode;
    }

    /** Volume 0..100 applied to all group listeners. */
    public void setVolume(int percent) {
        this.volume = Math.max(0, Math.min(100, percent));
    }

    public int volume() {
        return volume;
    }

    public synchronized boolean shuffle() {
        if (pending.size() < 2) {
            return false;
        }
        List<AudioTrack> list = new ArrayList<>(pending);
        Collections.shuffle(list);
        pending.clear();
        pending.addAll(list);
        return true;
    }

    public synchronized AudioTrack current() {
        return current;
    }

    public synchronized List<AudioTrack> pendingSnapshot() {
        return new ArrayList<>(pending);
    }

    public synchronized int pendingSize() {
        return pending.size();
    }

    /** Clean clone with matching userData. */
    private static AudioTrack copyOf(AudioTrack t) {
        AudioTrack c = t.makeClone();
        c.setUserData(t.getUserData());
        return c;
    }

    private void record(AudioTrack t) {
        history.addLast(copyOf(t));
        while (history.size() > HISTORY_MAX) {
            history.pollFirst();
        }
    }

    private void forget(AudioTrack t) {
        String id = t.getIdentifier();
        for (Iterator<AudioTrack> it = history.descendingIterator(); it.hasNext(); ) {
            if (it.next().getIdentifier().equals(id)) {
                it.remove();
                return;
            }
        }
    }

    private void start(AudioTrack track, boolean announce) {
        current = track;
        paused = false;
        player.setPaused(false);
        player.startTrack(track, false);
        idleSince = 0;
        if (announce) {
            broadcast(nowPlaying(track));
        }
    }

    private void advance() {
        AudioTrack next = pending.poll();
        if (next == null && loopMode == LoopMode.QUEUE && !history.isEmpty()) {
            for (AudioTrack h : history) {
                pending.add(copyOf(h));
            }
            history.clear();
            next = pending.poll();
        }
        if (next == null) {
            current = null;
            paused = false;
            history.clear();
            player.stopTrack();
            idleSince = System.currentTimeMillis();
        } else {
            record(next);
            start(next, true);
        }
    }

    /** Milliseconds elapsed since the session became idle (0 if currently playing). */
    public long idleFor() {
        long since = idleSince;
        return since == 0 ? 0 : System.currentTimeMillis() - since;
    }

    // ---------------------------------------------------------------- Messages

    public static Component trackLine(Messages m, AudioTrack t) {
        String len = t.getInfo().isStream ? m.plain("bossbar.live") : TimeFmt.format(t.getDuration());
        return m.get("track-line", "title", t.getInfo().title, "length", len);
    }

    private Component nowPlaying(AudioTrack t) {
        Messages m = plugin.messages();
        Object by = t.getUserData();
        Component requested = by == null ? Component.empty() : m.get("announce.requested-by", "player", String.valueOf(by));
        return m.get("announce.now-playing", "track", trackLine(m, t), "requested", requested);
    }

    /** Broadcasts a message to all members in this voice group. Safe from any thread. */
    public void broadcast(Component message) {
        Runnable send = () -> {
            for (VoicechatConnection c : members) {
                Player p = Bukkit.getPlayer(c.getPlayer().getUuid());
                if (p != null) {
                    p.sendMessage(message);
                }
            }
        };
        if (Bukkit.isPrimaryThread()) {
            send.run();
        } else if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, send);
        }
    }

    // --------------------------------------------------------------- BossBar

    /** Updates Bossbar progress and viewers. Called every second on main thread. */
    void updateBossBar(Set<UUID> hidden) {
        AudioTrack t;
        boolean isPaused;
        LoopMode mode;
        synchronized (this) {
            t = current;
            isPaused = paused;
            mode = loopMode;
        }
        if (t == null || !plugin.cfg().general().getBoolean("bossbar.enabled", true)) {
            clearBar();
            return;
        }

        Messages m = plugin.messages();
        long dur = t.getDuration();
        boolean live = t.getInfo().isStream || dur <= 0 || dur == Long.MAX_VALUE;
        float progress = live ? 1f : (float) Math.min(1.0, Math.max(0.0, t.getPosition() / (double) dur));

        String author = t.getInfo().author;
        Component authorPart = Component.empty();
        if (author != null && !author.isBlank() && !author.toLowerCase(Locale.ROOT).startsWith("unknown")) {
            authorPart = m.get("bossbar.author", "author", shorten(author, 24));
        }
        String time = live ? m.plain("bossbar.live") : TimeFmt.format(t.getPosition()) + " / " + TimeFmt.format(dur);

        bar.name(m.get("bossbar.title",
                "title", shorten(t.getInfo().title, 46),
                "author", authorPart,
                "time", time,
                "paused", isPaused ? m.get("bossbar.paused") : Component.empty(),
                "loop", switch (mode) {
                    case TRACK -> m.get("bossbar.loop");
                    case QUEUE -> m.get("bossbar.loop-queue");
                    case OFF -> Component.empty();
                }));
        bar.progress(progress);

        var g = plugin.cfg().general();
        BossBar.Color color = isPaused
                ? enumOr(BossBar.Color.class, g.getString("bossbar.color-paused", "YELLOW"), BossBar.Color.YELLOW)
                : live
                ? enumOr(BossBar.Color.class, g.getString("bossbar.color-live", "RED"), BossBar.Color.RED)
                : enumOr(BossBar.Color.class, g.getString("bossbar.color-playing", "PINK"), BossBar.Color.PINK);
        bar.color(color);

        Set<UUID> want = new HashSet<>(memberIds);
        want.removeAll(hidden);
        for (Iterator<UUID> it = barViewers.iterator(); it.hasNext(); ) {
            UUID id = it.next();
            if (!want.contains(id)) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) {
                    p.hideBossBar(bar);
                }
                it.remove();
            }
        }
        for (UUID id : want) {
            if (barViewers.contains(id)) {
                continue;
            }
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.showBossBar(bar);
                barViewers.add(id);
            }
        }
    }

    private void clearBar() {
        for (UUID id : barViewers) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.hideBossBar(bar);
            }
        }
        barViewers.clear();
    }

    private static <E extends Enum<E>> E enumOr(Class<E> type, String name, E def) {
        try {
            return Enum.valueOf(type, name.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            return def;
        }
    }

    private static String shorten(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    // ---------------------------------------------------------------- Cleanup

    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        pending.clear();
        history.clear();
        current = null;
        output.stopPlaying();
        player.destroy();
        encoder.close();
        if (Bukkit.isPrimaryThread()) {
            clearBar();
        } else if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, this::clearBar);
        }
    }
}
