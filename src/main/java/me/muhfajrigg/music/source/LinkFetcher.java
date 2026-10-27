package me.muhfajrigg.music.source;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches page metadata (OpenGraph / Title) from external URLs for lookup hosts.
 * Includes SSRF protection: validates non-public IP ranges, restricts redirect hops,
 * and limits payload size.
 */
public final class LinkFetcher {

    public record Meta(URI finalUri, String ogTitle, String title, String description, String siteName) {
    }

    private static final int MAX_BYTES = 400_000;
    private static final int MAX_REDIRECTS = 4;

    private static final Pattern META_TAG = Pattern.compile("<meta\\s[^>]*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ATTR = Pattern.compile("([a-zA-Z:_-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')");
    private static final Pattern TITLE = Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ENTITY = Pattern.compile("&(#[xX][0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);");

    private final Executor executor;
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    public LinkFetcher(Executor executor) {
        this.executor = executor;
    }

    private record Page(URI uri, String html) {
    }

    /** Fetches raw HTML string with size limitation and SSRF protections. */
    public CompletableFuture<String> fetchHtml(URI start, Predicate<URI> allowed, String userAgent,
                                               int timeoutSeconds, int maxBytes) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return fetchPage(start, allowed, userAgent, timeoutSeconds, maxBytes).html();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CompletionException(e);
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }, executor);
    }

    public CompletableFuture<Meta> fetch(URI start, Predicate<URI> allowed, String userAgent, int timeoutSeconds) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return fetchBlocking(start, allowed, userAgent, timeoutSeconds);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CompletionException(e);
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }, executor);
    }

    private Meta fetchBlocking(URI start, Predicate<URI> allowed, String userAgent, int timeoutSeconds)
            throws IOException, InterruptedException {
        Page p = fetchPage(start, allowed, userAgent, timeoutSeconds, MAX_BYTES);
        return parse(p.uri(), p.html());
    }

    private Page fetchPage(URI start, Predicate<URI> allowed, String userAgent, int timeoutSeconds, int maxBytes)
            throws IOException, InterruptedException {
        URI uri = start;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            if (!allowed.test(uri)) {
                throw new IOException("Host not allowed: " + uri.getHost());
            }
            requirePublic(uri);

            HttpRequest req = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(Math.max(2, timeoutSeconds)))
                    .header("User-Agent", userAgent)
                    .header("Accept", "text/html,application/xhtml+xml")
                    .header("Accept-Language", "en-US,en;q=0.8")
                    .GET()
                    .build();
            HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            int code = res.statusCode();

            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                res.body().close();
                String loc = res.headers().firstValue("location").orElse(null);
                if (loc == null || loc.isBlank()) {
                    throw new IOException("Redirect header without location");
                }
                uri = uri.resolve(loc.trim());
                continue;
            }
            if (code != 200) {
                res.body().close();
                throw new IOException("HTTP " + code);
            }
            String html;
            try (InputStream in = res.body()) {
                html = new String(in.readNBytes(maxBytes), StandardCharsets.UTF_8);
            }
            return new Page(uri, html);
        }
        throw new IOException("Too many redirects");
    }

    /** Rejects non-public/internal network addresses to prevent SSRF vulnerabilities. */
    static void requirePublic(URI uri) throws IOException {
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IOException("Unsupported URL scheme");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IOException("URL without host");
        }
        for (InetAddress a : InetAddress.getAllByName(host)) {
            byte[] b = a.getAddress();
            boolean carrierNat = b.length == 4 && (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 64;
            boolean uniqueLocalV6 = b.length == 16 && (b[0] & 0xFE) == 0xFC;
            if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress()
                    || a.isSiteLocalAddress() || a.isMulticastAddress() || carrierNat || uniqueLocalV6) {
                throw new IOException("Non-public network address blocked");
            }
        }
    }

    // --------------------------------------------------------------- Parsing

    static Meta parse(URI uri, String html) {
        int headEnd = html.toLowerCase(Locale.ROOT).indexOf("</head>");
        String head = headEnd > 0 ? html.substring(0, headEnd) : html;

        Map<String, String> metas = new HashMap<>();
        Matcher m = META_TAG.matcher(head);
        while (m.find()) {
            Map<String, String> attrs = new HashMap<>();
            Matcher a = ATTR.matcher(m.group());
            while (a.find()) {
                attrs.put(a.group(1).toLowerCase(Locale.ROOT), a.group(2) != null ? a.group(2) : a.group(3));
            }
            String key = attrs.containsKey("property") ? attrs.get("property") : attrs.get("name");
            String content = attrs.get("content");
            if (key != null && content != null) {
                metas.putIfAbsent(key.toLowerCase(Locale.ROOT), unescape(content).trim());
            }
        }
        Matcher t = TITLE.matcher(head);
        String title = t.find() ? unescape(t.group(1)).trim() : "";

        String ogTitle = firstNonBlank(metas.get("og:title"), metas.get("twitter:title"));
        String desc = firstNonBlank(metas.get("og:description"), metas.get("twitter:description"), metas.get("description"));
        String site = firstNonBlank(metas.get("og:site_name"));
        return new Meta(uri, ogTitle, title, desc, site);
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "";
    }

    static String unescape(String s) {
        Matcher m = ENTITY.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String e = m.group(1);
            String r = m.group();
            try {
                if (e.startsWith("#x") || e.startsWith("#X")) {
                    r = new String(Character.toChars(Integer.parseInt(e.substring(2), 16)));
                } else if (e.startsWith("#")) {
                    r = new String(Character.toChars(Integer.parseInt(e.substring(1))));
                } else {
                    r = switch (e.toLowerCase(Locale.ROOT)) {
                        case "amp" -> "&";
                        case "lt" -> "<";
                        case "gt" -> ">";
                        case "quot" -> "\"";
                        case "apos" -> "'";
                        case "nbsp" -> " ";
                        case "middot" -> "\u00B7";
                        case "ndash" -> "\u2013";
                        case "mdash" -> "\u2014";
                        default -> m.group();
                    };
                }
            } catch (IllegalArgumentException ignored) {
                // invalid character entity: keep as is
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(r));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
