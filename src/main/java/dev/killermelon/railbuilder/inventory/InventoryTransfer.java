package dev.killermelon.railbuilder.inventory;

/** Small transfer contract shared by the Minecraft adapter and standalone safety regressions. */
public final class InventoryTransfer {
    private InventoryTransfer() {}

    public enum Result { READY, CURSOR_BUSY, INVALID_SLOTS, CURSOR_REMAINS, DESTINATION_MISMATCH }

    public interface Access {
        boolean cursorEmpty();
        boolean validSlots();
        void swapWithHotbar();
        void moveToInventory();
        boolean destinationMatches();
    }

    public static Result execute(Access inventory, boolean hotbarDestination) {
        if (!inventory.cursorEmpty()) return Result.CURSOR_BUSY;
        if (!inventory.validSlots()) return Result.INVALID_SLOTS;
        // PICKUP can insert into bundles instead of swapping. SWAP never uses the cursor.
        if (hotbarDestination) inventory.swapWithHotbar();
        else inventory.moveToInventory();
        if (!inventory.cursorEmpty()) return Result.CURSOR_REMAINS;
        return inventory.destinationMatches() ? Result.READY : Result.DESTINATION_MISMATCH;
    }
}
