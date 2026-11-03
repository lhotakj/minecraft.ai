# AI Harness & Standards for Minecraft Plugins (Java 21 / PaperMC)

This document serves as the **AI Harness and Engineering Specification** for building, maintaining, and extending high-performance Minecraft plugins using **Java 21** and the **PaperMC API**.

It establishes the strict performance, threading, architectural, and safety invariants that both automated AI coding assistants (like Antigravity, GitHub Copilot, Cursor) and human developers must uphold.

---

## 1. Threading & Concurrency Invariants

### Rule 1: Never perform I/O or LLM requests on the Main Server Thread
Minecraft's primary game tick (20 ticks/sec, 50ms per tick) runs on a single main thread. Any blocking call halts the server, kicking players and dropping TPS to 0.

```java
// ❌ CRITICAL VIOLATION - Freezes the server for seconds
String response = httpClient.send(request, BodyHandlers.ofString()).body();

// ✅ PROPER HARNESS PATTERN - Off-thread asynchronous dispatch
getServer().getScheduler().runTaskAsynchronously(this, () -> {
    try {
        String response = aiProvider.complete(system, userMessage);
        JsonObject plan = extractJson(response);
        // Dispatch world mutation safely back to main thread
        getServer().getScheduler().runTask(this, () -> applyPlan(plan));
    } catch (Exception e) {
        getLogger().warning("Async failure: " + e.getMessage());
    }
});
```

### Rule 2: Never mutate world blocks or call non-thread-safe Bukkit APIs asynchronously
World block data, entity states, and player inventories are **not thread-safe**. Calling `world.getBlockAt(...)` or `block.setBlockData(...)` from an asynchronous thread causes race conditions, chunk corruption, and crashes.

- **Asynchronous Phase**: Network communication, JSON serialization, math calculations, coordinate transformations, validation.
- **Synchronous Phase**: `world.getBlockAt()`, `block.setBlockData()`, sending chat packets.

---

## 2. World Mutation & TPS Preservation

### Rule 3: Chunk-Safe Throttling with `BukkitRunnable`
Placing 50,000 blocks in a single server tick will freeze the server for hundreds of milliseconds. Always throttle large block placements across server ticks:

```java
public final class BlockWorker extends BukkitRunnable {
    private final int perTick; // e.g., 4000 blocks / tick
    private int cursor;

    @Override
    public void run() {
        int end = Math.min(cursor + perTick, totalBlocks);
        for (; cursor < end; cursor++) {
            world.getBlockAt(x[cursor], y[cursor], z[cursor])
                 .setBlockData(target[cursor], false); // false = NO physics updates
        }
        if (cursor >= totalBlocks) {
            cancel();
            onDone.run();
        }
    }
}
```

### Rule 4: Disable Physics Updates during Batch Operations
Passing `false` into `Block.setBlockData(data, false)` tells Minecraft to bypass immediate block and lighting updates (preventing falling gravel cascading, water flow checks, and redstone recalculations).

### Rule 5: Bottom-Up Layer Ordering
Always sort planned block operations by ascending $Y$ coordinate:
```java
entries.sort(Comparator.comparingInt(e -> e.getKey().y()));
```
This ensures foundations and lower floors exist before upper floors, railings, lanterns, and doors are placed on top of them.

---

## 3. Memory & Resource Safety

### Rule 6: Never store strong references to `org.bukkit.entity.Player`
Storing `Player` references across asynchronous tasks or in persistent collections leads to memory leaks when players disconnect, keeping the entire `CraftPlayer` and chunk memory retained in the heap.

```java
// ❌ WRONG: Leaks player memory on disconnect
private final Map<Player, Deque<UndoEntry>> undoHistory = new HashMap<>();

// ✅ PROPER HARNESS PATTERN: Key collections by UUID
private final Map<UUID, Deque<UndoEntry>> undoHistory = new HashMap<>();
```

### Rule 7: Bounded History Stacks
All in-memory histories (undo buffers, cooldown caches) must have explicit upper limits (e.g., maximum 3 undo operations per player) to prevent unbounded memory growth on long-running servers.

---

## 4. Robust AI Output Parsing & Sanitization

LLMs can occasionally output unexpected whitespace, markdown fences, or trailing commas. The ingestion pipeline must be resilient:

### Rule 8: Lenient JSON Parsing
```java
// Extract outermost JSON block and parse with Lenient JsonReader
static JsonObject extractJson(String text) {
    int start = text.indexOf('{');
    int end = text.lastIndexOf('}');
    if (start < 0 || end <= start) {
        throw new IllegalArgumentException("the AI did not return a JSON build plan.");
    }
    String json = text.substring(start, end + 1);
    try {
        var reader = new com.google.gson.stream.JsonReader(new java.io.StringReader(json));
        reader.setLenient(true); // Tolerates trailing commas, unquoted keys
        return JsonParser.parseReader(reader).getAsJsonObject();
    } catch (Exception e) {
        return JsonParser.parseString(json).getAsJsonObject();
    }
}
```

### Rule 9: Blockstate Whitespace Sanitization
Minecraft's `BlockArgumentParser` throws `IllegalArgumentException` if whitespace exists within block state brackets (e.g. `[facing = north, half = lower]`). Always sanitize:
```java
name = name.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
if (!name.contains(":")) {
    name = "minecraft:" + name;
}
```

---

## 5. Modern Java 21 Standards

1. **Records**: Use Java records for immutable data transfer:
   ```java
   record Pos(int x, int y, int z) {}
   record UndoEntry(World world, String name, int[] xs, int[] ys, int[] zs, BlockData[] previous) {}
   ```
2. **Text Blocks (`"""`)**: Use multi-line text blocks for prompts and JSON schemas.
3. **Pattern Matching & Switch Expressions**:
   ```java
   switch (opName) {
       case "fill" -> fill(o, false);
       case "hollow_box" -> fill(o, true);
       case "setblock" -> setBlock(o);
       default -> throw new PlanException("Unknown operation: " + opName);
   }
   ```
4. **Decoupled Interfaces**: Allow block parsing dependency injection (`Function<String, BlockData>`) so business logic can be unit-tested without loading a full Minecraft server instance.

---

## 6. Verification & Automated CI Harness

Every modification to the codebase must pass the automated CI pipeline:
- `mvn clean test` (Fast unit tests running with JUnit 5).
- Maven packaging with dynamic version stamping (`0.1.<build-number>`).
- Zero checkstyle or compiler warnings with Java 21.

