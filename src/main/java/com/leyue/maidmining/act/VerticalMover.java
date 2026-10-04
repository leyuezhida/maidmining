package com.leyue.maidmining.act;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.leyue.maidmining.MaidMiningMod;
import com.leyue.maidmining.cfg.MiningConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * 垂直移动原语（MM-304 / MM-305）。
 * <p>
 * <b>语义严格对齐"玩家怎么上去"</b>，而不是"把女仆瞬移上去"。这来之不易：
 * <ul>
 *   <li><b>净高按碰撞箱推导</b>（{@code ceil(bbHeight)}，通常 2），不硬编码 3。
 *       1×3 的矿道就是硬编码 3 的产物，每前进一格多挖一格，白白多挖 1/3；</li>
 *   <li><b>起跳处局部加高</b>（{@link MiningConfig#jumpHeadroom()}）。女仆高 1.5 格，
 *       在 1×2 隧道里天花板会把跳跃截断在 0.5 格以内，而台阶有 1.0 格 ——
 *       <b>物理上永远迈不上去</b>。所以只在起跳那一格多挖 1 格挖出站位凹坑，
 *       玩家在 2 格高巷道里上台阶时也是这么干的；</li>
 *   <li><b>跳跃优先，传送只做兜底</b>。上行由 {@link #drive} 每 tick 直接驱动
 *       {@code MoveControl}，因为 vanilla {@code MoveToTargetSink} 靠
 *       {@code navigation.createPath()} 造路，拿不到路径时会直接抹掉 {@code WALK_TARGET} 停下
 *       ——这就是"上行只蹦不走"的真身；</li>
 *   <li><b>兜底传送必须先过碰撞校验</b>，且传送后 {@code resetFallDistance()}。
 *       {@code setPos} 不做碰撞解算也不清落差（已核实），传送后可能被判定窒息，
 *       且累积的落差会在下次落地一次性结算成坠落伤害。</li>
 * </ul>
 */
public final class VerticalMover {

    private BlockPos climbDest;
    private int driveTicks;
    private double driveStartY;

    /** 正在执行的上行目标；非 null 时每 tick 直接驱动移动。 */
    public BlockPos climbDest() {
        return climbDest;
    }

    public boolean isClimbing() {
        return climbDest != null;
    }

    /** 隧道净高：由女仆碰撞箱推导，禁止硬编码（MM-304）。 */
    public static int requiredHeight(EntityMaid maid) {
        return Math.max(1, Mth.ceil(maid.getBbHeight()));
    }

    /** 女仆能不能<b>真的站进</b>这个格子：用包围盒与世界碰撞求交。 */
    public static boolean canStandAt(ServerLevel level, EntityMaid maid, BlockPos feet) {
        AABB box = maid.getBoundingBox().move(
                feet.getX() + 0.5D - maid.getX(),
                feet.getY() - maid.getY(),
                feet.getZ() + 0.5D - maid.getZ());
        return !level.getBlockCollisions(maid, box).iterator().hasNext();
    }

    /** 该格是否可通行：空气或允许进入的流体（MM-401：岩浆不再视为可通行）。 */
    public static boolean isPassable(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return true;
        }
        if (state.getFluidState().isEmpty()) {
            return false;
        }
        // 有流体：按配置决定是否可进入
        var fluid = state.getFluidState();
        if (fluid.is(net.minecraft.tags.FluidTags.LAVA) && !MiningConfig.allowLava()) {
            return false;
        }
        return fluid.is(net.minecraft.tags.FluidTags.WATER) ? MiningConfig.allowWater() : false;
    }

    /** 开始驱动一次上行（不校验，调用方需先确认 dest 可站）。 */
    public void begin(BlockPos dest, EntityMaid maid) {
        climbDest = dest.immutable();
        driveTicks = 0;
        driveStartY = maid.getY();
        maid.getJumpControl().jump();
    }

    /** 已驱动了多少 tick（诊断用）。 */
    public int driveTicks() {
        return driveTicks;
    }

    /** 结束本轮上行驱动。 */
    public void end() {
        climbDest = null;
        driveTicks = 0;
        driveStartY = 0.0D;
    }

    /**
     * 每 tick 驱动一次短距离上行/水平接管（绕过寻路）。
     * <p>
     * 到位判定通过后即 {@link #end()} 返回 {@code true}。
     *
     * @param stallTakeover true 表示这是一次"发呆接管"（水平移动），此时不该起跳
     */
    public boolean drive(ServerLevel level, EntityMaid maid, boolean stallTakeover) {
        if (climbDest == null) {
            return true;
        }
        driveTicks++;

        double dx = maid.getX() - (climbDest.getX() + 0.5D);
        double dz = maid.getZ() - (climbDest.getZ() + 0.5D);
        if (maid.blockPosition().getY() >= climbDest.getY() && dx * dx + dz * dz < 0.25D) {
            end();
            return true;
        }

        // 别让 MoveToTargetSink 掺和（它会因为拿不到路径而清掉走位目标、停下导航）
        maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        maid.getNavigation().stop();
        maid.getMoveControl().setWantedPosition(
                climbDest.getX() + 0.5D, climbDest.getY(), climbDest.getZ() + 0.5D,
                MiningConfig.moveSpeed());

        // 只有目标确实更高时才起跳（水平接管时不该蹦跶）。
        // MoveControl 自带的自动跳跃在"恰好 1 格外"时不成立，所以必须自己发。
        boolean needUp = climbDest.getY() > maid.blockPosition().getY();
        if (!stallTakeover && needUp && maid.getY() - driveStartY < 0.4D && maid.onGround()) {
            maid.getJumpControl().jump();
        }

        // 超时兜底：校验碰撞后传送（最后手段，正常走不到这里）
        if (driveTicks > MiningConfig.climbDriveTimeoutTicks()) {
            BlockPos dest = climbDest;
            end();
            if (!MiningConfig.allowTeleportFallback()) {
                MaidMiningMod.LOGGER.info("[MaidMining] Climb timeout, teleport fallback disabled maid={}",
                        maid.getId());
                return true;
            }
            if (canStandAt(level, maid, dest)) {
                maid.setPos(dest.getX() + 0.5D, dest.getY(), dest.getZ() + 0.5D);
                // setPos 不清 fallDistance，必须显式重置，否则累积的落差会在下次落地一次性结算
                maid.resetFallDistance();
                MaidMiningMod.LOGGER.info("[MaidMining] Teleport fallback ({},{},{}) maid={}",
                        dest.getX(), dest.getY(), dest.getZ(), maid.getId());
            } else {
                // 静默失败会让"卡住"无从定位，这里必须留痕
                MaidMiningMod.LOGGER.info("[MaidMining] Climb timeout, dest not standable ({},{},{}) maid={}",
                        dest.getX(), dest.getY(), dest.getZ(), maid.getId());
            }
        }
        return false;
    }

    /**
     * 上行一格：净空 → 起跳净空 → 碰撞校验 → 交给逐 tick 驱动。
     * <p>
     * <b>「起跳净空」是 §1.5-B 的真正根因，也是最容易被"优化掉"的一步</b>：
     * 女仆碰撞箱高 1.5，在 1×2 隧道里身体占 {@code [y, y+1.5]}、天花板在 {@code y+2}，
     * 起跳初速 0.42 却在 0.5 格处被天花板清零 ⇒ <b>2 格高巷道里 1 格台阶物理上迈不上去</b>
     * （玩家在 2 格高走廊里也跳不起来）。所以起跳那一格要多挖 1 格挖出站位凹坑，
     * 玩家上台阶时也是这么干的。<b>注意只挖这一格，不是把整条矿道加高成 1×3。</b>
     *
     * @param dest  目标落脚格
     * @param avoid 需要避开的物品（副手过滤物，不该被拿去垫脚）
     * @return 本轮是否成功安排了上行
     */
    public boolean stepUp(ServerLevel level, EntityMaid maid, BlockPos feet, BlockPos dest,
                          int height, ItemStack avoid) {
        // 1) 净空：dest .. dest+height-1（每 tick 只处理一格，避免一次性大改世界）
        for (int i = 0; i < height; i++) {
            BlockPos need = dest.above(i);
            if (isPassable(level, need)) {
                continue;
            }
            DigExecutor.dig(level, maid, need);
            // 无论成功还是失败都返回 false：挖完这一格（或碰到不可破坏的墙）下周期重试，
            // 由调用方累计卡住次数后放弃目标
            return false;
        }

        // 2) 起跳净空（玩家上台阶时也是这么干的）
        BlockPos jumpSpace = feet.above(height + MiningConfig.jumpHeadroom() - 1);
        if (!isPassable(level, jumpSpace)) {
            DigExecutor.dig(level, maid, jumpSpace);
            return false;
        }

        // 3) 碰撞校验：女仆能否真的站进 dest
        if (!canStandAt(level, maid, dest)) {
            end();
            return false;
        }

        // 4) 交给逐 tick 驱动（绕开寻路，见 drive 的说明）
        if (!isClimbing()) {
            begin(dest, maid);
        }
        return true;
    }

    /**
     * 横向台阶：先保证前方落脚面存在（必要时垫一块），再委托 {@link #stepUp}。
     * <p>
     * <b>关键时序</b>：垫块只在站在地面上时做。空中女仆的碰撞箱高于该格，
     * 相交检查会放行，结果是往自己正要落进去的格子里砌一块方块，把自己挡住
     * ——这是实机反馈过的 bug，不是理论风险。
     */
    public boolean stepUpHorizontal(ServerLevel level, EntityMaid maid, BlockPos feet, BlockPos stepPos,
                                    int height, ItemStack avoid) {
        if (isPassable(level, stepPos)) {
            if (!maid.onGround()) {
                end();
                return false;
            }
            if (!Scaffolder.placeStepBlock(level, maid, stepPos, avoid)) {
                return false;
            }
        }
        return stepUp(level, maid, feet, stepPos.above(), height, avoid);
    }

    /**
     * 原地起塔（目标在正上方、脚下就是唯一通路）。
     * <p>
     * 与玩家「垫柱」产生同样的世界状态，但去掉了「跳跃中放方块」的时序依赖：
     * 先校验并抬升到 {@code dest}，再立刻把刚空出来的 {@code feet} 补上 —— 此时女仆已不在该格，
     * 碰撞校验必然通过，下一 tick 她就有支撑、不会掉回原点。
     * <p>
     * 旧的 setPos-only 实现因为不补方块，会被重力直接拉回原点（净进度为 0），
     * 这也是"向上卡住"的成因之一。
     */
    public boolean towerUp(ServerLevel level, EntityMaid maid, BlockPos feet, BlockPos dest,
                            ItemStack avoid) {
        if (!canStandAt(level, maid, dest)) {
            end();
            return false;
        }
        if (!isPassable(level, feet)) {
            // 应该不会发生（女仆正站在这里）；状态异常时不要盲目改世界
            end();
            return false;
        }
        if (!MiningConfig.scaffoldEnabled()) {
            return false;
        }
        maid.setPos(dest.getX() + 0.5D, dest.getY(), dest.getZ() + 0.5D);
        maid.resetFallDistance();
        boolean placed = Scaffolder.placeStepBlock(level, maid, feet, avoid);
        end();
        return placed;
    }
}
