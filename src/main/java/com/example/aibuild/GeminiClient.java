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

/** Gemini, via the Gemini API's models.generateContent endpoint. */
final class GeminiClient implements AiProvider {

    private static final String BASE = "https://generativelanguage.googleapis.com/v1beta/models/";

    private final String apiKey;
    private final String model;
    private final int maxOutputTokens;
    private final String thinkingLevel;
    private final int timeoutSeconds;

    /** @param thinkingLevel "low", "medium" or "high"; empty means "leave it to the model's default". */
    GeminiClient(String apiKey, String model, int maxOutputTokens, String thinkingLevel, int timeoutSeconds) {
        this.apiKey = apiKey;
        this.model = model.startsWith("models/") ? model.substring("models/".length()) : model;
        this.maxOutputTokens = maxOutputTokens;
        this.thinkingLevel = thinkingLevel;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public String label() {
        return model;
    }

    @Override
    public String complete(String system, String user) throws IOException, InterruptedException {
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
                .timeout(Duration.ofSeconds(timeoutSeconds))
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
