# AiBuild

A Paper plugin that adds `/ai build <description>`. It sends your description to an AI model (Claude or Gemini), gets back a JSON build plan (boxes, ellipses, arched arcades, spheres, single blocks), validates it, and builds it in front of you a few thousand blocks per tick. `/ai undo` reverts it.

```
/ai build a Colosseum
/ai build a small medieval watchtower with a lantern on top
/ai undo
```

## Requirements

- Paper 1.21.x server, Java 21
- Maven 3.8+ and JDK 21 on the machine you build on
- An API key for the provider you pick:
  - Gemini: a key from Google AI Studio (https://aistudio.google.com/apikey)
  - Claude: a key from the Anthropic Console (https://console.anthropic.com)

## Build

```
mvn package
```

The plugin ends up at `target/AiBuild.jar`. If your server is not 1.21.4, change the `paper-api` version in `pom.xml` (and `api-version` in `src/main/resources/plugin.yml`) to match.

## Install

1. Copy `AiBuild.jar` to the server's `plugins/` folder and start the server once. (Upgrading from the first version? Delete `plugins/AiBuild/config.yml` first so the new layout is generated.)
2. Pick a provider in `plugins/AiBuild/config.yml` (`provider: gemini` or `provider: anthropic`) and set its key, either way:
   - environment variable `GEMINI_API_KEY` / `ANTHROPIC_API_KEY` for the server process (recommended), or
   - `gemini.api-key` / `anthropic.api-key` in the config.
3. `/ai reload` (or restart). The server log says which model is ready, or what is missing.
4. Make yourself an op (or grant `aibuild.use`), stand on flat ground facing where you want it. The build appears in front of you, with its ground layer on the block under your feet.

## Providers

| Provider | Default model | Key |
|---|---|---|
| `gemini` | `gemini-3.8-flash` | `gemini.api-key` / `GEMINI_API_KEY` |
| `anthropic` | `claude-sonnet-5-5` | `anthropic.api-key` / `ANTHROPIC_API_KEY` |

Change `gemini.model` / `anthropic.model` to try other models. Gemini notes:

- Gemini 3.8 Flash "thinks" before answering, and thinking tokens count toward `gemini.max-output-tokens`. The default (32768, model max 65536) leaves plenty of room; if a build fails with "reply was cut off", raise it or set `gemini.thinking-level: low`.
- The request asks Gemini for JSON output directly (`responseMimeType: application/json`); the plugin still tolerates stray text or code fences around it.

## Permissions

| Permission | Default | Use |
|---|---|---|
| `aibuild.use` | op | `/ai build`, `/ai undo` |
| `aibuild.admin` | op | `/ai reload` |

## How it works

1. The plugin sends your text plus a system prompt that describes six operations: `fill`, `hollow_box`, `ellipse`, `arcade`, `sphere`, `setblock`.
2. The model replies with `{"name": "...", "ops": [...]}`.
3. `BuildPlanner` validates every operation (known blocks, deny-list, size and block-count limits) and computes the blocks without touching the world.
4. `BlockWorker` places them bottom-up, `blocks-per-tick` per tick, without physics updates, remembering the old blocks for `/ai undo`.

The `arcade` operation exists for things like the Colosseum's rows of arches: it cuts evenly spaced arched openings through an elliptical wall.

Code layout: `AiProvider` is the small interface (`complete(system, user)`); `AnthropicClient` and `GeminiClient` implement it and `AiProviders` picks one from the config. Adding another backend means one new class plus one `case` in `AiProviders`.

## Limits and safety

- All limits live in `config.yml` and are enforced by the plugin, not by the AI: total blocks, width/depth, height, per-player cooldown, deny-list (TNT, lava, command blocks, spawners, bedrock, and so on).
- Every `/ai build` is one paid API call (on a free Gemini tier, it counts against your rate limits). A cooldown (15s by default) and the `aibuild.use` permission are your cost controls.
- Undo restores block types and states, not chest contents or other block entity data, and only for the last 3 builds per player (kept in memory, lost on restart).
- Builds ignore terrain: on a slope, part of the build may float or be buried. Flat ground works best.
- Keep API keys out of version control.

## Tuning ideas

- Lag while building: lower `limits.blocks-per-tick`.
- Different style: edit the prompt in `Prompts.java` (e.g. "always build in Gothic style").
