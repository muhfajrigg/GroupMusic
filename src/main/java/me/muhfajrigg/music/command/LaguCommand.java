package me.muhfajrigg.music.command;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import me.muhfajrigg.music.GroupMusic;
import me.muhfajrigg.music.config.Messages;
import me.muhfajrigg.music.music.GroupSession;
import me.muhfajrigg.music.music.MusicManager;
import me.muhfajrigg.music.source.TrackResolver;
import me.muhfajrigg.music.util.TimeFmt;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Handles all in-game music commands for players and server administrators.
 */
public final class LaguCommand implements CommandExecutor, TabCompleter {

    public static final List<String> NAMES = List.of(
            "music", "musicsearch", "musicstop", "musicpause", "musicskip",
            "musicqueue", "musicnow", "musicloop", "musicshuffle", "musicbar", "musicvolume",
            "musiclogin", "musicreload");

    private static final int QUEUE_PAGE = 10;

    private final GroupMusic plugin;
    private final MusicManager music;

    public LaguCommand(GroupMusic plugin, MusicManager music) {
        this.plugin = plugin;
        this.music = music;
    }

    private Messages m() {
        return plugin.messages();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String name = cmd.getName().toLowerCase(Locale.ROOT);

        // Admin commands: can be executed from console without being in a voice group
        if (name.equals("musiclogin") || name.equals("lagulogin")
                || name.equals("musicreload") || name.equals("lagureload")) {
            if (!sender.hasPermission("groupmusic.admin")) {
                sender.sendMessage(m().get("general.no-permission"));
                return true;
            }
            if (name.contains("login")) {
                music.startLogin(sender);
            } else {
                plugin.reloadAll();
                sender.sendMessage(m().get("reload.done"));
                sender.sendMessage(m().get("reload.note"));
            }
            return true;
        }

        if (!(sender instanceof Player p)) {
            sender.sendMessage(m().get("general.players-only"));
            return true;
        }

        if (name.equals("musicbar") || name.equals("lagubar")) {
            boolean on = music.toggleBar(p.getUniqueId());
            p.sendMessage(m().get(on ? "general.bossbar-on" : "general.bossbar-off"));
            return true;
        }

        if (!music.ready()) {
            p.sendMessage(m().get("general.voice-not-ready"));
            return true;
        }

        // Primary requirement: player must be connected to SVC and inside a voice group
        VoicechatConnection conn = music.connectionOf(p);
        if (conn == null) {
            p.sendMessage(m().get(isBedrock(p) ? "general.no-voice-connection-bedrock" : "general.no-voice-connection"));
            return true;
        }
        Group group = conn.getGroup();
        if (group == null) {
            p.sendMessage(m().get(isBedrock(p) ? "general.no-group-bedrock" : "general.no-group"));
            return true;
        }
        UUID gid = group.getId();

        switch (name) {
            case "music", "lagu" -> handleMusic(p, gid, args);
            case "musicsearch", "lagucari" -> suggest(p, gid, args);
            case "musicstop", "lagustop" -> stop(p, gid);
            case "musicpause", "lagupause" -> pause(p, gid);
            case "musicskip", "laguskip" -> skip(p, gid);
            case "musicqueue", "laguqueue" -> queue(p, gid);
            case "musicnow", "lagunow" -> now(p, gid);
            case "musicloop", "lagulagi" -> loop(p, gid, args);
            case "musicshuffle", "lagushuffle" -> shuffle(p, gid);
            case "musicvolume", "laguvolume" -> volume(p, gid, args);
            default -> {
            }
        }
        return true;
    }

    private void handleMusic(Player p, UUID gid, String[] args) {
        if (args.length == 0) {
            sendHelp(p);
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        String[] subArgs = java.util.Arrays.copyOfRange(args, 1, args.length);
        switch (sub) {
            case "help", "h", "bantuan", "?" -> {
                sendHelp(p);
                return;
            }
            case "play", "p" -> {
                play(p, gid, subArgs);
                return;
            }
            case "pause", "resume" -> {
                pause(p, gid);
                return;
            }
            case "skip", "s" -> {
                skip(p, gid);
                return;
            }
            case "queue", "q" -> {
                queue(p, gid);
                return;
            }
            case "volume", "vol" -> {
                volume(p, gid, subArgs);
                return;
            }
            case "loop", "lp" -> {
                loop(p, gid, subArgs);
                return;
            }
            case "search", "cari" -> {
                suggest(p, gid, subArgs);
                return;
            }
            case "stop" -> {
                stop(p, gid);
                return;
            }
            case "shuffle" -> {
                shuffle(p, gid);
                return;
            }
            case "now", "np" -> {
                now(p, gid);
                return;
            }
        }
        play(p, gid, args);
    }

    private void sendHelp(Player p) {
        p.sendMessage(m().get("help.header"));
        for (Component line : m().lines("help.lines")) {
            p.sendMessage(line);
        }
        p.sendMessage(m().get("help.footer"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        String name = cmd.getName().toLowerCase(Locale.ROOT);
        if (args.length == 1) {
            List<String> options = switch (name) {
                case "music", "lagu" -> List.of("play", "pause", "resume", "skip", "queue", "volume", "loop", "search", "stop", "shuffle", "now", "help");
                case "musicloop", "lagulagi" -> List.of("track", "queue", "off", "lagu", "semua", "mati");
                case "musicvolume", "laguvolume" -> List.of("0", "25", "50", "75", "100");
                default -> List.of();
            };
            String typed = args[0].toLowerCase(Locale.ROOT);
            return options.stream().filter(o -> o.startsWith(typed)).toList();
        } else if (args.length == 2 && (name.equals("music") || name.equals("lagu"))) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            List<String> options = switch (sub) {
                case "loop", "lp" -> List.of("off", "track", "queue");
                case "volume", "vol" -> List.of("0", "25", "50", "75", "100");
                default -> List.of();
            };
            String typed = args[1].toLowerCase(Locale.ROOT);
            return options.stream().filter(o -> o.startsWith(typed)).toList();
        }
        return List.of();
    }

    // ------------------------------------------------------------------ Play

    private void play(Player p, UUID gid, String[] args) {
        if (args.length == 0) {
            p.sendMessage(m().get("play.usage"));
            return;
        }
        String query = String.join(" ", args);
        p.sendMessage(m().get("play.searching", "query", query));
        music.resolver().resolve(query, p.getUniqueId(), callback(p, gid));
    }

    /** AI music suggestion based on a mood or natural language description. */
    private void suggest(Player p, UUID gid, String[] args) {
        if (!music.ai().available() || !music.ai().suggestEnabled()) {
            p.sendMessage(m().get("ai.disabled"));
            return;
        }
        if (args.length == 0) {
            p.sendMessage(m().get("ai.usage"));
            return;
        }
        if (!p.hasPermission("groupmusic.ai.bypass-cooldown")) {
            long wait = music.ai().tryAcquire(p.getUniqueId());
            if (wait > 0) {
                p.sendMessage(m().get("ai.cooldown", "seconds", wait));
                return;
            }
        }
        String request = String.join(" ", args);
        p.sendMessage(m().get("ai.thinking", "query", request));
        music.resolver().suggest(request, callback(p, gid));
    }

    private TrackResolver.Callback callback(Player p, UUID gid) {
        return new TrackResolver.Callback() {
            @Override
            public void found(List<AudioTrack> tracks) {
                sync(() -> addTracks(p, gid, tracks));
            }

            @Override
            public void none() {
                sync(() -> p.sendMessage(m().get("play.not-found")));
            }

            @Override
            public void failed(String reason) {
                sync(() -> p.sendMessage(m().get("play.load-failed", "reason", reason)));
            }
        };
    }

    private void addTracks(Player p, UUID gid, List<AudioTrack> tracks) {
        if (!p.isOnline()) {
            return;
        }
        // Ensure player is still in the same voice group after resolving completes
        VoicechatConnection c = music.connectionOf(p);
        Group g = c == null ? null : c.getGroup();
        if (g == null || !g.getId().equals(gid)) {
            p.sendMessage(m().get("general.left-group"));
            return;
        }

        int maxQueue = plugin.cfg().general().getInt("limits.max-queue", 50);
        long maxMs = plugin.cfg().general().getLong("limits.max-track-minutes", 20) * 60_000L;

        GroupSession session = music.sessionFor(gid);
        music.refresh(session);

        int added = 0;
        int tooLong = 0;
        boolean full = false;
        int lastPos = 0;
        AudioTrack last = null;

        for (AudioTrack t : tracks) {
            if (maxMs > 0 && !t.getInfo().isStream && t.getDuration() > maxMs) {
                tooLong++;
                continue;
            }
            if (session.pendingSize() >= maxQueue) {
                full = true;
                break;
            }
            t.setUserData(p.getName());
            lastPos = session.enqueue(t);
            last = t;
            added++;
        }

        if (added == 0) {
            if (full) {
                p.sendMessage(m().get("play.queue-full", "max", maxQueue));
            } else if (tooLong > 0) {
                p.sendMessage(m().get("play.too-long", "minutes", maxMs / 60_000L));
            }
            return;
        }
        if (added == 1 && lastPos > 0) {
            p.sendMessage(m().get("play.queued", "position", lastPos, "track", GroupSession.trackLine(m(), last)));
        } else if (added > 1) {
            p.sendMessage(m().get("play.added-many", "count", added));
        }
        if (tooLong > 0) {
            p.sendMessage(m().get("play.skipped-long", "count", tooLong));
        }
        if (full) {
            p.sendMessage(m().get("play.skipped-full"));
        }
    }

    // --------------------------------------------------------- Controls

    private void stop(Player p, UUID gid) {
        GroupSession s = music.existing(gid);
        if (s == null) {
            p.sendMessage(m().get("control.nothing-playing-group"));
            return;
        }
        s.broadcast(m().get("announce.stopped", "player", p.getName()));
        music.remove(s);
    }

    private void pause(Player p, UUID gid) {
        GroupSession s = music.existing(gid);
        Boolean paused = s == null ? null : s.togglePause();
        if (paused == null) {
            p.sendMessage(m().get("control.nothing-playing"));
            return;
        }
        s.broadcast(m().get(paused ? "announce.paused" : "announce.resumed", "player", p.getName()));
    }

    private void skip(Player p, UUID gid) {
        GroupSession s = music.existing(gid);
        if (s == null || !s.skip()) {
            p.sendMessage(m().get("control.nothing-to-skip"));
            return;
        }
        s.broadcast(m().get("announce.skipped", "player", p.getName()));
    }

    private void queue(Player p, UUID gid) {
        GroupSession s = music.existing(gid);
        AudioTrack cur = s == null ? null : s.current();
        if (cur == null) {
            p.sendMessage(m().get("queue.empty"));
            return;
        }
        List<AudioTrack> pending = s.pendingSnapshot();
        p.sendMessage(m().get("queue.header", "track", GroupSession.trackLine(m(), cur)));
        if (pending.isEmpty()) {
            p.sendMessage(m().get("queue.none-next"));
            return;
        }
        int shown = Math.min(QUEUE_PAGE, pending.size());
        for (int i = 0; i < shown; i++) {
            p.sendMessage(m().get("queue.line", "number", i + 1, "track", GroupSession.trackLine(m(), pending.get(i))));
        }
        if (pending.size() > shown) {
            p.sendMessage(m().get("queue.more", "count", pending.size() - shown));
        }
    }

    private void now(Player p, UUID gid) {
        GroupSession s = music.existing(gid);
        AudioTrack cur = s == null ? null : s.current();
        if (cur == null) {
            p.sendMessage(m().get("control.nothing-playing"));
            return;
        }
        String progress = cur.getInfo().isStream
                ? m().plain("bossbar.live")
                : TimeFmt.format(cur.getPosition()) + " / " + TimeFmt.format(cur.getDuration());
        p.sendMessage(m().get("now.line",
                "title", cur.getInfo().title,
                "progress", progress,
                "loop", switch (s.loopMode()) {
                    case TRACK -> m().get("now.loop-tag");
                    case QUEUE -> m().get("now.loop-queue-tag");
                    case OFF -> Component.empty();
                }));
    }

    /**
     * Toggles or sets loop mode:
     * - /musicloop -> Cycle: OFF -> TRACK -> QUEUE -> OFF
     * - /musicloop track -> Loop current track
     * - /musicloop queue -> Loop whole queue
     * - /musicloop off   -> Disable loop
     */
    private void loop(Player p, UUID gid, String[] args) {
        GroupSession s = music.existing(gid);
        if (s == null || s.current() == null) {
            p.sendMessage(m().get("control.nothing-playing"));
            return;
        }
        GroupSession.LoopMode mode;
        if (args.length == 0) {
            mode = s.cycleLoop();
        } else {
            mode = switch (args[0].toLowerCase(Locale.ROOT)) {
                case "track", "one", "single", "lagu", "satu" -> GroupSession.LoopMode.TRACK;
                case "queue", "all", "semua", "antrean", "antrian" -> GroupSession.LoopMode.QUEUE;
                case "off", "no", "disable", "mati" -> GroupSession.LoopMode.OFF;
                default -> null;
            };
            if (mode == null) {
                p.sendMessage(m().get("loop.usage"));
                return;
            }
            s.setLoopMode(mode);
        }
        String key = switch (mode) {
            case TRACK -> "announce.loop-on";
            case QUEUE -> "announce.loop-queue-on";
            case OFF -> "announce.loop-off";
        };
        s.broadcast(m().get(key, "player", p.getName()));
    }

    /** /musicvolume [0-100]: Displays or adjusts group volume level. */
    private void volume(Player p, UUID gid, String[] args) {
        if (args.length == 0) {
            p.sendMessage(m().get("volume.current", "volume", music.volumeOf(gid)));
            return;
        }
        String raw = args[0].trim();
        if (raw.endsWith("%")) {
            raw = raw.substring(0, raw.length() - 1);
        }
        int value;
        try {
            value = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            p.sendMessage(m().get("volume.usage"));
            return;
        }
        if (value < 0 || value > 100) {
            p.sendMessage(m().get("volume.usage"));
            return;
        }
        music.setVolume(gid, value);
        GroupSession s = music.existing(gid);
        Component msg = m().get("announce.volume", "player", p.getName(), "volume", value);
        if (s != null) {
            s.broadcast(msg);
        } else {
            p.sendMessage(msg);
        }
    }

    private void shuffle(Player p, UUID gid) {
        GroupSession s = music.existing(gid);
        if (s == null || !s.shuffle()) {
            p.sendMessage(m().get("control.shuffle-too-few"));
            return;
        }
        s.broadcast(m().get("announce.shuffled", "player", p.getName()));
    }

    private void sync(Runnable r) {
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, r);
        }
    }

    /** Bedrock players using Floodgate have a UUID with 0 as the most significant bits. */
    private static boolean isBedrock(Player p) {
        return p.getUniqueId().getMostSignificantBits() == 0L;
    }
}
