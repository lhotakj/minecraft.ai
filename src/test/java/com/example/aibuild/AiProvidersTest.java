package com.example.aibuild;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiProvidersTest {

    @Test
    void testParseModelsSpaceDelimited() {
        List<String> models = AiProviders.parseModels("gemini-3.8-flash gemini-3.7-flash gemini-3.5-flash");
        assertEquals(3, models.size());
        assertEquals("gemini-3.8-flash", models.get(0));
        assertEquals("gemini-3.7-flash", models.get(1));
        assertEquals("gemini-3.5-flash", models.get(2));
    }

    @Test
    void testParseModelsCommaDelimited() {
        List<String> models = AiProviders.parseModels("gemini-3.8-flash, gemini-3.7-flash, gemini-3.5-flash");
        assertEquals(3, models.size());
        assertEquals("gemini-3.8-flash", models.get(0));
        assertEquals("gemini-3.7-flash", models.get(1));
        assertEquals("gemini-3.5-flash", models.get(2));
    }

    @Test
    void testParseModelsHandlesDuplicatesAndExtraWhitespace() {
        List<String> models = AiProviders.parseModels("  gemini-3.8-flash   gemini-3.8-flash \n gemini-3.7-flash  ");
        assertEquals(2, models.size());
        assertEquals("gemini-3.8-flash", models.get(0));
        assertEquals("gemini-3.7-flash", models.get(1));
    }

    @Test
    void testParseModelsEmpty() {
        assertTrue(AiProviders.parseModels("").isEmpty());
        assertTrue(AiProviders.parseModels(null).isEmpty());
        assertTrue(AiProviders.parseModels("   ").isEmpty());
    }

    @Test
    void testParseProvidersSpaceDelimited() {
        List<String> providers = AiProviders.parseProviders("openrouter gemini openai");
        assertEquals(3, providers.size());
        assertEquals("openrouter", providers.get(0));
        assertEquals("gemini", providers.get(1));
        assertEquals("openai", providers.get(2));
    }

    @Test
    void testParseProvidersCommaDelimited() {
        List<String> providers = AiProviders.parseProviders("openrouter, gemini, anthropic");
        assertEquals(3, providers.size());
        assertEquals("openrouter", providers.get(0));
        assertEquals("gemini", providers.get(1));
        assertEquals("anthropic", providers.get(2));
    }

    @Test
    void testParseProvidersHandlesDuplicatesAndWhitespace() {
        List<String> providers = AiProviders.parseProviders("  OpenRouter   gemini \n openrouter   OPENAI ");
        assertEquals(3, providers.size());
        assertEquals("openrouter", providers.get(0));
        assertEquals("gemini", providers.get(1));
        assertEquals("openai", providers.get(2));
    }

    @Test
    void testParseProvidersEmpty() {
        assertTrue(AiProviders.parseProviders("").isEmpty());
        assertTrue(AiProviders.parseProviders(null).isEmpty());
        assertTrue(AiProviders.parseProviders("   ").isEmpty());
    }

    @Test
    void testGeminiClientLabel() {
        GeminiClient single = new GeminiClient("key", List.of("gemini-3.8-flash"), 1000, "", 30);
        assertEquals("gemini-3.8-flash", single.label());

        GeminiClient multi = new GeminiClient("key",
                List.of("gemini-3.8-flash", "gemini-3.7-flash", "gemini-3.5-flash"), 1000, "", 30);
        assertEquals("gemini-3.8-flash (+2 fallbacks)", multi.label());
    }

    @Test
    void testAnthropicClientLabel() {
        AnthropicClient single = new AnthropicClient("key", List.of("claude-sonnet-5-5"), 1000, 30);
        assertEquals("claude-sonnet-5-5", single.label());

        AnthropicClient multi = new AnthropicClient("key", List.of("claude-sonnet-5-5", "claude-3-5-haiku-latest"),
                1000, 30);
        assertEquals("claude-sonnet-5-5 (+1 fallbacks)", multi.label());
    }

    @Test
    void testOpenAiClientLabel() {
        OpenAiClient single = new OpenAiClient("key", List.of("gpt-4o"), 1000, 30);
        assertEquals("gpt-4o", single.label());

        OpenAiClient multi = new OpenAiClient("key", List.of("gpt-4o", "gpt-4o-mini"), 1000, "medium", 30);
        assertEquals("gpt-4o (+1 fallbacks)", multi.label());
    }

    @Test
    void testOpenRouterClientLabel() {
        OpenRouterClient single = new OpenRouterClient("key", List.of("google/gemini-2.5-flash"), 1000, 30);
        assertEquals("google/gemini-2.5-flash", single.label());

        OpenRouterClient multi = new OpenRouterClient("key",
                List.of("google/gemini-2.5-flash", "openai/gpt-4o", "anthropic/claude-3.7-sonnet"), 1000, "high", 30);
        assertEquals("google/gemini-2.5-flash (+2 fallbacks)", multi.label());
    }

    @Test
    void testChainedAiProviderLabel() {
        AiProvider p1 = new OpenRouterClient("key", List.of("google/gemini-2.5-flash"), 1000, 30);
        AiProvider p2 = new GeminiClient("key", List.of("gemini-3.8-flash"), 1000, "", 30);

        ChainedAiProvider single = new ChainedAiProvider(List.of(p1));
        assertEquals("google/gemini-2.5-flash", single.label());

        ChainedAiProvider multi = new ChainedAiProvider(List.of(p1, p2));
        assertEquals("google/gemini-2.5-flash (+1 provider fallbacks)", multi.label());
    }

    @Test
    void testChainedAiProviderFailover() throws Exception {
        AiProvider failing = new AiProvider() {
            @Override
            public String label() {
                return "MockFailingProvider";
            }

            @Override
            public String complete(String system, String user, Consumer<String> statusListener) throws IOException {
                throw new IOException("HTTP 503 high demand");
            }
        };

        AiProvider success = new AiProvider() {
            @Override
            public String label() {
                return "MockSuccessProvider";
            }

            @Override
            public String complete(String system, String user, Consumer<String> statusListener) {
                statusListener.accept("Generating build from fallback provider");
                return "{\"name\": \"Castle\", \"ops\": []}";
            }
        };

        ChainedAiProvider chain = new ChainedAiProvider(List.of(failing, success));
        List<String> statuses = new ArrayList<>();
        String result = chain.complete("sys", "user", statuses::add);

        assertEquals("{\"name\": \"Castle\", \"ops\": []}", result);
        assertTrue(statuses.stream().anyMatch(s -> s.contains("Failing over to provider 'MockSuccessProvider'")));
        assertTrue(statuses.stream().anyMatch(s -> s.contains("Generating build from fallback provider")));
    }

    @Test
    void testChainedAiProviderAllFail() {
        AiProvider failing1 = new AiProvider() {
            @Override
            public String label() {
                return "ProviderA";
            }

            @Override
            public String complete(String system, String user, Consumer<String> statusListener) throws IOException {
                throw new IOException("API error 503");
            }
        };

        AiProvider failing2 = new AiProvider() {
            @Override
            public String label() {
                return "ProviderB";
            }

            @Override
            public String complete(String system, String user, Consumer<String> statusListener) throws IOException {
                throw new IOException("API error 401 unauthorized");
            }
        };

        ChainedAiProvider chain = new ChainedAiProvider(List.of(failing1, failing2));
        IOException ex = assertThrows(IOException.class, () -> chain.complete("sys", "user", s -> {}));
        assertTrue(ex.getMessage().contains("All configured AI providers failed"));
        assertTrue(ex.getMessage().contains("ProviderA"));
        assertTrue(ex.getMessage().contains("ProviderB"));
    }

    @Test
    void testNormalizeThinkingLevel() {
        assertEquals("", AiProviders.normalizeThinkingLevel(""));
        assertEquals("", AiProviders.normalizeThinkingLevel(null));
        assertEquals("", AiProviders.normalizeThinkingLevel("   "));
        assertEquals("", AiProviders.normalizeThinkingLevel("none"));
        assertEquals("", AiProviders.normalizeThinkingLevel("OFF"));
        assertEquals("low", AiProviders.normalizeThinkingLevel("low"));
        assertEquals("low", AiProviders.normalizeThinkingLevel("LOW"));
        assertEquals("medium", AiProviders.normalizeThinkingLevel("Medium"));
        assertEquals("high", AiProviders.normalizeThinkingLevel("  HIGH  "));

        assertThrows(IllegalStateException.class, () -> AiProviders.normalizeThinkingLevel("extreme"));
        assertThrows(IllegalStateException.class, () -> AiProviders.normalizeThinkingLevel("fast"));
    }

    @Test
    void testCreateFromYamlConfig() {
        String yaml = """
                provider: openai
                openai:
                  api-key: "test-openai-key"
                  model: "gpt-4o gpt-4o-mini"
                  max-tokens: 8000
                  thinking-level: "low"
                """;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(new StringReader(yaml));
        AiProvider provider = AiProviders.create(cfg);
        assertTrue(provider instanceof OpenAiClient);
        assertEquals("gpt-4o (+1 fallbacks)", provider.label());

        String openRouterYaml = """
                provider: openrouter
                openrouter:
                  api-key: "test-openrouter-key"
                  model: "google/gemini-2.5-flash"
                  max-tokens: 12000
                  thinking-level: "high"
                """;
        YamlConfiguration routerCfg = YamlConfiguration.loadConfiguration(new StringReader(openRouterYaml));
        AiProvider routerProvider = AiProviders.create(routerCfg);
        assertTrue(routerProvider instanceof OpenRouterClient);
        assertEquals("google/gemini-2.5-flash", routerProvider.label());

        String geminiYaml = """
                provider: gemini
                gemini:
                  api-key: "test-gemini-key"
                  model: "gemini-3.8-flash"
                  max-output-tokens: 20000
                  thinking-level: "medium"
                """;
        YamlConfiguration geminiCfg = YamlConfiguration.loadConfiguration(new StringReader(geminiYaml));
        AiProvider geminiProvider = AiProviders.create(geminiCfg);
        assertTrue(geminiProvider instanceof GeminiClient);
        assertEquals("gemini-3.8-flash", geminiProvider.label());

        String anthropicYaml = """
                provider: anthropic
                anthropic:
                  api-key: "test-claude-key"
                  model: "claude-sonnet-5-5"
                  max-tokens: 16000
                  thinking-level: "low"
                """;
        YamlConfiguration anthropicCfg = YamlConfiguration.loadConfiguration(new StringReader(anthropicYaml));
        AiProvider anthropicProvider = AiProviders.create(anthropicCfg);
        assertTrue(anthropicProvider instanceof AnthropicClient);
        assertEquals("claude-sonnet-5-5", anthropicProvider.label());
    }

    @Test
    void testCreateFromYamlConfigProviderOrder() {
        String yaml = """
                provider-order: "openrouter gemini"
                openrouter:
                  api-key: "test-router-key"
                  model: "google/gemini-2.5-flash"
                  max-tokens: 12000
                gemini:
                  api-key: "test-gemini-key"
                  model: "gemini-3.8-flash"
                  max-output-tokens: 20000
                """;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(new StringReader(yaml));
        AiProvider provider = AiProviders.create(cfg);

        assertTrue(provider instanceof ChainedAiProvider);
        ChainedAiProvider chained = (ChainedAiProvider) provider;
        assertEquals(2, chained.providers().size());
        assertTrue(chained.providers().get(0) instanceof OpenRouterClient);
        assertTrue(chained.providers().get(1) instanceof GeminiClient);
        assertEquals("google/gemini-2.5-flash (+1 provider fallbacks)", chained.label());
    }

    @Test
    void testMissingApiKeyThrows() {
        String yaml = """
                provider: openai
                openai:
                  api-key: ""
                """;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(new StringReader(yaml));
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> AiProviders.create(cfg));
        assertTrue(ex.getMessage().contains("openai.api-key"));
    }

    @Test
    void testUnknownProviderThrows() {
        String yaml = """
                provider: unsupported-ai
                """;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(new StringReader(yaml));
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> AiProviders.create(cfg));
        assertTrue(ex.getMessage().contains("Unknown provider"));
    }

    @Test
    void testAiOrderKeyAlias() {
        String yaml = """
                ai_order: "openrouter gemini"
                openrouter:
                  api-key: "test-router-key"
                  model: "google/gemini-2.5-flash"
                gemini:
                  api-key: "test-gemini-key"
                  model: "gemini-3.8-flash"
                """;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(new StringReader(yaml));
        AiProvider provider = AiProviders.create(cfg);
        assertTrue(provider instanceof ChainedAiProvider);
        ChainedAiProvider chained = (ChainedAiProvider) provider;
        assertEquals(2, chained.providers().size());
        assertTrue(chained.providers().get(0) instanceof OpenRouterClient);
        assertTrue(chained.providers().get(1) instanceof GeminiClient);
    }

    @Test
    void testAiOrderYamlList() {
        String yaml = """
                ai_order:
                  - openrouter
                  - gemini
                openrouter:
                  api-key: "test-router-key"
                  model: "google/gemini-2.5-flash"
                gemini:
                  api-key: "test-gemini-key"
                  model: "gemini-3.8-flash"
                """;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(new StringReader(yaml));
        AiProvider provider = AiProviders.create(cfg);
        assertTrue(provider instanceof ChainedAiProvider);
        ChainedAiProvider chained = (ChainedAiProvider) provider;
        assertEquals(2, chained.providers().size());
    }

    @Test
    void testAiOrderDirectModelNames() {
        String yaml = """
                ai_order: "gemini-3.8-flash gemini-3.7-flash gemini-3.5-flash"
                gemini:
                  api-key: "test-gemini-key"
                """;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(new StringReader(yaml));
        AiProvider provider = AiProviders.create(cfg);
        assertTrue(provider instanceof GeminiClient);
        assertEquals("gemini-3.8-flash (+2 fallbacks)", provider.label());
    }

    @Test
    void testAiOrderCrossProviderModels() {
        String yaml = """
                ai_order: "openrouter/free gemini:gemini-3.8-flash"
                openrouter:
                  api-key: "test-router-key"
                gemini:
                  api-key: "test-gemini-key"
                """;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(new StringReader(yaml));
        AiProvider provider = AiProviders.create(cfg);
        assertTrue(provider instanceof ChainedAiProvider);
        ChainedAiProvider chained = (ChainedAiProvider) provider;
        assertEquals(2, chained.providers().size());
        assertEquals("openrouter/free", chained.providers().get(0).label());
        assertEquals("gemini-3.8-flash", chained.providers().get(1).label());
    }

    @Test
    void testProviderModelAsYamlList() {
        String yaml = """
                provider: gemini
                gemini:
                  api-key: "test-gemini-key"
                  model:
                    - gemini-3.8-flash
                    - gemini-3.7-flash
                """;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(new StringReader(yaml));
        AiProvider provider = AiProviders.create(cfg);
        assertTrue(provider instanceof GeminiClient);
        assertEquals("gemini-3.8-flash (+1 fallbacks)", provider.label());
    }
}
