package com.example.aibuild;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Shared HTTP plumbing for the provider clients. */
final class Http {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private Http() {}

    /** Sends the request and returns the body of a 200 response; anything else becomes an IOException. */
    static String post(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response =
                CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("API returned HTTP " + response.statusCode() + ": " + errorMessage(response.body()));
        }
        return response.body();
    }

    /** Both Anthropic and Google wrap errors as {"error": {"message": "..."}}. */
    private static String errorMessage(String body) {
        try {
            JsonObject error = JsonParser.parseString(body).getAsJsonObject().getAsJsonObject("error");
            return error.get("message").getAsString();
        } catch (RuntimeException e) {
            return body.length() > 200 ? body.substring(0, 200) : body;
        }
    }
}
