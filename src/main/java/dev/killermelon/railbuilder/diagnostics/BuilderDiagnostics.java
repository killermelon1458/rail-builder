package dev.killermelon.railbuilder.diagnostics;

import dev.killermelon.railbuilder.RailBuilder;
import dev.killermelon.railbuilder.inventory.InventoryTransfer;
import net.minecraft.screen.slot.SlotActionType;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.SlotUtils;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;

import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/** Logs safe inventory preparation without serializing item components/NBT. */
public final class BuilderDiagnostics {
    private final String module;
    private final BooleanSupplier enabled;
    private final Map<String, String> previous = new HashMap<>();
    private final List<Transfer> pending = new ArrayList<>();
    private int sequence;
    private int ticks;
    private String inventoryFailure = "No matching item was available.";

    public String inventoryFailure() { return inventoryFailure; }

    private record Transfer(int id, int destination, int handlerSlot, int syncId,
                            Predicate<ItemStack> predicate, boolean initiallyMatched, int expiresAt, boolean serverObserved) {}

    public BuilderDiagnostics(String module, BooleanSupplier enabled) {
        this.module = module;
        this.enabled = enabled;
    }

    public void reset() {
        previous.clear();
        pending.clear();
        inventoryFailure = "No matching item was available.";
    }

    public void debug(String key, String message) {
        debug(key, message, message);
    }

    private void debug(String key, String signature, String message) {
        if (enabled.getAsBoolean() && !signature.equals(previous.put(key, signature))) {
            RailBuilder.LOG.info("[RailBuilder] [{}] {}", module, message);
        }
    }

    public void critical(String key, String message) {
        String signature = message.replaceAll("id=[0-9]+", "id=<operation>");
        if (!signature.equals(previous.put("critical:" + key, signature))) {
            RailBuilder.LOG.warn("[RailBuilder] [{}] {}", module, message);
        }
    }

    public void exception(String operation, Throwable exception) {
        // Exception messages can contain external data; log type and stack locations only.
        StringBuilder trace = new StringBuilder(exception.getClass().getName());
        for (StackTraceElement frame : exception.getStackTrace()) trace.append("\n    at ").append(frame);
        critical("exception", "Exception during " + operation + ": " + trace);
    }

    public static String stack(ItemStack stack) {
        return stack.isEmpty() ? "empty" : Registries.ITEM.getId(stack.getItem()) + " x" + stack.getCount();
    }

    private String context() {
        var player = MeteorClient.mc.player;
        if (player == null) return "player=unavailable";
        var handler = player.currentScreenHandler;
        return "handler=" + handler.getClass().getSimpleName() + " syncId=" + handler.syncId
            + " revision=" + handler.getRevision() + " cursor=" + stack(handler.getCursorStack());
    }

    public String contents() {
        var player = MeteorClient.mc.player;
        if (player == null) return "inventory=unavailable";
        StringBuilder result = new StringBuilder();
        for (int slot = 0; slot < player.getInventory().size(); slot++) {
            ItemStack item = player.getInventory().getStack(slot);
            if (!item.isEmpty()) result.append(slot).append('=').append(stack(item)).append(';');
        }
        return result.toString();
    }

    public FindItemResult search(Predicate<ItemStack> predicate, int preferred, String request, String state) {
        var player = MeteorClient.mc.player;
        inventoryFailure = "No matching item was available.";
        ItemStack cursor = player.currentScreenHandler.getCursorStack();
        if (!cursor.isEmpty()) {
            boolean requested = predicate.test(cursor);
            inventoryFailure = (requested ? "Requested item is held on the cursor" : "Cursor contains " + stack(cursor))
                + "; return the cursor stack to inventory before restarting. No item was dropped.";
            critical("cursor-search:" + request, (requested ? "CURSOR_HELD_REQUEST " : "CURSOR_BUSY ")
                + "request=" + request + " state=" + state + " preferred=" + preferred + " " + context());
            return new FindItemResult(-1, 0);
        }
        ItemStack current = player.getInventory().getStack(preferred);
        if (!current.isEmpty() && predicate.test(current)) {
            // Ready-slot hits are logged once per request/state/item, not on every count change.
            String signature = "READY request=" + request + " state=" + state + " preferred="
                + preferred + " item=" + Registries.ITEM.getId(current.getItem());
            debug("ready:" + request, signature, signature + " count=" + current.getCount() + " " + context());
            return new FindItemResult(preferred, current.isEmpty() ? 0 : current.getCount());
        }
        FindItemResult result = InvUtils.find(predicate);
        String details = "request=" + request + " state=" + state + " preferred=" + preferred
            + " destination=" + stack(current) + " searchSlot=" + result.slot() + " count=" + result.count();
        debug("search:" + request, "SEARCH " + details + " " + context());
        if (!result.found()) {
            boolean independentlyPresent = false;
            for (int i = 0; i < player.getInventory().size(); i++) {
                if (predicate.test(player.getInventory().getStack(i))) independentlyPresent = true;
            }
            inventoryFailure = independentlyPresent ? "Inventory search disagrees with the inventory snapshot."
                : "No matching stack in the client player inventory (cursor empty).";
            critical("search:" + request, (independentlyPresent ? "SEARCH_DISAGREEMENT " : "ABSENT_IN_CLIENT_INVENTORY ")
                + details + " " + context());
            debug("contents:" + request, "FAILURE inventory slots (0-based): " + contents());
        }
        if (result.found()) previous.remove("critical:search:" + request);
        return result;
    }

    /** Hotbar transfers use one cursor-free SWAP; a failed preparation is never reported as ready. */
    public boolean move(int from, int to, Predicate<ItemStack> predicate, String request) {
        var player = MeteorClient.mc.player;
        int id = ++sequence;
        int handlerSlot = SlotUtils.indexToId(to);
        int syncId = player.currentScreenHandler.syncId;
        debug("move", "TRANSFER id=" + id + " request=" + request + " from=" + from + " to=" + to
            + " handlerFrom=" + SlotUtils.indexToId(from) + " handlerTo=" + handlerSlot
            + " source=" + stack(player.getInventory().getStack(from))
            + " destination=" + stack(player.getInventory().getStack(to)) + " " + context());
        if (!player.currentScreenHandler.getCursorStack().isEmpty()) {
            critical("cursor-before", "UNEXPECTED_CURSOR before transfer id=" + id + " " + context());
        }
        try {
            var handler = player.currentScreenHandler;
            int sourceSlot = SlotUtils.indexToId(from);
            boolean valid = sourceSlot >= 0 && handlerSlot >= 0
                && sourceSlot < handler.slots.size() && handlerSlot < handler.slots.size()
                && handler.getSlot(sourceSlot).inventory == player.getInventory()
                && handler.getSlot(sourceSlot).getIndex() == from
                && handler.getSlot(handlerSlot).inventory == player.getInventory()
                && handler.getSlot(handlerSlot).getIndex() == to;
            InventoryTransfer.Result result = InventoryTransfer.execute(new InventoryTransfer.Access() {
                public boolean cursorEmpty() { return handler.getCursorStack().isEmpty(); }
                public boolean validSlots() { return valid; }
                public void swapWithHotbar() {
                    debug("action", "SWAP id=" + id + " clickedHandlerSlot=" + sourceSlot + " hotbarButton=" + to);
                    mcClickSwap(handler.syncId, sourceSlot, to);
                }
                public void moveToInventory() { InvUtils.move().from(from).to(to); }
                public boolean destinationMatches() {
                    ItemStack destination = player.getInventory().getStack(to);
                    return !destination.isEmpty() && predicate.test(destination);
                }
            }, to >= 0 && to < 9);
            debug("after-move", "AFTER_MOVE id=" + id + " result=" + result + " " + context());
            // Never use dropHand: an unexpected cursor stack must be preserved, not discarded.
            ItemStack destination = player.getInventory().getStack(to);
            boolean matches = result == InventoryTransfer.Result.READY;
            String outcome = "id=" + id + " request=" + request + " to=" + to + " destination=" + stack(destination)
                + " sourceAfter=" + stack(player.getInventory().getStack(from)) + " " + context() + " serverConfirmation=pending/unknown";
            debug("after-transfer", "AFTER_TRANSFER " + outcome);
            if (matches) previous.remove("critical:transfer:" + request);
            if (!matches) {
                inventoryFailure = "Item found at inventory slot " + from + " but preparation of slot " + to
                    + " failed: " + result + ". Destination=" + stack(destination)
                    + "; cursor=" + stack(handler.getCursorStack()) + ". No item was dropped.";
                critical("transfer:" + request, (destination.isEmpty() ? "TRANSFER_NOT_OBSERVED " : "UNEXPECTED_DESTINATION ")
                    + outcome + " (not item absence; may be pending/unsynchronized)");
                debug("transfer-inventory", "FAILURE inventory slots (0-based): " + contents());
            }
            if (enabled.getAsBoolean()) {
                if (pending.size() == 8) previous.remove("server:" + pending.removeFirst().id());
                pending.add(new Transfer(id, to, handlerSlot, syncId, predicate, matches, ticks + 10, false));
            }
            return matches;
        } catch (RuntimeException exception) {
            exception("inventory transfer id=" + id, exception);
            throw exception;
        }
    }

    private void mcClickSwap(int syncId, int sourceSlot, int hotbarSlot) {
        MeteorClient.mc.interactionManager.clickSlot(syncId, sourceSlot, hotbarSlot, SlotActionType.SWAP, MeteorClient.mc.player);
    }

    public void tick() {
        ticks++;
        if (!enabled.getAsBoolean()) { pending.clear(); return; }
        for (Transfer transfer : List.copyOf(pending)) {
            if (ticks >= transfer.expiresAt()) {
                debug("confirmation-window", (transfer.serverObserved() ? "OBSERVATION_WINDOW_CLOSED transfer=" : "UNCONFIRMED transfer=") + transfer.id() + " to=" + transfer.destination()
                    + " serverUpdateObserved=" + transfer.serverObserved() + " (10 tick window; not proof of acceptance/rejection)");
                pending.remove(transfer);
                previous.remove("server:" + transfer.id());
            }
        }
    }

    /** Read packet contents only, before application: never mistake local prediction for server acceptance. */
    public void packet(Object packet) {
        if (!enabled.getAsBoolean()) return;
        if (!MeteorClient.mc.isOnThread()) {
            MeteorClient.mc.execute(() -> packet(packet));
            return;
        }
        for (Transfer transfer : List.copyOf(pending)) {
            ItemStack reported = null;
            if (packet instanceof InventoryS2CPacket p && p.syncId() == transfer.syncId()
                && transfer.handlerSlot() >= 0 && transfer.handlerSlot() < p.contents().size()) {
                reported = p.contents().get(transfer.handlerSlot());
            } else if (packet instanceof ScreenHandlerSlotUpdateS2CPacket p) {
                if (p.getSyncId() == transfer.syncId() && p.getSlot() == transfer.handlerSlot()) reported = p.getStack();
                else if (p.getSyncId() == -2 && p.getSlot() == transfer.destination()) reported = p.getStack();
            }
            if (reported != null) {
                boolean matches = transfer.predicate().test(reported);
                debug("server:" + transfer.id(), "SERVER_UPDATE transfer=" + transfer.id() + " destination="
                    + transfer.destination() + " reported=" + stack(reported) + " matchesRequest=" + matches
                    + " initiallyMatched=" + transfer.initiallyMatched()
                    + (matches ? "" : " possible rejection/reversal, consumption, or stale update; correlate with state"));
                int index = pending.indexOf(transfer);
                if (index >= 0) pending.set(index, new Transfer(transfer.id(), transfer.destination(), transfer.handlerSlot(),
                    transfer.syncId(), transfer.predicate(), transfer.initiallyMatched(), transfer.expiresAt(), true));
            }
        }
    }
}
