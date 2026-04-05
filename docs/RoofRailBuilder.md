# RoofRailBuilder

A [Meteor Client](https://meteorclient.com/) addon module designed specifically for laying powered rails on the **Nether roof** (or any flat open surface). This module does not construct a tunnel — it simply places rails and power sources in a straight line as you walk, with built-in **shulker box restocking**, **job persistence across disconnects**, and **soft pause/resume** support.

---

## Requirements

- Meteor Client
- [Baritone](https://github.com/cabaletta/baritone) (required for restocking movement and recovery)




---

## Quick Start

1. Stand at the exact position where you want the rail line to begin, on a flat surface (e.g. the Nether roof).
2. Face the direction you want to build.
3. Fill your inventory with the required materials (see [Inventory Checklist](#inventory-checklist)).
4. Enable the module.

The module locks your starting position and facing direction the moment it is toggled on. It will immediately begin walking forward and placing rails.

> **Note:** If a saved job exists for the current world and dimension, the module will **automatically restore it** instead of starting a new one. Disable the module first and delete any existing snapshot if you want to start fresh.

---

## Settings

### General

| Setting | Default | Description |
|---|---|---|
| `length` | `2048` | Total number of powered rails to place. Range: 1–8192. |
| `rotate` | `true` | Rotate toward blocks before placing. |
| `place-range` | `4.5` | Maximum block placement reach in blocks. |
| `resume-wait-seconds` | `30` | How many seconds to wait after new supply shulkers are detected before automatically resuming. |
| `auto-resume-on-join` | `true` | Automatically resume a saved job after reconnecting to the same world and dimension. |
| `pause-resume-key` | *(unbound)* | Keybind for soft-pausing or resuming the current job without disabling the module. |

---

### Power

| Setting | Default | Description |
|---|---|---|
| `power-side` | `Right` | Which side of the rail the power source is placed on, relative to your facing direction. `Left` or `Right`. |
| `power-type` | `REDSTONE_BLOCK` | `REDSTONE_BLOCK`: places a redstone block one block to the side of the rail. `REDSTONE_TORCH`: places a redstone torch on top of a support block at the same position. |
| `power-spacing` | `17` | Distance between power source placements (every N rails). |

> **Redstone Torch note:** Torch mode requires a solid block to already exist at the support position (one block below the torch position). The module will disable itself with an error if no support is found.

---

### Inventory

| Setting | Default | Description |
|---|---|---|
| `inventory-delay` | `3` ticks | Delay between container interactions to avoid desyncs. |
| `reserve-empty-slots` | `3` | Minimum number of inventory slots to keep free when pulling items from shulkers. |
| `food-item` | Golden Carrot | The food item to maintain in the food hotbar slot. |
| `rail-hotbar-slot` | `0` | Dedicated hotbar slot for powered rails. |
| `power-hotbar-slot` | `1` | Dedicated hotbar slot for redstone blocks or torches. |
| `food-hotbar-slot` | `2` | Dedicated hotbar slot for food. |
| `chest-hotbar-slot` | `3` | Hotbar slot used to hold the pause chest or supply shulker during restocking. |

---

## How It Works

### Build Loop

Each tick while building, the module:

1. Checks if the current chunk is loaded before attempting any placements.
2. Ensures the food hotbar slot is filled; triggers a restock if food runs out.
3. Clears any obstructing blocks at the rail position and one block above it.
4. At power intervals (`step % power-spacing == 0`), places the configured power source beside the rail.
5. Places a powered rail at the current position.
6. Advances to the next step and autosaves the job snapshot.

The module holds the forward and sprint keys to walk automatically during normal building — Baritone is only used for repositioning during restocking and recovery.

---

### Restocking

When powered rails, power items, or food run out, the module enters **Restock** state:

1. It searches your inventory for a shulker box containing the needed item.
2. It moves to a position beside the last placed rail, places the shulker, opens it, and pulls items until the slot budget is filled.
3. It breaks the shulker and waits for it to drop as an item entity, then picks it up.
4. Once restocked, it triggers a recovery walk back to the build position and resumes.

**If no supply shulker is found** for the needed item, the module enters **PauseForSupplies** state:

1. It places a chest at the restock position and deposits any empty shulkers into it for the supplier to refill.
2. It enters **WaitForRestock** state and idles until new supply shulkers appear in your inventory.
3. Once detected, it waits `resume-wait-seconds` before automatically resuming.

> Supply shulkers should contain only powered rails, redstone blocks/torches, or food — one type per shulker. The module identifies which shulker to open based on what item is needed.

---

### Job Persistence

The module saves a job snapshot (`roof-rail-builder-job.json`) in the Meteor Client folder every 20 ticks and on disconnect. The snapshot stores:

- World/server ID and dimension
- Build origin, facing direction, and step progress
- Power configuration
- Current state and pending request type

On the next login, if `auto-resume-on-join` is enabled and the snapshot matches the current world and dimension, the module resumes automatically. If the job was manually paused before disconnecting, it loads in a paused state instead — press the `pause-resume-key` to continue.

On resume, the module verifies the last 5 placed rails against the world to correct any progress discrepancy before walking back to the build position.

> **Disabling the module** deletes the saved snapshot. Use the pause keybind instead if you want to preserve the job.

---

### Soft Pause / Resume

Pressing the `pause-resume-key` while the module is active toggles a soft pause:

- **Pause**: stops movement and Baritone, saves the job snapshot with `paused=true`. The module remains enabled but does nothing until resumed.
- **Resume**: restores the job from the snapshot and walks back to the build position.

This is useful for manual intervention (e.g. adjusting inventory, waiting for a supplier) without losing progress.

---

### Recovery

Recovery is triggered when:

- The module detects the player has drifted more than 0.7 blocks off the rail line during building.
- A placement stalls at the same block for 10 ticks.
- Baritone idles for 20 ticks during a navigation goal.
- The module resumes from a saved snapshot.

During recovery, Baritone walks the player back to the last successfully placed rail position. Once there, the module realigns yaw/pitch and resumes building.

---

## Module Interactions

The module automatically **pauses** (without stopping) when the following Meteor modules are active:

- `AutoEat` (while eating)
- `AutoGap` (while eating a golden apple)
- `KillAura` (while attacking)

Movement keys are released during these windows and re-pressed automatically afterward.

---

## Inventory Checklist

| Item | Purpose |
|---|---|
| Powered Rails | Rail placement |
| Redstone Blocks **or** Redstone Torches | Power source *(matching `power-type`)* |
| Food (e.g. Golden Carrots) | Kept in the food hotbar slot |
| Supply Shulker Boxes | Carrying bulk stacks of rails, power items, and food for auto-restock |
| Chest | Used during the pause-for-supplies sequence to deposit empty shulkers |

Keep supply shulkers loaded with a **single item type each**. The module selects the shulker with the fewest stacks of the needed item to drain them in order.

---

## Tips

- Set `power-spacing` to `17` for optimal cart speed on a straight nether roof line (a power source every 17 rails keeps carts fully boosted).
- Keep several shulkers of rails in your inventory — the module can drain them sequentially without stopping.
- The `reserve-empty-slots` setting prevents the module from completely filling your inventory when pulling from shulkers, leaving room to pick up the empty shulker afterward.
- If you need to manually adjust your position or inventory without losing the job, use the `pause-resume-key` rather than disabling the module.
- On servers with high latency, increase `inventory-delay` to reduce container interaction errors during restocking.
- Mushrooms on the Nether roof are handled automatically — the module breaks them and tosses any that end up in inventory.