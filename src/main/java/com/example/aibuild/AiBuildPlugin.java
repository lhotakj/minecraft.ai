package com.example.aibuild;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /ai build &lt;description&gt; - asks an AI model (Gemini, Claude, OpenAI, or
 * OpenRouter) for a
 * JSON build plan, validates it and builds it in
 * front of the player. /ai undo reverts the most recent builds.
 */
public final class AiBuildPlugin extends JavaPlugin implements TabExecutor {

    private record UndoEntry(World world, String name, int[] xs, int[] ys, int[] zs, BlockData[] previous) {
    }

    private final Set<UUID> busy = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> lastRequest = new HashMap<>();
    private final Map<UUID, Deque<UndoEntry>> undoHistory = new HashMap<>();
    private Set<Material> denied = Set.of();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadDenied();
        var command = getCommand("ai");
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        }
        try {
            getLogger().info("AI provider ready: " + AiProviders.create(getConfig()).label());
        } catch (IllegalStateException e) {
            getLogger().warning(e.getMessage());
        }
    }

    // ---------------------------------------------------------------- commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            usage(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("reload")) {
            if (!sender.hasPermission("aibuild.admin")) {
                sender.sendMessage(Component.text("You may not do that.", NamedTextColor.RED));
                return true;
            }
            reloadConfig();
            loadDenied();
            sender.sendMessage(Component.text("[AI] Config reloaded.", NamedTextColor.GREEN));
            return true;
        }

        if (sub.equals("version")) {
            handleVersion(sender);
            return true;
        }

        if (sub.equals("order") || sub.equals("providers") || sub.equals("models")) {
            handleOrder(sender);
            return true;
        }

        if (sub.equals("help")) {
            handleHelp(sender);
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can build.");
            return true;
        }
        switch (sub) {
            case "build" -> handleBuild(player, args);
            case "undo" -> handleUndo(player);
            default -> usage(sender);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        List<String> options = new ArrayList<>(List.of("build", "undo", "version", "order", "help"));
        if (sender.hasPermission("aibuild.admin")) {
            options.add("reload");
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        options.removeIf(option -> !option.startsWith(prefix));
        return options;
    }

    private void usage(CommandSender sender) {
        sender.sendMessage(Component.text("Usage: /ai <build <description> | undo | version | order | help"
                + (sender.hasPermission("aibuild.admin") ? " | reload>" : ">"), NamedTextColor.YELLOW));
    }

    private void handleVersion(CommandSender sender) {
        String ver = getPluginMeta().getVersion();
        sender.sendMessage(Component.text("[AI] AiBuild v" + ver, NamedTextColor.GOLD));
        try {
            AiProvider provider = AiProviders.create(getConfig());
            sender.sendMessage(Component.text("[AI] Active AI Provider: " + provider.label(), NamedTextColor.GRAY));
        } catch (Exception e) {
            sender.sendMessage(Component.text("[AI] AI Provider status: Not configured (" + e.getMessage() + ")",
                    NamedTextColor.RED));
        }
    }

    private void handleOrder(CommandSender sender) {
        try {
            AiProvider provider = AiProviders.create(getConfig());
            sender.sendMessage(Component.text("=== AiBuild AI Configuration ===", NamedTextColor.GOLD));
            sender.sendMessage(Component.text("Active AI Execution: ", NamedTextColor.YELLOW)
                    .append(Component.text(provider.label(), NamedTextColor.WHITE)));
            if (provider instanceof ChainedAiProvider chained) {
                sender.sendMessage(Component.text("Provider Chain (" + chained.providers().size() + " providers):", NamedTextColor.GRAY));
                int idx = 1;
                for (AiProvider p : chained.providers()) {
                    sender.sendMessage(Component.text("  " + idx + ". " + p.label(), NamedTextColor.AQUA));
                    idx++;
                }
            }
        } catch (Exception e) {
            sender.sendMessage(Component.text("[AI] AI Provider status: Not configured (" + e.getMessage() + ")",
                    NamedTextColor.RED));
        }
    }

    private void handleHelp(CommandSender sender) {
        sender.sendMessage(Component.text("=== AiBuild Commands ===", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("/ai build <description>", NamedTextColor.YELLOW)
                .append(Component.text(" - Build a structure using AI", NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("  Example: ", NamedTextColor.GRAY)
                .append(Component.text(
                        "/ai build for me a house with three floors from oak wood, oak doors and a small balcony in second floor",
                        NamedTextColor.AQUA)));
        sender.sendMessage(Component.text("/ai undo", NamedTextColor.YELLOW)
                .append(Component.text(" - Revert your most recent build", NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("/ai version", NamedTextColor.YELLOW)
                .append(Component.text(" - Display plugin version & provider info", NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("/ai order", NamedTextColor.YELLOW)
                .append(Component.text(" - Display active AI execution order & model chain", NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("/ai help", NamedTextColor.YELLOW)
                .append(Component.text(" - Show this help menu", NamedTextColor.WHITE)));
        if (sender.hasPermission("aibuild.admin")) {
            sender.sendMessage(Component.text("/ai reload", NamedTextColor.YELLOW)
                    .append(Component.text(" - Reload configuration & deny-list", NamedTextColor.WHITE)));
        }
        sender.sendMessage(
                Component.text("Tip: Stand on flat ground facing open terrain before building.", NamedTextColor.GRAY));
    }

    // ---------------------------------------------------------------- /ai build

    private void handleBuild(Player player, String[] args) {
        UUID id = player.getUniqueId();
        String description = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
        if (description.isEmpty()) {
            send(id, "Usage: /ai build <what to build>", NamedTextColor.YELLOW);
            return;
        }
        if (description.length() > 500) {
            send(id, "Please keep the description under 500 characters.", NamedTextColor.RED);
            return;
        }
        AiProvider provider;
        try {
            provider = AiProviders.create(getConfig());
        } catch (IllegalStateException e) {
            send(id, e.getMessage(), NamedTextColor.RED);
            return;
        }
        if (busy.contains(id)) {
            send(id, "Still working on your previous request.", NamedTextColor.YELLOW);
            return;
        }

        FileConfiguration cfg = getConfig();
        long now = System.currentTimeMillis();
        long cooldownMs = cfg.getInt("limits.cooldown-seconds", 15) * 1000L;
        Long last = lastRequest.get(id);
        if (last != null && now - last < cooldownMs) {
            long wait = (cooldownMs - (now - last) + 999) / 1000;
            send(id, "Please wait " + wait + "s before the next build.", NamedTextColor.YELLOW);
            return;
        }
        busy.add(id);
        lastRequest.put(id, now);

        int maxBlocks = cfg.getInt("limits.max-blocks", 200000);
        int maxSize = cfg.getInt("limits.max-size", 120);
        int maxHeight = cfg.getInt("limits.max-height", 100);
        String system = Prompts.system(maxBlocks, maxSize, maxHeight);
        String userMessage = "Build request: " + description;

        World world = player.getWorld();
        Location where = player.getLocation();
        getLogger().info(player.getName() + " requested build: \"" + description + "\" using " + provider.label());
        send(id, "Asking " + provider.label() + " to design: " + description, NamedTextColor.GRAY);

        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                String reply = provider.complete(system, userMessage, status -> {
                    getLogger().info(status);
                    runSync(() -> send(id, status, NamedTextColor.GRAY));
                });
                getLogger().info("Received response from " + provider.label() + " (" + reply.length()
                        + " chars). Parsing plan JSON...");
                JsonObject plan = extractJson(reply);
                runSync(() -> startBuild(id, world, where, plan, maxBlocks, maxSize, maxHeight));
            } catch (Exception e) {
                getLogger().warning("AI request failed: " + e.getMessage());
                runSync(() -> {
                    busy.remove(id);
                    send(id, "The AI request failed: " + e.getMessage(), NamedTextColor.RED);
                });
            }
        });
    }

    private void startBuild(UUID id, World world, Location where, JsonObject root,
            int maxBlocks, int maxSize, int maxHeight) {
        try {
            if (!root.has("ops") || !root.get("ops").isJsonArray()) {
                throw new BuildPlanner.PlanException("the AI reply had no 'ops' list.");
            }
            String name = root.has("name") && root.get("name").isJsonPrimitive()
                    ? root.get("name").getAsString()
                    : "structure";

            BuildPlanner planner = new BuildPlanner(maxBlocks, denied);
            Map<BuildPlanner.Pos, BlockData> plan = planner.plan(root.getAsJsonArray("ops"));
            Map<BuildPlanner.Pos, List<BuildPlanner.ItemInfo>> containerItems = planner.getContainerItems();
            if (plan.isEmpty()) {
                throw new BuildPlanner.PlanException("the plan was empty.");
            }

            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (BuildPlanner.Pos p : plan.keySet()) {
                minX = Math.min(minX, p.x());
                maxX = Math.max(maxX, p.x());
                minY = Math.min(minY, p.y());
                maxY = Math.max(maxY, p.y());
                minZ = Math.min(minZ, p.z());
                maxZ = Math.max(maxZ, p.z());
            }
            int sizeX = maxX - minX + 1, sizeY = maxY - minY + 1, sizeZ = maxZ - minZ + 1;
            if (sizeX > maxSize || sizeZ > maxSize || sizeY > maxHeight) {
                throw new BuildPlanner.PlanException("the build is too large (" + sizeX + "x" + sizeY + "x" + sizeZ
                        + ", limit " + maxSize + " wide, " + maxHeight + " tall).");
            }

            // Put the build in front of the player: nearest edge `gap` blocks away, ground
            // layer = block under the feet.
            int[] dir = facing(where.getYaw());
            double halfAhead = (dir[0] != 0 ? sizeX : sizeZ) / 2.0;
            double distance = halfAhead + getConfig().getInt("placement.gap", 4);
            int offX = (int) Math.round(where.getBlockX() + dir[0] * distance - (minX + maxX) / 2.0);
            int offZ = (int) Math.round(where.getBlockZ() + dir[1] * distance - (minZ + maxZ) / 2.0);
            int baseY = where.getBlockY() - 1;

            List<Map.Entry<BuildPlanner.Pos, BlockData>> entries = new ArrayList<>(plan.entrySet());
            entries.sort(Comparator.comparingInt(e -> e.getKey().y())); // build bottom-up

            int worldMin = world.getMinHeight(), worldMax = world.getMaxHeight();
            int[] xs = new int[entries.size()];
            int[] ys = new int[entries.size()];
            int[] zs = new int[entries.size()];
            BlockData[] data = new BlockData[entries.size()];
            @SuppressWarnings("unchecked")
            List<BuildPlanner.ItemInfo>[] items = new List[entries.size()];
            int count = 0;
            for (Map.Entry<BuildPlanner.Pos, BlockData> entry : entries) {
                BuildPlanner.Pos p = entry.getKey();
                int wy = baseY + p.y();
                if (wy < worldMin || wy >= worldMax) {
                    continue;
                }
                xs[count] = p.x() + offX;
                ys[count] = wy;
                zs[count] = p.z() + offZ;
                data[count] = entry.getValue();
                items[count] = containerItems.get(p);
                count++;
            }
            final int[] fx = Arrays.copyOf(xs, count);
            final int[] fy = Arrays.copyOf(ys, count);
            final int[] fz = Arrays.copyOf(zs, count);
            final BlockData[] fd = Arrays.copyOf(data, count);
            final List<BuildPlanner.ItemInfo>[] fi = Arrays.copyOf(items, count);
            final BlockData[] previous = new BlockData[count];

            send(id, "Plan ready: " + name + " (" + sizeX + "x" + sizeY + "x" + sizeZ + ", " + count
                    + " blocks). Building...", NamedTextColor.GRAY);
            getLogger().info("Plan validated: " + name + " (" + sizeX + "x" + sizeY + "x" + sizeZ + ", " + count
                    + " blocks). Starting block placement...");

            new BlockWorker(world, fx, fy, fz, fd, fi, previous, getConfig().getInt("limits.blocks-per-tick", 4000),
                    () -> {
                        int placed = 0;
                        for (BlockData old : previous) {
                            if (old != null) {
                                placed++;
                            }
                        }
                        Deque<UndoEntry> history = undoHistory.computeIfAbsent(id, k -> new ArrayDeque<>());
                        history.addFirst(new UndoEntry(world, name, fx, fy, fz, previous));
                        int keep = Math.max(1, getConfig().getInt("limits.undo-history", 3));
                        while (history.size() > keep) {
                            history.removeLast();
                        }
                        busy.remove(id);
                        getLogger().info("Build completed: " + name + " (" + placed + " blocks placed).");
                        send(id, "Done: " + name + " (" + placed + " blocks changed). /ai undo reverts it.",
                                NamedTextColor.GREEN);
                    },
                    error -> {
                        busy.remove(id);
                        getLogger().warning("Building failed: " + error.getMessage());
                        send(id, "Building stopped because of an error (see the server log).", NamedTextColor.RED);
                    }).runTaskTimer(this, 1L, 1L);
        } catch (BuildPlanner.PlanException e) {
            busy.remove(id);
            getLogger().warning("Invalid build plan: " + e.getMessage());
            send(id, "Could not build that: " + e.getMessage(), NamedTextColor.RED);
        }
    }

    // ---------------------------------------------------------------- /ai undo

    private void handleUndo(Player player) {
        UUID id = player.getUniqueId();
        if (busy.contains(id)) {
            send(id, "Still working on your previous request.", NamedTextColor.YELLOW);
            return;
        }
        Deque<UndoEntry> history = undoHistory.get(id);
        UndoEntry entry = history == null ? null : history.pollFirst();
        if (entry == null) {
            send(id, "Nothing to undo.", NamedTextColor.YELLOW);
            return;
        }
        busy.add(id);
        send(id, "Undoing " + entry.name() + "...", NamedTextColor.GRAY);
        new BlockWorker(entry.world(), entry.xs(), entry.ys(), entry.zs(), entry.previous(), null,
                getConfig().getInt("limits.blocks-per-tick", 4000),
                () -> {
                    busy.remove(id);
                    send(id, "Undone.", NamedTextColor.GREEN);
                },
                error -> {
                    busy.remove(id);
                    getLogger().warning("Undo failed: " + error);
                    send(id, "Undo stopped because of an error (see the server log).", NamedTextColor.RED);
                }).runTaskTimer(this, 1L, 1L);
    }

    // ---------------------------------------------------------------- helpers

    /**
     * The AI may wrap its JSON in prose or code fences despite instructions; take
     * the outermost object.
     */
    static JsonObject extractJson(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("the AI did not return a JSON build plan.");
        }
        String json = text.substring(start, end + 1);
        try {
            var reader = new com.google.gson.stream.JsonReader(new java.io.StringReader(json));
            reader.setLenient(true);
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception ignored) {
            // Remove trailing commas before closing braces/brackets (common LLM artifact)
            String sanitized = json.replaceAll(",\\s*([\\]}])", "$1");
            var reader = new com.google.gson.stream.JsonReader(new java.io.StringReader(sanitized));
            reader.setLenient(true);
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    /**
     * Cardinal direction the player faces as {dx, dz}. Minecraft yaw: 0 = south
     * (+z), 90 = west (-x).
     */
    private static int[] facing(float yaw) {
        double rad = Math.toRadians(yaw);
        double dx = -Math.sin(rad);
        double dz = Math.cos(rad);
        return Math.abs(dx) > Math.abs(dz)
                ? new int[] { (int) Math.signum(dx), 0 }
                : new int[] { 0, (int) Math.signum(dz) };
    }

    private void loadDenied() {
        Set<Material> set = EnumSet.noneOf(Material.class);
        for (String name : getConfig().getStringList("denied-blocks")) {
            Material material = Material.matchMaterial(name);
            if (material == null) {
                getLogger().warning("denied-blocks: unknown material '" + name + "'");
            } else {
                set.add(material);
            }
        }
        denied = set;
    }

    private void runSync(Runnable task) {
        if (isEnabled()) {
            getServer().getScheduler().runTask(this, task);
        }
    }

    private void send(UUID id, String text, NamedTextColor color) {
        Player player = getServer().getPlayer(id);
        if (player != null) {
            player.sendMessage(Component.text("[AI] " + text, color));
        }
    }
}
