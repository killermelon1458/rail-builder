# Occupied hotbar slots and bundle inventory fix

## Confirmed evidence

The original diagnostic reproduction selected REDSTONE_BLOCK in state PlacePower.
It found 64 redstone blocks in main inventory index 21, while preferred hotbar
index 7 contained a bundle. Repeated PICKUP-based movements did not replace it.
The terminal search found no ordinary inventory stack because redstone was then
held on the cursor. Selected diagnostic events were:

```text
SEARCH request=minecraft:redstone_block state=PlacePower preferred=7 destination=minecraft:bundle x1 searchSlot=21 count=64 cursor=empty
TRANSFER from=21 to=7 handlerFrom=21 handlerTo=43 source=minecraft:redstone_block x64 destination=minecraft:bundle x1
AFTER_TRANSFER destination=minecraft:bundle x1 sourceAfter=minecraft:redstone_block x64
SERVER_UPDATE destination=7 reported=minecraft:bundle x1 matchesRequest=false
SEARCH request=minecraft:redstone_block state=PlacePower preferred=7 searchSlot=-1 count=0 cursor=minecraft:redstone_block x64
```

The search initially succeeded. The final absent-item message therefore obscured
an inventory preparation/cursor failure. Server updates corroborated the unchanged
bundle destination, but did not identify which asynchronous update moved the
redstone onto the cursor. No specific inventory mod or server rejection was proven.

## Mechanism and fix

Vanilla BundleItem intercepts LEFT/PICKUP interactions with a nonempty incoming
stack. It can insert items into a bundle or refuse insertion instead of swapping
the slots. Meteor's `InvUtils.move()` and recursive cursor cleanup use PICKUP.
A local disposable-world reproduction confirmed repeated unexpected destinations
with unchanged redstone and a full bundle; the exact terminal cursor correction
was not separately reproduced there.

Hotbar preparation now uses one vanilla SWAP action with the mapped source slot
and destination hotbar button. It exchanges complete stacks without using the
cursor and preserves the displaced bundle and contents. Before transferring, the
adapter checks cursor emptiness and actual screen-handler/player-inventory
mappings. Unknown mappings and occupied cursors fail safely.

Preparation succeeds only when the destination is nonempty, matches the requested
item predicate, and the cursor remains empty. Shared callers honor failure.
The former `InvUtils.dropHand()` cleanup was removed from preparation; unexpected
cursor contents are preserved. Existing unrelated discard policy is unchanged.
Search reports cursor-held requests explicitly, and errors distinguish failed
preparation from missing materials. Server-update diagnostics are bounded and
deduplicated.

## Regression evidence

`verifyInventoryTransfer`, included in `check`/`build`, covers empty, dirt, empty
bundle and full bundle destinations; stack/count/content preservation; requested
and unrelated cursor stacks; invalid mappings; refused operations; unexpected
cursor results; and the existing move-to-empty-main-inventory path. Restoring
PICKUP in a temporary negative fixture caused the expected safety-test failure.

Graphical disposable-world checks during the 0.1.4 fix confirmed:

* Main-inventory redstone swapped with a full bundle; server-side checks confirmed
  redstone in the hotbar and all 64 stone preserved in the displaced bundle.
* Another-hotbar-slot/empty-bundle, empty-destination and dirt-destination cases
  completed with one SWAP.
* Cursor-held redstone produced the explicit safe error and remained on the cursor;
  genuinely absent redstone produced the separate missing-stack error.
* RoofRailBuilder's shared path swapped redstone past a full bundle and preserved
  its contents during an Overworld inventory smoke test.

These were focused inventory checks, not full nether construction, long-distance
pathing or saved-job recovery validation. All 21 production class entries in
0.1.5 match the tested 0.1.4 code; 0.1.5 changes dependency metadata and versioning.
See [the current compatibility matrix](DependencyCompatibility.md) and
[release verification record](releases/v0.1.5-verification.md).
