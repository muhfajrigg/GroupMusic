package me.muhfajrigg.music.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.ArrayList;
import java.util.List;

/**
 * Message renderer formatting strings with Adventure MiniMessage.
 * Raw strings are safely treated as plain text placeholders unless wrapped in {@link Raw}.
 */
public final class Messages {

    /** Unescaped string to be parsed as MiniMessage tags (e.g. for URLs in click events). */
    public record Raw(String value) {
    }

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final ConfigManager cfg;

    public Messages(ConfigManager cfg) {
        this.cfg = cfg;
    }

    public Component get(String key, Object... args) {
        String raw = cfg.messages().getString(key);
        if (raw == null) {
            return Component.text("[Missing message: " + key + "]", NamedTextColor.RED);
        }
        return render(raw, args);
    }

    public List<Component> lines(String key, Object... args) {
        List<Component> out = new ArrayList<>();
        for (String line : cfg.messages().getStringList(key)) {
            out.add(render(line, args));
        }
        return out;
    }

    /** Plain text representation without color codes. */
    public String plain(String key, Object... args) {
        return PlainTextComponentSerializer.plainText().serialize(get(key, args));
    }

    private Component render(String raw, Object... args) {
        TagResolver.Builder b = TagResolver.builder();
        b.resolver(Placeholder.parsed("prefix", cfg.messages().getString("prefix", "<aqua>♪ </aqua>")));
        for (int i = 0; i + 1 < args.length; i += 2) {
            String name = String.valueOf(args[i]);
            Object v = args[i + 1];
            if (v instanceof Component c) {
                b.resolver(Placeholder.component(name, c));
            } else if (v instanceof Raw r) {
                b.resolver(Placeholder.parsed(name, r.value()));
            } else {
                b.resolver(Placeholder.unparsed(name, String.valueOf(v)));
            }
        }
        return MM.deserialize(raw, b.build());
    }
}
