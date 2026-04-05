# PoweredRailBuilder

A Meteor Clientaddon module that automatically builds a fully powered rail tunnel in the direction you are facing. It handles terrain clearing, liquid sealing, falling blocks, floor and shell construction, power source placement, optional lighting, and Baritone-assisted movement — all in one continuous operation.

---

## Requirements

- Meteor Client
- [Baritone](https://github.com/cabaletta/baritone) (optional but strongly recommended — required for automatic movement and recovery)

---

## Quick Start

1. Stand at the exact position where you want the tunnel to begin.
2. Face the direction you want to build.
3. Fill your inventory with the required materials (see [Inventory Checklist](#inventory-checklist)).
4. Enable the module.

The module locks your starting position and facing direction the moment it is toggled on. Moving before toggling will shift the tunnel's origin.

---

## Settings

### General

| Setting | Default | Description |
|---|---|---|
| `length` | `128` | Number of slices (blocks forward) to build. Range: 1–2048. Can be manually typed to any number. |
| `use-baritone` | `true` | Use Baritone to walk to each new build position automatically. |
| `stop-on-death` | `true` | Disable the module and cancel Baritone if you die. *(Requires Baritone.)* |
| `path-idle-timeout` | `40` ticks | How many ticks Baritone can be stuck before a recovery backtrack is triggered. |
| `rotate` | `true` | Rotate toward blocks before placing them. |
| `place-range` | `4.5` | Maximum block placement reach in blocks. |
| `handle-liquids` | `true` | Seal water and lava in the next slice before building the current one. |

---

### Structure

| Setting | Default | Description |
|---|---|---|
| `width` | `2` | Interior tunnel width in blocks. Range: 1–7. |
| `height` | `3` | Interior tunnel height in blocks. Range: 2–6. |
| `rail-side` | `Right` | Which side of the tunnel the rail lane is on, relative to your facing direction. `Left` or `Right`. |
| `rail-offset` | `1` | Lateral position of the rail inside the tunnel. `0` = your walking lane, `width-1` = far edge. |
| `ensure-shell` | `false` | Build side walls and a roof enclosing the tunnel. |
| `place-corners` | `true` | Include the four corner blocks of the shell. *(Visible when `ensure-shell` is on.)* |
| `second-floor-layer` | `false` | Add a second layer of floor blocks one block below the surface floor. Automatically enabled for hidden torch setups. |
| `third-floor-layer` | `false` | Add a third floor layer below the second for a fully flat bottom. |
| `encapsulate-power` | `false` | Fill floor blocks around the power source, without overwriting existing rails. |
| `clear-tunnel-space` | `true` | Break any blocks obstructing the tunnel interior before placing. |

---

### Materials

| Setting | Default Blocks | Description |
|---|---|---|
| `floor-blocks` | Obsidian, Cobblestone, Stone, Deepslate | Blocks used for the floor and non-shell structural positions. Must be full-cube. |
| `shell-blocks` | Obsidian, Cobblestone, Stone, Deepslate | Blocks used for walls and roof when `ensure-shell` is on. Must be full-cube. |
| `corner-blocks` | Obsidian | Blocks used specifically for shell corners. *(Visible when `ensure-shell` + `place-corners` are on.)* |
| `liquid-uses-shell-blocks` | `true` | Use your shell block list to seal liquids instead of a separate list. |
| `liquid-blocks` | Netherrack, Cobblestone | Blocks used for liquid sealing when the above is disabled. Must be full-cube. |

---

### Power

| Setting | Default | Description |
|---|---|---|
| `rail-mode` | `ALL_POWERED` | `ALL_POWERED`: every rail is a powered rail. `MIXED`: alternates sections of powered rails with regular rails. |
| `power-spacing` | `16` | Distance (in slices) between power source placements. |
| `powered-length` | `4` | In `MIXED` mode, how many consecutive powered rails appear at the start of each cycle. The power source is centered within this section. |
| `power-type` | `REDSTONE_BLOCK` | `REDSTONE_BLOCK`: places a redstone block adjacent to the rail. `REDSTONE_TORCH`: places a redstone torch on a support block. |
| `power-lateral-offset` | `0` | Moves the power source left/right relative to the rail position. Negative = toward your walking lane. |
| `power-vertical-offset` | `-1` | Moves the power source up/down relative to the rail position. Range: -2 to 1. Cannot be `0,0` with lateral offset simultaneously. |

> **Note:** The power source cannot occupy the same position as the rail. If both offsets are `0`, the module will error on activation.

---

### Lighting

| Setting | Default | Description |
|---|---|---|
| `place-light` | `false` | Enable automatic light source placement. |
| `light-spacing` | `20` | Distance between light placements in slices. |
| `light-block` | Torch | Block used as the light source. |
| `light-lateral-offset` | `0` | Lateral position of the light relative to the rail. |
| `light-vertical-offset` | `1` | Vertical position of the light relative to the rail. Range: -2 to 3. |

---

### Tools

| Setting | Default | Description |
|---|---|---|
| `protect-tool-durability` | `true` | Skip tools that are below the minimum durability threshold. |
| `min-tool-durability` | `50` | Durability floor. Tools with less than this remaining will not be used. *(Visible when protection is on.)* |
| `pickaxe-blocks` | Stone, Cobblestone, Deepslate, Obsidian, Netherrack, etc. | Block types that prefer a pickaxe for breaking. |
| `shovel-blocks` | Dirt, Grass, Sand, Gravel, Soul Sand, etc. | Block types that prefer a shovel for breaking. |
| `axe-blocks` | All wood/log/plank/stem types | Block types that prefer an axe for breaking. |

---

## How It Works

Each slice is built in the following order, advancing to the next slice once all steps are complete:

1. **HandleLiquids** — Seals any liquids in the upcoming slice using liquid or shell blocks, and breaks gravity blocks in the lookahead area.
2. **HandleFallingBlocks** — Waits for falling blocks (gravel, sand) to settle before proceeding.
3. **PlaceShell** *(if enabled)* — Builds side walls and roof.
4. **ClearSpace** *(if enabled)* — Breaks obstructing blocks inside the tunnel.
5. **PlaceFloors** — Lays floor blocks (and additional layers if configured).
6. **PlacePower** *(at power intervals)* — Places the redstone block or torch to power the rail.
7. **PlaceLight** *(if enabled, at light intervals)* — Places the light source block.
8. **EncapsulatePower** *(if enabled)* — Fills floor blocks around the power source.
9. **PlaceRail** — Places the powered rail (or regular rail in mixed mode).
10. **MoveToNext** — Uses Baritone to walk to the next slice position.
11. **Advance** — Increments the step counter and resets state.

---

## Recovery Behavior

If Baritone is enabled and the player does not move for `path-idle-timeout` ticks while a goal is active, the module automatically enters **recovery mode**:

- It backtracks one slice at a time, directing Baritone to an earlier position.
- Once the player reaches the recovery target, the module resumes from the backtracked step.
- If recovery fails after too many attempts (based on tunnel size), the module disables itself with an error.

Recovery requires Baritone. Without it, a stall will immediately stop the module.

---

## Module Interactions

The module automatically **pauses** (without stopping) when the following Meteor modules are active:

- `AutoEat` (while eating)
- `AutoGap` (while eating a golden apple)
- `KillAura` (while attacking)

Baritone is paused during these windows and resumes automatically.

---

## Inventory Checklist

Ensure you have enough of each item before starting. The module will stop with an error if a required item runs out mid-build.

| Item | Purpose |
|---|---|
| Powered Rails | Rail placement (all modes) |
| Regular Rails | Rail placement *(MIXED mode only)* |
| Redstone Blocks **or** Redstone Torches | Power source *(depending on `power-type`)* |
| Floor blocks (e.g. Obsidian, Cobblestone) | Floor layers |
| Shell blocks | Walls and roof *(if `ensure-shell` is on)* |
| Corner blocks (e.g. Obsidian) | Shell corners *(if `place-corners` is on)* |
| Liquid seal blocks | Liquid sealing *(if `liquid-uses-shell-blocks` is off)* |
| Light source blocks (e.g. Torches) | Lighting *(if `place-light` is on)* |
| Pickaxe, Shovel, Axe | Breaking terrain |

---

## Tips

- **Facing direction is locked on activation.** Stand still and face exactly where you want to build before toggling.
- For Nether tunnel builds, set `floor-blocks` to Netherrack or Blackstone and add those to the `pickaxe-blocks` list.
- Use `ensure-shell` when building through water, lava lakes, or unstable terrain.
- `MIXED` rail mode conserves powered rails significantly — a `power-spacing` of 16 with `powered-length` of 4 will keep carts at full speed.
- Increasing `path-idle-timeout` in laggy environments reduces false recovery triggers.
- Enable `protect-tool-durability` and keep spare tools in your inventory to avoid the module stopping unexpectedly.