package com.example.aibuild;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JsonExtractionTest {

    @Test
    @DisplayName("Extract pure JSON without surrounding text")
    void testPureJson() {
        String input = """
                {"name": "test_house", "ops": []}
                """;
        JsonObject json = AiBuildPlugin.extractJson(input);
        assertEquals("test_house", json.get("name").getAsString());
        assertTrue(json.get("ops").getAsJsonArray().isEmpty());
    }

    @Test
    @DisplayName("Extract JSON wrapped in markdown code fence and prose")
    void testMarkdownCodeFences() {
        String input = """
                Here is your design for a 3-floor oak house:
                ```json
                {
                  "name": "Oak Manor",
                  "ops": [
                    {"op": "fill", "x1": 0, "y1": 0, "z1": 0, "x2": 5, "y2": 0, "z2": 5, "block": "minecraft:oak_planks"}
                  ]
                }
                ```
                Enjoy building this!
                """;
        JsonObject json = AiBuildPlugin.extractJson(input);
        assertEquals("Oak Manor", json.get("name").getAsString());
        assertEquals(1, json.get("ops").getAsJsonArray().size());
    }

    @Test
    @DisplayName("Lenient parser tolerates trailing commas in ops list")
    void testTrailingCommas() {
        String input = """
                {
                  "name": "Tolerant House",
                  "ops": [
                    {"op": "setblock", "x": 0, "y": 1, "z": 0, "block": "minecraft:oak_door[half=lower]"},
                  ],
                }
                """;
        JsonObject json = AiBuildPlugin.extractJson(input);
        assertEquals("Tolerant House", json.get("name").getAsString());
        assertEquals(1, json.get("ops").getAsJsonArray().size());
    }

    @Test
    @DisplayName("Reject text without JSON objects")
    void testMissingJson() {
        String input = "I am an AI and I cannot build that right now.";
        assertThrows(IllegalArgumentException.class, () -> AiBuildPlugin.extractJson(input));
    }
}

