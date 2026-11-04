package com.example.aibuild;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the AI's JSON operations into a map of local position -> block, validating everything on
 * the way. Nothing here touches the world; placing is done later by {@link BlockWorker}.
 */
final class BuildPlanner {

    record Pos(int x, int y, int z) {}

    record ItemInfo(String materialName, int count) {
        ItemInfo {
            if (materialName == null || materialName.isBlank()) {
                materialName = "stone";
            }
            if (materialName.startsWith("minecraft:")) {
                materialName = materialName.substring("minecraft:".length());
            }
            materialName = materialName.toLowerCase(Locale.ROOT);
            count = Math.max(1, count);
        }
    }

    static final class PlanException extends Exception {
        PlanException(String message) {
            super(message);
        }
    }

    private static final int MAX_OPS = 2000;

    private final Map<Pos, BlockData> blocks = new LinkedHashMap<>();
    private final Map<Pos, List<ItemInfo>> containerItems = new LinkedHashMap<>();
    private final Map<String, BlockData> dataCache = new HashMap<>();
    private final int maxBlocks;
    private final Set<Material> denied;
    private final Function<String, BlockData> blockParser;

    BuildPlanner(int maxBlocks, Set<Material> denied) {
        this(maxBlocks, denied, Bukkit::createBlockData);
    }

    BuildPlanner(int maxBlocks, Set<Material> denied, Function<String, BlockData> blockParser) {
        this.maxBlocks = maxBlocks;
        this.denied = denied;
        this.blockParser = blockParser;
    }

    Map<Pos, BlockData> plan(JsonArray ops) throws PlanException {
        if (ops.size() > MAX_OPS) {
            throw new PlanException("The plan has too many operations (" + ops.size() + ").");
        }
        for (int i = 0; i < ops.size(); i++) {
            String name = "?";
            try {
                JsonObject o = ops.get(i).getAsJsonObject();
                name = str(o, "op").toLowerCase(Locale.ROOT);
                switch (name) {
                    case "fill", "box", "cuboid", "solid_box" -> fill(o, false);
                    case "hollow_box", "hollowbox", "shell", "room" -> fill(o, true);
                    case "ellipse", "cylinder", "circle", "disc", "disk" -> ellipse(o);
                    case "arcade", "arch", "arches" -> arcade(o);
                    case "sphere", "dome", "hemisphere", "ball" -> sphere(o);
                    case "setblock", "set_block", "block", "place" -> {
                        Pos p = new Pos(num(o, "x"), num(o, "y"), num(o, "z"));
                        BlockData d = block(o, null);
                        put(p, d);
                        List<ItemInfo> items = extractItems(o);
                        if (!items.isEmpty()) {
                            containerItems.put(p, items);
                        }
                    }
                    case "comment", "note", "meta", "info", "noop", "description" -> {
                        // Informational operations ignored gracefully
                    }
                    default -> throw new PlanException("unknown operation '" + name + "'");
                }
            } catch (PlanException e) {
                throw new PlanException("op #" + (i + 1) + " (" + name + "): " + e.getMessage());
            } catch (IllegalStateException | ClassCastException e) {
                throw new PlanException("op #" + (i + 1) + " is malformed.");
            }
        }
        return blocks;
    }

    Map<Pos, List<ItemInfo>> getContainerItems() {
        return containerItems;
    }

    // ---------------------------------------------------------------- operations

    private void fill(JsonObject o, boolean hollow) throws PlanException {
        int x1 = num(o, "x1"), y1 = num(o, "y1"), z1 = num(o, "z1");
        int x2 = num(o, "x2", x1), y2 = num(o, "y2", y1), z2 = num(o, "z2", z1);
        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
        int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);
        long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (volume > maxBlocks) {
            throw new PlanException("box is larger than the block limit.");
        }
        BlockData data = block(o, null);
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (hollow && x != minX && x != maxX && y != minY && y != maxY && z != minZ && z != maxZ) {
                        continue;
                    }
                    put(new Pos(x, y, z), data);
                }
            }
        }
    }

    private void ellipse(JsonObject o) throws PlanException {
        int cx = num(o, "cx"), cz = num(o, "cz"), y = num(o, "y");
        int rx = num(o, "rx"), rz = num(o, "rz");
        int height = num(o, "height", 1);
        int thickness = num(o, "thickness", 1);
        range("rx", rx, 1, 256);
        range("rz", rz, 1, 256);
        range("height", height, 1, 256);
        BlockData data = block(o, null);

        double outerX = rx + 0.5, outerZ = rz + 0.5;
        double innerX = outerX - thickness, innerZ = outerZ - thickness;
        boolean filled = thickness <= 0 || innerX <= 0.5 || innerZ <= 0.5;

        List<int[]> cells = new ArrayList<>();
        for (int x = cx - rx; x <= cx + rx; x++) {
            for (int z = cz - rz; z <= cz + rz; z++) {
                double ox = (x - cx) / outerX, oz = (z - cz) / outerZ;
                if (ox * ox + oz * oz > 1) {
                    continue;
                }
                if (!filled) {
                    double ix = (x - cx) / innerX, iz = (z - cz) / innerZ;
                    if (ix * ix + iz * iz <= 1) {
                        continue;
                    }
                }
                cells.add(new int[] {x, z});
            }
        }
        if ((long) cells.size() * height > maxBlocks) {
            throw new PlanException("shape is larger than the block limit.");
        }
        for (int h = 0; h < height; h++) {
            for (int[] cell : cells) {
                put(new Pos(cell[0], y + h, cell[1]), data);
            }
        }
    }

    /** Cuts evenly spaced arched openings through a previously built elliptical wall. */
    private void arcade(JsonObject o) throws PlanException {
        int cx = num(o, "cx"), cz = num(o, "cz"), y = num(o, "y");
        int rx = num(o, "rx"), rz = num(o, "rz");
        int count = num(o, "count");
        int width = num(o, "width", 3);
        int height = num(o, "height", 5);
        int thickness = num(o, "thickness", 1);
        double offset = Math.toRadians(numD(o, "offset_deg", 0));
        range("rx", rx, 2, 256);
        range("rz", rz, 2, 256);
        range("count", count, 1, 256);
        range("width", width, 1, 32);
        range("height", height, 2, 64);
        range("thickness", thickness, 1, 32);
        BlockData data = block(o, "minecraft:air");
        boolean carve = isAir(data.getMaterial());

        double half = width / 2.0;
        double arcCentre = height - half; // height at which the rounded top begins
        double bandHalf = thickness / 2.0 + 1.0;
        double midX = rx + 0.5 - thickness / 2.0;
        double midZ = rz + 0.5 - thickness / 2.0;
        int reach = (int) Math.ceil(Math.max(half, bandHalf)) + 2;

        for (int k = 0; k < count; k++) {
            double t = offset + 2 * Math.PI * k / count;
            double px = cx + midX * Math.cos(t);
            double pz = cz + midZ * Math.sin(t);
            // outward normal of the ellipse at this point, and the tangent along the wall
            double nx = Math.cos(t) / (rx + 0.5), nz = Math.sin(t) / (rz + 0.5);
            double len = Math.hypot(nx, nz);
            nx /= len;
            nz /= len;
            double tx = -nz, tz = nx;

            int bx = (int) Math.round(px), bz = (int) Math.round(pz);
            for (int dx = -reach; dx <= reach; dx++) {
                for (int dz = -reach; dz <= reach; dz++) {
                    int x = bx + dx, z = bz + dz;
                    double vx = x - px, vz = z - pz;
                    double along = vx * tx + vz * tz;
                    double depth = vx * nx + vz * nz;
                    if (Math.abs(depth) > bandHalf || Math.abs(along) > half) {
                        continue;
                    }
                    for (int h = 0; h < height; h++) {
                        double hc = h + 0.5;
                        boolean inside = hc <= arcCentre
                                || along * along + (hc - arcCentre) * (hc - arcCentre) <= half * half;
                        if (!inside) {
                            continue;
                        }
                        Pos p = new Pos(x, y + h, z);
                        if (carve && !blocks.containsKey(p)) {
                            continue; // only open up what is really there
                        }
                        put(p, data);
                    }
                }
            }
        }
    }

    private void sphere(JsonObject o) throws PlanException {
        int cx = num(o, "cx"), cy = num(o, "cy"), cz = num(o, "cz");
        int r = num(o, "r");
        range("r", r, 1, 64);
        boolean hollow = o.has("hollow") && o.get("hollow").isJsonPrimitive() && o.get("hollow").getAsBoolean();
        String half = o.has("half") && o.get("half").isJsonPrimitive()
                ? o.get("half").getAsString().toLowerCase(Locale.ROOT) : "";
        BlockData data = block(o, null);

        double outer = (r + 0.5) * (r + 0.5);
        double inner = (r - 0.5) * (r - 0.5);
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                if (half.equals("top") && dy < 0 || half.equals("bottom") && dy > 0) {
                    continue;
                }
                for (int dz = -r; dz <= r; dz++) {
                    double d2 = dx * dx + dy * dy + dz * dz;
                    if (d2 > outer || hollow && d2 <= inner) {
                        continue;
                    }
                    put(new Pos(cx + dx, cy + dy, cz + dz), data);
                }
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private void put(Pos pos, BlockData data) throws PlanException {
        if (blocks.size() >= maxBlocks && !blocks.containsKey(pos)) {
            throw new PlanException("the build exceeds the limit of " + maxBlocks + " blocks.");
        }
        blocks.put(pos, data);
    }

    private BlockData block(JsonObject o, String fallback) throws PlanException {
        String raw = o.has("block") && o.get("block").isJsonPrimitive() ? o.get("block").getAsString() : fallback;
        if (raw == null || raw.isBlank()) {
            raw = fallback != null && !fallback.isBlank() ? fallback : "minecraft:stone";
        }
        int braceIndex = raw.indexOf('{');
        String blockPart = braceIndex >= 0 ? raw.substring(0, braceIndex) : raw;

        blockPart = blockPart.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        if (!blockPart.contains(":")) {
            blockPart = "minecraft:" + blockPart;
        }
        BlockData cached = dataCache.get(blockPart);
        if (cached != null) {
            return cached;
        }

        BlockData data = resolveBlockData(blockPart, fallback);
        if (data != null && denied.contains(data.getMaterial())) {
            throw new PlanException("block '" + blockPart + "' is not allowed on this server.");
        }
        dataCache.put(blockPart, data);
        return data;
    }

    private BlockData resolveBlockData(String blockPart, String fallback) throws PlanException {
        String normalized = normalizeBlockName(blockPart);

        // 1. Try parsing normalized name with properties
        try {
            BlockData data = blockParser.apply(normalized);
            if (data != null) {
                return data;
            }
        } catch (IllegalArgumentException | NullPointerException ignored) {}

        int bracket = normalized.indexOf('[');
        String baseId = bracket >= 0 ? normalized.substring(0, bracket) : normalized;
        String props = bracket >= 0 ? normalized.substring(bracket) : "";

        // 2. Torch on wall: torch[facing=...] -> wall_torch[facing=...]
        if (baseId.endsWith("torch") && !baseId.contains("wall") && props.contains("facing")) {
            String wallBase = baseId.replace("torch", "wall_torch");
            String kept = validProperties(wallBase, props);
            BlockData data = tryParse(wallBase + kept);
            if (data != null) {
                return data;
            }
        }

        // 3. Keep only the valid properties, then fall back to the bare block
        //    (heals invalid properties like glass_pane[facing=east])
        if (bracket >= 0) {
            String kept = validProperties(baseId, props);
            if (!kept.isEmpty()) {
                BlockData data = tryParse(baseId + kept);
                if (data != null) {
                    return data;
                }
            }
            try {
                BlockData data = blockParser.apply(baseId);
                if (data != null) {
                    return data;
                }
            } catch (IllegalArgumentException | NullPointerException ignored) {}
        }

        // 4. Try heuristic fallback for unknown or hallucinated block names
        String heuristic = findHeuristicBlock(baseId);
        if (heuristic != null && !heuristic.equals(baseId)) {
            if (bracket >= 0) {
                try {
                    BlockData data = blockParser.apply(heuristic + props);
                    if (data != null) {
                        return data;
                    }
                } catch (IllegalArgumentException | NullPointerException ignored) {}
            }
            try {
                BlockData data = blockParser.apply(heuristic);
                if (data != null) {
                    return data;
                }
            } catch (IllegalArgumentException | NullPointerException ignored) {}
        }

        // 5. Try explicit fallback if provided
        if (fallback != null && !fallback.isBlank()) {
            try {
                BlockData data = blockParser.apply(fallback);
                if (data != null) {
                    return data;
                }
            } catch (IllegalArgumentException | NullPointerException ignored) {}
        }

        // 6. Last resort default block: minecraft:stone
        try {
            BlockData data = blockParser.apply("minecraft:stone");
            if (data != null) {
                return data;
            }
        } catch (IllegalArgumentException | NullPointerException ignored) {}

        throw new PlanException("unknown block '" + blockPart + "'.");
    }

    private BlockData tryParse(String s) {
        try {
            return blockParser.apply(s);
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }

    /** Returns "[a=b,c=d]" containing only the properties the block accepts individually, or "". */
    private String validProperties(String baseId, String props) {
        if (props.length() < 3 || !props.endsWith("]")) {
            return "";
        }
        List<String> kept = new ArrayList<>();
        for (String prop : props.substring(1, props.length() - 1).split(",")) {
            if (prop.contains("=") && tryParse(baseId + "[" + prop + "]") != null) {
                kept.add(prop);
            }
        }
        return kept.isEmpty() ? "" : "[" + String.join(",", kept) + "]";
    }

    static String normalizeBlockName(String blockPart) {
        if (blockPart == null || blockPart.isBlank()) {
            return "minecraft:stone";
        }
        int bracket = blockPart.indexOf('[');
        String base = bracket >= 0 ? blockPart.substring(0, bracket) : blockPart;
        String props = bracket >= 0 ? blockPart.substring(bracket) : "";

        // Normalize plural "bricks" to singular "brick" for stairs, slabs, walls, fences:
        base = base.replace("_bricks_stairs", "_brick_stairs")
                   .replace("_bricks_slab", "_brick_slab")
                   .replace("_bricks_wall", "_brick_wall")
                   .replace("_bricks_fence", "_brick_fence")
                   .replace(":bricks_stairs", ":brick_stairs")
                   .replace(":bricks_slab", ":brick_slab")
                   .replace(":bricks_wall", ":brick_wall")
                   .replace(":bricks_fence", ":brick_fence");

        // Normalize wood aliases (e.g. oak_wood_stairs -> oak_stairs, oak_wooden_stairs -> oak_stairs)
        base = base.replace("_wood_stairs", "_stairs")
                   .replace("_wooden_stairs", "_stairs")
                   .replace("_wood_slab", "_slab")
                   .replace("_wooden_slab", "_slab")
                   .replace("_wood_fence", "_fence")
                   .replace("_wooden_fence", "_fence")
                   .replace("_wood_fence_gate", "_fence_gate")
                   .replace("_wooden_fence_gate", "_fence_gate")
                   .replace("_wood_door", "_door")
                   .replace("_wooden_door", "_door")
                   .replace("_wood_trapdoor", "_trapdoor")
                   .replace("_wooden_trapdoor", "_trapdoor")
                   .replace("_wood_planks", "_planks")
                   .replace("_wooden_planks", "_planks")
                   .replace("_wood_pressure_plate", "_pressure_plate")
                   .replace("_wooden_pressure_plate", "_pressure_plate")
                   .replace("_wood_button", "_button")
                   .replace("_wooden_button", "_button");

        // Common block renames and aliases:
        base = switch (base) {
            case "minecraft:grass" -> "minecraft:short_grass";
            case "minecraft:smooth_stone_stairs" -> "minecraft:stone_brick_stairs";
            case "minecraft:smooth_stone_wall" -> "minecraft:stone_brick_wall";
            case "minecraft:iron_bars_pane" -> "minecraft:iron_bars";
            case "minecraft:leaves" -> "minecraft:oak_leaves";
            case "minecraft:log" -> "minecraft:oak_log";
            case "minecraft:wood" -> "minecraft:oak_wood";
            case "minecraft:planks" -> "minecraft:oak_planks";
            case "minecraft:stairs" -> "minecraft:oak_stairs";
            case "minecraft:slab" -> "minecraft:stone_brick_slab";
            case "minecraft:wall" -> "minecraft:stone_brick_wall";
            case "minecraft:torch_wall" -> "minecraft:wall_torch";
            case "minecraft:redstone_torch_wall" -> "minecraft:redstone_wall_torch";
            case "minecraft:soul_torch_wall" -> "minecraft:soul_wall_torch";
            default -> base;
        };

        return base + props;
    }

    static String findHeuristicBlock(String baseId) {
        if (baseId == null) return "minecraft:stone";
        String lower = baseId.toLowerCase(Locale.ROOT);

        if (lower.endsWith("_stairs")) {
            if (lower.contains("wood") || lower.contains("oak") || lower.contains("plank") || lower.contains("spruce")
                    || lower.contains("birch") || lower.contains("jungle") || lower.contains("acacia") || lower.contains("dark_oak")) {
                return "minecraft:oak_stairs";
            }
            return "minecraft:stone_brick_stairs";
        }
        if (lower.endsWith("_slab")) {
            if (lower.contains("wood") || lower.contains("oak") || lower.contains("plank") || lower.contains("spruce")
                    || lower.contains("birch") || lower.contains("jungle") || lower.contains("acacia") || lower.contains("dark_oak")) {
                return "minecraft:oak_slab";
            }
            return "minecraft:stone_brick_slab";
        }
        if (lower.endsWith("_wall")) {
            return "minecraft:stone_brick_wall";
        }
        if (lower.endsWith("_fence_gate")) {
            return "minecraft:oak_fence_gate";
        }
        if (lower.endsWith("_fence")) {
            if (lower.contains("nether")) {
                return "minecraft:nether_brick_fence";
            }
            return "minecraft:oak_fence";
        }
        if (lower.endsWith("_door")) {
            return "minecraft:oak_door";
        }
        if (lower.endsWith("_trapdoor")) {
            return "minecraft:oak_trapdoor";
        }
        if (lower.endsWith("_button")) {
            return "minecraft:stone_button";
        }
        if (lower.endsWith("_pressure_plate")) {
            return "minecraft:stone_pressure_plate";
        }
        if (lower.contains("lantern")) {
            return "minecraft:lantern";
        }
        if (lower.contains("torch")) {
            return "minecraft:torch";
        }
        if (lower.contains("glass")) {
            return lower.contains("pane") ? "minecraft:glass_pane" : "minecraft:glass";
        }
        if (lower.contains("brick")) {
            return "minecraft:stone_bricks";
        }
        if (lower.contains("cobble")) {
            return "minecraft:cobblestone";
        }
        if (lower.contains("stone")) {
            return "minecraft:stone";
        }
        if (lower.contains("wood") || lower.contains("plank") || lower.contains("log")) {
            return "minecraft:oak_planks";
        }
        if (lower.contains("sand")) {
            return lower.contains("sandstone") ? "minecraft:sandstone" : "minecraft:sand";
        }
        if (lower.contains("iron")) {
            return lower.contains("bar") ? "minecraft:iron_bars" : "minecraft:iron_block";
        }
        if (lower.contains("carpet")) {
            return "minecraft:red_carpet";
        }
        if (lower.contains("bed")) {
            return "minecraft:red_bed";
        }
        return "minecraft:stone";
    }

    static List<ItemInfo> extractItems(JsonObject o) {
        List<ItemInfo> items = new ArrayList<>();
        if (o.has("block") && o.get("block").isJsonPrimitive()) {
            String raw = o.get("block").getAsString();
            int brace = raw.indexOf('{');
            if (brace >= 0) {
                items.addAll(parseItemsFromNbt(raw.substring(brace)));
            }
        }
        if (o.has("items") && o.get("items").isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray("items")) {
                if (e.isJsonObject()) {
                    JsonObject io = e.getAsJsonObject();
                    String id = io.has("id") && io.get("id").isJsonPrimitive() ? io.get("id").getAsString() : "";
                    int count = io.has("count") && io.get("count").isJsonPrimitive() ? io.get("count").getAsInt() : 1;
                    if (!id.isBlank()) {
                        items.add(new ItemInfo(id, count));
                    }
                }
            }
        }
        return items;
    }

    static List<ItemInfo> parseItemsFromNbt(String nbt) {
        if (nbt == null || nbt.isBlank()) {
            return List.of();
        }
        List<ItemInfo> items = new ArrayList<>();
        Pattern itemPattern = Pattern.compile("\\{([^{}]+)\\}");
        Matcher m = itemPattern.matcher(nbt);
        Pattern idPattern = Pattern.compile("(?:id|Item)\\s*:\\s*[\"']?([a-zA-Z0-9_:]+)[\"']?", Pattern.CASE_INSENSITIVE);
        Pattern countPattern = Pattern.compile("(?:count|Count)\\s*:\\s*([0-9]+)[bBsSlL]?", Pattern.CASE_INSENSITIVE);
        while (m.find()) {
            String itemContent = m.group(1);
            Matcher idMatcher = idPattern.matcher(itemContent);
            if (idMatcher.find()) {
                String id = idMatcher.group(1);
                int count = 1;
                Matcher countMatcher = countPattern.matcher(itemContent);
                if (countMatcher.find()) {
                    try {
                        count = Math.max(1, Math.min(64, Integer.parseInt(countMatcher.group(1))));
                    } catch (NumberFormatException ignored) {}
                }
                items.add(new ItemInfo(id, count));
            }
        }
        return items;
    }

    private static boolean isAir(Material material) {
        return material == Material.AIR || material == Material.CAVE_AIR || material == Material.VOID_AIR;
    }

    private static void range(String field, int value, int min, int max) throws PlanException {
        if (value < min || value > max) {
            throw new PlanException("'" + field + "' must be between " + min + " and " + max + ".");
        }
    }

    private static String str(JsonObject o, String key) throws PlanException {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) {
            throw new PlanException("missing '" + key + "'.");
        }
        return e.getAsString();
    }

    private static int num(JsonObject o, String key) throws PlanException {
        return (int) Math.round(numD(o, key, null));
    }

    private static int num(JsonObject o, String key, int fallback) throws PlanException {
        return (int) Math.round(numD(o, key, (double) fallback));
    }

    private static double numD(JsonObject o, String key, double fallback) throws PlanException {
        return numD(o, key, (Double) fallback);
    }

    private static double numD(JsonObject o, String key, Double fallback) throws PlanException {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) {
            String[] aliases = switch (key) {
                case "x1" -> new String[]{"x_1", "from_x", "start_x", "min_x", "x"};
                case "y1" -> new String[]{"y_1", "from_y", "start_y", "min_y", "y"};
                case "z1" -> new String[]{"z_1", "from_z", "start_z", "min_z", "z"};
                case "x2" -> new String[]{"x_2", "to_x", "end_x", "max_x", "x1", "x"};
                case "y2" -> new String[]{"y_2", "to_y", "end_y", "max_y", "y1", "y"};
                case "z2" -> new String[]{"z_2", "to_z", "end_z", "max_z", "z1", "z"};
                case "cx" -> new String[]{"center_x", "x", "x1"};
                case "cy" -> new String[]{"center_y", "y", "y1"};
                case "cz" -> new String[]{"center_z", "z", "z1"};
                case "rx" -> new String[]{"radius_x", "r_x", "r"};
                case "rz" -> new String[]{"radius_z", "r_z", "r"};
                case "r" -> new String[]{"radius", "rx", "rz"};
                case "height" -> new String[]{"h", "len_y"};
                case "thickness" -> new String[]{"thick", "t", "w"};
                case "count" -> new String[]{"num_arches", "arches", "n"};
                case "width" -> new String[]{"w"};
                default -> new String[0];
            };
            for (String alias : aliases) {
                if (o.has(alias) && !o.get(alias).isJsonNull()) {
                    e = o.get(alias);
                    break;
                }
            }
        }
        if (e == null || e.isJsonNull()) {
            if (fallback == null) {
                throw new PlanException("missing '" + key + "'.");
            }
            return fallback;
        }
        try {
            return e.getAsDouble();
        } catch (RuntimeException ex) {
            if (fallback != null) {
                return fallback;
            }
            throw new PlanException("'" + key + "' must be a number.");
        }
    }
}
