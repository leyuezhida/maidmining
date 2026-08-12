package com.leyue.maidmining.mining;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.util.TaskEquipUtil;
import com.leyue.maidmining.MaidMiningMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BehaviorUtils;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 挖矿核心行为：SEARCH -> DIG(逐格挖隧道) -> MINE。
 * 以女仆为中心搜索 3 区块半径内（含地下）的矿石，向下挖竖井、水平挖巷道、
 * 必要时搭方块向上攀爬，稳定抵达每一个目标并挖掉。
 * <p>
 * 放弃 MOVE(原生寻路) 的原因：Minecraft Pathfinder 在地底矿洞场景下几乎不可用
 * （createPath/moveTo 对石头包围的矿石位置反复返回 null），
 * 逐格挖隧道 (DIG) 是最稳定可靠的接近方式。
 */
public class MiningTunnelBehavior extends Behavior<EntityMaid> {
    private enum State { SEARCH, DIG, MINE }

    private final MiningTunnelFinder finder = new MiningTunnelFinder();

    private State state = State.SEARCH;
    private BlockPos target;
    private int timer;
    private int digTimer;
    private int stuckCount;
    private final Set<BlockPos> failedTargets = new HashSet<>();
    private final Map<BlockPos, Integer> failCounts = new HashMap<>();
    private BlockPos lastMined;
    private int climbFlip;

    public MiningTunnelBehavior() {
        super(Map.<MemoryModuleType<?>, MemoryStatus>of(), Integer.MAX_VALUE);
    }

    @Override
    protected boolean canStillUse(ServerLevel level, EntityMaid maid, long gameTime) {
        return true;
    }

    @Override
    protected void start(ServerLevel level, EntityMaid maid, long gameTime) {
        failedTargets.clear();
        failCounts.clear();
        toSearch(maid);
    }

    @Override
    protected void stop(ServerLevel level, EntityMaid maid, long gameTime) {
        maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        maid.getNavigation().stop();
    }

    @Override
    protected void tick(ServerLevel level, EntityMaid maid, long gameTime) {
        timer++;
        if (!MiningValidator.isPickaxe(maid.getMainHandItem()) && timer % 20 == 0) {
            equipPickaxe(maid);
        }
        if (timer % 10 == 0) {
            pickupDrops(level, maid);
        }
        switch (state) {
            case SEARCH -> searching(level, maid);
            case DIG -> digging(level, maid);
            case MINE -> mining(level, maid);
        }
    }

    // ========== SEARCH ==========

    private void searching(ServerLevel level, EntityMaid maid) {
        ItemStack tool = maid.getMainHandItem();
        if (!MiningValidator.isPickaxe(tool)) {
            if (timer % 100 == 0) {
                maid.sendSystemMessage(Component.literal("I need a pickaxe to mine!"));
                MaidMiningMod.LOGGER.info("[MaidMining] No pickaxe maid={}", maid.getId());
            }
            return;
        }
        Optional<BlockPos> found = finder.findNearestOre(level, maid, tool, maid.getOffhandItem(), failedTargets);
        if (found.isPresent()) {
            target = found.get();
            // Skip ores near bedrock: bedrock is unbreakable, the maid can never reach these
            if (MiningValidator.isNearBedrock(level, target)) {
                failedTargets.add(target);
                MaidMiningMod.LOGGER.info("[MaidMining] Skipped bedrock-near ore ({},{},{}) maid={}",
                        target.getX(), target.getY(), target.getZ(), maid.getId());
                target = null;
                return;
            }
            state = State.DIG;
            digTimer = 0;
            String tgtName = MiningValidator.targetOreName(maid.getOffhandItem());
            MaidMiningMod.LOGGER.info("[MaidMining] Locked ore ({},{},{}) target={} maid={}",
                    target.getX(), target.getY(), target.getZ(),
                    tgtName == null ? "all" : tgtName, maid.getId());
        }
    }

    // ========== DIG ==========

    private void digging(ServerLevel level, EntityMaid maid) {
        // Target validation
        if (target == null || !MiningValidator.isOreBreakable(level, target,
                maid.getMainHandItem(), maid.getOffhandItem())) {
            if (target != null) {
                BlockState bs = level.getBlockState(target);
                MaidMiningMod.LOGGER.info("[MaidMining] Target invalid ({},{},{}) block={} breakable={} maid={}",
                        target.getX(), target.getY(), target.getZ(),
                        bs.isAir() ? "air" : ForgeRegistries.BLOCKS.getKey(bs.getBlock()),
                        MiningValidator.isOreBreakable(level, target, maid.getMainHandItem(), maid.getOffhandItem()),
                        maid.getId());
            }
            toSearch(maid);
            return;
        }
        if (isAdjacent(maid, target)) {
            state = State.MINE;
            return;
        }
        // Throttle
        digTimer++;
        if (digTimer < MiningConfig.DIG_INTERVAL_TICKS) {
            return;
        }
        digTimer = 0;

        BlockPos feet = maid.blockPosition();
        int dx = Integer.signum(target.getX() - feet.getX());
        int dy = target.getY() - feet.getY();
        int dz = Integer.signum(target.getZ() - feet.getZ());
        int hDist = Math.abs(target.getX() - feet.getX()) + Math.abs(target.getZ() - feet.getZ());

        // Anti-diagonal: in narrow tunnels, moving both X and Z at once (diagonal)
        // causes the maid to get stuck on corners. Align one axis at a time.
        if (dx != 0 && dz != 0) {
            int distX = Math.abs(target.getX() - feet.getX());
            int distZ = Math.abs(target.getZ() - feet.getZ());
            if (distX >= distZ) { dz = 0; } else { dx = 0; }
            hDist = Math.abs(target.getX() - feet.getX()) + Math.abs(target.getZ() - feet.getZ());
        }

        // --- Phase 1: adjust height ---

        if (dy >= 2) {
            if (tryClimb(level, maid)) return;
            if (hDist > 0) {
                advanceHorizontal(level, maid, feet.offset(dx, 0, dz), feet.offset(dx, 1, dz));
            } else {
                toSearch(maid);
            }
            return;
        }

        if (dy <= -2) {
            BlockPos below = feet.below();
            if (breakBlock(level, maid, below)) { stuckCount = 0; return; }
            if (isPassable(level, below)) {
                BehaviorUtils.setWalkAndLookTargetMemories(maid, below, MiningConfig.MOVE_SPEED, 0);
                return;
            }
            if (++stuckCount > MiningConfig.MAX_STUCK_COUNT) {
                if (tryClimb(level, maid)) { stuckCount = 0; return; }
                if (hDist > 0) {
                    advanceHorizontal(level, maid, feet.offset(dx, 0, dz), feet.offset(dx, 1, dz));
                    return;
                }
                failedTargets.add(target);
                MaidMiningMod.LOGGER.info("[MaidMining] Blocked ({},{},{}) maid={}",
                        target.getX(), target.getY(), target.getZ(), maid.getId());
                toSearch(maid);
                return;
            }
            return;
        }

        // --- Phase 2: horizontal approach (|dy| <= 1) ---

        // Going up: exclusively use tryClimb; never fall through to horizontal
        // (horizontal approach would destroy scaffold blocks and can't help go up)
        if (dy > 0 && hDist > 0) {
            if (tryClimb(level, maid)) return;
            return; // climb failed, retry next tick
        }

        if (hDist > 0) {
            BlockPos next = feet.offset(dx, 0, dz);

            // 1) Clear front block (foot level) — must succeed before advancing
            boolean frontOk = isPassable(level, next) || breakBlock(level, maid, next);
            if (!frontOk) {
                if (++stuckCount > MiningConfig.MAX_STUCK_COUNT) {
                    if (tryClimb(level, maid)) { stuckCount = 0; return; }
                    failedTargets.add(target);
                    MaidMiningMod.LOGGER.info("[MaidMining] Blocked ({},{},{}) maid={}",
                            target.getX(), target.getY(), target.getZ(), maid.getId());
                    toSearch(maid);
                }
                return; // wait for next tick to retry
            }

            // 2) Cliff check: if front is a drop, dig down instead
            if (isPassable(level, next) && isPassable(level, next.below())) {
                breakBlock(level, maid, feet.below());
                return;
            }

            // 3) Clear head blocks: priority y+1 first, then y+2 (one per tick)
            BlockPos head = feet.offset(dx, 1, dz);
            BlockPos head2 = feet.offset(dx, 2, dz);
            if (!head.equals(next) && !isPassable(level, head)) {
                if (!breakBlock(level, maid, head)) {
                    if (++stuckCount > MiningConfig.MAX_STUCK_COUNT) {
                        if (tryClimb(level, maid)) { stuckCount = 0; return; }
                        failedTargets.add(target);
                        MaidMiningMod.LOGGER.info("[MaidMining] Blocked ({},{},{}) maid={}",
                                target.getX(), target.getY(), target.getZ(), maid.getId());
                        toSearch(maid);
                    }
                }
                return;
            }
            if (!head2.equals(next) && !isPassable(level, head2)) {
                if (!breakBlock(level, maid, head2)) {
                    if (++stuckCount > MiningConfig.MAX_STUCK_COUNT) {
                        if (tryClimb(level, maid)) { stuckCount = 0; return; }
                        failedTargets.add(target);
                        MaidMiningMod.LOGGER.info("[MaidMining] Blocked ({},{},{}) maid={}",
                                target.getX(), target.getY(), target.getZ(), maid.getId());
                        toSearch(maid);
                    }
                }
                return;
            }

            stuckCount = 0;
            BehaviorUtils.setWalkAndLookTargetMemories(maid, next, MiningConfig.MOVE_SPEED, 0);
            return;
        }

        if (dy > 0) {
            if (tryClimb(level, maid)) return;
            toSearch(maid);
            return;
        }

        breakBlock(level, maid, feet.below());
    }

    private void advanceHorizontal(ServerLevel level, EntityMaid maid, BlockPos next, BlockPos head) {
        if (breakBlock(level, maid, next)) stuckCount = 0;
        if (!head.equals(next)) breakBlock(level, maid, head);
        if (isPassable(level, next)) {
            BehaviorUtils.setWalkAndLookTargetMemories(maid, next, MiningConfig.MOVE_SPEED, 0);
        }
    }

    // ========== MINE ==========

    private void mining(ServerLevel level, EntityMaid maid) {
        if (target == null) { toSearch(maid); return; }
        ItemStack tool = maid.getMainHandItem();
        if (MiningValidator.isOreBreakable(level, target, tool) && isAdjacent(maid, target)) {
            if (maid.destroyBlock(target)) {
                tool.hurtAndBreak(1, maid, item -> {});
                maid.swing(InteractionHand.MAIN_HAND);
                lastMined = target;
                MaidMiningMod.LOGGER.info("[MaidMining] Mined ({},{},{}) maid={}",
                        target.getX(), target.getY(), target.getZ(), maid.getId());
            } else {
                MaidMiningMod.LOGGER.info("[MaidMining] Destroy fail ({},{},{}) block={} adj={} maid={}",
                        target.getX(), target.getY(), target.getZ(),
                        level.getBlockState(target).isAir() ? "air" : ForgeRegistries.BLOCKS.getKey(level.getBlockState(target).getBlock()),
                        isAdjacent(maid, target), maid.getId());
            }
        }
        toSearch(maid);
    }

    // ========== HELPERS ==========

    /** Whether the maid can pass through this block (air or fluid, no solid collision). */
    private static boolean isPassable(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.isAir() || !state.getFluidState().isEmpty();
    }

    private boolean breakBlock(ServerLevel level, EntityMaid maid, BlockPos pos) {
        ItemStack tool = maid.getMainHandItem();
        if (!MiningValidator.isDiggable(level, pos, tool)) return false;
        if (!maid.canDestroyBlock(pos)) return false;
        if (maid.destroyBlock(pos)) {
            tool.hurtAndBreak(1, maid, item -> {});
            maid.swing(InteractionHand.MAIN_HAND);
            return true;
        }
        return false;
    }

    private boolean isAdjacent(EntityMaid maid, BlockPos pos) {
        BlockPos feet = maid.blockPosition();
        return Math.abs(pos.getX() - feet.getX()) <= MiningConfig.ADJACENT_DIST
                && Math.abs(pos.getY() - feet.getY()) <= MiningConfig.ADJACENT_DIST
                && Math.abs(pos.getZ() - feet.getZ()) <= MiningConfig.ADJACENT_DIST;
    }

    /** Climb upward: side-step or tower-up. NEVER place scaffold in mid-air
     *  at a position the maid can't step onto — that causes a destroy/rebuild loop. */
    private boolean tryClimb(ServerLevel level, EntityMaid maid) {
        BlockPos feet = maid.blockPosition();
        int dx = Integer.signum(target.getX() - feet.getX());
        int dz = Integer.signum(target.getZ() - feet.getZ());
        if (dx == 0 && dz == 0) {
            // Straight up: tower mode — clear head, place block UNDER feet if needed
            BlockPos above1 = feet.above();
            BlockPos above2 = above1.above();
            BlockPos above3 = above2.above();
            if (!isPassable(level, above3)) return breakBlock(level, maid, above3);
            if (!isPassable(level, above2)) return breakBlock(level, maid, above2);
            if (!isPassable(level, above1)) return breakBlock(level, maid, above1);
            // Tower up: place block directly under feet (only if air, not fluid)
            if (level.getBlockState(feet.below()).isAir()) {
                boolean placed = placeStepBlock(level, maid, feet.below());
                if (placed && timer % 20 == 0) {
                    MaidMiningMod.LOGGER.info("[MaidMining] Tower block placed ({}) maid={}",
                            feet.below(), maid.getId());
                }
                return placed;
            }
            // On solid ground — step straight up (jump control alone can't do this)
            maid.setPos(maid.getX(), above1.getY(), maid.getZ());
            return true;
        }
        // Sideways: anti-diagonal, clear path, place scaffold if needed, teleport onto it
        if (dx != 0 && dz != 0) {
            if (Math.abs(target.getX() - feet.getX()) >= Math.abs(target.getZ() - feet.getZ())) dz = 0;
            else dx = 0;
        }
        BlockPos stepPos = feet.offset(dx, 0, dz);
        BlockPos dest = stepPos.above();
        BlockPos destHead = dest.above();
        BlockPos destHead2 = destHead.above();

        // Clear 3-high head space first
        if (!isPassable(level, destHead2)) return breakBlock(level, maid, destHead2);
        if (!isPassable(level, destHead)) return breakBlock(level, maid, destHead);
        if (!isPassable(level, dest)) return breakBlock(level, maid, dest);

        // Clear stepPos only if it's a full wall (both stepPos and above are solid)
        if (!isPassable(level, stepPos)
                && !isPassable(level, stepPos.above())) {
            return breakBlock(level, maid, stepPos);
        }

        // Place scaffold if gap, then teleport onto it (bypasses unreliable jump pathfinding)
        if (level.getBlockState(stepPos).isAir()) {
            boolean placed = placeStepBlock(level, maid, stepPos);
            if (placed) {
                maid.setPos(stepPos.getX() + 0.5, dest.getY(), stepPos.getZ() + 0.5);
            }
            return placed;
        }
        // stepPos is solid (stepping stone) — walk up
        BehaviorUtils.setWalkAndLookTargetMemories(maid, dest, MiningConfig.MOVE_SPEED, 0);
        return true;
    }

    private void pickupDrops(ServerLevel level, EntityMaid maid) {
        AABB area = maid.getBoundingBox().inflate(1.5);
        List<ItemEntity> drops = level.getEntitiesOfClass(ItemEntity.class, area, e -> !e.isRemoved());
        for (ItemEntity drop : drops) {
            ItemStack stack = drop.getItem();
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) continue;
            ItemStackHandler inv = maid.getMaidInv();
            int left = stack.getCount();
            for (int i = 0; i < inv.getSlots() && left > 0; i++) {
                ItemStack slot = inv.getStackInSlot(i);
                if (slot.isEmpty()) {
                    ItemStack put = stack.copy(); put.setCount(left);
                    inv.setStackInSlot(i, put); left = 0;
                } else if (ItemStack.isSameItemSameTags(slot, stack) && slot.getCount() < slot.getMaxStackSize()) {
                    int space = slot.getMaxStackSize() - slot.getCount();
                    int move = Math.min(space, left);
                    slot.grow(move); left -= move;
                }
            }
            if (left == 0) drop.discard();
        }
    }

    private boolean placeStepBlock(ServerLevel level, EntityMaid maid, BlockPos pos) {
        ItemStackHandler inv = maid.getMaidInv();
        BlockState placeState = null;
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (!s.isEmpty() && s.getItem() instanceof BlockItem bi
                    && bi.getBlock().defaultBlockState().isSolid()) {
                inv.extractItem(i, 1, false);
                placeState = bi.getBlock().defaultBlockState();
                break;
            }
        }
        if (placeState == null) return false;

        // Collision check: prevent maid from getting trapped in her own placed block
        // (critical fix — placing a solid block at her feet or inside her hitbox = stuck)
        VoxelShape shape = placeState.getCollisionShape(level, pos);
        if (!shape.isEmpty()) {
            AABB blockBox = shape.bounds().move(pos);
            if (maid.getBoundingBox().intersects(blockBox)) {
                return false; // block would collide with the maid, skip this spot
            }
        }

        level.setBlock(pos, placeState, 3);
        maid.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    private void toSearch(EntityMaid maid) {
        if (target != null) {
            if (target.equals(lastMined)) {
                failCounts.remove(target);
            } else {
                int count = failCounts.getOrDefault(target, 0) + 1;
                if (count >= MiningConfig.MAX_TARGET_RETRIES) {
                    failedTargets.add(target);
                    failCounts.remove(target);
                    MaidMiningMod.LOGGER.info("[MaidMining] Blacklisted ({},{},{}) maid={}",
                            target.getX(), target.getY(), target.getZ(), maid.getId());
                } else {
                    failCounts.put(target, count);
                }
            }
        }
        state = State.SEARCH;
        timer = 0;
        target = null;
        lastMined = null;
        digTimer = 0;
        stuckCount = 0;
        climbFlip = 0;
        finder.reset();
        maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        maid.getNavigation().stop();
    }

    private void equipPickaxe(EntityMaid maid) {
        if (TaskEquipUtil.tryEquipFromBackpack(maid, stack -> stack.is(ItemTags.PICKAXES))) return;
        ItemStackHandler inv = maid.getMaidInv();
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (stack.is(ItemTags.PICKAXES)) {
                ItemStack picked = inv.extractItem(i, stack.getCount(), false);
                if (!maid.getMainHandItem().isEmpty()) inv.setStackInSlot(i, maid.getMainHandItem());
                maid.setItemInHand(InteractionHand.MAIN_HAND, picked);
                return;
            }
        }
    }
}
