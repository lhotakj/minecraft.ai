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

/**
 * Turns the AI's JSON operations into a map of local position -> block, validating everything on
 * the way. Nothing here touches the world; placing is done later by {@link BlockWorker}.
 */
final class BuildPlanner {

    record Pos(int x, int y, int z) {}

    static final class PlanException extends Exception {
        PlanException(String message) {
            super(message);
        }
    }

    private static final int MAX_OPS = 2000;

    private final Map<Pos, BlockData> blocks = new LinkedHashMap<>();
    private final Map<String, BlockData> dataCache = new HashMap<>();
    private final int maxBlocks;
    private final Set<Material> denied;

    BuildPlanner(int maxBlocks, Set<Material> denied) {
        this.maxBlocks = maxBlocks;
        this.denied = denied;
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
                    case "fill" -> fill(o, false);
                    case "hollow_box" -> fill(o, true);
                    case "ellipse" -> ellipse(o);
                    case "arcade" -> arcade(o);
                    case "sphere" -> sphere(o);
                    case "setblock" -> put(new Pos(num(o, "x"), num(o, "y"), num(o, "z")), block(o, null));
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

    // ---------------------------------------------------------------- operations

    private void fill(JsonObject o, boolean hollow) throws PlanException {
        int x1 = num(o, "x1"), y1 = num(o, "y1"), z1 = num(o, "z1");
        int x2 = num(o, "x2"), y2 = num(o, "y2"), z2 = num(o, "z2");
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
        boolean carve = data.getMaterial().isAir();

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
        String name = o.has("block") && o.get("block").isJsonPrimitive() ? o.get("block").getAsString() : fallback;
        if (name == null || name.isBlank()) {
            throw new PlanException("missing 'block'.");
        }
        name = name.trim().toLowerCase(Locale.ROOT);
        if (!name.contains(":")) {
            name = "minecraft:" + name;
        }
        BlockData cached = dataCache.get(name);
        if (cached != null) {
            return cached;
        }
        BlockData data;
        try {
            data = Bukkit.createBlockData(name);
        } catch (IllegalArgumentException e) {
            throw new PlanException("unknown block '" + name + "'.");
        }
        if (denied.contains(data.getMaterial())) {
            throw new PlanException("block '" + name + "' is not allowed on this server.");
        }
        dataCache.put(name, data);
        return data;
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
            if (fallback == null) {
                throw new PlanException("missing '" + key + "'.");
            }
            return fallback;
        }
        try {
            return e.getAsDouble();
        } catch (RuntimeException ex) {
            throw new PlanException("'" + key + "' must be a number.");
        }
    }
}
