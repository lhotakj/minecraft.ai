# AI Integration & Architecture

This document details the complete end-to-end architecture of the **AiBuild** plugin: how user prompts in Minecraft are transformed into voxel structures in the game world, the safety guarantees, and the concurrent execution pipeline.

---

## 1. High-Level Pipeline

```
  [Player in Minecraft]
           │
           ▼
    /ai build <prompt>
           │
           ▼
  [AiBuildPlugin (Main Thread)]
     • Validate sender (Player)
     • Check player cooldown & busy status
     • Capture player location, yaw direction, and target world
           │
           ▼ (Asynchronous Thread)
  [AiProvider (GeminiClient / AnthropicClient)]
     • Format system prompt with server limits
     • Issue non-blocking HTTP POST via java.net.http.HttpClient
     • Receive raw response text
           │
           ▼
  [extractJson & JsonReader]
     • Extract outermost JSON block
     • Lenient parsing (tolerates code fences, trailing commas)
           │
           ▼ (Main Server Thread)
  [BuildPlanner]
     • Validate ops count (max 2000 ops)
     • Calculate voxel coordinates in memory (Pos -> BlockData)
     • Enforce bounding box (max-blocks, max-size, max-height)
     • Strip block state spacing & verify against denied-blocks
           │
           ▼
  [BlockWorker (BukkitRunnable)]
     • Transform local coords to world coords facing the player
     • Sort layers bottom-up (Y ascending)
     • Batch place up to 4000 blocks/tick (without physics updates)
     • Snapshot previous blocks into UndoEntry
           │
           ▼
  [Completion & Notification]
     • Record undo history (up to 3 builds per player)
     • Send in-game confirmation message to player
```

---

## 2. Spatial Coordinate System

The AI planner operates in a normalized local coordinate space:
- **$X$-axis**: East / West local axis
- **$Z$-axis**: North / South local axis
- **$Y$-axis**: Vertical elevation (ground surface at $Y=0$, foundations at $Y < 0$, superstructure at $Y \ge 1$)

### Placement & Rotation Mapping
When `startBuild` prepares to place the blocks:
1. It calculates the player's primary cardinal facing vector using their yaw:
   $$\vec{d} = (\text{round}(-\sin(\text{yaw})), \text{round}(\cos(\text{yaw})))$$
2. It offsets the entire structure so that its nearest edge starts `placement.gap` (default 4) blocks in front of the player.
3. The ground layer ($Y=0$) is anchored at the block underneath the player's feet (`where.getBlockY() - 1`).

---

## 3. Shape Primitive Algorithms

### 1. `fill` & `hollow_box`
- Computes bounding volume $\Delta X \times \Delta Y \times \Delta Z$.
- `fill` populates all integer points within $[x_{\min}, x_{\max}] \times [y_{\min}, y_{\max}] \times [z_{\min}, z_{\max}]$.
- `hollow_box` skips interior points where $x_{\min} < x < x_{\max}$ and $y_{\min} < y < y_{\max}$ and $z_{\min} < z < z_{\max}$, saving memory and block counts while creating walkable interiors.

### 2. `ellipse`
- Generates elliptical or circular cylinders.
- Tests normalized radial distance:
  $$\left(\frac{x - c_x}{r_x + 0.5}\right)^2 + \left(\frac{z - c_z}{r_z + 0.5}\right)^2 \le 1$$
- For hollow walls, carves out the inner ellipse:
  $$\left(\frac{x - c_x}{r_x + 0.5 - \text{thickness}}\right)^2 + \left(\frac{z - c_z}{r_z + 0.5 - \text{thickness}}\right)^2 > 1$$

### 3. `arcade`
- Creates arched openings through an existing elliptical wall (e.g. for arenas, colosseums, aqueducts).
- Distributes $k$ arches along the perimeter using angle step $\theta_k = \text{offset} + \frac{2\pi k}{\text{count}}$.
- At each angle, calculates the normal $\vec{n}$ and tangent $\vec{t}$ to cut an arched gateway (rectangular base + semi-circular arch).

### 4. `sphere`
- Evaluates Euclidean distance squared:
  $$d^2 = \Delta x^2 + \Delta y^2 + \Delta z^2$$
- Bounded between $(r - 0.5)^2 < d^2 \le (r + 0.5)^2$ for hollow shells.
- Supports hemispherical clipping: `half: "top"` (domes) or `half: "bottom"` (bowls).

### 5. `setblock`
- Places an individual block at exact coordinates. Essential for doors, torches, lanterns, stairs, and decorative details.

---

## 4. Minecraft Engine Optimizations

Building large structures in Minecraft can easily freeze a server if not implemented carefully. AiBuild achieves zero-lag execution through three core design choices:

1. **Off-Thread Network I/O**:
   All communication with Google Gemini or Anthropic Claude runs inside an asynchronous Bukkit task (`runTaskAsynchronously`). The Minecraft server loop is never blocked waiting for an HTTP response.

2. **Throttled Tick Worker (`BlockWorker`)**:
   Instead of updating 50,000 blocks in a single server tick (which causes a severe lag spike and TPS drop), `BlockWorker` runs as a recurring synchronous `BukkitRunnable` placing up to `limits.blocks-per-tick` (default 4,000) blocks per tick.

3. **Suppression of Physics Updates (`setBlockData(wanted, false)`)**:
   Passing `false` disables immediate block updates (e.g. gravel falling, water flowing, redstone recalculations, lighting passes) during the construction phase. Once blocks are in place, the server normalizes them smoothly.

4. **Transactional Undo (`/ai undo`)**:
   When placing blocks, `BlockWorker` captures the existing `BlockData` at every modified coordinate. Each player maintains an undo stack (default size 3) stored by player `UUID` (avoiding memory leaks from retaining player entity references).

