package com.example.aibuild;

import com.google.gson.JsonArray;
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

/** OpenRouter, via the OpenRouter Chat Completions API with model failover and unified reasoning support. */
final class OpenRouterClient implements AiProvider {

    private static final URI ENDPOINT = URI.create("https://openrouter.ai/api/v1/chat/completions");

    private final String apiKey;
    private final List<String> models;
    private final int maxTokens;
    private final String thinkingLevel;
    private final int timeoutSeconds;

    OpenRouterClient(String apiKey, List<String> models, int maxTokens, int timeoutSeconds) {
        this(apiKey, models, maxTokens, "", timeoutSeconds);
    }

    OpenRouterClient(String apiKey, List<String> models, int maxTokens, String thinkingLevel, int timeoutSeconds) {
        this.apiKey = apiKey;
        this.models = models == null || models.isEmpty()
                ? List.of("google/gemini-2.5-flash", "openai/gpt-4o", "anthropic/claude-3.7-sonnet")
                : models;
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
            statusListener.accept("Calling OpenRouter API with model '" + model + "' (" + (i + 1) + "/" + models.size() + ")...");
            long start = System.currentTimeMillis();
            Exception lastException = null;

            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    String reply = callChatCompletions(model, system, user, perModelTimeout);
                    long elapsed = System.currentTimeMillis() - start;
                    statusListener.accept("Received response from '" + model + "' in " + elapsed + "ms");
                    return reply;
                } catch (Exception e) {
                    lastException = e;
                    String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                    if (attempt == 0 && isTransient(msg)) {
                        statusListener.accept("Transient error from '" + model + "' (" + msg + "). Retrying in 1s...");
                        Thread.sleep(1000);
                        continue;
                    }
                    break;
                }
            }

            long elapsed = System.currentTimeMillis() - start;
            String msg = lastException != null && lastException.getMessage() != null
                    ? lastException.getMessage() : String.valueOf(lastException);
            errors.add(model + " (" + elapsed + "ms): " + msg);
            if (i + 1 < models.size()) {
                String next = models.get(i + 1);
                statusListener.accept("Model '" + model + "' failed (" + msg + "). Failing over to '" + next + "'...");
            }
        }
        throw new IOException("All configured OpenRouter models failed:\n- " + String.join("\n- ", errors));
    }

    private static boolean isTransient(String msg) {
        return msg.contains("503") || msg.contains("502") || msg.contains("504")
                || msg.contains("429") || msg.contains("rate limit") || msg.contains("overloaded");
    }

    private String callChatCompletions(String model, String system, String user, int timeout)
            throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);

        JsonArray messages = new JsonArray();

        JsonObject sysMsg = new JsonObject();
        sysMsg.addProperty("role", "system");
        sysMsg.addProperty("content", system);
        messages.add(sysMsg);

        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", user);
        messages.add(userMsg);

        body.add("messages", messages);
        body.addProperty("max_tokens", maxTokens);

        // OpenRouter unified reasoning configuration
        if (!thinkingLevel.isEmpty()) {
            JsonObject reasoning = new JsonObject();
            reasoning.addProperty("effort", thinkingLevel);
            body.add("reasoning", reasoning);
        }

        JsonObject responseFormat = new JsonObject();
        responseFormat.addProperty("type", "json_object");
        body.add("response_format", responseFormat);

        HttpRequest request = HttpRequest.newBuilder(ENDPOINT)
                .timeout(Duration.ofSeconds(timeout))
                .header("content-type", "application/json")
                .header("authorization", "Bearer " + apiKey)
                .header("HTTP-Referer", "https://github.com/lhotakj/minecraft.ai")
                .header("X-Title", "AiBuild")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        JsonObject json = JsonParser.parseString(Http.post(request)).getAsJsonObject();
        JsonArray choices = json.has("choices") && json.get("choices").isJsonArray()
                ? json.getAsJsonArray("choices") : new JsonArray();
        if (choices.isEmpty()) {
            throw new IOException("OpenRouter returned no choices.");
        }

        JsonObject choice = choices.get(0).getAsJsonObject();
        String finish = choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull()
                ? choice.get("finish_reason").getAsString() : "";
        if ("length".equals(finish)) {
            throw new IOException("OpenRouter's reply was cut off. Tokens count toward the limit: raise "
                    + "openrouter.max-tokens in config.yml, lower openrouter.thinking-level, or ask for a simpler build.");
        }

        JsonObject msgObj = choice.getAsJsonObject("message");
        if (msgObj != null && msgObj.has("refusal") && !msgObj.get("refusal").isJsonNull()
                && !msgObj.get("refusal").getAsString().isEmpty()) {
            throw new IOException("OpenRouter refused the request: " + msgObj.get("refusal").getAsString());
        }

        if (msgObj == null || !msgObj.has("content") || msgObj.get("content").isJsonNull()) {
            throw new IOException("OpenRouter returned no text content.");
        }

        String reply = msgObj.get("content").getAsString();
        if (reply.isBlank()) {
            throw new IOException("OpenRouter returned an empty response.");
        }
        return reply;
    }
}

