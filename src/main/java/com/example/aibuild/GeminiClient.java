package com.example.aibuild;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Gemini, via the Gemini API's models.generateContent endpoint with automatic model failover. */
final class GeminiClient implements AiProvider {

    private static final String BASE = "https://generativelanguage.googleapis.com/v1beta/models/";

    private final String apiKey;
    private final List<String> models;
    private final int maxOutputTokens;
    private final String thinkingLevel;
    private final int timeoutSeconds;

    GeminiClient(String apiKey, List<String> models, int maxOutputTokens, String thinkingLevel, int timeoutSeconds) {
        this.apiKey = apiKey;
        this.models = models == null || models.isEmpty() ? List.of("gemini-3.8-flash") : models;
        this.maxOutputTokens = maxOutputTokens;
        this.thinkingLevel = thinkingLevel;
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
    public String complete(String system, String user, Consumer<String> statusListener) throws IOException, InterruptedException {
        List<String> errors = new ArrayList<>();
        int perModelTimeout = Math.min(timeoutSeconds, Math.max(30, timeoutSeconds / Math.max(1, models.size())));

        for (int i = 0; i < models.size(); i++) {
            String rawModel = models.get(i);
            String model = rawModel.startsWith("models/") ? rawModel.substring("models/".length()) : rawModel;
            statusListener.accept("Calling Gemini API with model '" + model + "' (" + (i + 1) + "/" + models.size() + ")...");
            long start = System.currentTimeMillis();
            boolean retried = false;
            while (true) {
                try {
                    String reply = callGenerateContent(model, system, user, perModelTimeout);
                    long elapsed = System.currentTimeMillis() - start;
                    statusListener.accept("Received response from '" + model + "' in " + elapsed + "ms");
                    return reply;
                } catch (Exception e) {
                    long elapsed = System.currentTimeMillis() - start;
                    String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                    if (!retried && isTransient(msg)) {
                        retried = true;
                        statusListener.accept("Model '" + model + "' experienced temporary high demand (HTTP 503/429), retrying in 2s...");
                        Thread.sleep(2000);
                        continue;
                    }
                    errors.add(model + " (" + elapsed + "ms): " + msg);
                    if (i + 1 < models.size()) {
                        String next = models.get(i + 1);
                        statusListener.accept("Model '" + model + "' failed (" + msg + "). Failing over to '" + next + "'...");
                    }
                    break;
                }
            }
        }
        throw new IOException("All configured Gemini models failed:\n- " + String.join("\n- ", errors));
    }

    private static boolean isTransient(String msg) {
        return msg.contains("503") || msg.contains("high demand") || msg.contains("429") || msg.contains("RESOURCE_EXHAUSTED");
    }

    private String callGenerateContent(String model, String system, String user, int timeout) throws IOException, InterruptedException {
        JsonObject systemInstruction = new JsonObject();
        systemInstruction.add("parts", parts(system));

        JsonObject content = new JsonObject();
        content.addProperty("role", "user");
        content.add("parts", parts(user));
        JsonArray contents = new JsonArray();
        contents.add(content);

        JsonObject generationConfig = new JsonObject();
        generationConfig.addProperty("maxOutputTokens", maxOutputTokens);
        generationConfig.addProperty("responseMimeType", "application/json");
        if (!thinkingLevel.isEmpty()) {
            JsonObject thinking = new JsonObject();
            thinking.addProperty("thinkingLevel", thinkingLevel);
            generationConfig.add("thinkingConfig", thinking);
        }

        JsonObject body = new JsonObject();
        body.add("systemInstruction", systemInstruction);
        body.add("contents", contents);
        body.add("generationConfig", generationConfig);

        URI uri = URI.create(BASE + URLEncoder.encode(model, StandardCharsets.UTF_8) + ":generateContent");
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(timeout))
                .header("content-type", "application/json")
                .header("x-goog-api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        JsonObject json = JsonParser.parseString(Http.post(request)).getAsJsonObject();

        JsonArray candidates = json.has("candidates") && json.get("candidates").isJsonArray()
                ? json.getAsJsonArray("candidates") : new JsonArray();
        if (candidates.isEmpty()) {
            String reason = "no candidates";
            if (json.has("promptFeedback") && json.get("promptFeedback").isJsonObject()) {
                JsonElement block = json.getAsJsonObject("promptFeedback").get("blockReason");
                if (block != null && !block.isJsonNull()) {
                    reason = "blocked: " + block.getAsString();
                }
            }
            throw new IOException("Gemini returned no answer (" + reason + ").");
        }

        JsonObject candidate = candidates.get(0).getAsJsonObject();
        String finish = candidate.has("finishReason") && !candidate.get("finishReason").isJsonNull()
                ? candidate.get("finishReason").getAsString() : "";
        if (finish.equals("MAX_TOKENS")) {
            throw new IOException("Gemini's reply was cut off. Thinking tokens count toward the limit: raise "
                    + "gemini.max-output-tokens in config.yml, lower gemini.thinking-level, or ask for a simpler build.");
        }

        StringBuilder text = new StringBuilder();
        if (candidate.has("content") && candidate.get("content").isJsonObject()) {
            JsonObject answer = candidate.getAsJsonObject("content");
            if (answer.has("parts") && answer.get("parts").isJsonArray()) {
                for (JsonElement element : answer.getAsJsonArray("parts")) {
                    JsonObject part = element.getAsJsonObject();
                    boolean thought = part.has("thought") && part.get("thought").isJsonPrimitive()
                            && part.get("thought").getAsBoolean();
                    if (!thought && part.has("text") && part.get("text").isJsonPrimitive()) {
                        text.append(part.get("text").getAsString());
                    }
                }
            }
        }
        if (text.length() == 0) {
            throw new IOException("Gemini returned no text" + (finish.isEmpty() ? "." : " (finish reason: " + finish + ")."));
        }
        return text.toString();
    }

    private static JsonArray parts(String text) {
        JsonObject part = new JsonObject();
        part.addProperty("text", text);
        JsonArray parts = new JsonArray();
        parts.add(part);
        return parts;
    }
}
