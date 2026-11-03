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

/** Claude, via the Anthropic Messages API. */
final class AnthropicClient implements AiProvider {

    private static final URI ENDPOINT = URI.create("https://api.anthropic.com/v1/messages");

    private final String apiKey;
    private final String model;
    private final int maxTokens;
    private final int timeoutSeconds;

    AnthropicClient(String apiKey, String model, int maxTokens, int timeoutSeconds) {
        this.apiKey = apiKey;
        this.model = model;
        this.maxTokens = maxTokens;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public String label() {
        return model;
    }

    @Override
    public String complete(String system, String user) throws IOException, InterruptedException {
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

        HttpRequest request = HttpRequest.newBuilder(ENDPOINT)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("content-type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        JsonObject json = JsonParser.parseString(Http.post(request)).getAsJsonObject();
        JsonElement stop = json.get("stop_reason");
        if (stop != null && !stop.isJsonNull() && "max_tokens".equals(stop.getAsString())) {
            throw new IOException("The AI's reply was cut off. Raise anthropic.max-tokens in config.yml or ask for a simpler build.");
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
