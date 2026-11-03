# Prompting Guide for AiBuild

This guide explains how to formulate prompts for `/ai build` in Minecraft to achieve optimal architectural results, clean layouts, and detailed aesthetics.

---

## 1. Anatomy of a Prompt

While the AI build engine can handle short prompts like `/ai build a pyramid`, you will get significantly better and more detailed structures by providing:
1. **Building Type**: House, watchtower, castle, colosseum, temple, library.
2. **Dimensions or Scale**: Number of floors, approximate footprint (e.g. small, medium, grand).
3. **Materials / Palette**: Oak wood, stone bricks, spruce logs, deepslate, quartz, sandstone.
4. **Architectural Features**: Doors, balconies, gabled roof, arched windows, perimeter fences, interior stairs.

---

## 2. Featured Example: 3-Story Oak House with Balcony

Here is the exact prompt and how the AI translates it into operations:

```minecraft
/ai build for me a house with three floors from oak wood, oak doors and a small balcony in second floor
```

### How the Model Deconstructs the Request:
1. **Ground Floor Foundation & Flooring ($Y=0$)**:
   - `fill` with `minecraft:oak_planks` or `minecraft:stone_bricks` covering the footprint (e.g., $10 \times 10$).
2. **Walls & Framing ($Y=1 \dots 12$)**:
   - Corner pillars with `minecraft:oak_log` (or `stripped_oak_log`).
   - Walls with `minecraft:oak_planks` using `hollow_box`.
3. **Intermediate Floors**:
   - Ground floor ceiling / 2nd story floor at $Y=4$ with `minecraft:oak_planks` (leaving a $2 \times 2$ hole for stairs).
   - 2nd floor ceiling / 3rd story floor at $Y=8$ with `minecraft:oak_planks`.
4. **Entrance Doors ($Y=1, Y=2$)**:
   - Minecraft doors are 2 blocks tall!
   - `setblock` at $Y=1$: `minecraft:oak_door[half=lower,facing=north]`
   - `setblock` at $Y=2$: `minecraft:oak_door[half=upper,facing=north]`
5. **Second-Floor Balcony ($Y=4, Y=5$)**:
   - A $4 \times 2$ platform jutting out from the 2nd floor at $Y=4$ (`minecraft:oak_planks` or `minecraft:oak_slab`).
   - Guard rails around the balcony perimeter at $Y=5$ using `minecraft:oak_fence`.
   - Access door from the 2nd-floor interior onto the balcony (`minecraft:oak_door` lower and upper halves).
6. **Roof ($Y=13 \dots 16$)**:
   - Sloped roof using `minecraft:oak_stairs[facing=...]`.
7. **Details & Lighting**:
   - Glass pane windows carved into walls.
   - Hanging lanterns (`minecraft:lantern[hanging=true]`).

---

## 3. Block State Cheat Sheet

The AI build engine supports full Minecraft blockstate properties in Mojang format:

| Element | Example Block ID & States |
|---|---|
| **Door (Lower Half)** | `minecraft:oak_door[half=lower,facing=south]` |
| **Door (Upper Half)** | `minecraft:oak_door[half=upper,facing=south]` |
| **Stairs (Ascending North)** | `minecraft:oak_stairs[facing=north,half=bottom]` |
| **Hanging Lantern** | `minecraft:lantern[hanging=true]` |
| **Pillar Log (Vertical)** | `minecraft:oak_log[axis=y]` |
| **Glass Pane** | `minecraft:glass_pane` |
| **Fence / Railing** | `minecraft:oak_fence` |
| **Trapdoor** | `minecraft:oak_trapdoor[half=bottom,open=false]` |

---

## 4. More Example Prompts

### Medieval Castle
```minecraft
/ai build a square medieval stone castle with 4 corner towers, battlements on the walls, an arched entrance gate and torches along the walkway
```

### Roman Colosseum (with Sand Arena & Subterranean Lion Hypogeum)
```minecraft
/ai build a Colloseum in size 100 pixes diamenter. Do all the details. inside sand floor with hidden dungeon where were lions. As close to the real building as possible
```

**Deconstruction by the AI Engine:**
- **Scale**: `rx = 49, rz = 39` creates an outer ellipse with a diameter of ~99-100 blocks, staying comfortably within the plugin's `max-size` limit (120 blocks).
- **Subterranean Hypogeum ($Y = -6 \dots -1$)**: Built beneath the sand floor with `stone_bricks`, carving corridors and animal holding pens with `iron_bars` and lanterns.
- **Arena Sand Floor ($Y = 0$)**: An elliptical disc `minecraft:sand` with wooden trapdoors connecting down to the animal cages.
- **Stepped Seating (Cavea)**: Concentric sandstone ellipses rising outward from $Y=1$ to $Y=9$.
- **Outer Facade & Arches ($Y=1 \dots 18$)**: Multi-story monumental wall with 3 tiers of `arcade` operations carving realistic Roman arched openings.

### Modern Glass Villa
```minecraft
/ai build a two-floor modern luxury villa made from smooth quartz, oak wood accents, large floor-to-ceiling glass panes and a rooftop terrace
```

### Underground Crypt
```minecraft
/ai build a dark stone brick crypt with vaulted corridors, soul lanterns, and cracked stone brick floors
```

