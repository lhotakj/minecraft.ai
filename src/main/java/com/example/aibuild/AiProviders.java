package com.example.aibuild;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Builds the provider or provider fallback chain selected by {@code provider-order:} or {@code provider:} in config.yml. */
final class AiProviders {

    private AiProviders() {}

    /** @throws IllegalStateException with a player-readable message if the config is unusable */
    static AiProvider create(FileConfiguration cfg) {
        int timeout = cfg.getInt("request-timeout-seconds", 180);

        String providerOrder = getSetting(cfg.getString("provider-order", ""), "PROVIDER_ORDER", "");
        if (providerOrder.isEmpty()) {
            providerOrder = getSetting("", "AI_PROVIDER_ORDER", "");
        }

        List<String> orderList = parseProviders(providerOrder);
        if (!orderList.isEmpty()) {
            List<AiProvider> chain = new ArrayList<>();
            for (String providerName : orderList) {
                chain.add(createSingle(providerName, cfg, timeout));
            }
            if (chain.size() == 1) {
                return chain.get(0);
            }
            return new ChainedAiProvider(chain);
        }

        // Fallback to single provider setting:
        String provider = getSetting(cfg.getString("provider", "gemini"), "AI_PROVIDER", "gemini")
                .trim().toLowerCase(Locale.ROOT);
        return createSingle(provider, cfg, timeout);
    }

    static AiProvider createSingle(String provider, FileConfiguration cfg, int timeout) {
        return switch (provider.trim().toLowerCase(Locale.ROOT)) {
            case "anthropic", "claude" -> new AnthropicClient(
                    key(cfg.getString("anthropic.api-key", ""), List.of("ANTHROPIC_API_KEY", "ANTHROPIC_TOKEN"), "anthropic.api-key"),
                    parseModels(getSetting(cfg.getString("anthropic.model", ""), "ANTHROPIC_MODEL", "claude-sonnet-5-5")),
                    getIntSetting(cfg, "anthropic.max-tokens", List.of("ANTHROPIC_MAX_TOKENS"), 16000),
                    getThinkingLevel(cfg, "anthropic.thinking-level", "ANTHROPIC_THINKING_LEVEL"),
                    timeout);
            case "gemini", "google" -> new GeminiClient(
                    key(cfg.getString("gemini.api-key", ""), List.of("GEMINI_API_KEY", "GEMINI_TOKEN"), "gemini.api-key"),
                    parseModels(getSetting(cfg.getString("gemini.model", ""), "GEMINI_MODEL", "gemini-3.8-flash gemini-3.7-flash gemini-3.5-flash")),
                    getIntSetting(cfg, "gemini.max-output-tokens", List.of("GEMINI_MAX_OUTPUT_TOKENS", "GEMINI_MAX_TOKENS"), 32768),
                    getThinkingLevel(cfg, "gemini.thinking-level", "GEMINI_THINKING_LEVEL"),
                    timeout);
            case "openai", "chatgpt" -> new OpenAiClient(
                    key(cfg.getString("openai.api-key", ""), List.of("OPENAI_API_KEY", "OPENAI_TOKEN"), "openai.api-key"),
                    parseModels(getSetting(cfg.getString("openai.model", ""), "OPENAI_MODEL", "gpt-4o gpt-4o-mini")),
                    getIntSetting(cfg, "openai.max-tokens", List.of("OPENAI_MAX_TOKENS", "OPENAI_MAX_COMPLETION_TOKENS"), 16000),
                    getThinkingLevel(cfg, "openai.thinking-level", "OPENAI_THINKING_LEVEL"),
                    timeout);
            case "openrouter", "open-router" -> new OpenRouterClient(
                    key(cfg.getString("openrouter.api-key", ""), List.of("OPENROUTER_API_KEY", "OPENROUTER_TOKEN"), "openrouter.api-key"),
                    parseModels(getSetting(cfg.getString("openrouter.model", ""), "OPENROUTER_MODEL", "google/gemini-2.5-flash openai/gpt-4o anthropic/claude-3.7-sonnet")),
                    getIntSetting(cfg, "openrouter.max-tokens", List.of("OPENROUTER_MAX_TOKENS"), 16000),
                    getThinkingLevel(cfg, "openrouter.thinking-level", "OPENROUTER_THINKING_LEVEL"),
                    timeout);
            default -> throw new IllegalStateException(
                    "Unknown provider '" + provider + "' in config.yml (use 'gemini', 'anthropic', 'openai', or 'openrouter').");
        };
    }

    static List<String> parseProviders(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split("[\\s,]+"))
                .map(String::trim)
                .map(s -> s.toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
    }

    static String normalizeThinkingLevel(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String level = raw.trim().toLowerCase(Locale.ROOT);
        if (level.equals("none") || level.equals("off")) {
            return "";
        }
        if (level.equals("low") || level.equals("medium") || level.equals("high")) {
            return level;
        }
        throw new IllegalStateException(
                "Invalid thinking-level '" + raw + "'. Supported values are: low, medium, high (or empty for default).");
    }

    private static String getThinkingLevel(FileConfiguration cfg, String configPath, String envVar) {
        String raw = System.getenv(envVar);
        if (raw == null || raw.trim().isEmpty()) {
            raw = cfg.getString(configPath, "");
        }
        if (raw == null || raw.trim().isEmpty()) {
            raw = System.getenv("THINKING_LEVEL");
            if (raw == null || raw.trim().isEmpty()) {
                raw = cfg.getString("thinking-level", "");
            }
        }
        return normalizeThinkingLevel(raw);
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

    private static int getIntSetting(FileConfiguration cfg, String configPath, List<String> envVars, int fallback) {
        for (String envVar : envVars) {
            String env = System.getenv(envVar);
            if (env != null && !env.trim().isEmpty()) {
                try {
                    return Integer.parseInt(env.trim());
                } catch (NumberFormatException ignored) {}
            }
        }
        return cfg.getInt(configPath, fallback);
    }

    private static String key(String configured, List<String> envVars, String configPath) {
        String key = configured == null ? "" : configured.trim();
        if (key.isEmpty()) {
            for (String envVar : envVars) {
                String env = System.getenv(envVar);
                if (env != null && !env.trim().isEmpty()) {
                    key = env.trim();
                    break;
                }
            }
        }
        if (key.isEmpty()) {
            throw new IllegalStateException(
                    "No API key set: fill in '" + configPath + "' in config.yml or set the " + envVars.get(0) + " environment variable.");
        }
        return key;
    }

    static String key(String configured, String envVar, String configPath) {
        return key(configured, List.of(envVar), configPath);
    }
}
