# Linear Automation System Architecture

## General-Purpose Planner + Executor + Resource System

## 1. Goal

Build a reusable automation framework for Meteor that can:

* construct any **linear slice-based structure**
* react safely to world conditions
* manage resources without embedding inventory logic into tasks
* support both:

  * **builder use cases** (rails, tunnels, bridges, highways)
  * **non-builder use cases** (mining, farming, transport, supply automation)

This is **not** a “rail builder architecture.”
It is a **deterministic planning and execution architecture** with a generalized resource system.

---

## 2. Core Design Principles

### 2.1 Pure planning

Planning defines what the world **should** look like.
Planning never reads the world and never calls Minecraft APIs.

### 2.2 Reactive execution

Execution compares the plan to the current world and decides what actions are needed right now.

### 2.3 Single inventory authority

Only the inventory system may mutate inventory state.

### 2.4 Resource system does not control task flow

The resource system reports availability and performs acquisition, but does **not** decide whether the builder pauses, retries, or aborts.

### 2.5 Reusable contracts, not task-specific APIs

No APIs like `ensureRails()` or `ensureFood()`.
Everything uses generalized `Requirement` objects.

### 2.6 Layered execution

Each layer has one job:

* planner defines desired state
* analyzer computes differences
* executor performs actions
* resource system fulfills requirements
* handlers perform physical world interactions

---

## 3. High-Level Architecture

```text
Task Module / Automation Module
            ↓
      Execution Engine
   ↓         ↓         ↓
Planner   World Analyzer  Movement/Interaction
            ↓
         Task Queue
            ↓
     InventoryManagerCore
            ↓
      ResourceManagerCore
      ↓        ↓        ↓
 LocalProvider ShulkerProvider EnderChestProvider
            ↓
 TemporaryStoragePlanner
            ↓
   World Interaction Handlers
```

---

## 4. Top-Level Layers

### 4.1 Automation Module

Examples:

* LinearBuilderModule
* RoofRailModule
* HighwayModule
* MiningModule

Responsibilities:

* defines configuration
* owns planner instance
* owns execution engine instance
* decides high-level stop/start behavior
* receives status from executor and exposes UI

Does **not**:

* move items directly
* decide hotbar swaps directly
* place temporary storage directly
* open containers directly

---

### 4.2 Planner

Type: pure computation service

Responsibilities:

* generate `SlicePlan` for any step
* define desired final structure
* define required solid blocks
* define required empty space
* define feature positions (rails, torches, power, etc.)
* define protected region for preprocessing systems

Does **not**:

* read world state
* check liquids
* check inventory
* decide movement
* place or break blocks

---

### 4.3 Execution Engine

Type: reactive orchestration service

Responsibilities:

* request `SlicePlan`
* gather world state for relevant positions
* compute action differences
* prioritize work
* coordinate:

  * preprocessing
  * breaking
  * placing
  * movement
  * resource fulfillment
* advance only when slice is complete

Does **not**:

* mutate inventory directly
* open storage directly
* embed item-specific logic

---

### 4.4 InventoryManagerCore

Type: backend inventory authority

Responsibilities:

* own all inventory and hotbar mutation
* maintain slot policy and reservations
* determine whether a requirement is locally satisfiable
* expose slots for usable resources
* escalate unsatisfied requirements to `ResourceManagerCore`

Does **not**:

* place storage blocks
* move the player
* open or close containers itself
* decide whether the automation should pause

---

### 4.5 ResourceManagerCore

Type: backend resource orchestrator

Responsibilities:

* fulfill requirements that local inventory cannot satisfy
* choose among resource providers
* batch requests when beneficial
* lock InventoryManager during resource operations
* report resource acquisition status

Does **not**:

* decide whether the builder stops or pauses
* decide task priority
* contain builder-specific item logic

---

## 5. Planner Architecture

### 5.1 Planner Contract

```java
SlicePlan planSlice(int step, BuildContext ctx);
```

Optional:

```java
List<SlicePlan> planRange(int startStep, int count, BuildContext ctx);
```

---

### 5.2 BuildContext

Pure configuration object.

Contains things like:

* origin
* forward direction
* lateral direction
* width
* height
* offsets
* module-specific options

No world references.

---

### 5.3 SlicePlan

Core deterministic output.

```java
class SlicePlan {
    int step;

    Set<BlockIntent> solids;      // blocks that must exist
    Set<SpaceIntent> air;         // positions that must be empty
    Set<FeatureIntent> features;  // rails, torches, power, etc.

    Set<BlockPos> protectedRegion; // all blocks/air that must remain dry/stable
    Set<BlockPos> preferredStandPositions; // optional movement hints
}
```

---

### 5.4 Intent Types

#### BlockIntent

For standard blocks.

```java
class BlockIntent {
    BlockPos pos;
    BlockRequirement requirement;
    IntentPriority priority;
}
```

#### SpaceIntent

For required empty space.

```java
class SpaceIntent {
    BlockPos pos;
    ClearPolicy clearPolicy;
}
```

#### FeatureIntent

For special placements with extra rules.

```java
class FeatureIntent {
    BlockPos pos;
    FeatureType type;
    BlockRequirement requirement;
    FeaturePlacementPolicy policy;
}
```

Examples:

* rail
* redstone block
* torch
* ladder
* sign
* chest

---

### 5.5 Planner Ordering

Planner composes final slice in passes:

```text
planBase()
planShell()
planFeatures()
planOverrides()
finalizeProtectedRegion()
```

This gives:

* deterministic overwrite behavior
* no place-then-break structural logic
* reusable slice generation

---

## 6. World Analysis Layer

### 6.1 Purpose

Compare desired slice state to current world state and produce actionable work.

### 6.2 WorldSnapshot

Gather only the region needed for current and nearby slices.

Contains:

* block states
* fluids
* falling blocks
* entities
* player position
* reachable placement/break information

---

### 6.3 Diff Engine

Compares:

* `SlicePlan`
* `WorldSnapshot`

Outputs:

```java
class SliceWorkPlan {
    List<WorldTask> safetyTasks;
    List<WorldTask> clearTasks;
    List<WorldTask> placeTasks;
    List<WorldTask> featureTasks;
}
```

---

## 7. Task Model

### 7.1 General Task Contract

```java
interface WorldTask {
    TaskPriority priority();
    boolean requiresMovement();
    List<Requirement> requirements();
    TaskResult tick(TaskExecutionContext ctx);
}
```

---

### 7.2 Main Task Categories

#### Safety tasks

Highest priority.
Examples:

* falling block mitigation
* liquid sealing
* hazard removal

#### Clear tasks

Remove blocks that violate required air or block positions.

#### Placement tasks

Place ordinary solids.

#### Feature tasks

Place rails, torches, power sources, etc.

#### Movement tasks

Move player into valid execution position.

---

### 7.3 Task Priorities

Strict global ordering:

```text
1. Safety
2. Accessibility / clearing
3. Structural placement
4. Feature placement
5. Advancement
```

No exceptions unless explicitly designed.

---

## 8. Preprocessing Systems

### 8.1 Water / Liquid Handling

This is not planner logic.
This is execution preprocessing using the planner’s `protectedRegion`.

#### Rules

* If liquid is **inside** protected region → use temporary suppression blocks
* If liquid is **adjacent to** protected region → use permanent seal blocks
* If shell already exists in plan → use those planned shell intents where possible
* If shell is absent → create seal-only support using sealing material rules

#### Block classes

* planned blocks
* permanent seal blocks
* temporary suppression blocks

The plan is not mutated.
The executor uses the plan as the reference mask.

---

### 8.2 Falling Block Handling

Also preprocessing.

Responsibilities:

* detect gravity blocks above protected region
* break or stabilize before continuing
* wait for falling entities to settle before placement

No structural placement proceeds while instability remains.

---

## 9. Execution Engine

### 9.1 Main Tick Flow

```text
1. Load / refill planned slice buffer
2. Build world snapshot
3. Generate SliceWorkPlan
4. If safety tasks exist, run them
5. Else if clear tasks exist, run them
6. Else if placement/feature tasks exist, run them
7. Else advance
```

---

### 9.2 Slice Buffer

Planner may compute multiple future slices ahead.

Purpose:

* reduce planning overhead during fast paths
* support movement lookahead
* support future optimizations

Planner may stay synchronous initially.
No async needed until proven necessary.

---

### 9.3 Advancement Rule

Advance to next slice only when:

* all required slice tasks are complete
* no blocking hazards remain
* player is in valid forward state

---

## 10. Movement System

### 10.1 Responsibility

Movement is a service used by tasks and executor.

#### Responsibilities:

* move to stand positions
* ensure reachability for place/break/use actions
* recover when idle or stalled
* coordinate with Baritone or direct motion

Does **not**:

* decide what structure to build
* manage inventory

---

### 10.2 Movement Contract

```java
interface MovementController {
    MoveStatus ensurePosition(MovementGoal goal);
    void cancel();
}
```

#### MovementGoal

Includes:

* desired stand block
* acceptable radius
* facing hint
* posture hints if needed

---

## 11. Interaction System

### 11.1 Responsibility

Physical world actions only.

#### Responsibilities:

* place block
* break block
* interact with block
* swing/use item
* rotate if needed

Does **not**:

* decide what item is needed
* decide whether the task should happen

---

### 11.2 Contract

```java
interface InteractionController {
    ActionStatus place(BlockPos pos, int slot, PlacementPolicy policy);
    ActionStatus breakBlock(BlockPos pos, BreakPolicy policy);
    ActionStatus interact(BlockPos pos, int slot, InteractionPolicy policy);
}
```

---

## 12. Inventory & Resource System

This is the most important revision.

### 12.1 Responsibility Boundary

#### InventoryManagerCore

Owns:

* hotbar state
* slot assignments
* local inventory checks
* item movement within player inventory
* slot reservations
* exposing usable slots

#### ResourceManagerCore

Owns:

* resource acquisition beyond local inventory
* calling providers
* batching
* temporary storage access workflow
* synchronization/locking during acquisition

#### Execution Engine

Owns:

* deciding that a task cannot proceed without a requirement
* deciding whether to wait, retry, skip, or abort

---

### 12.2 Requirement Model

Replace task-shaped methods like:

* `ensureMaterial`
* `ensureTool`
* `ensureFood`

with one generalized model.

```java
class Requirement {
    RequirementKind kind;           // block/tool/food/utility/etc. optional hint
    Predicate<ItemStack> matcher;
    int minCount;
    SlotPreference slotPreference;  // optional
    RequirementPolicy policy;       // optional
}
```

Examples:

* “Any floor block”
* “Best tool for this block”
* “Any edible food matching policy”
* “Any powered rail”
* “Any disposable sealing block”

---

### 12.3 InventoryManagerCore API

```java
AvailabilityStatus ensureAvailable(Requirement req);

AvailabilityStatus ensureAvailableBatch(List<Requirement> reqs);

int getUsableSlot(Requirement req);

boolean isLocallyAvailable(Requirement req);

void reserveSlot(SlotRole role, int slot);

void reserveEmptySlots(int count);
```

---

### 12.4 AvailabilityStatus

```java
enum AvailabilityStatus {
    AVAILABLE,
    ACQUIRING,
    UNAVAILABLE
}
```

Meaning:

* `AVAILABLE`: task may proceed now
* `ACQUIRING`: resource system is working; executor should wait
* `UNAVAILABLE`: cannot fulfill requirement under current policies

---

### 12.5 Slot Roles

Retain slot roles, but as policy, not hardcoded task logic.

Examples:

* food
* weapon
* tool
* utility
* flexible supply

Slot roles must be configurable and reusable across task types.

---

## 13. ResourceManagerCore

### 13.1 Purpose

Fulfills requirements not already satisfiable from local inventory.

### 13.2 API

```java
AcquireStatus request(Requirement req);

AcquireStatus requestBatch(List<Requirement> reqs);
```

---

### 13.3 Rules

* lock inventory authority during active resource operations
* no direct builder-specific logic
* prefer lower-cost providers first
* batch expensive provider accesses when possible

---

### 13.4 AcquireStatus

```java
enum AcquireStatus {
    COMPLETE,
    IN_PROGRESS,
    FAILED
}
```

This is returned to InventoryManager, then surfaced as availability status.

---

## 14. Resource Providers

ResourceManager should not be a monolith.
It should orchestrate providers.

### 14.1 Provider Contract

```java
interface ResourceProvider {
    boolean canPotentiallyProvide(Requirement req);
    AcquireStatus fulfill(Requirement req, ResourceContext ctx);
}
```

Optional:

```java
AcquireStatus fulfillBatch(List<Requirement> reqs, ResourceContext ctx);
int estimatedCost(List<Requirement> reqs);
```

---

### 14.2 Providers

#### LocalProvider

Checks only local inventory/hotbar.

#### ShulkerProvider

Can retrieve resources from placed or placeable shulkers.

#### EnderChestProvider

Can retrieve resources from ender chest and optionally pull shulkers/items from it.

Later:

* ground item provider
* crafting provider
* furnace provider
* player handoff provider

---

## 15. Temporary Storage Planner

Renamed from `TemporaryStoragePlacement` to make it more explicit.

### 15.1 Purpose

Return a valid world-space execution plan for temporary resource containers.

### 15.2 Responsibilities

* choose placement positions near player
* ensure support block
* ensure interaction space
* ensure break/pickup feasibility
* optionally accept preferred position hints
* return movement + placement plan, not just a position

---

### 15.3 Output

```java
class StoragePlacementPlan {
    BlockPos placePos;
    BlockPos standPos;
    Direction interactSide;
    boolean requiresSupportBlock;
}
```

This is important. Returning only a `BlockPos` is not enough.

---

## 16. Handlers

Handlers are low-level world executors.

### 16.1 ShulkerHandler

Responsibilities:

* place shulker
* open GUI
* move requested items in/out
* close GUI
* break shulker
* ensure pickup or report failure

Does not:

* decide what items are needed
* choose location itself

---

### 16.2 EnderChestHandler

Responsibilities:

* place ender chest
* open GUI
* retrieve items or shulkers
* close GUI
* break ender chest if needed

Does not:

* decide what to retrieve
* choose location itself

---

## 17. Standalone Inventory Module Wrapper

### 17.1 InventoryManagerModule

This may still exist as the UI/config wrapper.

#### Responsibilities

* expose settings
* run standalone autonomous inventory logic if enabled
* provide control mode switch:

  * `AUTO`
  * `EXTERNAL`

#### Rule

When external APIs are in use, standalone autonomous logic is suspended.

This keeps the module compatible with Meteor expectations without contaminating the core services.

---

## 18. General Builder Control Flow Example

### Scenario: Linear builder needs floor blocks, rail blocks, and a sealing block

1. Builder asks planner for current slice
2. Executor analyzes world vs plan
3. Executor creates tasks
4. Highest priority task needs a block requirement
5. Executor calls:

```java
inventory.ensureAvailable(req)
```

6. InventoryManager:

* checks hotbar
* checks inventory
* if not satisfiable locally, escalates to ResourceManager

7. ResourceManager:

* selects cheapest suitable provider
* acquires resource
* returns status

8. InventoryManager reports `AVAILABLE`
9. Executor gets slot:

```java
inventory.getUsableSlot(req)
```

10. Interaction system places/breaks/interacts
11. Slice completes
12. Executor advances

---

## 19. Non-Builder Reuse Example

### Mining automation

Planner may be trivial or absent.
Execution still uses:

* movement
* interaction
* inventory manager
* resource manager
* providers

Requirements might be:

* best pickaxe
* food
* torches
* disposable blocks

Same resource system, no builder-specific rewrite.

---

## 20. Responsibility Matrix

| Layer                 | Defines goal |                      Reads world |                                                            Moves items | Uses containers | Moves player |           Places/breaks |
| --------------------- | -----------: | -------------------------------: | ---------------------------------------------------------------------: | --------------: | -----------: | ----------------------: |
| Planner               |          yes |                               no |                                                                     no |              no |           no |                      no |
| Execution Engine      |          yes |                              yes |                                                                     no |              no |  coordinates |             coordinates |
| InventoryManagerCore  |           no |                   inventory only |                                                                    yes |              no |           no |                      no |
| ResourceManagerCore   |           no | yes (through providers/handlers) | no direct inventory mutation except through InventoryManager lock path |     coordinates |           no | no direct world actions |
| Providers             |           no |                              yes |                                                                     no |             yes |           no |                      no |
| Handlers              |           no |                              yes |                                                                     no |             yes |           no |                     yes |
| MovementController    |           no |                              yes |                                                                     no |              no |          yes |                      no |
| InteractionController |           no |                              yes |                                                                     no |              no |           no |                     yes |

---

## 21. Implementation Order

Do not build this all at once.

### Phase 1

* `Requirement`
* `SlicePlan`
* basic planner
* basic diff engine
* basic executor
* local-only InventoryManager

### Phase 2

* InteractionController
* MovementController
* safety tasks
* liquid/falling block preprocessors

### Phase 3

* ResourceManagerCore
* provider abstraction
* TemporaryStoragePlanner
* ShulkerProvider + handler

### Phase 4

* EnderChestProvider + handler
* batching
* slot reservation policies
* standalone wrapper module

### Phase 5

* performance optimization
* lookahead slice buffering
* provider cost heuristics
* caching and smarter placement

---

## 22. What This Architecture Is

This rebuild is:

> a deterministic linear planning system paired with a reactive world executor and a generalized resource orchestration engine.

That is the correct abstraction.

Not:

* “better rail builder”
* “inventory helper”
* “state machine with more settings”

---

## 23. Final Summary

### Planner

Pure slice generation.

### Executor

Reactive world reconciliation.

### InventoryManager

Single authority over inventory state.

### ResourceManager

Generic acquisition orchestrator.

### Providers / Handlers

Low-level resource access execution.

### Result

A reusable automation framework that can build rails today and support completely different in-game automation tomorrow.

Inventory Management Revision: Safe Operations Model

Goal of This Revision

Replace the current reactive inventory handling with a deterministic, failure-resistant system that:

Never drops items unintentionally

Handles dynamic inventory changes (mining, pickups, packets)

Eliminates race-condition failures during item moves



---

Core Problem

Current system assumptions are invalid:

Assumes slots remain unchanged during multi-tick operations

Assumes destination slots stay empty

Falls back to dropping items when assumptions break


Reality:

Inventory can change at any time (pickup, packets, module interactions)

Multi-step operations are not atomic



---

Key Design Principles

1. Single Inventory Authority

All inventory operations must go through one system:

No direct InvUtils.move() outside controller

No module-level inventory manipulation


InventoryController.enqueue(operation)


---

2. No Implicit Drops (Hard Rule)

Dropping items is NOT a valid fallback.

If a move cannot be completed:

Pause

Retry

Re-evaluate state


Never:

throw item


---

3. Dynamic Buffer Slots (Replaces Reserved Slots)

Do NOT reserve permanent empty slots.

Instead:

Find a usable slot at runtime

Must not be critical (hotbar, active use)


findBufferSlot():
  return slot where:
    not hotbar-critical
    not reserved

This allows:

Full inventory operation

No forced item dumping

Compatibility with tunnel mining



---

4. Safe Move Operation

All moves must follow this structure:

moveSafe(source, target):
  if target not empty:
    buffer = findBufferSlot()
    if buffer == -1: FAIL
    move(target, buffer)

  move(source, target)

Rules:

Never overwrite

Always clear destination first

Always guarantee space before move



---

5. Atomic Attempt Model

Operations must not assume stability across ticks.

Bad:

check -> wait -> move

Correct:

validate -> act immediately -> verify


---

6. Post-Operation Validation

After every operation:

if expected state != actual state:
  retry or recover

Examples:

Item not in expected slot

Slot changed mid-operation



---

7. External Mutation Handling

Inventory can change due to:

Item pickup

Server packets

Other modules


System must:

Detect unexpected changes

Abort current operation safely

Recompute next action



---

8. Queue-Based Execution

Inventory operations must be serialized:

One operation at a time

Deterministic execution order


InventoryController:
  queue<Operation>
  process one per tick

Prevents:

Internal conflicts

Overlapping moves



---

What This Solves

Shulker being thrown during slot conflicts

Items picked up mid-move breaking logic

Inventory desync issues

Race conditions across ticks



---

What This Does NOT Require

Empty inventory slots

Reserved permanent slots

Dropping excess mined blocks



---

Implementation Requirements

Must implement:

InventoryController

moveSafe(source, target)

findBufferSlot()

Operation queue

Post-condition validation


Must remove:

Direct InvUtils usage in modules

Any fallback that drops items automatically



---

Final Invariant

At all times:

> No inventory operation may result in unintended item loss, regardless of external changes.
