package com.example.aibuild;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void testGeminiClientLabel() {
        GeminiClient single = new GeminiClient("key", List.of("gemini-3.8-flash"), 1000, "", 30);
        assertEquals("gemini-3.8-flash", single.label());

        GeminiClient multi = new GeminiClient("key", List.of("gemini-3.8-flash", "gemini-3.7-flash", "gemini-3.5-flash"), 1000, "", 30);
        assertEquals("gemini-3.8-flash (+2 fallbacks)", multi.label());
    }

    @Test
    void testAnthropicClientLabel() {
        AnthropicClient single = new AnthropicClient("key", List.of("claude-sonnet-5-5"), 1000, 30);
        assertEquals("claude-sonnet-5-5", single.label());

        AnthropicClient multi = new AnthropicClient("key", List.of("claude-sonnet-5-5", "claude-3-5-haiku-latest"), 1000, 30);
        assertEquals("claude-sonnet-5-5 (+1 fallbacks)", multi.label());
    }
}

