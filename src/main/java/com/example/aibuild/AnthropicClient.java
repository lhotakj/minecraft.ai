package com.example.aibuild;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Claude, via the Anthropic Messages API with model failover and extended
 * thinking support.
 */
final class AnthropicClient implements AiProvider {

    private static final URI ENDPOINT = URI.create("https://api.anthropic.com/v1/messages");

    private final String apiKey;
    private final List<String> models;
    private final int maxTokens;
    private final String thinkingLevel;
    private final int timeoutSeconds;

    AnthropicClient(String apiKey, List<String> models, int maxTokens, int timeoutSeconds) {
        this(apiKey, models, maxTokens, "", timeoutSeconds);
    }

    AnthropicClient(String apiKey, List<String> models, int maxTokens, String thinkingLevel, int timeoutSeconds) {
        this.apiKey = apiKey;
        this.models = models == null || models.isEmpty() ? List.of("claude-sonnet-5-5") : models;
        this.maxTokens = maxTokens;
        this.thinkingLevel = thinkingLevel == null ? "" : thinkingLevel.trim().toLowerCase(Locale.ROOT);
        this.timeoutSeconds = Math.max(10, timeoutSeconds);
    }

    @Override
    public String label() {
        if (models.size() == 1) {
            return models.get(0);
        }
        return models.get(0) + " (+" + (models.size() - 1) + " fallbacks)";
    }

    @Override
    public String complete(String system, String user, Consumer<String> statusListener)
            throws IOException, InterruptedException {
        List<String> errors = new ArrayList<>();
        int perModelTimeout = Math.min(timeoutSeconds, Math.max(30, timeoutSeconds / Math.max(1, models.size())));

        for (int i = 0; i < models.size(); i++) {
            String model = models.get(i);
            statusListener
                    .accept("Calling Claude API with model '" + model + "' (" + (i + 1) + "/" + models.size() + ")...");
            long start = System.currentTimeMillis();
            try {
                String reply = callMessages(model, system, user, perModelTimeout);
                long elapsed = System.currentTimeMillis() - start;
                statusListener.accept("Received response from '" + model + "' in " + elapsed + "ms");
                return reply;
            } catch (Exception e) {
                long elapsed = System.currentTimeMillis() - start;
                String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                errors.add(model + " (" + elapsed + "ms): " + msg);
                if (i + 1 < models.size()) {
                    String next = models.get(i + 1);
                    statusListener
                            .accept("Model '" + model + "' failed (" + msg + "). Failing over to '" + next + "'...");
                }
            }
        }
        throw new IOException("All configured Claude models failed:\n- " + String.join("\n- ", errors));
    }

    private String callMessages(String model, String system, String user, int timeout)
            throws IOException, InterruptedException {
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.addProperty("content", user);
        JsonArray messages = new JsonArray();
        messages.add(message);

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("max_tokens", maxTokens);
        body.addProperty("system", system);
        body.add("messages", messages);

        if (!thinkingLevel.isEmpty()) {
            int budgetTokens = switch (thinkingLevel) {
                case "low" -> Math.min(2048, Math.max(1024, maxTokens / 4));
                case "medium" -> Math.min(8192, Math.max(1024, maxTokens / 2));
                case "high" -> Math.min(16384, Math.max(1024, (int) (maxTokens * 0.75)));
                default -> 0;
            };
            if (budgetTokens >= 1024 && maxTokens > budgetTokens) {
                JsonObject thinking = new JsonObject();
                thinking.addProperty("type", "enabled");
                thinking.addProperty("budget_tokens", budgetTokens);
                body.add("thinking", thinking);
            }
        }

        HttpRequest request = HttpRequest.newBuilder(ENDPOINT)
                .timeout(Duration.ofSeconds(timeout))
                .header("content-type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        JsonObject json = JsonParser.parseString(Http.post(request)).getAsJsonObject();
        JsonElement stop = json.get("stop_reason");
        if (stop != null && !stop.isJsonNull() && "max_tokens".equals(stop.getAsString())) {
            throw new IOException(
                    "The AI's reply was cut off. Tokens count toward the limit: raise anthropic.max-tokens in config.yml, lower anthropic.thinking-level, or ask for a simpler build.");
        }

        StringBuilder text = new StringBuilder();
        for (JsonElement element : json.getAsJsonArray("content")) {
            JsonObject block = element.getAsJsonObject();
            if ("text".equals(block.get("type").getAsString())) {
                text.append(block.get("text").getAsString());
            }
        }
        if (text.length() == 0) {
            throw new IOException("The API returned no text.");
        }
        return text.toString();
    }
}
