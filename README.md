# AiBuild

[![Build and Release](https://github.com/lhotakj/minecraft.ai/actions/workflows/build.yml/badge.svg)](https://github.com/lhotakj/minecraft.ai/actions/workflows/build.yml)
[![Minecraft Paper](https://img.shields.io/badge/PaperMC-1.21.x-blue.svg)](https://papermc.io)
[![Java 21](https://img.shields.io/badge/Java-21-orange.svg)](https://adoptium.net)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

**AiBuild** is a high-performance PaperMC Minecraft server plugin (written in Java 21) that brings natural-language AI generation into Minecraft. Players can type conversational commands like:

```minecraft
/ai build for me a house with three floors from oak wood, oak doors and a small balcony in second floor
```

The plugin sends the prompt to an advanced AI model (Google Gemini or Anthropic Claude), receives a deterministic JSON build plan, validates the geometry and safety limits, and constructs it in front of the player chunk-safely across ticks with zero lag.

---

## 🌟 Key Features

- **Conversational Building**: Natural language input with tolerance for phrasing and minor typos (e.g. "housw" -> house).
- **Complex Architectures**: Understands multi-floor structures, doors (matching top and bottom halves), balconies, windows, roofs, stairs, and decorative details.
- **Multi AI Providers**:
  - **Google Gemini** (Gemini 3.8 Flash, 2.5 Flash, 2.0 Flash)
  - **Anthropic Claude** (Claude Sonnet 3.5 / 3.7)
  - **OpenAI** (GPT-4o, GPT-4o-mini, o1, o3-mini)
  - **OpenRouter** (Multi-model gateway: DeepSeek-R1, Llama 3, Gemini, Claude, OpenAI)
- **Zero-Lag Server Execution**: Places up to 4,000 blocks per tick without physics recalculations, preventing TPS drops.
- **Instant Undo**: `/ai undo` restores the previous world state in case you want to rebuild or adjust placement.
- **Safety Envelope**: Configurable maximum blocks, dimensions, player cooldowns, and a forbidden materials deny-list (TNT, lava, bedrock, command blocks).
- **Automated CI/CD**: Automatic GitHub Actions workflow that builds every release, stamps `0.1.<build-number>`, and publishes downloadable `.jar` releases.

---

## 📥 Download & Installation

### Option 1: Download Pre-built Release (Recommended)
1. Go to the [Releases](https://github.com/lhotakj/minecraft.ai/releases) tab in GitHub.
2. Download the latest **`AiBuild.jar`** (or versioned `AiBuild-0.1.<build-number>.jar`).
3. Place `AiBuild.jar` into your Minecraft server's `plugins/` folder.
4. Ensure your server is running **Paper 1.21.x** with **Java 21**.

### Option 2: Download Artifact from GitHub Actions
1. Open the [Actions](https://github.com/lhotakj/minecraft.ai/actions) tab.
2. Click on the latest workflow run on `main`.
3. Scroll down to **Artifacts** and download `AiBuild-0.1.<build-number>`.

---

## ⚙️ Configuration & API Key Setup

1. Start your server once to generate `plugins/AiBuild/config.yml`.
2. Configure your AI API key using **either** method:

### Method A: Environment Variable (Recommended)
Set the variables on the machine or container running the server:
```bash
# Optional: Select provider ("gemini", "anthropic", "openai", or "openrouter"; defaults to "gemini")
export AI_PROVIDER="gemini"

# Optional: Provider failover chain (tries openrouter first, then gemini):
export PROVIDER_ORDER="openrouter gemini"

# For Gemini:
export GEMINI_API_KEY="AIzaSy..."
export GEMINI_MODEL="gemini-3.8-flash gemini-3.7-flash gemini-3.5-flash"
export GEMINI_MAX_OUTPUT_TOKENS="32768"
export GEMINI_THINKING_LEVEL="medium"   # low | medium | high

# For Claude (Anthropic):
export ANTHROPIC_API_KEY="sk-ant-..."
export ANTHROPIC_MODEL="claude-sonnet-5-5 claude-3-5-haiku-latest"
export ANTHROPIC_MAX_TOKENS="16000"
export ANTHROPIC_THINKING_LEVEL=""      # low | medium | high (Claude 3.7+ extended thinking)

# For OpenAI:
export OPENAI_API_KEY="sk-proj-..."
export OPENAI_MODEL="gpt-4o gpt-4o-mini" # or reasoning models: "o3-mini gpt-4o"
export OPENAI_MAX_TOKENS="16000"
export OPENAI_THINKING_LEVEL="low"      # low | medium | high (for reasoning models: o1, o3-mini)

# For OpenRouter:
export OPENROUTER_API_KEY="sk-or-v1-..."
export OPENROUTER_MODEL="google/gemini-2.5-flash openai/gpt-4o anthropic/claude-3.7-sonnet"
export OPENROUTER_MAX_TOKENS="16000"
export OPENROUTER_THINKING_LEVEL=""     # low | medium | high (mapped to underlying model)
```

### Method B: In `plugins/AiBuild/config.yml`
```yaml
# Select provider: "gemini", "anthropic", "openai", or "openrouter"
provider: gemini

# Optional: Provider failover order (space or comma-delimited).
# Each provider tries its full model chain before failing over to the next provider.
provider-order: "openrouter gemini"

gemini:
  api-key: "AIzaSy..."
  # Space/comma-delimited list of models with automatic failover if one hits 503/429
  model: "gemini-3.8-flash gemini-3.7-flash gemini-3.5-flash"
  max-output-tokens: 32768
  thinking-level: "" # optional: low | medium | high

anthropic:
  api-key: "sk-ant-..."
  model: "claude-sonnet-5-5 claude-3-5-haiku-latest"
  max-tokens: 16000
  thinking-level: "" # optional: low | medium | high (extended thinking)

openai:
  api-key: "sk-proj-..."
  model: "gpt-4o gpt-4o-mini"
  max-tokens: 16000
  thinking-level: "" # optional: low | medium | high (for reasoning models: o1, o3-mini)

openrouter:
  api-key: "sk-or-v1-..."
  model: "google/gemini-2.5-flash openai/gpt-4o anthropic/claude-3.7-sonnet"
  max-tokens: 16000
  thinking-level: "" # optional: low | medium | high (unified reasoning effort)
```

### 🧠 Thinking Levels (`thinking-level`)

All providers support the `thinking-level` setting with three standardized options:
- `low`: Quick reasoning pass; lower latency, fewer internal tokens used.
- `medium`: Balanced reasoning depth for detailed architecture plans.
- `high`: Maximum reasoning depth for intricate geometry and complex multi-structure builds.
- `""` *(empty)*: Uses the model's default reasoning behavior (or disabled where applicable).

How each provider handles thinking levels:
- **Google Gemini**: Transmitted via `thinkingConfig.thinkingLevel`.
- **OpenAI**: Configures `reasoning_effort` for reasoning models (`o1`, `o3-mini`, etc.).
- **OpenRouter**: Transmitted via OpenRouter's unified `reasoning: { effort: ... }` parameter.
- **Anthropic Claude**: Allocates an extended thinking token budget (`low` ~ 2k tokens, `medium` ~ 8k tokens, `high` ~ 16k tokens) on thinking-capable models like Claude 3.7 Sonnet.

3. Run `/ai reload` in-game or from server console.

---

## 🎮 How to Use in Game

1. Grant the player OP or permission `aibuild.use`.
2. Stand on flat ground facing open space. The build will appear in front of you, with the ground floor matching the elevation under your feet.
3. Run your build command:

```minecraft
# Example: 3-floor oak house with balcony and doors
/ai build for me a house with three floors from oak wood, oak doors and a small balcony in second floor

# Example: Medieval Watchtower
/ai build a medieval watchtower from cobblestone and dark oak with lanterns on top

# Example: Roman Colosseum
/ai build a sandstone Colosseum with three tiers of arched arcades
```

To revert the build:
```minecraft
/ai undo
```

To view plugin version and AI provider status:
```minecraft
/ai version
```

To display in-game help and examples:
```minecraft
/ai help
```

To reload configuration:
```minecraft
/ai reload
```

---

## 🔐 Permissions

| Permission | Default | Description |
|---|---|---|
| `aibuild.use` | `op` | Allows executing `/ai build`, `/ai undo`, `/ai version`, and `/ai help` |
| `aibuild.admin` | `op` | Allows executing `/ai reload` |

---

## 🛠️ Building from Source

To compile the plugin locally:

```bash
# Prerequisites: JDK 21+ and Maven 3.8+
mvn clean package
```

The compiled plugin will be at `target/AiBuild.jar`.

---

## 📚 Technical Documentation & AI Harness

- [AI Integration & Architecture](docs/AI_ARCHITECTURE.md): Full technical breakdown of prompt processing, spatial math, coordinate transformations, and non-blocking scheduling.
- [Prompting Guide](docs/PROMPT_GUIDE.md): Tips and best practices for creating complex structures, multi-floor buildings, roofs, and block palettes.
- [AI Harness Specification](AI_HARNESS.md): Standards for AI coding agents and human developers on Bukkit/Paper threading rules, chunk safety, and Java 21 conventions.
- [Build Plan JSON Schema](docs/schemas/build-plan.schema.json): Formal JSON Schema specification for the AI structure plan.

---

## 📜 License

This project is licensed under the MIT License.
