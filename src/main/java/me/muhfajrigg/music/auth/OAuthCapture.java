package me.muhfajrigg.music.auth;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Intercepts YouTube OAuth verification codes and activation links
 * from the dev.lavalink.youtube logger to display them directly in player chat.
 */
public final class OAuthCapture extends AbstractAppender {

    public interface Listener {
        void onCode(String url, String code, String raw);

        void onToken(String refreshToken);
    }

    private static final Pattern URL = Pattern.compile("https?://[^\\s\"')]+");
    private static final Pattern CODE = Pattern.compile("\\b[A-Z0-9]{3,5}(?:-[A-Z0-9]{3,5}){2}\\b");
    private static final Pattern TOKEN = Pattern.compile("1//[A-Za-z0-9_\\-]{20,}");

    private final Listener listener;
    private volatile boolean attached;

    public OAuthCapture(Listener listener) {
        super("GroupMusicOAuth", null, null, true, Property.EMPTY_ARRAY);
        this.listener = listener;
    }

    public synchronized void attach() {
        if (attached) {
            return;
        }
        start();
        ((org.apache.logging.log4j.core.Logger) LogManager.getRootLogger()).addAppender(this);
        attached = true;
    }

    public synchronized void detach() {
        if (!attached) {
            return;
        }
        attached = false;
        ((org.apache.logging.log4j.core.Logger) LogManager.getRootLogger()).removeAppender(this);
        stop();
    }

    @Override
    public void append(LogEvent event) {
        String logger = event.getLoggerName();
        if (logger == null || !logger.startsWith("dev.lavalink.youtube")) {
            return;
        }
        String msg = event.getMessage().getFormattedMessage();
        if (msg == null) {
            return;
        }

        Matcher t = TOKEN.matcher(msg);
        if (t.find()) {
            listener.onToken(t.group());
            return;
        }
        if (msg.contains("/device")) {
            Matcher u = URL.matcher(msg);
            String url = u.find() ? u.group() : "https://www.google.com/device";
            Matcher c = CODE.matcher(msg);
            listener.onCode(url, c.find() ? c.group() : null, msg);
        }
    }
}
