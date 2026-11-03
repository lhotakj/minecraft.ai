package com.example.aibuild;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Builds the provider selected by {@code provider:} in config.yml with model fallback support. */
final class AiProviders {

    private AiProviders() {}

    /** @throws IllegalStateException with a player-readable message if the config is unusable */
    static AiProvider create(FileConfiguration cfg) {
        String provider = cfg.getString("provider", "gemini").trim().toLowerCase(Locale.ROOT);
        int timeout = cfg.getInt("request-timeout-seconds", 180);
        return switch (provider) {
            case "anthropic", "claude" -> new AnthropicClient(
                    key(cfg.getString("anthropic.api-key", ""), "ANTHROPIC_API_KEY", "anthropic.api-key"),
                    parseModels(getSetting(cfg.getString("anthropic.model", ""), "ANTHROPIC_MODEL", "claude-sonnet-5-5")),
                    cfg.getInt("anthropic.max-tokens", 16000),
                    timeout);
            case "gemini", "google" -> new GeminiClient(
                    key(cfg.getString("gemini.api-key", ""), "GEMINI_API_KEY", "gemini.api-key"),
                    parseModels(getSetting(cfg.getString("gemini.model", ""), "GEMINI_MODEL", "gemini-2.5-flash gemini-2.0-flash gemini-1.5-flash")),
                    cfg.getInt("gemini.max-output-tokens", 32768),
                    cfg.getString("gemini.thinking-level", "").trim().toLowerCase(Locale.ROOT),
                    timeout);
            default -> throw new IllegalStateException(
                    "Unknown provider '" + provider + "' in config.yml (use 'anthropic' or 'gemini').");
        };
    }

    static List<String> parseModels(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split("[\\s,]+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
    }

    private static String getSetting(String configured, String envVar, String fallback) {
        String env = System.getenv(envVar);
        if (env != null && !env.trim().isEmpty()) {
            return env.trim();
        }
        if (configured != null && !configured.trim().isEmpty()) {
            return configured.trim();
        }
        return fallback;
    }

    private static String key(String configured, String envVar, String configPath) {
        String key = configured == null ? "" : configured.trim();
        if (key.isEmpty()) {
            String env = System.getenv(envVar);
            key = env == null ? "" : env.trim();
        }
        if (key.isEmpty()) {
            throw new IllegalStateException(
                    "No API key set: fill in '" + configPath + "' in config.yml or set the " + envVar + " environment variable.");
        }
        return key;
    }
}
