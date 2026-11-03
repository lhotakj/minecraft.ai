package com.example.aibuild;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class BuildPlannerTest {

    private static BlockData mockBlockData(Material material) {
        return (BlockData) Proxy.newProxyInstance(
                BlockData.class.getClassLoader(),
                new Class<?>[]{BlockData.class},
                (proxy, method, args) -> {
                    if ("getMaterial".equals(method.getName())) {
                        return material;
                    }
                    if ("toString".equals(method.getName())) {
                        return "MockBlockData[" + material + "]";
                    }
                    return null;
                }
        );
    }

    @Test
    @DisplayName("Plan a three-floor oak house with balcony and doors")
    void testThreeFloorOakHousePlan() throws Exception {
        String jsonOps = """
                [
                  {"op": "fill", "x1": -5, "y1": 0, "z1": -5, "x2": 5, "y2": 0, "z2": 5, "block": "minecraft:oak_planks"},
                  {"op": "hollow_box", "x1": -5, "y1": 1, "z1": -5, "x2": 5, "y2": 12, "z2": 5, "block": "minecraft:oak_planks"},
                  {"op": "fill", "x1": -4, "y1": 4, "z1": -4, "x2": 4, "y2": 4, "z2": 4, "block": "minecraft:oak_planks"},
                  {"op": "fill", "x1": -4, "y1": 8, "z1": -4, "x2": 4, "y2": 8, "z2": 4, "block": "minecraft:oak_planks"},
                  {"op": "fill", "x1": -2, "y1": 4, "z1": 6, "x2": 2, "y2": 4, "z2": 7, "block": "minecraft:oak_planks"},
                  {"op": "fill", "x1": -2, "y1": 5, "z1": 7, "x2": 2, "y2": 5, "z2": 7, "block": "minecraft:oak_fence"},
                  {"op": "setblock", "x": 0, "y": 1, "z": -5, "block": "minecraft:oak_door[half=lower,facing=north]"},
                  {"op": "setblock", "x": 0, "y": 2, "z": -5, "block": "minecraft:oak_door[half=upper,facing=north]"},
                  {"op": "setblock", "x": 0, "y": 5, "z": 5, "block": "minecraft:oak_door[half=lower,facing=south]"},
                  {"op": "setblock", "x": 0, "y": 6, "z": 5, "block": "minecraft:oak_door[half=upper,facing=south]"}
                ]
                """;
        JsonArray ops = JsonParser.parseString(jsonOps).getAsJsonArray();

        BuildPlanner planner = new BuildPlanner(50000, Collections.emptySet(), name -> {
            if (name.contains("fence")) return mockBlockData(Material.OAK_FENCE);
            if (name.contains("door")) return mockBlockData(Material.OAK_DOOR);
            return mockBlockData(Material.OAK_PLANKS);
        });

        Map<BuildPlanner.Pos, BlockData> plan = planner.plan(ops);

        assertFalse(plan.isEmpty());
        // Verify entrance door lower and upper exist
        assertTrue(plan.containsKey(new BuildPlanner.Pos(0, 1, -5)));
        assertTrue(plan.containsKey(new BuildPlanner.Pos(0, 2, -5)));
        // Verify balcony door exists on second floor
        assertTrue(plan.containsKey(new BuildPlanner.Pos(0, 5, 5)));
        assertTrue(plan.containsKey(new BuildPlanner.Pos(0, 6, 5)));
        // Verify balcony railing fence exists
        assertTrue(plan.containsKey(new BuildPlanner.Pos(0, 5, 7)));
    }

    @Test
    @DisplayName("Sanitize spaces in block states")
    void testWhitespaceSanitization() throws Exception {
        String jsonOps = """
                [
                  {"op": "setblock", "x": 1, "y": 1, "z": 1, "block": "minecraft:oak_stairs[ facing = north , half = bottom ]"}
                ]
                """;
        JsonArray ops = JsonParser.parseString(jsonOps).getAsJsonArray();

        AtomicReference<String> passedName = new AtomicReference<>();
        BuildPlanner planner = new BuildPlanner(1000, Collections.emptySet(), name -> {
            passedName.set(name);
            return mockBlockData(Material.OAK_STAIRS);
        });

        planner.plan(ops);

        assertEquals("minecraft:oak_stairs[facing=north,half=bottom]", passedName.get());
    }

    @Test
    @DisplayName("Reject denied materials like TNT")
    void testDeniedMaterial() {
        String jsonOps = """
                [
                  {"op": "setblock", "x": 0, "y": 1, "z": 0, "block": "minecraft:tnt"}
                ]
                """;
        JsonArray ops = JsonParser.parseString(jsonOps).getAsJsonArray();

        BuildPlanner planner = new BuildPlanner(1000, Set.of(Material.TNT), name -> mockBlockData(Material.TNT));

        assertThrows(BuildPlanner.PlanException.class, () -> planner.plan(ops));
    }

    @Test
    @DisplayName("Reject plans exceeding max block limit")
    void testBlockLimitExceeded() {
        String jsonOps = """
                [
                  {"op": "fill", "x1": 0, "y1": 0, "z1": 0, "x2": 10, "y2": 10, "z2": 10, "block": "minecraft:stone"}
                ]
                """;
        JsonArray ops = JsonParser.parseString(jsonOps).getAsJsonArray();

        // 11 * 11 * 11 = 1331 blocks > limit 100
        BuildPlanner planner = new BuildPlanner(100, Collections.emptySet(), name -> mockBlockData(Material.STONE));

        assertThrows(BuildPlanner.PlanException.class, () -> planner.plan(ops));
    }

    @Test
    @DisplayName("Plan full 100-diameter Colosseum with sand floor, 3-tier arcades, and underground lion hypogeum")
    void testColosseumWith100DiameterAndHiddenLionDungeon() throws Exception {
        String jsonOps = """
                [
                  {"op": "fill", "x1": -28, "y1": -6, "z1": -20, "x2": 28, "y2": -1, "z2": 20, "block": "minecraft:stone_bricks"},
                  {"op": "fill", "x1": -25, "y1": -5, "z1": -3, "x2": 25, "y2": -2, "z2": 3, "block": "minecraft:air"},
                  {"op": "fill", "x1": -18, "y1": -5, "z1": 5, "x2": -6, "y2": -2, "z2": 15, "block": "minecraft:iron_bars"},
                  {"op": "fill", "x1": 6, "y1": -5, "z1": -15, "x2": 18, "y2": -2, "z2": -5, "block": "minecraft:iron_bars"},
                  {"op": "setblock", "x": 0, "y": -2, "z": 0, "block": "minecraft:lantern"},
                  {"op": "ellipse", "cx": 0, "cz": 0, "y": 0, "rx": 30, "rz": 20, "height": 1, "thickness": 0, "block": "minecraft:sand"},
                  {"op": "setblock", "x": 0, "y": 0, "z": 0, "block": "minecraft:oak_trapdoor"},
                  {"op": "ellipse", "cx": 0, "cz": 0, "y": 1, "rx": 35, "rz": 25, "height": 3, "thickness": 2, "block": "minecraft:sandstone"},
                  {"op": "ellipse", "cx": 0, "cz": 0, "y": 4, "rx": 40, "rz": 30, "height": 3, "thickness": 2, "block": "minecraft:sandstone"},
                  {"op": "ellipse", "cx": 0, "cz": 0, "y": 7, "rx": 45, "rz": 35, "height": 3, "thickness": 2, "block": "minecraft:sandstone"},
                  {"op": "ellipse", "cx": 0, "cz": 0, "y": 1, "rx": 49, "rz": 39, "height": 18, "thickness": 2, "block": "minecraft:sandstone"},
                  {"op": "arcade", "cx": 0, "cz": 0, "y": 1, "rx": 49, "rz": 39, "count": 32, "width": 3, "height": 5, "thickness": 2, "block": "minecraft:air"},
                  {"op": "arcade", "cx": 0, "cz": 0, "y": 7, "rx": 49, "rz": 39, "count": 32, "width": 3, "height": 5, "thickness": 2, "block": "minecraft:air"},
                  {"op": "arcade", "cx": 0, "cz": 0, "y": 13, "rx": 49, "rz": 39, "count": 32, "width": 3, "height": 4, "thickness": 2, "block": "minecraft:air"}
                ]
                """;
        JsonArray ops = JsonParser.parseString(jsonOps).getAsJsonArray();

        BuildPlanner planner = new BuildPlanner(200000, Collections.emptySet(), name -> {
            if (name.contains("air")) return mockBlockData(Material.AIR);
            if (name.contains("sandstone")) return mockBlockData(Material.SANDSTONE);
            if (name.contains("sand")) return mockBlockData(Material.SAND);
            if (name.contains("iron_bars")) return mockBlockData(Material.IRON_BARS);
            if (name.contains("trapdoor")) return mockBlockData(Material.OAK_TRAPDOOR);
            if (name.contains("lantern")) return mockBlockData(Material.LANTERN);
            return mockBlockData(Material.STONE_BRICKS);
        });

        Map<BuildPlanner.Pos, BlockData> plan = planner.plan(ops);

        assertFalse(plan.isEmpty());

        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;

        boolean hasSandAtGround = false;
        boolean hasUndergroundLionBars = false;

        for (Map.Entry<BuildPlanner.Pos, BlockData> entry : plan.entrySet()) {
            BuildPlanner.Pos p = entry.getKey();
            minX = Math.min(minX, p.x());
            maxX = Math.max(maxX, p.x());
            minY = Math.min(minY, p.y());
            maxY = Math.max(maxY, p.y());
            minZ = Math.min(minZ, p.z());
            maxZ = Math.max(maxZ, p.z());

            if (p.y() == 0 && entry.getValue().getMaterial() == Material.SAND) {
                hasSandAtGround = true;
            }
            if (p.y() < 0 && entry.getValue().getMaterial() == Material.IRON_BARS) {
                hasUndergroundLionBars = true;
            }
        }

        int diameterX = maxX - minX + 1;
        int diameterZ = maxZ - minZ + 1;

        // Verify diameter is approximately 100 blocks (outer rx = 49 -> span 99)
        assertEquals(99, diameterX, "Major axis diameter should be ~100 blocks");
        assertEquals(79, diameterZ, "Minor axis diameter should match ellipse ratio");

        // Verify bounds fit within server limits
        assertTrue(diameterX <= 120, "Width within max-size limit of 120");
        assertTrue(diameterZ <= 120, "Depth within max-size limit of 120");

        // Verify vertical dimension (from -6 underground hypogeum to +18 superstructure = 25 height <= 100)
        assertEquals(-6, minY, "Hypogeum dungeon reaches -6 depth");
        assertEquals(18, maxY, "Superstructure reaches 18 height");
        assertTrue((maxY - minY + 1) <= 100, "Height within max-height limit of 100");

        // Verify total blocks is comfortably within the 200,000 limit
        assertTrue(plan.size() < 200000, "Plan must fit in 200,000 blocks limit");
        assertTrue(plan.size() > 5000, "Should generate a grand, detailed structure");

        // Verify the user's specific details are present
        assertTrue(hasSandAtGround, "Must include sand floor on the arena surface at Y=0");
        assertTrue(hasUndergroundLionBars, "Must include underground lion dungeon with iron bars at Y < 0");
    }

    @Test
    @DisplayName("Plan handles chest with NBT items without failing")
    void testChestWithNbtItems() throws Exception {
        String jsonOps = """
                [
                  {"op": "setblock", "x": 1, "y": 1, "z": 1, "block": "minecraft:chest[facing=west]{items:[{slot:13b,id:\\"minecraft:iron_ingot\\",count:1b}]}"},
                  {"op": "setblock", "x": 2, "y": 1, "z": 1, "block": "minecraft:chest{items:[{id:\\"diamond\\",count:5}]}"}
                ]
                """;
        JsonArray ops = JsonParser.parseString(jsonOps).getAsJsonArray();

        AtomicReference<String> lastParsedName = new AtomicReference<>();
        BuildPlanner planner = new BuildPlanner(100, Collections.emptySet(), name -> {
            lastParsedName.set(name);
            assertFalse(name.contains("{"), "Braces should be stripped from block parser name: " + name);
            return mockBlockData(Material.CHEST);
        });

        Map<BuildPlanner.Pos, BlockData> plan = planner.plan(ops);
        assertEquals(2, plan.size());

        Map<BuildPlanner.Pos, java.util.List<BuildPlanner.ItemInfo>> containerItems = planner.getContainerItems();
        assertEquals(2, containerItems.size());

        java.util.List<BuildPlanner.ItemInfo> items1 = containerItems.get(new BuildPlanner.Pos(1, 1, 1));
        assertNotNull(items1);
        assertEquals(1, items1.size());
        assertEquals("iron_ingot", items1.get(0).materialName());
        assertEquals(1, items1.get(0).count());

        java.util.List<BuildPlanner.ItemInfo> items2 = containerItems.get(new BuildPlanner.Pos(2, 1, 1));
        assertNotNull(items2);
        assertEquals(1, items2.size());
        assertEquals("diamond", items2.get(0).materialName());
        assertEquals(5, items2.get(0).count());
    }
}

