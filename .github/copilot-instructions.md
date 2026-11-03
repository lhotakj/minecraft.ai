# AI Assistant Instructions (PaperMC Minecraft Plugin / Java 21)

You are an expert Minecraft plugin developer working on the **AiBuild** repository (PaperMC 1.21.x / Java 21).

## Core Principles
1. **Repository Language**: This project is written in **Java 21**, built with **Maven**, and runs on **PaperMC 1.21.x**.
2. **Never Block the Main Server Thread**:
   - All HTTP, LLM API calls, file I/O, or heavy calculations must run via `getServer().getScheduler().runTaskAsynchronously(...)`.
   - Never call Bukkit world manipulation methods (`setBlockData`, `getBlockAt`) asynchronously. Return to the main thread via `runTask(...)`.
3. **Prevent TPS Drops**:
   - Batch large block changes across multiple ticks using `BlockWorker` (default 4000 blocks/tick).
   - Set blocks with `block.setBlockData(wanted, false)` to suppress immediate cascade physics updates.
   - Sort operations bottom-up (ascending $Y$).
4. **Memory Management**:
   - Never store `org.bukkit.entity.Player` references in long-lived or async collections. Use `java.util.UUID`.
   - Keep undo history bounded per player.
5. **Robust Parsing**:
   - LLMs may output markdown fences or trailing commas. Use lenient JSON parsing (`reader.setLenient(true)`).
   - Sanitize all blockstate strings by removing whitespace (`name.replaceAll("\\s+", "").toLowerCase(Locale.ROOT)`).
6. **Testing**:
   - Maintain unit tests in `src/test/java/com/example/aibuild/`.
   - Decouple Bukkit dependencies using `Function<String, BlockData>` so planners can be verified in standard JUnit 5 suites.
