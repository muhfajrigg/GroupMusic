package me.muhfajrigg.music.auth;

import dev.lavalink.youtube.YoutubeAudioSourceManager;
import me.muhfajrigg.music.GroupMusic;
import me.muhfajrigg.music.config.Messages;
import me.muhfajrigg.music.source.TrackResolver;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * Handles Google OAuth login for YouTube source.
 * Intercepts verification code and activation link to display them in chat and title.
 * Automatically saves the refresh token to youtube.yml.
 */
public final class YoutubeLogin {

    private final GroupMusic plugin;
    private final YoutubeAudioSourceManager youtube;
    private OAuthCapture capture;
    private BukkitTask timeout;

    public YoutubeLogin(GroupMusic plugin, YoutubeAudioSourceManager youtube) {
        this.plugin = plugin;
        this.youtube = youtube;
    }

    private Messages m() {
        return plugin.messages();
    }

    public synchronized void start(CommandSender sender) {
        stop();
        capture = new OAuthCapture(new OAuthCapture.Listener() {
            @Override
            public void onCode(String url, String code, String raw) {
                runMain(() -> showCode(sender, url, code, raw));
            }

            @Override
            public void onToken(String refreshToken) {
                runMain(() -> {
                    plugin.cfg().youtube().set("oauth-refresh-token", refreshToken);
                    plugin.cfg().youtube().set("oauth", false);
                    plugin.cfg().saveYoutube();
                    sender.sendMessage(m().get("login.success"));
                    sender.sendMessage(m().get("login.success-info"));
                    stop();
                });
            }
        });
        capture.attach();
        // 15-minute expiration timeout to ensure Log4j appender does not leak memory
        timeout = Bukkit.getScheduler().runTaskLater(plugin, this::stop, 20L * 60 * 15);

        sender.sendMessage(m().get("login.starting"));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                youtube.useOauth2(null, false);
            } catch (Exception e) {
                plugin.getLogger().warning("YouTube login failed to start: " + TrackResolver.describe(e));
                runMain(() -> sender.sendMessage(m().get("login.failed-start")));
                runMain(this::stop);
            }
        });
    }

    private void showCode(CommandSender sender, String url, String code, String raw) {
        Messages.Raw safeUrl = new Messages.Raw(strip(url));
        if (code != null) {
            Messages.Raw safeCode = new Messages.Raw(strip(code));
            for (Component line : m().lines("login.lines", "url", safeUrl, "code", safeCode)) {
                sender.sendMessage(line);
            }
        } else {
            for (Component line : m().lines("login.lines-no-code", "url", safeUrl, "raw", raw == null ? "" : raw)) {
                sender.sendMessage(line);
            }
        }
        if (sender instanceof Player p && code != null) {
            p.showTitle(Title.title(
                    Component.text(code, NamedTextColor.YELLOW, TextDecoration.BOLD),
                    m().get("login.title-sub"),
                    Title.Times.times(java.time.Duration.ofMillis(300), java.time.Duration.ofSeconds(40),
                            java.time.Duration.ofSeconds(1))));
        }
    }

    private static String strip(String s) {
        return s == null ? "" : s.replaceAll("[<>'\"\\s]", "");
    }

    public synchronized void stop() {
        if (capture != null) {
            capture.detach();
            capture = null;
        }
        if (timeout != null) {
            timeout.cancel();
            timeout = null;
        }
    }

    private void runMain(Runnable r) {
        if (Bukkit.isPrimaryThread()) {
            r.run();
        } else if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, r);
        }
    }
}
