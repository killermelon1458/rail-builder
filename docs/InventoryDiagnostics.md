# Builder diagnostic logging

PoweredRailBuilder and RoofRailBuilder have a `debug-logging` Boolean setting,
**off by default**. Enable it in the module's settings before reproducing an issue.
Messages use Minecraft's normal logger and the `[RailBuilder]` prefix. Critical
preparation failures are reported even when detailed logging is disabled.

## What is recorded

* Activation settings and builder state transitions.
* Requested item IDs, inventory search results, preferred slot indices/counts.
* Source/destination stacks and mapped screen-handler slots before transfers.
* Transfer results, cursor-held items, and relevant inventory contents on failure.
* Pending server inventory updates and observed synchronization outcomes.
* Pathing/recovery, restocking/resource failures, and unexpected states/exceptions.

Logging focuses on state changes and operations, with deduplication to avoid
per-tick spam. It records item IDs/counts, not item component/NBT contents,
credentials or authentication information. Review surrounding Minecraft/mod log
entries before sharing the complete log; those other components may log data
outside Rail Builder's control.

## Locate and extract logs

The development client's log is `run/logs/latest.log`. A normal installation's
log is `logs/latest.log` inside the selected Minecraft profile directory. Copy it
before another launch overwrites it. For a local copy:

```bash
rg -n -F '[RailBuilder]' /path/to/profile/logs/latest.log > rail-builder-diagnostics.log
```

Record the addon/dependency versions, selected module and power source, source
inventory index, preferred destination, active screen, and the exact error. Supply
surrounding log lines when they clarify a transfer or server update.

## Failure interpretation

`SEARCH` distinguishes an ordinary inventory hit from a genuinely absent stack.
`CURSOR_HELD_REQUEST` means the requested item is on the cursor, outside the
ordinary inventory search; `CURSOR_BUSY` means another item occupies the cursor.
Return that stack safely to inventory before restarting; no automatic drop occurs.

`TRANSFER` and `AFTER_TRANSFER` identify source/destination contents and results.
An unexpected destination after a found stack is a preparation failure, not proof
that the item was absent. Pending/server updates distinguish an initially wrong
result from a later reversal, but an asynchronous correction alone does not
establish which server/mod operation caused it. Do not infer server rejection
without evidence of a valid operation followed by a contradictory update.

The historical bundle failure and vanilla SWAP fix are documented in
[RedstoneInventoryFix.md](RedstoneInventoryFix.md). Both modules share the
instrumented preparation adapter and safe transfer policy.

## Manual reproduction checklist

Use a disposable world, disable the module between cases, and empty the cursor
before ordinary transfer tests. Inventory indices are zero-based: configured
preferred index 7 is the eighth hotbar slot.

1. Select REDSTONE_BLOCK. Put redstone in preferred hotbar index 7.
2. Put redstone in another hotbar slot, keeping index 7 empty.
3. Put redstone in main inventory, keeping index 7 empty.
4. Occupy index 7 with an unrelated item, an empty bundle, then a full bundle.
5. Test a cursor-held requested stack and an unrelated cursor stack separately.
6. Repeat relevant shared-path cases with RoofRailBuilder and test restocking.

For ordinary transfers, confirm one SWAP, the requested stack at the destination,
the displaced stack intact at the source, and an empty cursor. Confirm no repeated
pickup fallback and no automatic item dropping. For cursor tests, confirm a clear
safe failure and preserved cursor stack. Inspect search, transfer and server-update
entries together. In multiplayer, verify the server's eventual inventory state.

`./gradlew verifyInventoryTransfer --no-daemon` runs deterministic safety
regressions. It does not emulate a full Minecraft server or replace this checklist.
