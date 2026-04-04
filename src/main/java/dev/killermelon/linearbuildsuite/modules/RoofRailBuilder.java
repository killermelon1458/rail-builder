package dev.killermelon.linearbuildsuite.modules;

import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.GoalBlock;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixin.ShulkerBoxScreenHandlerAccessor;
import meteordevelopment.meteorclient.settings.*;
import dev.killermelon.linearbuildsuite.LinearBuildSuite;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.combat.KillAura;
import meteordevelopment.meteorclient.systems.modules.player.AutoEat;
import meteordevelopment.meteorclient.systems.modules.player.AutoGap;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import net.minecraft.util.WorldSavePath;
import net.minecraft.client.option.KeyBinding;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.utils.misc.Keybind;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.RedstoneTorchBlock;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.ShulkerBoxScreen;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.function.Predicate;

@SuppressWarnings("ConstantConditions")
public class RoofRailBuilder extends Module {
    private enum PowerType {
        REDSTONE_BLOCK,
        REDSTONE_TORCH
    }

    private enum Side {
        Left,
        Right
    }

    private enum State {
        Build,
        Restock,
        PauseForSupplies,
        WaitForRestock,
        Resume,
        Done
    }

    private enum RequestType {
        RAIL,
        POWER,
        FOOD
    }

    private static final ItemStack[] CONTAINER_ITEMS = new ItemStack[27];
    private static final double DRIFT_TOLERANCE = 0.7;
    private static final int RECOVERY_IDLE_TIMEOUT = 20;
    private static final int SHULKER_PICKUP_WAIT_TICKS = 20;
    private static final int PLACEMENT_STALL_TIMEOUT_TICKS = 10;
    private static final int SNAPSHOT_VERSION = 1;
    private static final int SNAPSHOT_AUTOSAVE_TICKS = 20;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int RESUME_VERIFY_WINDOW = 5;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgPower = settings.createGroup("Power");
    private final SettingGroup sgInventory = settings.createGroup("Inventory");

    private final Setting<Integer> length = sgGeneral.add(new IntSetting.Builder()
        .name("length")
        .description("How many powered rails to place.")
        .defaultValue(2048)
        .min(1)
        .sliderRange(64, 8192)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Rotate while placing.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> placeRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("place-range")
        .description("Max placement reach.")
        .defaultValue(4.5)
        .range(1, 6)
        .sliderRange(1, 5.5)
        .build()
    );

    private final Setting<Integer> resumeWaitSeconds = sgGeneral.add(new IntSetting.Builder()
        .name("resume-wait-seconds")
        .description("How long to wait after new supply shulkers appear before resuming.")
        .defaultValue(30)
        .range(1, 300)
        .sliderRange(5, 60)
        .build()
    );

    private final Setting<Boolean> autoResumeOnJoin = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-resume-on-join")
        .description("Resume a saved roof rail job automatically after reconnecting to the same world.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Keybind> pauseResumeKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("pause-resume-key")
        .description("Soft pause / resume for the current saved roof rail job.")
        .defaultValue(Keybind.fromKey(-1))
        .build()
    );

    private final Setting<Side> powerSide = sgPower.add(new EnumSetting.Builder<Side>()
        .name("power-side")
        .description("Which side of the rail line power is placed on relative to your facing.")
        .defaultValue(Side.Right)
        .build()
    );

    private final Setting<PowerType> powerType = sgPower.add(new EnumSetting.Builder<PowerType>()
        .name("power-type")
        .description("What type of power source to use.")
        .defaultValue(PowerType.REDSTONE_BLOCK)
        .build()
    );

    private final Setting<Integer> powerSpacing = sgPower.add(new IntSetting.Builder()
        .name("power-spacing")
        .description("Distance between power placements.")
        .defaultValue(17)
        .range(1, 256)
        .sliderRange(1, 64)
        .build()
    );

    private final Setting<Integer> inventoryDelay = sgInventory.add(new IntSetting.Builder()
        .name("inventory-delay")
        .description("Delay in ticks between container interactions.")
        .defaultValue(3)
        .range(0, 20)
        .sliderRange(0, 10)
        .build()
    );

    private final Setting<Integer> reserveEmptySlots = sgInventory.add(new IntSetting.Builder()
        .name("reserve-empty-slots")
        .description("Minimum empty inventory slots to leave free.")
        .defaultValue(3)
        .range(1, 9)
        .sliderRange(1, 5)
        .build()
    );

    private final Setting<Item> foodItem = sgInventory.add(new ItemSetting.Builder()
        .name("food-item")
        .description("Dedicated food item.")
        .defaultValue(Items.GOLDEN_CARROT)
        .build()
    );

    private final Setting<Integer> railHotbarSlot = sgInventory.add(new IntSetting.Builder()
        .name("rail-hotbar-slot")
        .description("Dedicated hotbar slot for powered rails.")
        .defaultValue(0)
        .range(0, 8)
        .sliderRange(0, 8)
        .build()
    );

    private final Setting<Integer> powerHotbarSlot = sgInventory.add(new IntSetting.Builder()
        .name("power-hotbar-slot")
        .description("Dedicated hotbar slot for power items.")
        .defaultValue(1)
        .range(0, 8)
        .sliderRange(0, 8)
        .build()
    );

    private final Setting<Integer> foodHotbarSlot = sgInventory.add(new IntSetting.Builder()
        .name("food-hotbar-slot")
        .description("Dedicated hotbar slot for food.")
        .defaultValue(2)
        .range(0, 8)
        .sliderRange(0, 8)
        .build()
    );

    private final Setting<Integer> chestHotbarSlot = sgInventory.add(new IntSetting.Builder()
        .name("chest-hotbar-slot")
        .description("Dedicated hotbar slot for the pause chest.")
        .defaultValue(3)
        .range(0, 8)
        .sliderRange(0, 8)
        .build()
    );

    private boolean recovering;
    private BlockPos recoveryTargetPos;
    private BlockPos lastGoal;
    private int pathIdleTicks;
    private Vec3d lastPathPlayerPos;
    private float lockedYaw;
    private float lockedPitch;

    private Direction forward;
    private Direction powerDir;
    private Direction supplySideDir;
    private BlockPos origin;
    private State state = State.Done;
    private RequestType requestType;

    private int step;
    private int lastPlacedRailStep;
    private int inventoryTimer;
    private int waitTimer;
    private int syncId;

    private BlockPos activeContainerPos;
    private BlockPos pauseChestPos;
    private int activeShulkerInventorySlot = -1;
    private boolean breakingPlacedContainer;
    private int placeAttemptTicks;
    private int lastAttemptedStep = -1;
    private BlockPos lastAttemptedPos;
    private boolean waitingForShulkerPickup;
    private int shulkerInventorySnapshot;
    private int pickupWaitTicks;
    private int pickupFallbackIndex;
    private static final int CONTAINER_REPOSITION_TIMEOUT_TICKS = 60;
    private int containerPrePlaceDistance;
    private int containerPrePlaceStallTicks;
    private boolean jobPaused;
    private boolean pauseResumeKeyWasPressed;
    private boolean pendingJoinResume;
    private int snapshotAutosaveTimer;
    private boolean pendingResumeRailCheck;

    private static final class JobSnapshot {
        int version = SNAPSHOT_VERSION;
        String serverId;
        String dimensionId;
        boolean paused;
        boolean resumePending;
        int originX;
        int originY;
        int originZ;
        String forward;
        String state;
        String requestType;
        int step;
        int lastPlacedRailStep;
        float lockedYaw;
        float lockedPitch;
        int length;
        String powerSide;
        String powerType;
        int powerSpacing;
    }


    public RoofRailBuilder() {
        super(LinearBuildSuite.CATEGORY, "roof-rail-builder", "Quick one-off nether roof powered rail builder with simple shulker refills.");
    }

    @Override
    public void onActivate() {
        if (!Utils.canUpdate()) return;
        pendingResumeRailCheck = false;
        jobPaused = false;
        pauseResumeKeyWasPressed = false;
        pendingJoinResume = false;
        snapshotAutosaveTimer = SNAPSHOT_AUTOSAVE_TICKS;

        JobSnapshot snapshot = loadJobSnapshot();
        if (snapshot != null && matchesCurrentWorld(snapshot)) {
            if (restoreFromSnapshot(snapshot, autoResumeOnJoin.get() && snapshot.resumePending)) {
                if (jobPaused) info("Loaded paused roof rail job. Use the pause-resume key to continue.");
                else info("Resumed saved roof rail job.");
                return;
            }
        }

        origin = mc.player.getBlockPos();
        forward = mc.player.getHorizontalFacing();
        if (forward == Direction.UP || forward == Direction.DOWN) {
            error("Invalid facing direction.");
            toggle();
            return;
        }

        powerDir = powerSide.get() == Side.Right ? forward.rotateYClockwise() : forward.rotateYCounterclockwise();
        supplySideDir = powerDir.getOpposite();
        lockedYaw = getYawForDirection(forward);

        lockedPitch = 0f;

        recovering = false;
        recoveryTargetPos = null;
        lastGoal = null;
        pathIdleTicks = 0;
        lastPathPlayerPos = null;
        containerPrePlaceDistance = 1;
        containerPrePlaceStallTicks = 0;
        step = 0;
        lastPlacedRailStep = -1;
        inventoryTimer = 0;
        waitTimer = 0;
        syncId = 0;

        activeContainerPos = null;
        pauseChestPos = null;
        activeShulkerInventorySlot = -1;
        breakingPlacedContainer = false;
        waitingForShulkerPickup = false;
        shulkerInventorySnapshot = 0;
        pickupWaitTicks = 0;
        pickupFallbackIndex = 0;
        resetPlacementRetry();

        requestType = null;
        state = State.Build;

        setMovement(true);
        saveJobSnapshot(false, false);
        info("Start locked. Facing %s. Power=%s side=%s spacing=%d", forward, powerType.get(), powerSide.get(), powerSpacing.get());
    }

    @Override
    public void onDeactivate() {
        pendingResumeRailCheck = false;
        releaseMovement();
        stopBaritone();
        recovering = false;
        resetPlacementRetry();
        if (mc.currentScreen != null) mc.currentScreen.close();
        activeContainerPos = null;
        pauseChestPos = null;
        activeShulkerInventorySlot = -1;
        breakingPlacedContainer = false;
        waitingForShulkerPickup = false;
        requestType = null;
        jobPaused = false;
        pauseResumeKeyWasPressed = false;
        pendingJoinResume = false;
        deleteJobSnapshot();
    }

    private java.io.File getJobFile() {
        return new java.io.File(MeteorClient.FOLDER, "roof-rail-builder-job.json");
    }

    private java.io.File getJobFallbackFile() {
        return new java.io.File(MeteorClient.FOLDER, "roof-rail-builder-job-fallback.json");
    }
    private String getCurrentServerId() {
        if (mc == null) return "unknown";

        if (mc.isInSingleplayer()) {
            if (mc.getServer() != null) {
                try {
                    return "singleplayer:" + mc.getServer().getSavePath(WorldSavePath.ROOT).getFileName();
                } catch (Throwable ignored) {
                }
            }

            return "singleplayer";
        }

        return mc.getCurrentServerEntry() != null ? "multiplayer:" + mc.getCurrentServerEntry().address : "multiplayer:unknown";
    }

    private String getCurrentDimensionId() {
        if (mc == null || mc.world == null) return "unknown";
        return mc.world.getRegistryKey().getValue().toString();
    }

    private State getPersistedState() {
        if (state == State.PauseForSupplies || state == State.WaitForRestock) return state;
        if (state == State.Restock || requestType != null || getNeededRequest() != null) return State.Restock;
        if (state == State.Done) return State.Done;
        return State.Build;
    }
    
    

    private void clearTransientResumeState() {
        pendingResumeRailCheck = false;
        recovering = false;
        recoveryTargetPos = null;
        lastGoal = null;
        pathIdleTicks = 0;
        lastPathPlayerPos = null;
        inventoryTimer = 0;
        waitTimer = 0;
        syncId = 0;
        containerPrePlaceDistance = 1;
        containerPrePlaceStallTicks = 0;
        activeContainerPos = null;
        pauseChestPos = null;
        activeShulkerInventorySlot = -1;
        breakingPlacedContainer = false;
        waitingForShulkerPickup = false;
        shulkerInventorySnapshot = 0;
        pickupWaitTicks = 0;
        pickupFallbackIndex = -1;
        resetPlacementRetry();
        stopBaritone();
        releaseMovement();
        if (mc.currentScreen != null) mc.currentScreen.close();
    }

    private JobSnapshot captureJobSnapshot(boolean paused, boolean resumePending) {
        if (origin == null || forward == null || state == State.Done) return null;

        JobSnapshot snapshot = new JobSnapshot();
        snapshot.serverId = getCurrentServerId();
        snapshot.dimensionId = getCurrentDimensionId();
        snapshot.paused = paused;
        snapshot.resumePending = resumePending;
        snapshot.originX = origin.getX();
        snapshot.originY = origin.getY();
        snapshot.originZ = origin.getZ();
        snapshot.forward = forward.name();
        snapshot.state = getPersistedState().name();
        snapshot.requestType = requestType != null ? requestType.name() : null;
        snapshot.step = step;
        snapshot.lastPlacedRailStep = lastPlacedRailStep;
        snapshot.lockedYaw = lockedYaw;
        snapshot.lockedPitch = lockedPitch;
        snapshot.length = length.get();
        snapshot.powerSide = powerSide.get().name();
        snapshot.powerType = powerType.get().name();
        snapshot.powerSpacing = powerSpacing.get();
        return snapshot;
    }

    private void saveJobSnapshot(boolean paused, boolean resumePending) {
        JobSnapshot snapshot = captureJobSnapshot(paused, resumePending);
        if (snapshot == null) return;

        java.io.File primary = getJobFile();
        java.io.File fallback = getJobFallbackFile();

        try {
            Files.createDirectories(primary.getParentFile().toPath());
        } catch (Exception e) {
            warning("Failed to prepare roof rail job folder: %s", e.getMessage());
            return;
        }

        try {
            try (Writer writer = Files.newBufferedWriter(primary.toPath())) {
                GSON.toJson(snapshot, writer);
            }
            return;
        } catch (Exception ignored) {
            // Fall through to fallback file.
        }

        try {
            try (Writer writer = Files.newBufferedWriter(fallback.toPath())) {
                GSON.toJson(snapshot, writer);
            }
        } catch (Exception e) {
            warning("Failed to save roof rail job: %s", e.getMessage());
        }
    }

    private JobSnapshot loadJobSnapshot() {
        java.io.File[] candidates = new java.io.File[] { getJobFile(), getJobFallbackFile() };

        for (java.io.File file : candidates) {
            try {
                if (!file.exists()) continue;

                try (Reader reader = Files.newBufferedReader(file.toPath())) {
                    JobSnapshot snapshot = GSON.fromJson(reader, JobSnapshot.class);
                    if (snapshot == null || snapshot.version != SNAPSHOT_VERSION) continue;
                    return snapshot;
                }
            } catch (Exception ignored) {
            }
        }

        return null;
    }

    private void deleteJobSnapshot() {
        try {
            Files.deleteIfExists(getJobFile().toPath());
        } catch (Exception ignored) {
        }

        try {
            Files.deleteIfExists(getJobFallbackFile().toPath());
        } catch (Exception ignored) {
        }
    }

    private boolean matchesCurrentWorld(JobSnapshot snapshot) {
        if (snapshot == null || mc == null || mc.world == null) return false;
        return getCurrentServerId().equals(snapshot.serverId)
            && getCurrentDimensionId().equals(snapshot.dimensionId);
    }

    private boolean restoreFromSnapshot(JobSnapshot snapshot, boolean shouldResumeNow) {
        if (snapshot == null || !matchesCurrentWorld(snapshot)) return false;

        Direction savedForward;
        try {
            savedForward = Direction.valueOf(snapshot.forward);
        } catch (IllegalArgumentException | NullPointerException e) {
            return false;
        }

        if (savedForward == Direction.UP || savedForward == Direction.DOWN) return false;
        origin = new BlockPos(snapshot.originX, snapshot.originY, snapshot.originZ);
        forward = savedForward;
        powerDir = powerSide.get() == Side.Right ? forward.rotateYClockwise() : forward.rotateYCounterclockwise();
        supplySideDir = powerDir.getOpposite();
        lockedYaw = snapshot.lockedYaw;
        lockedPitch = snapshot.lockedPitch;
        step = Math.max(0, snapshot.step);
        lastPlacedRailStep = snapshot.lastPlacedRailStep;
        requestType = snapshot.requestType != null ? RequestType.valueOf(snapshot.requestType) : null;
        state = snapshot.state != null ? State.valueOf(snapshot.state) : State.Build;
        clearTransientResumeState();
        snapshotAutosaveTimer = SNAPSHOT_AUTOSAVE_TICKS;
        pendingResumeRailCheck = true;
        step = Math.max(0, lastPlacedRailStep + 1);

    if (state != State.PauseForSupplies && state != State.WaitForRestock) {
        RequestType needed = getNeededRequest();
        if (needed != null) {
            requestType = needed;
            state = State.Restock;
        } else {
            state = State.Build;
        }
    } else if (pauseChestPos == null) {
        pauseChestPos = getPauseChestPos();
    }

        jobPaused = !shouldResumeNow;

        if (!jobPaused) {
            if (state == State.Build && lastPlacedRailStep >= 0) startRecovery();
            else if (state == State.Build) setMovement(true);
            saveJobSnapshot(false, false);
        } else {
            saveJobSnapshot(true, false);
        }

        return true;
    }

    private void pauseCurrentJob() {
        if (state == State.Done || origin == null || forward == null) return;

        clearTransientResumeState();
        KeyBinding.updatePressedStates();
        jobPaused = true;
        saveJobSnapshot(true, false);
        info("Roof rail job paused.");
    }



    private void resumeSavedJob(boolean automatic) {
        JobSnapshot snapshot = loadJobSnapshot();
        if (snapshot == null) {
            warning("No saved roof rail job found.");
            return;
        }

        if (!matchesCurrentWorld(snapshot)) {
            warning("Saved roof rail job belongs to a different world or dimension.");
            return;
        }

        if (!restoreFromSnapshot(snapshot, true)) {
            warning("Failed to restore saved roof rail job.");
            return;
        }

        info(automatic ? "Auto-resumed saved roof rail job." : "Resumed saved roof rail job.");
    }

    private void handlePauseResumeKey() {
        boolean pressed = pauseResumeKey.get().isPressed();
        if (!pressed || pauseResumeKeyWasPressed) {
            pauseResumeKeyWasPressed = pressed;
            return;
        }

        pauseResumeKeyWasPressed = true;

        if (jobPaused) resumeSavedJob(false);
        else pauseCurrentJob();
    }

    private float getYawForDirection(Direction dir) {
        return switch (dir) {
            case SOUTH -> 0f;
            case WEST -> 90f;
            case NORTH -> 180f;
            case EAST -> -90f;
            default -> 0f;
        };
    }

    private void stopBaritone() {
        lastGoal = null;
        pathIdleTicks = 0;
        lastPathPlayerPos = null;

        try {
            var baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            baritone.getPathingBehavior().cancelEverything();
            baritone.getCustomGoalProcess().setGoal(null);
        } catch (Throwable ignored) {
        }
    }

    private void resetPathIdle() {
        pathIdleTicks = 0;
        lastPathPlayerPos = mc.player != null ? mc.player.getEntityPos() : null;
    }

    private void setBaritoneGoal(BlockPos goal) {
        try {
            BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess()
                .setGoalAndPath(new GoalBlock(goal.getX(), goal.getY(), goal.getZ()));
            lastGoal = goal.toImmutable();
            resetPathIdle();
        } catch (Throwable ignored) {
        }
    }

    private void tickPathIdle() {
        if (lastGoal == null || mc.player == null) {
            pathIdleTicks = 0;
            lastPathPlayerPos = null;
            return;
        }

        Vec3d currentPos = mc.player.getEntityPos();
        if (lastPathPlayerPos != null && currentPos.squaredDistanceTo(lastPathPlayerPos) <= 0.01) pathIdleTicks++;
        else {
            pathIdleTicks = 0;
            lastPathPlayerPos = currentPos;
        }
    }

    private double getRailLineDrift() {
        int anchorStep = Math.max(0, lastPlacedRailStep);
        BlockPos anchorWalk = getWalkPos(anchorStep);
        Vec3d railCenter = Vec3d.ofCenter(anchorWalk);
        Vec3d playerPos = mc.player.getEntityPos();

        return switch (forward.getAxis()) {
            case X -> Math.abs(playerPos.z - railCenter.z);
            case Z -> Math.abs(playerPos.x - railCenter.x);
            default -> 0;
        };
    }

    private boolean isChunkLoadedForPos(BlockPos pos) {
        return mc.world.getChunkManager().isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4);
    }

    private boolean isCurrentBuildSliceLoaded(int currentStep) {
        BlockPos railPos = getRailPos(currentStep);
        BlockPos aboveRailPos = railPos.up();
        BlockPos powerPos = getPowerPos(currentStep);

        if (!isChunkLoadedForPos(railPos)) return false;
        if (!isChunkLoadedForPos(aboveRailPos)) return false;
        if (!isChunkLoadedForPos(powerPos)) return false;

        if (powerType.get() == PowerType.REDSTONE_TORCH) {
            BlockPos supportPos = powerPos.down();
            if (!isChunkLoadedForPos(supportPos)) return false;
        }

        return true;
    }

    private boolean isOffRailLine() {
        return getRailLineDrift() > DRIFT_TOLERANCE;
    }

    private void startRecovery() {
        releaseMovement();
        stopBaritone();
        recovering = true;
        recoveryTargetPos = getWalkPos(Math.max(0, lastPlacedRailStep));
        mc.player.setYaw(lockedYaw);
        mc.player.setPitch(lockedPitch);
        setBaritoneGoal(recoveryTargetPos);
    }

    private void tickRecovery() {
        if (!recovering || recoveryTargetPos == null) return;

        mc.player.setYaw(lockedYaw);
        mc.player.setPitch(lockedPitch);

        boolean onTargetBlock = mc.player.getBlockPos().equals(recoveryTargetPos);
        boolean closeEnough = mc.player.getBlockPos().getSquaredDistance(recoveryTargetPos) <= 2.0;
        if (onTargetBlock || (closeEnough && getRailLineDrift() <= DRIFT_TOLERANCE)) {
            stopBaritone();

            if (pendingResumeRailCheck) {
                pendingResumeRailCheck = false;

                int oldAnchor = lastPlacedRailStep;
                reconcileSavedRailProgress();
                step = Math.max(0, lastPlacedRailStep + 1);

                if (lastPlacedRailStep != oldAnchor) {
                    if (lastPlacedRailStep >= 0) {
                        recoveryTargetPos = getWalkPos(lastPlacedRailStep);
                        setBaritoneGoal(recoveryTargetPos);
                        return;
                    } else {
                        recovering = false;
                        recoveryTargetPos = null;
                        inventoryTimer = 0;
                        state = State.Build;
                        setMovement(true);
                        return;
                    }
                }
            }

            recovering = false;
            recoveryTargetPos = null;
            inventoryTimer = 0;
            state = State.Build;
            setMovement(true);
            return;
        }
        if (lastGoal == null || !lastGoal.equals(recoveryTargetPos)) {
            setBaritoneGoal(recoveryTargetPos);
            return;
        }

        if (pathIdleTicks >= RECOVERY_IDLE_TIMEOUT) setBaritoneGoal(recoveryTargetPos);
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (state != State.Done && origin != null && forward != null) {
            saveJobSnapshot(jobPaused, !jobPaused && autoResumeOnJoin.get());
        }

        releaseMovement();
        stopBaritone();
        recovering = false;
        resetPlacementRetry();
        pendingJoinResume = autoResumeOnJoin.get();
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        pendingJoinResume = true;
    }

    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        if (event.packet instanceof InventoryS2CPacket p) syncId = p.syncId();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!Utils.canUpdate()) return;
        if (mc.player == null || mc.world == null) return;

        handlePauseResumeKey();

        if (pendingJoinResume) {
            pendingJoinResume = false;
            JobSnapshot snapshot = loadJobSnapshot();
            if (snapshot != null && snapshot.resumePending && autoResumeOnJoin.get() && !jobPaused) {
                resumeSavedJob(true);
            }
        }

        if (jobPaused) {
            //releaseMovement(); 
            stopBaritone();
            return;
        }

        if (inventoryTimer > 0) inventoryTimer--;
        if (snapshotAutosaveTimer > 0) snapshotAutosaveTimer--;
        if (snapshotAutosaveTimer == 0) {
            if (state != State.Done && origin != null && forward != null) saveJobSnapshot(false, false);
            snapshotAutosaveTimer = SNAPSHOT_AUTOSAVE_TICKS;
        }

        if (shouldPauseForOtherModules()) {
            releaseMovement();
            stopBaritone();
            return;
        }

        tickPathIdle();

        if (recovering) {
            tickRecovery();
            return;
        }

        if (state == State.Build && isOffRailLine()) {
            startRecovery();
            tickRecovery();
            return;
        }

        switch (state) {
            case Build -> tickBuild();
            case Restock -> tickRestock();
            case PauseForSupplies -> tickPauseForSupplies();
            case WaitForRestock -> tickWaitForRestock();
            case Resume -> tickResume();
            case Done -> releaseMovement();
        }
    }

    private void tickBuild() {
        if (step >= length.get()) {
            state = State.Done;
            deleteJobSnapshot();
            releaseMovement();
            info("Finished.");
            return;
        }

        if (!isCurrentBuildSliceLoaded(step)) {
            releaseMovement();
            stopBaritone();
            return;
        }        

        if (!ensureFoodHotbarReady()) return;

        stopBaritone();
        setMovement(true);

        requestType = getNeededRequest();
        if (requestType != null) {
            state = State.Restock;
            resetPlacementRetry();
            releaseMovement();
            return;
        }

        BlockPos railPos = getRailPos(step);
        BlockPos aboveRailPos = railPos.up();

        if (clearForRail(railPos)) return;
        if (clearForRail(aboveRailPos)) return;

        if (shouldPlacePowerAtStep(step)) {
            BlockPos powerPos = getPowerPos(step);
            if (clearForPower(powerPos)) return;

            if (powerType.get() == PowerType.REDSTONE_TORCH) {
                BlockPos supportPos = powerPos.down();
                if (ensureTorchSupport(supportPos)) return;
                if (!isRedstoneTorch(mc.world.getBlockState(powerPos))) {
                    if (!placeTorch(powerPos, supportPos)) return;
                }
            } else {
                if (!isRedstoneBlock(mc.world.getBlockState(powerPos))) {
                    FindItemResult power = ensurePowerItem();
                    if (!power.found()) {
                        requestType = RequestType.POWER;
                        state = State.Restock;
                        resetPlacementRetry();
                        releaseMovement();
                        return;
                    }
                    if (!ensureWorkingRange(powerPos)) return;
                    if (trackPlacementStall(powerPos)) return;
                    BlockUtils.place(powerPos, power, rotate.get(), 50);
                    return;
                }
            }
        }

        if (!isPoweredRail(mc.world.getBlockState(railPos))) {
            FindItemResult rail = ensureRailItem();
            if (!rail.found()) {
                requestType = RequestType.RAIL;
                state = State.Restock;
                resetPlacementRetry();
                releaseMovement();
                return;
            }

            if (!ensureWorkingRange(railPos)) return;
            if (trackPlacementStall(railPos)) return;
            BlockUtils.place(railPos, rail, rotate.get(), 50);
            lastPlacedRailStep = step;
            return;
        }

        resetPlacementRetry();
        lastPlacedRailStep = Math.max(lastPlacedRailStep, step);
        step++;
        saveJobSnapshot(false, false);
    }

    private void tickRestock() {
        releaseMovement();
        if (activeContainerPos == null && !waitingForShulkerPickup) stopBaritone();

        if (requestType == null) {
            RequestType neededNow = getNeededRequest();
            if (neededNow == null) {
                state = State.Build;
                return;
            }
            requestType = neededNow;
        }

        tossInventoryMushrooms();

        if (inventoryTimer > 0) return;

        if (activeContainerPos == null && canContinueBuildingFromInventory(requestType)) {
            requestType = null;
            state = State.Build;
            return;
        }

        if (activeContainerPos != null) {
            if (!waitingForShulkerPickup) {
                if (!ensureContainerPrePlacePos(activeContainerPos)) return;
            }

            handlePlacedShulkerRestock();
            return;
        }
        if (ensureRequestedItemHotbarReady(requestType) && isRestockSatisfied(requestType)) {
            requestType = null;
            state = State.Build;
            return;
        }

        activeShulkerInventorySlot = findInventoryShulkerWithLeast(requestType);
        if (activeShulkerInventorySlot == -1) {
            stopBaritone();

            if (hasRawRequestedItem(requestType) && ensureRequestedItemHotbarReady(requestType)) {
                requestType = null;
                state = State.Build;
            } else {
                state = State.PauseForSupplies;
            }
            return;
        }

        BlockPos placePos = getRestockShulkerPos();
        activeContainerPos = placePos;
        breakingPlacedContainer = false;
        waitingForShulkerPickup = false;
        shulkerInventorySnapshot = 0;
        pickupWaitTicks = 0;
        pickupFallbackIndex = -1;
        resetContainerPrePlaceRetry();

        if (!ensureContainerPrePlacePos(placePos)) return;

        BlockState stateAtPos = mc.world.getBlockState(placePos);
        if (!stateAtPos.isAir() && !Utils.isShulker(stateAtPos.getBlock().asItem())) {
            if (breakSimpleObstacle(placePos)) return;
            return;
        }

        if (!Utils.isShulker(stateAtPos.getBlock().asItem())) {
            FindItemResult shulker = moveInventorySlotToHotbar(activeShulkerInventorySlot, chestHotbarSlot.get());
            if (!shulker.found()) {
                warning("Failed to move supply shulker to hotbar safely.");
                activeContainerPos = null;
                activeShulkerInventorySlot = -1;
                state = State.PauseForSupplies;
                return;
            }

            if (!ensureContainerPlacementReady(placePos)) return;
            if (!withinPlaceRange(placePos)) return;

            BlockUtils.place(placePos, shulker, rotate.get(), 50);
            resetContainerPrePlaceRetry();
            inventoryTimer = inventoryDelay.get();
            return;
        }

        interactBlock(placePos);
        inventoryTimer = inventoryDelay.get();
    }

    private void handlePlacedShulkerRestock() {
        if (mc.currentScreen instanceof ShulkerBoxScreen screen) {
            if (screen.getScreenHandler().syncId != syncId && syncId != 0) return;

            var inv = ((ShulkerBoxScreenHandlerAccessor) screen.getScreenHandler()).meteor$getInventory();
            int moved = pullRequestedItemsFromContainer(inv, requestType);
            if (moved > 0) {
                inventoryTimer = inventoryDelay.get();
                return;
            }

            breakingPlacedContainer = true;
            mc.currentScreen.close();
            inventoryTimer = inventoryDelay.get();
            return;
        }

        BlockState blockState = mc.world.getBlockState(activeContainerPos);
        if (Utils.isShulker(blockState.getBlock().asItem())) {
            if (inventoryTimer > 0) return;

            if (breakingPlacedContainer || getRestockSlotBudget(requestType) <= 0) {
                if (!waitingForShulkerPickup) {
                    stopBaritone();
                    waitingForShulkerPickup = true;
                    shulkerInventorySnapshot = countInventoryShulkers();
                    pickupWaitTicks = SHULKER_PICKUP_WAIT_TICKS;
                    pickupFallbackIndex = -1;
                }

                if (breakSimpleObstacle(activeContainerPos)) return;
                return;
            }

            interactBlock(activeContainerPos);
            inventoryTimer = inventoryDelay.get();
            return;
        }

        if (waitingForShulkerPickup) {
            tossInventoryMushrooms();

            if (countInventoryShulkers() > shulkerInventorySnapshot) {
                stopBaritone();
                activeContainerPos = null;
                activeShulkerInventorySlot = -1;
                breakingPlacedContainer = false;
                waitingForShulkerPickup = false;
                shulkerInventorySnapshot = 0;
                pickupWaitTicks = 0;
                pickupFallbackIndex = -1;

                if (requestType != null && isRestockSatisfied(requestType)) {
                    requestType = null;
                    startRecovery();
                } else {
                    state = State.Restock;
                }
                return;
            }

            if (pickupWaitTicks > 0) {
                pickupWaitTicks--;
                return;
            }

            BlockPos loosePos = getNearestLooseShulkerPos(activeContainerPos);
            if (loosePos != null) {
                if (lastGoal == null || !lastGoal.equals(loosePos)) setBaritoneGoal(loosePos);
                return;
            }

            if (lastGoal == null || pathIdleTicks >= RECOVERY_IDLE_TIMEOUT) {
                pickupFallbackIndex = (pickupFallbackIndex + 1) % 8;
                setBaritoneGoal(getPickupFallbackPos(activeContainerPos, pickupFallbackIndex));
            }
            return;
        }

        // If it got picked up instantly, continue anyway.
        activeContainerPos = null;
        activeShulkerInventorySlot = -1;
        breakingPlacedContainer = false;

        if (requestType != null && isRestockSatisfied(requestType)) {
            requestType = null;
            startRecovery();
        } else {
            state = State.Restock;
        }
    }

    private void tickPauseForSupplies() {
        
        if (inventoryTimer > 0) return;

        if (pauseChestPos == null) pauseChestPos = getPauseChestPos();
        if (!ensureContainerPrePlacePos(pauseChestPos)) return;
        stopBaritone();

        BlockState stateAtPos = mc.world.getBlockState(pauseChestPos);
        if (stateAtPos.isAir()) {
            FindItemResult chest = ensureChestItem();
            if (!chest.found()) {
                warning("No chest found for pause sequence. Waiting without chest.");
                state = State.WaitForRestock;
                waitTimer = 0;
                return;
            }

            if (!ensureContainerPlacementReady(pauseChestPos)) return;
            if (!withinPlaceRange(pauseChestPos)) return;

            BlockUtils.place(pauseChestPos, chest, rotate.get(), 50);
            resetContainerPrePlaceRetry();
            inventoryTimer = inventoryDelay.get();
            return;
        }

        if (stateAtPos.getBlock() == Blocks.CHEST || stateAtPos.getBlock() == Blocks.TRAPPED_CHEST) {
            if (mc.currentScreen instanceof GenericContainerScreen screen && !(mc.currentScreen instanceof ShulkerBoxScreen)) {
                if (screen.getScreenHandler().syncId != syncId && syncId != 0) return;

                if (depositEmptyShulkersToChest(screen)) {
                    inventoryTimer = inventoryDelay.get();
                    return;
                }

                mc.currentScreen.close();
                inventoryTimer = inventoryDelay.get();
                state = State.WaitForRestock;
                waitTimer = 0;
                return;
            }

            interactBlock(pauseChestPos);
            inventoryTimer = inventoryDelay.get();
            return;
        }

        if (breakSimpleObstacle(pauseChestPos)) return;
    }

    private void tickWaitForRestock() {
        
        stopBaritone();

        if (!hasSupplyShulkerForAnyNeededItem()) {
            waitTimer = 0;
            return;
        }

        if (waitTimer == 0) {
            waitTimer = resumeWaitSeconds.get() * 20;
            info("Detected new supply shulkers. Waiting %d seconds before resuming.", resumeWaitSeconds.get());
        }

        waitTimer--;
        if (waitTimer > 0) return;

        state = State.Resume;
    }

    private void tickResume() {
        containerPrePlaceDistance = 1;
        containerPrePlaceStallTicks = 0;
        requestType = null;
        activeContainerPos = null;
        activeShulkerInventorySlot = -1;
        breakingPlacedContainer = false;
        waitingForShulkerPickup = false;
        shulkerInventorySnapshot = 0;
        pickupWaitTicks = 0;
        pickupFallbackIndex = -1;
        step = Math.max(0, lastPlacedRailStep + 1);
        pauseChestPos = null;
        resetPlacementRetry();
        state = State.Build;
        saveJobSnapshot(false, false);

        if (lastPlacedRailStep >= 0) startRecovery();
        else setMovement(true);
            }

    private RequestType getNeededRequest() {
        if (countItem(Items.POWERED_RAIL) == 0) return RequestType.RAIL;
        if (countItem(getPowerItem()) == 0) return RequestType.POWER;
        if (countItem(foodItem.get()) == 0) return RequestType.FOOD;
        return null;
    }

    private boolean hasSupplyShulkerForAnyNeededItem() {
        RequestType needed = getNeededRequest();
        if (needed == null) {
            return findInventoryShulkerWithLeast(RequestType.RAIL) != -1
                || findInventoryShulkerWithLeast(RequestType.POWER) != -1
                || findInventoryShulkerWithLeast(RequestType.FOOD) != -1;
        }
        return findInventoryShulkerWithLeast(needed) != -1;
    }



    private BlockPos getRailPos(int currentStep) {
        return origin.offset(forward, currentStep + 1);
    }

    private BlockPos getWalkPos(int currentStep) {
        return getRailPos(currentStep).offset(powerDir);
    }

    private BlockPos getPowerPos(int currentStep) {
        return getRailPos(currentStep).offset(powerDir);
    }

    private BlockPos getRestockShulkerPos() {
        int anchorStep = Math.max(0, lastPlacedRailStep);
        return getRailPos(anchorStep).offset(powerDir, 2);
    }

    private BlockPos getPauseChestPos() {
        return getRestockShulkerPos();
    }

    private boolean shouldPlacePowerAtStep(int currentStep) {
        return currentStep % powerSpacing.get() == 0;
    }

    private boolean clearForRail(BlockPos pos) {
        BlockState stateAtPos = mc.world.getBlockState(pos);
        if (stateAtPos.isAir()) return false;
        if (isPoweredRail(stateAtPos)) return false;
        return breakSimpleObstacle(pos);
    }

    private boolean clearForPower(BlockPos pos) {
        BlockState stateAtPos = mc.world.getBlockState(pos);
        if (powerType.get() == PowerType.REDSTONE_BLOCK) {
            if (isRedstoneBlock(stateAtPos)) return false;
        } else {
            if (isRedstoneTorch(stateAtPos)) return false;
        }

        if (stateAtPos.isAir()) return false;
        return breakSimpleObstacle(pos);
    }

    private boolean ensureTorchSupport(BlockPos supportPos) {
        BlockState supportState = mc.world.getBlockState(supportPos);
        if (!supportState.isAir()) return false;

        warning("Torch mode requires solid support at %s. None found.", supportPos.toShortString());
        toggle();
        return true;
    }

    private boolean placeTorch(BlockPos torchPos, BlockPos supportPos) {
        FindItemResult torch = ensurePowerItem();
        if (!torch.found()) {
            requestType = RequestType.POWER;
            state = State.Restock;
            resetPlacementRetry();
            return false;
        }

        if (!withinPlaceRange(torchPos)) return false;
        if (!mc.world.getBlockState(torchPos).isAir()) return false;
        if (trackPlacementStall(torchPos)) return true;

        InvUtils.swap(torch.slot(), false);
        BlockUtils.interact(
            new BlockHitResult(Vec3d.ofCenter(supportPos), Direction.UP, supportPos, false),
            Hand.MAIN_HAND,
            rotate.get()
        );
        return true;
    }

    private boolean breakSimpleObstacle(BlockPos pos) {
        if (!ensureWorkingRange(pos)) return true;

        BlockState stateAtPos = mc.world.getBlockState(pos);
        Block block = stateAtPos.getBlock();
        if (block == Blocks.AIR) return false;

        if (block == Blocks.RED_MUSHROOM || block == Blocks.BROWN_MUSHROOM) {
            FindItemResult handish = ensureFoodItem();
            if (handish.found()) InvUtils.swap(handish.slot(), false);
            BlockUtils.breakBlock(pos, true);
            return true;
        }

        BlockUtils.breakBlock(pos, true);
        return true;
    }

    private boolean isMushroom(ItemStack stack) {
        return stack.getItem() == Items.BROWN_MUSHROOM || stack.getItem() == Items.RED_MUSHROOM;
    }

    private BlockPos getContainerPrePlacePos(BlockPos pos) {
        return pos.offset(powerDir.getOpposite(), Math.max(1, containerPrePlaceDistance));
    }

    private boolean isAtContainerPrePlacePos(BlockPos pos) {
        return mc.player.getBlockPos().equals(getContainerPrePlacePos(pos));
    }

    private boolean ensureContainerPrePlacePos(BlockPos pos) {
        BlockPos goal = getContainerPrePlacePos(pos);

        if (isAtContainerPrePlacePos(pos)) {
            if (lastGoal != null) stopBaritone();
            return true;
        }

        if (lastGoal == null || !lastGoal.equals(goal)) setBaritoneGoal(goal);
        return false;
    }

    private int countRestockUsableEmptySlots() {
        int total = 0;

        // Only count the 36 normal inventory slots (hotbar + main inventory).
        // Do not count armor/offhand or any other non-restock destinations.
        for (int i = 0; i < 36; i++) {
            // While a restock shulker is placed, its hotbar slot is not truly available.
            if (activeContainerPos != null && i == chestHotbarSlot.get()) continue;

            if (mc.player.getInventory().getStack(i).isEmpty()) total++;
        }

        return total;
    }

    private boolean isRailStepLoaded(int railStep) {
        BlockPos pos = getRailPos(railStep);
        return mc.world.getChunkManager().isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4);
    }

    private int findResumeAnchorStep() {
        if (mc == null || mc.world == null || origin == null || lastPlacedRailStep < 0) return lastPlacedRailStep;

        int saved = Math.min(lastPlacedRailStep, length.get() - 1);
        int windowStart = Math.max(0, saved - (RESUME_VERIFY_WINDOW - 1));
        int furthestForwardMissing = -1;

        // Only verify the last 5 rails near the saved point.
        for (int s = saved; s >= windowStart; s--) {
            if (!isRailStepLoaded(s)) return lastPlacedRailStep;

            if (!isPoweredRail(mc.world.getBlockState(getRailPos(s)))) {
                furthestForwardMissing = s;
                break;
            }
        }

        // Last 5 rails are intact. Do not adjust anything.
        if (furthestForwardMissing == -1) return lastPlacedRailStep;

        // A rail is missing in the last 5. Walk backward until we find a valid placed rail.
        for (int s = furthestForwardMissing - 1; s >= 0; s--) {
            if (!isRailStepLoaded(s)) return lastPlacedRailStep;

            if (isPoweredRail(mc.world.getBlockState(getRailPos(s)))) return s;
        }

        // No earlier valid rail found. Restart from the beginning.
        return -1;
    }

    private void reconcileSavedRailProgress() {
        int anchor = findResumeAnchorStep();
        if (anchor == lastPlacedRailStep) return;

        warning("Adjusted saved rail progress from step %d to %d after reconnect check.", lastPlacedRailStep, anchor);

        lastPlacedRailStep = anchor;
        step = Math.max(0, anchor + 1);
    }

    private int getFillableSlotBudget() {
        return Math.max(0, countRestockUsableEmptySlots() - getRailReserveTarget());
    }

    private int getRestockSlotBudget(RequestType type) {
        int usableEmpty = countRestockUsableEmptySlots();
        if (usableEmpty <= 0) return 0;

        return switch (type) {
            case RAIL -> Math.max(0, usableEmpty - getRailReserveTarget());
            case POWER -> Math.min(usableEmpty, hasAnyItem(getPowerItem()) ? 0 : 1);
            case FOOD -> Math.min(usableEmpty, hasAnyItem(foodItem.get()) ? 0 : 1);
        };
    }

    private boolean isRestockSatisfied(RequestType type) {
        if (type == null) return true;
        return getRestockSlotBudget(type) <= 0;
    }

    private int pullRequestedItemsFromContainer(net.minecraft.inventory.Inventory inv, RequestType type) {
        int budget = getRestockSlotBudget(type);
        if (budget <= 0) return 0;

        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;
            if (!matchesRequest(stack, type)) continue;

            mc.interactionManager.clickSlot(syncId, i, 0, SlotActionType.QUICK_MOVE, mc.player);
            return 1;
        }

        return 0;
    }

    private boolean depositEmptyShulkersToChest(GenericContainerScreen screen) {
        int chestSlots = screen.getScreenHandler().getInventory().size() - mc.player.getInventory().size();
        if (chestSlots < 0) chestSlots = 27;

        for (int invSlot = 0; invSlot < mc.player.getInventory().size(); invSlot++) {
            ItemStack stack = mc.player.getInventory().getStack(invSlot);
            if (!isEmptyShulker(stack)) continue;
            if (invSlot == chestHotbarSlot.get()) continue;

            int handlerSlot = playerInvSlotToScreenSlot(invSlot, chestSlots);
            mc.interactionManager.clickSlot(screen.getScreenHandler().syncId, handlerSlot, 0, SlotActionType.QUICK_MOVE, mc.player);
            return true;
        }

        return false;
    }

    private int playerInvSlotToScreenSlot(int invSlot, int containerSlots) {
        if (invSlot >= 9) return containerSlots + (invSlot - 9);
        return containerSlots + 27 + invSlot;
    }

    private boolean matchesRequest(ItemStack stack, RequestType type) {
        return switch (type) {
            case RAIL -> stack.getItem() == Items.POWERED_RAIL;
            case POWER -> stack.getItem() == getPowerItem();
            case FOOD -> stack.getItem() == foodItem.get();
        };
    }

    private Item getRequestItem(RequestType type) {
        return switch (type) {
            case RAIL -> Items.POWERED_RAIL;
            case POWER -> getPowerItem();
            case FOOD -> foodItem.get();
        };
    }

    private boolean hasRawRequestedItem(RequestType type) {
        return countItem(getRequestItem(type)) > 0;
    }

    private boolean ensureRequestedItemHotbarReady(RequestType type) {
        return switch (type) {
            case RAIL -> ensureRailItem().found();
            case POWER -> ensurePowerItem().found();
            case FOOD -> ensureFoodHotbarReady();
        };
    }

    private boolean isReservedHotbarSlot(int slot) {
        return slot == railHotbarSlot.get()
            || slot == powerHotbarSlot.get()
            || slot == foodHotbarSlot.get();
    }

    private int findInventoryShulkerWithLeast(RequestType type) {
        int bestSlot = -1;
        int leastCount = Integer.MAX_VALUE;

        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!Utils.isShulker(stack.getItem())) continue;

            int count = countRequestedInShulker(stack, type);
            if (count <= 0) continue;

            if (count < leastCount) {
                leastCount = count;
                bestSlot = i;
            }
        }

        return bestSlot;
    }

    private int countRequestedInShulker(ItemStack shulker, RequestType type) {
        Utils.getItemsInContainerItem(shulker, CONTAINER_ITEMS);

        int total = 0;
        for (ItemStack stack : CONTAINER_ITEMS) {
            if (stack == null || stack.isEmpty()) continue;
            if (matchesRequest(stack, type)) total += stack.getCount();
        }

        return total;
    }

    private boolean isEmptyShulker(ItemStack stack) {
        if (!Utils.isShulker(stack.getItem())) return false;
        Utils.getItemsInContainerItem(stack, CONTAINER_ITEMS);
        for (ItemStack inner : CONTAINER_ITEMS) {
            if (inner != null && !inner.isEmpty()) return false;
        }
        return true;
    }

    private boolean canContinueBuildingFromInventory(RequestType type) {
        if (type == null) return true;

        return switch (type) {
            case RAIL -> hasRawRequestedItem(RequestType.RAIL) && ensureRailItem().found();
            case POWER -> hasRawRequestedItem(RequestType.POWER) && ensurePowerItem().found();
            case FOOD -> hasRawRequestedItem(RequestType.FOOD) && ensureFoodHotbarReady();
        };
    }

    private int countInventoryShulkers() {
        int total = 0;
        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.isEmpty() && Utils.isShulker(stack.getItem())) total++;
        }
        return total;
    }

    private int countItem(Item item) {
        int total = 0;
        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.getItem() == item) total += stack.getCount();
        }
        return total;
    }

    private boolean hasAnyItem(Item item) {
        return countItem(item) > 0;
    }

    private int getMissingSupportReserve() {
        int extra = 0;

        if (!hasAnyItem(getPowerItem())) extra++;
        if (!hasAnyItem(foodItem.get())) extra++;

        return extra;
    }

    private int getRailReserveTarget() {
        return reserveEmptySlots.get() + getMissingSupportReserve();
    }

    private int countEmptySlots() {
        int total = 0;
        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            if (mc.player.getInventory().getStack(i).isEmpty()) total++;
        }
        return total;
    }

    private void tossInventoryMushrooms() {
        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            if (!isMushroom(stack)) continue;
            InvUtils.drop().slot(i);
            return;
        }
    }

    private void resetContainerPrePlaceRetry() {
        containerPrePlaceDistance = 1;
        containerPrePlaceStallTicks = 0;
    }

    private boolean hasLooseShulkerItemNearby(BlockPos pos) {
        return getNearestLooseShulkerPos(pos) != null;
    }

    private BlockPos getNearestLooseShulkerPos(BlockPos center) {
        Box box = new Box(center).expand(3.0);
        ItemEntity best = null;
        double bestDist = Double.MAX_VALUE;

        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof ItemEntity itemEntity)) continue;
            if (!box.contains(itemEntity.getEntityPos())) continue;
            if (!Utils.isShulker(itemEntity.getStack().getItem())) continue;

            double dist = itemEntity.getEntityPos().squaredDistanceTo(mc.player.getEntityPos());
            if (dist < bestDist) {
                bestDist = dist;
                best = itemEntity;
            }
        }

        return best != null ? best.getBlockPos() : null;
    }

    private BlockPos getPickupFallbackPos(BlockPos center, int index) {
        return switch (index) {
            case 0 -> center.north();
            case 1 -> center.south();
            case 2 -> center.east();
            case 3 -> center.west();
            case 4 -> center.north().east();
            case 5 -> center.north().west();
            case 6 -> center.south().east();
            default -> center.south().west();
        };
    }

    private BlockPos getClosestAdjacentContainerPos(BlockPos pos) {
        BlockPos[] candidates = new BlockPos[] {
            pos.north(),
            pos.south(),
            pos.east(),
            pos.west()
        };

        BlockPos best = candidates[0];
        double bestDist = Double.MAX_VALUE;

        for (BlockPos candidate : candidates) {
            double dist = mc.player.getEntityPos().squaredDistanceTo(Vec3d.ofCenter(candidate));
            if (dist < bestDist) {
                bestDist = dist;
                best = candidate;
            }
        }

        return best;
    }

    private boolean isAdjacentToContainer(BlockPos pos) {
        BlockPos playerPos = mc.player.getBlockPos();
        if (playerPos.getY() != pos.getY()) return false;

        int dx = Math.abs(playerPos.getX() - pos.getX());
        int dz = Math.abs(playerPos.getZ() - pos.getZ());
        return dx + dz == 1;
    }

    private boolean ensureAdjacentToContainer(BlockPos pos) {
        if (isAdjacentToContainer(pos)) {
            if (lastGoal != null) stopBaritone();
            return true;
        }

        BlockPos goal = getClosestAdjacentContainerPos(pos);
        if (lastGoal == null || !lastGoal.equals(goal)) setBaritoneGoal(goal);
        return false;
    }

    private void interactBlock(BlockPos pos) {
        mc.interactionManager.interactBlock(
            mc.player,
            Hand.MAIN_HAND,
            new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false)
        );
    }

    private boolean withinPlaceRange(BlockPos pos) {
        return pos.getSquaredDistance(mc.player.getEyePos()) <= placeRange.get() * placeRange.get();
    }

    private boolean isPlayerBlockingPlacement(BlockPos pos) {
        return mc.player != null && mc.player.getBoundingBox().intersects(new Box(pos));
    }

    private boolean ensureContainerPlacementReady(BlockPos pos) {
        if (!ensureContainerPrePlacePos(pos)) return false;

        if (isPlayerBlockingPlacement(pos)) {
            containerPrePlaceStallTicks = 0;

            int newDistance = Math.min(containerPrePlaceDistance + 1, 5);
            if (newDistance != containerPrePlaceDistance) {
                containerPrePlaceDistance = newDistance;
            }

            stopBaritone();
            setBaritoneGoal(getContainerPrePlacePos(pos));
            return false;
        }

        if (BlockUtils.canPlace(pos)) {
            containerPrePlaceStallTicks = 0;
            return true;
        }

        BlockState stateAtPos = mc.world.getBlockState(pos);
        if (!stateAtPos.isAir()) {
            containerPrePlaceStallTicks = 0;
            breakSimpleObstacle(pos);
            return false;
        }

        if (++containerPrePlaceStallTicks < CONTAINER_REPOSITION_TIMEOUT_TICKS) return false;

        containerPrePlaceStallTicks = 0;
        containerPrePlaceDistance = Math.min(containerPrePlaceDistance + 1, 5);
        stopBaritone();
        setBaritoneGoal(getContainerPrePlacePos(pos));
        return false;
    }

    private Item getPowerItem() {
        return powerType.get() == PowerType.REDSTONE_BLOCK ? Items.REDSTONE_BLOCK : Items.REDSTONE_TORCH;
    }

    private boolean clearHotbarSlotSafely(int hotbarSlot) {
        ItemStack current = mc.player.getInventory().getStack(hotbarSlot);
        if (current.isEmpty()) return true;

        if (isMushroom(current)) {
            InvUtils.drop().slot(hotbarSlot);
            return true;
        }

        int empty = InvUtils.findEmpty().slot();
        if (empty == -1) return false;

        InvUtils.move().from(hotbarSlot).to(empty);
        InvUtils.dropHand();
        return true;
    }

    private boolean ensureFoodHotbarReady() {
        ItemStack current = mc.player.getInventory().getStack(foodHotbarSlot.get());
        if (current.getItem() == foodItem.get()) return true;

        FindItemResult found = InvUtils.find(stack -> stack.getItem() == foodItem.get());
        if (!found.found()) {
            requestType = RequestType.FOOD;
            state = State.Restock;
            releaseMovement();
            return false;
        }

        if (found.slot() != foodHotbarSlot.get()) {
            InvUtils.move().from(found.slot()).toHotbar(foodHotbarSlot.get());
            InvUtils.dropHand();
        }

        return true;
    }
    private FindItemResult ensureRailItem() {
        return ensureHotbarItem(stack -> stack.getItem() == Items.POWERED_RAIL, railHotbarSlot.get());
    }

    private FindItemResult ensurePowerItem() {
        Item target = getPowerItem();
        return ensureHotbarItem(stack -> stack.getItem() == target, powerHotbarSlot.get());
    }

    private FindItemResult ensureFoodItem() {
        Item target = foodItem.get();
        return ensureHotbarItem(stack -> stack.getItem() == target, foodHotbarSlot.get());
    }

    private FindItemResult ensureChestItem() {
        return ensureHotbarItem(stack -> stack.getItem() == Items.CHEST, chestHotbarSlot.get());
    }

    private FindItemResult moveInventorySlotToHotbar(int fromSlot, int hotbarSlot) {
        ItemStack stack = mc.player.getInventory().getStack(fromSlot);
        if (stack.isEmpty()) return new FindItemResult(-1, 0);

        if (fromSlot != hotbarSlot) {
            InvUtils.move().from(fromSlot).toHotbar(hotbarSlot);
            InvUtils.dropHand();
        }

        return getHotbarResult(hotbarSlot);
    }

    private FindItemResult ensureHotbarItem(Predicate<ItemStack> predicate, int preferredSlot) {
        ItemStack current = mc.player.getInventory().getStack(preferredSlot);
        if (predicate.test(current)) return getHotbarResult(preferredSlot);

        FindItemResult found = InvUtils.find(predicate);
        if (!found.found()) return found;

        if (found.slot() != preferredSlot) {
            InvUtils.move().from(found.slot()).toHotbar(preferredSlot);
            InvUtils.dropHand();
        }

        return getHotbarResult(preferredSlot);
    }

    private boolean ensureWorkingRange(BlockPos pos) {
        return withinPlaceRange(pos);
    }

    private void resetPlacementRetry() {
        placeAttemptTicks = 0;
        lastAttemptedStep = -1;
        lastAttemptedPos = null;
    }

    private boolean trackPlacementStall(BlockPos pos) {
        if (lastAttemptedStep != step || lastAttemptedPos == null || !lastAttemptedPos.equals(pos)) {
            lastAttemptedStep = step;
            lastAttemptedPos = pos.toImmutable();
            placeAttemptTicks = 0;
            return false;
        }

        if (++placeAttemptTicks < PLACEMENT_STALL_TIMEOUT_TICKS) return false;

        warning("Placement stalled at step %d. Starting recovery.", step);
        resetPlacementRetry();
        startRecovery();
        return true;
    }

    private FindItemResult getHotbarResult(int slot) {
        ItemStack stack = mc.player.getInventory().getStack(slot);
        return new FindItemResult(slot, stack.isEmpty() ? 0 : stack.getCount());
    }

    private boolean isPoweredRail(BlockState state) {
        return state.getBlock() == Blocks.POWERED_RAIL;
    }

    private boolean isRedstoneBlock(BlockState state) {
        return state.getBlock() == Blocks.REDSTONE_BLOCK;
    }

    private boolean isRedstoneTorch(BlockState state) {
        return state.getBlock() instanceof RedstoneTorchBlock || state.getBlock() == Blocks.REDSTONE_WALL_TORCH;
    }

    private boolean shouldPauseForOtherModules() {
        return Modules.get().get(AutoEat.class).eating
            || Modules.get().get(AutoGap.class).isEating()
            || Modules.get().get(KillAura.class).attacking;
    }

    private void setMovement(boolean active) {
        if (mc.options == null) return;
        mc.options.forwardKey.setPressed(active);
        mc.options.sprintKey.setPressed(active);
        if (mc.player != null) mc.player.setSprinting(active);
    }

    private void releaseMovement() {
        setMovement(false);
    }
}

