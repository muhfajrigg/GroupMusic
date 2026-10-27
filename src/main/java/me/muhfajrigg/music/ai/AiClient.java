package me.muhfajrigg.music.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import me.muhfajrigg.music.GroupMusic;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Lightweight HTTP client for AI completion endpoints. Supports:
 * - "openai"   : /chat/completions (OpenAI, Groq, OpenRouter, Gemini OpenAI-compatible, Ollama, etc.)
 * - "anthropic": /v1/messages (Claude)
 */
public final class AiClient {

    private final GroupMusic plugin;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final Gson gson = new Gson();

    public AiClient(GroupMusic plugin) {
        this.plugin = plugin;
    }

    private YamlConfiguration cfg() {
        return plugin.cfg().ai();
    }

    private String apiKey() {
        String key = cfg().getString("api-key", "");
        if (key == null || key.isBlank()) {
            key = System.getenv("GROUPMUSIC_AI_KEY");
        }
        return key == null ? "" : key.trim();
    }

    public boolean available() {
        return cfg().getBoolean("enabled", false) && !apiKey().isEmpty();
    }

    /** Sends prompt and returns raw response string from model. */
    public CompletableFuture<String> complete(String system, String user) {
        YamlConfiguration c = cfg();
        String provider = c.getString("provider", "openai").trim().toLowerCase(Locale.ROOT);
        boolean anthropic = provider.equals("anthropic") || provider.equals("claude");

        String base = c.getString("base-url", "").trim();
        if (base.isEmpty()) {
            base = anthropic ? "https://api.anthropic.com" : "https://api.openai.com/v1";
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String model = c.getString("model", "").trim();
        if (model.isEmpty()) {
            model = anthropic ? "claude-haiku-5-5" : "gpt-4o-mini";
        }
        int maxTokens = Math.max(16, c.getInt("max-output-tokens", 300));
        int timeout = Math.max(3, c.getInt("timeout-seconds", 20));
        String key = apiKey();

        HttpRequest request;
        try {
            if (anthropic) {
                if (base.endsWith("/v1")) {
                    base = base.substring(0, base.length() - 3);
                }
                JsonObject body = new JsonObject();
                body.addProperty("model", model);
                body.addProperty("max_tokens", maxTokens);
                body.addProperty("system", system);
                JsonArray msgs = new JsonArray();
                msgs.add(message("user", user));
                body.add("messages", msgs);
                request = HttpRequest.newBuilder(URI.create(base + "/v1/messages"))
                        .timeout(Duration.ofSeconds(timeout))
                        .header("Content-Type", "application/json")
                        .header("x-api-key", key)
                        .header("anthropic-version", "2023-06-01")
                        .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                        .build();
            } else {
                JsonObject body = new JsonObject();
                body.addProperty("model", model);
                body.addProperty(c.getString("max-tokens-field", "max_tokens"), maxTokens);
                JsonArray msgs = new JsonArray();
                msgs.add(message("system", system));
                msgs.add(message("user", user));
                body.add("messages", msgs);
                request = HttpRequest.newBuilder(URI.create(base + "/chat/completions"))
                        .timeout(Duration.ofSeconds(timeout))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + key)
                        .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                        .build();
            }
        } catch (IllegalArgumentException e) {
            return CompletableFuture.failedFuture(new IOException("base-url in ai.yml is invalid"));
        }

        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(res -> {
            if (res.statusCode() / 100 != 2) {
                String b = res.body() == null ? "" : res.body();
                throw new CompletionException(new IOException("HTTP " + res.statusCode() + ": "
                        + (b.length() > 200 ? b.substring(0, 200) : b)));
            }
            try {
                JsonObject o = gson.fromJson(res.body(), JsonObject.class);
                if (anthropic) {
                    return o.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
                }
                return o.getAsJsonArray("choices").get(0).getAsJsonObject()
                        .getAsJsonObject("message").get("content").getAsString();
            } catch (RuntimeException e) {
                throw new CompletionException(new IOException("Unrecognized AI API response format"));
            }
        });
    }

    private static JsonObject message(String role, String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", role);
        m.addProperty("content", content);
        return m;
    }
}
