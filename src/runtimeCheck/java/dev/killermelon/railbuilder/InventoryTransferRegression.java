package dev.killermelon.railbuilder;

import dev.killermelon.railbuilder.inventory.InventoryTransfer;
import static dev.killermelon.railbuilder.inventory.InventoryTransfer.Result.*;

/** Deterministic safety contract checks; actual vanilla bundle behavior is also tested in-game. */
public final class InventoryTransferRegression {
    private record Stack(String item, int count, int bundleContents) {}
    private static final Stack REDSTONE = new Stack("redstone_block", 64, 0);
    private static final Stack DIRT = new Stack("dirt", 64, 0);

    private static final class Inventory implements InventoryTransfer.Access {
        Stack source, destination, cursor;
        final String requested;
        boolean valid = true, refuseSwap, leaveCursor;
        int swaps, pickups;

        Inventory(Stack source, Stack destination) {
            this.source = source;
            this.destination = destination;
            requested = source == null ? "redstone_block" : source.item();
        }
        public boolean cursorEmpty() { return cursor == null; }
        public boolean validSlots() { return valid; }
        public boolean destinationMatches() {
            return destination != null && destination.count() > 0 && destination.item().equals(requested);
        }
        public void swapWithHotbar() {
            swaps++;
            if (refuseSwap) return;
            if (leaveCursor) { cursor = source; source = null; return; }
            Stack old = destination;
            destination = source;
            source = old;
        }
        public void moveToInventory() {
            pickups++;
            // Model the confirmed bundle interception: PICKUP is not a slot exchange.
            if (destination != null && destination.item().equals("bundle")) {
                cursor = source;
                source = null;
            } else {
                Stack old = destination;
                destination = source;
                source = old;
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        for (Stack displaced : new Stack[] {null, DIRT, new Stack("bundle", 1, 0), new Stack("bundle", 1, 64)}) {
            Inventory inventory = new Inventory(REDSTONE, displaced);
            check(InventoryTransfer.execute(inventory, true) == READY, "Hotbar preparation must succeed");
            check(inventory.destination.equals(REDSTONE), "Redstone stack/count must reach the destination");
            check(displaced == null ? inventory.source == null : displaced.equals(inventory.source),
                "Displaced stack and bundle contents must be preserved at source");
            check(inventory.cursor == null && inventory.swaps == 1 && inventory.pickups == 0,
                "Hotbar preparation must use one SWAP, no PICKUP/cursor operation");
        }
        for (Stack cursor : new Stack[] {REDSTONE, DIRT}) {
            Inventory inventory = new Inventory(null, new Stack("bundle", 1, 64));
            inventory.cursor = cursor;
            check(InventoryTransfer.execute(inventory, true) == CURSOR_BUSY, "Cursor-held items must block transfer");
            check(inventory.cursor.equals(cursor) && inventory.swaps == 0 && inventory.pickups == 0,
                "Cursor-held redstone or unrelated items must never be clicked, dropped, or duplicated");
        }
        Inventory invalid = new Inventory(REDSTONE, DIRT);
        invalid.valid = false;
        check(InventoryTransfer.execute(invalid, true) == INVALID_SLOTS && invalid.swaps == 0 && invalid.pickups == 0,
            "Unknown screen-handler mappings must not produce inventory actions");
        Inventory refused = new Inventory(REDSTONE, new Stack("bundle", 1, 64));
        refused.refuseSwap = true;
        check(InventoryTransfer.execute(refused, true) == DESTINATION_MISMATCH, "Wrong-item destination must not be success");
        check(refused.source.equals(REDSTONE) && refused.pickups == 0, "No unsafe retry after a refused swap");
        Inventory partial = new Inventory(REDSTONE, null);
        partial.leaveCursor = true;
        check(InventoryTransfer.execute(partial, true) == CURSOR_REMAINS && partial.cursor.equals(REDSTONE),
            "Unexpected cursor results must be preserved and reported, never dropped");
        Inventory clear = new Inventory(new Stack("bundle", 1, 64), null);
        check(InventoryTransfer.execute(clear, false) == READY && clear.pickups == 1 && clear.swaps == 0,
            "Existing move-to-empty-main-inventory path remains available");
        System.out.println("Inventory transfer regressions passed: empty/occupied/bundle destinations, cursor safety, mappings, failure verification, inventory clearing");
    }
}
