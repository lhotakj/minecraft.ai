package com.example.aibuild;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PromptsTest {

    @Test
    @DisplayName("System prompt formats configured limits")
    void testSystemPromptLimits() {
        int maxBlocks = 150000;
        int maxSize = 100;
        int maxHeight = 80;

        String prompt = Prompts.system(maxBlocks, maxSize, maxHeight);

        assertNotNull(prompt);
        assertTrue(prompt.contains("150000 blocks in total"));
        assertTrue(prompt.contains("100 blocks wide or deep"));
        assertTrue(prompt.contains("80 blocks tall"));
    }

    @Test
    @DisplayName("System prompt contains block naming and consistency rules")
    void testBlockNamingRules() {
        String prompt = Prompts.system(200000, 120, 100);

        assertTrue(prompt.contains("stone_brick_stairs"));
        assertTrue(prompt.contains("NEVER write stone_bricks_stairs"));
        assertTrue(prompt.contains("Palette consistency"));
        assertTrue(prompt.contains("Structure consistency"));
        assertTrue(prompt.contains("Block states"));
    }

    @Test
    @DisplayName("System prompt contains multi-floor, door, and balcony instructions")
    void testArchitecturalGuidelines() {
        String prompt = Prompts.system(200000, 120, 100);

        assertTrue(prompt.contains("Multi-floor structures"), "Must include multi-floor instructions");
        assertTrue(prompt.contains("Doors"), "Must include two-block tall door instructions");
        assertTrue(prompt.contains("Balconies & Windows"), "Must include balcony and window guidelines");
        assertTrue(prompt.contains("Material consistency"), "Must instruct material consistency");
        assertTrue(prompt.contains("Underground dungeons & hypogeums"), "Must include subterranean hypogeum and dungeon guidelines");
        assertTrue(prompt.contains("fill"), "Must document fill op");
        assertTrue(prompt.contains("hollow_box"), "Must document hollow_box op");
        assertTrue(prompt.contains("arcade"), "Must document arcade op");
        assertTrue(prompt.contains("setblock"), "Must document setblock op");
    }
}

