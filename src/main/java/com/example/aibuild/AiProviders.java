package com.example.aibuild;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Builds the provider or provider fallback chain selected by {@code provider-order:},
 * {@code ai_order:}, {@code ai-order:}, or single {@code provider:} in config.yml (or environment variables).
 */
final class AiProviders {

    private AiProviders() {}

    record ResolvedToken(String provider, String model) {}
    record ProviderTarget(String provider, List<String> models) {}

    /** @throws IllegalStateException with a player-readable message if the config is unusable */
    static AiProvider create(FileConfiguration cfg) {
        int timeout = cfg.getInt("request-timeout-seconds", 180);

        List<String> orderTokens = getOrderSetting(cfg);
        if (!orderTokens.isEmpty()) {
            List<ProviderTarget> targets = groupTargets(orderTokens);
            if (!targets.isEmpty()) {
                List<AiProvider> chain = new ArrayList<>();
                for (ProviderTarget target : targets) {
                    chain.add(createSingle(target.provider(), target.models(), cfg, timeout));
                }
                if (chain.size() == 1) {
                    return chain.get(0);
                }
                return new ChainedAiProvider(chain);
            }
        }

        // Fallback to single provider setting:
        String provider = getSingleProviderSetting(cfg);
        return createSingle(provider, List.of(), cfg, timeout);
    }

    static List<String> getOrderSetting(FileConfiguration cfg) {
        // 1. Check environment variables first
        List<String> envVars = List.of(
                "AI_ORDER",
                "AI_PROVIDER_ORDER",
                "PROVIDER_ORDER",
                "AI_PROVIDERS",
                "PROVIDERS",
                "ORDER"
        );
        for (String env : envVars) {
            String val = System.getenv(env);
            if (val != null && !val.trim().isEmpty()) {
                List<String> parsed = parseTokens(val);
                if (!parsed.isEmpty()) {
                    return parsed;
                }
            }
        }

        // 2. Check configuration paths
        List<String> configPaths = List.of(
                "provider-order",
                "provider_order",
                "providerOrder",
                "ai-order",
                "ai_order",
                "aiOrder",
                "providers",
                "order"
        );
        for (String path : configPaths) {
            if (!cfg.contains(path)) {
                continue;
            }
            if (cfg.isList(path)) {
                List<String> rawList = cfg.getStringList(path);
                List<String> result = new ArrayList<>();
                for (String item : rawList) {
                    if (item != null && !item.isBlank()) {
                        result.addAll(parseTokens(item));
                    }
                }
                if (!result.isEmpty()) {
                    return result;
                }
            } else {
                String val = cfg.getString(path, "");
                if (val != null && !val.trim().isEmpty()) {
                    List<String> parsed = parseTokens(val);
                    if (!parsed.isEmpty()) {
                        return parsed;
                    }
                }
            }
        }
        return List.of();
    }

    static String getSingleProviderSetting(FileConfiguration cfg) {
        List<String> envVars = List.of("AI_PROVIDER", "PROVIDER");
        for (String env : envVars) {
            String val = System.getenv(env);
            if (val != null && !val.trim().isEmpty()) {
                return val.trim().toLowerCase(Locale.ROOT);
            }
        }

        List<String> paths = List.of("provider", "ai_provider", "ai-provider", "aiProvider");
        for (String path : paths) {
            if (cfg.contains(path)) {
                String val = cfg.getString(path, "");
                if (val != null && !val.trim().isEmpty()) {
                    return val.trim().toLowerCase(Locale.ROOT);
                }
            }
        }
        return "gemini";
    }

    static List<ProviderTarget> groupTargets(List<String> tokens) {
        List<ProviderTarget> targets = new ArrayList<>();
        for (String token : tokens) {
            ResolvedToken resolved = resolveToken(token);
            if (!targets.isEmpty()
                    && targets.get(targets.size() - 1).provider().equalsIgnoreCase(resolved.provider())) {
                ProviderTarget prev = targets.get(targets.size() - 1);
                if (resolved.model() != null) {
                    if (!prev.models().contains(resolved.model())) {
                        prev.models().add(resolved.model());
                    }
                }
            } else {
                List<String> modelsList = new ArrayList<>();
                if (resolved.model() != null) {
                    modelsList.add(resolved.model());
                }
                targets.add(new ProviderTarget(resolved.provider(), modelsList));
            }
        }
        return targets;
    }

    static boolean isKnownProvider(String p) {
        return switch (p.toLowerCase(Locale.ROOT)) {
            case "gemini", "google", "anthropic", "claude", "openai", "chatgpt", "openrouter", "open-router" -> true;
            default -> false;
        };
    }

    static String normalizeProvider(String p) {
        return switch (p.toLowerCase(Locale.ROOT)) {
            case "gemini", "google" -> "gemini";
            case "anthropic", "claude" -> "anthropic";
            case "openai", "chatgpt" -> "openai";
            case "openrouter", "open-router" -> "openrouter";
            default -> p.toLowerCase(Locale.ROOT);
        };
    }

    static ResolvedToken resolveToken(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("Empty provider or model in configuration.");
        }
        String token = raw.trim();

        // 1. Explicit provider:model syntax (e.g., "gemini:gemini-3.8-flash", "openai:gpt-4o")
        int colon = token.indexOf(':');
        if (colon > 0 && !token.contains("/") && !token.endsWith(":free")) {
            String p = token.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String m = token.substring(colon + 1).trim();
            if (isKnownProvider(p)) {
                return new ResolvedToken(normalizeProvider(p), m.isEmpty() ? null : m);
            }
        }

        // 2. Known provider names directly ("gemini", "anthropic", "openai", "openrouter")
        String lower = token.toLowerCase(Locale.ROOT);
        if (isKnownProvider(lower)) {
            return new ResolvedToken(normalizeProvider(lower), null);
        }

        // 3. OpenRouter author/model format (e.g., "openrouter/free", "google/gemini-2.5-flash")
        if (token.contains("/")) {
            return new ResolvedToken("openrouter", token);
        }

        // 4. Inferred provider from model prefix
        if (lower.startsWith("gemini")) {
            return new ResolvedToken("gemini", token);
        }
        if (lower.startsWith("claude")) {
            return new ResolvedToken("anthropic", token);
        }
        if (lower.startsWith("gpt-") || lower.startsWith("o1") || lower.startsWith("o3") || lower.startsWith("o4") || lower.startsWith("chatgpt")) {
            return new ResolvedToken("openai", token);
        }

        throw new IllegalStateException(
                "Unknown provider '" + token + "' in config.yml (use 'gemini', 'anthropic', 'openai', or 'openrouter').");
    }

    static AiProvider createSingle(String provider, List<String> explicitModels, FileConfiguration cfg, int timeout) {
        String normalized = normalizeProvider(provider);
        return switch (normalized) {
            case "anthropic" -> new AnthropicClient(
                    key(cfg.getString("anthropic.api-key", ""), List.of("ANTHROPIC_API_KEY", "ANTHROPIC_TOKEN"), "anthropic.api-key"),
                    explicitModels != null && !explicitModels.isEmpty() ? explicitModels
                            : getModelsSetting(cfg, "anthropic", "ANTHROPIC", "claude-sonnet-5-5 claude-3-5-haiku-latest"),
                    getIntSetting(cfg, "anthropic.max-tokens", List.of("ANTHROPIC_MAX_TOKENS"), 16000),
                    getThinkingLevel(cfg, "anthropic.thinking-level", "ANTHROPIC_THINKING_LEVEL"),
                    timeout);
            case "gemini" -> new GeminiClient(
                    key(cfg.getString("gemini.api-key", ""), List.of("GEMINI_API_KEY", "GEMINI_TOKEN"), "gemini.api-key"),
                    explicitModels != null && !explicitModels.isEmpty() ? explicitModels
                            : getModelsSetting(cfg, "gemini", "GEMINI", "gemini-3.8-flash gemini-3.7-flash gemini-3.5-flash"),
                    getIntSetting(cfg, "gemini.max-output-tokens", List.of("GEMINI_MAX_OUTPUT_TOKENS", "GEMINI_MAX_TOKENS"), 32768),
                    getThinkingLevel(cfg, "gemini.thinking-level", "GEMINI_THINKING_LEVEL"),
                    timeout);
            case "openai" -> new OpenAiClient(
                    key(cfg.getString("openai.api-key", ""), List.of("OPENAI_API_KEY", "OPENAI_TOKEN"), "openai.api-key"),
                    explicitModels != null && !explicitModels.isEmpty() ? explicitModels
                            : getModelsSetting(cfg, "openai", "OPENAI", "gpt-4o gpt-4o-mini"),
                    getIntSetting(cfg, "openai.max-tokens", List.of("OPENAI_MAX_TOKENS", "OPENAI_MAX_COMPLETION_TOKENS"), 16000),
                    getThinkingLevel(cfg, "openai.thinking-level", "OPENAI_THINKING_LEVEL"),
                    timeout);
            case "openrouter" -> new OpenRouterClient(
                    key(cfg.getString("openrouter.api-key", ""), List.of("OPENROUTER_API_KEY", "OPENROUTER_TOKEN"), "openrouter.api-key"),
                    explicitModels != null && !explicitModels.isEmpty() ? explicitModels
                            : getModelsSetting(cfg, "openrouter", "OPENROUTER", "google/gemini-2.5-flash openai/gpt-4o anthropic/claude-3.7-sonnet"),
                    getIntSetting(cfg, "openrouter.max-tokens", List.of("OPENROUTER_MAX_TOKENS"), 16000),
                    getThinkingLevel(cfg, "openrouter.thinking-level", "OPENROUTER_THINKING_LEVEL"),
                    timeout);
            default -> throw new IllegalStateException(
                    "Unknown provider '" + provider + "' in config.yml (use 'gemini', 'anthropic', 'openai', or 'openrouter').");
        };
    }

    static AiProvider createSingle(String provider, FileConfiguration cfg, int timeout) {
        return createSingle(provider, List.of(), cfg, timeout);
    }

    static List<String> getModelsSetting(FileConfiguration cfg, String section, String envPrefix, String defaultModels) {
        // 1. Env vars
        List<String> envVars = List.of(
                envPrefix + "_MODEL",
                envPrefix + "_MODELS",
                envPrefix + "_ORDER",
                envPrefix + "_AI_ORDER"
        );
        for (String env : envVars) {
            String val = System.getenv(env);
            if (val != null && !val.trim().isEmpty()) {
                return parseModels(val);
            }
        }

        // 2. Config paths
        List<String> paths = List.of(
                section + ".model",
                section + ".models",
                section + ".ai_order",
                section + ".ai-order",
                section + ".order"
        );
        for (String path : paths) {
            if (!cfg.contains(path)) {
                continue;
            }
            if (cfg.isList(path)) {
                List<String> list = cfg.getStringList(path);
                List<String> parsed = new ArrayList<>();
                for (String item : list) {
                    if (item != null && !item.isBlank()) {
                        parsed.addAll(parseModels(item));
                    }
                }
                if (!parsed.isEmpty()) {
                    return parsed;
                }
            } else {
                String val = cfg.getString(path, "");
                if (val != null && !val.trim().isEmpty()) {
                    return parseModels(val);
                }
            }
        }
        return parseModels(defaultModels);
    }

    static List<String> parseTokens(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split("[\\s,]+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
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
