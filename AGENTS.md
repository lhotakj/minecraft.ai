# AGENTS.md - Guidelines for AI Coding Agents

## Project Overview
- **Name**: AiBuild
- **Platform**: PaperMC (Minecraft 1.21.x)
- **Language**: Java 21 (Maven build system)
- **Purpose**: Connects AI models (Gemini, Claude, OpenAI, and OpenRouter) to Minecraft to turn natural language prompts into structural builds.

## Key Files
- `src/main/java/com/example/aibuild/AiBuildPlugin.java`: Main plugin entry point, command handling (`/ai build`, `/ai undo`, `/ai reload`), coordinate transformation, and async task orchestration.
- `src/main/java/com/example/aibuild/Prompts.java`: System prompt engineering and primitive specification sent to AI providers.
- `src/main/java/com/example/aibuild/BuildPlanner.java`: In-memory geometry calculation, bounds checking, and blockstate validation.
- `src/main/java/com/example/aibuild/BlockWorker.java`: Scheduled tick worker placing blocks throttled per tick without physics updates.
- `src/main/java/com/example/aibuild/GeminiClient.java`, `AnthropicClient.java`, `OpenAiClient.java`, `OpenRouterClient.java`, & `ChainedAiProvider.java`: HTTP REST clients and provider failover chaining for Gemini, Claude, OpenAI, and OpenRouter.
- `docs/schemas/build-plan.schema.json`: Formal JSON Schema for build operations.
- `AI_HARNESS.md`: Full architectural and performance harness guidelines.

## Development Constraints
- Use Java 21 features: records, pattern matching, switch expressions, text blocks.
- Maintain decoupled block parsing in `BuildPlanner` to ensure unit tests in `src/test/` continue to run fast and standalone without needing a live Bukkit server.
- Adhere strictly to the thread-safety rules: Network I/O async, World mutations sync on main thread.

