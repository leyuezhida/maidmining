package com.leyue.maidmining.mining;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.util.TaskEquipUtil;
import com.leyue.maidmining.MaidMiningMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BehaviorUtils;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
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
 * 挖矿核心行为：SEARCH -&gt; DIG(逐格挖隧道) -&gt; MINE。
 * 以女仆为中心搜索 3 区块半径内（含地下）的矿石，向下挖竖井、水平挖巷道、
 * 必要时搭方块向上攀爬，稳定抵达每一个目标并挖掉。
 * <p>
 * 放弃 MOVE(原生寻路) 的原因：Minecraft Pathfinder 在地底矿洞场景下几乎不可用
 * （createPath/moveTo 对石头包围的矿石位置反复返回 null），
 * 逐格挖隧道 (DIG) 是最稳定可靠的接近方式。
 * <p>
 * <b>本版本针对两个历史妥协做了根治（见 OPTIMIZATION.md §1.5）：</b>
 * <ul>
 *   <li><b>A. 基岩失败分类</b>：破坏结果由 boolean 改为 {@link BreakResult}。旧实现把
 *       「永不可破 / 流体 / 被保护 / 暂时失败」压成一个 false，只能盲目重试（对基岩空转
 *       约 120 tick），再叠加错误的恢复动作（向上传送）就形成了「贴着基岩无限上爬」的死循环。
 *       现在 {@link BreakResult#UNBREAKABLE}/{@link BreakResult#PROTECTED} 会立刻放弃目标。</li>
 *   <li><b>B. 隧道净高与上行原语</b>：净高改为按女仆碰撞箱推导（{@link #requiredHeight}），
 *       矿道从 1×3 恢复为 1×2；上行用 {@link #stepUp} 原语——先真实跳跃、传送只做兜底，
 *       且通行判定用 {@code level.getBlockCollisions} 的包围盒校验，而不是「上面三格都是空气」。</li>
 * </ul>
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
    /** 本会话确认「永远挖不动」的方块（基岩等），规划时当作墙（§1.5-A）。 */
    private final Set<BlockPos> wallBlocks = new HashSet<>();
    private BlockPos lastMined;

    // ===== 上行原语状态（§1.5-B / MM-305 / §9 第四轮） =====
    /** 正在执行的上行目标。非 null 时每 tick 直接驱动 MoveControl，并暂停 DIG 状态机。 */
    private BlockPos climbDest;
    /** 本轮上行已经驱动了多少 tick。 */
    private int climbDriveTicks;
    /** 本轮上行开始时的 Y，用于判断「跳跃有没有真的把她抬起来」。 */
    private double climbDriveStartY;
    /** 暂时性破坏失败的累计次数。 */
    private int temporaryFailures;
    /** 每个目标只记一次诊断日志，避免刷屏（MM-801）。 */
    private boolean headroomLogged;
    private boolean ascendFailLogged;

    // ===== 无进展看门狗（MM-306 最小实现） =====
    /** 上一次「有进展」时与目标的距离平方。 */
    private double lastDistanceSqr = -1.0D;
    /** 距离连续没有缩短的 tick 数。 */
    private int noProgressTicks;

    // ===== 发呆接管（§9 第六轮）：位置长时间不变就绕过寻路直接驱动 =====
    /** 最近一次下发的水平「下一格」，发呆接管时作为直接驱动目标。 */
    private BlockPos pendingStep;
    /** 位置连续未变的 tick 数。 */
    private int stationaryTicks;
    private double lastX;
    private double lastY;
    private double lastZ;
    private boolean lastPosValid;

    private static final int MAX_WALL_RECORDS = 4096;

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
        wallBlocks.clear();
        // 记录碰撞箱与推导出的隧道净高：既便于实机核对（Q-10），也是 §1.5-B 的可观测证据
        MaidMiningMod.LOGGER.info("[MaidMining] Session start maid={} bbHeight={} tunnelHeight={}",
                maid.getId(), maid.getBbHeight(), requiredHeight(maid));
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

        // 无进展看门狗：兜住「顶墙空转」「贴着基岩上爬」这类死循环（MM-306）。
        // 只要与目标的距离在缩短就算有进展；连续 MAX_NO_PROGRESS_TICKS 没有缩短即放弃。
        if (target != null && !tickProgressWatchdog(level, maid)) {
            return;
        }

        // 上行/接管进行中：每 tick 直接驱动移动（绕过寻路），期间暂停 DIG/MINE 状态机
        if (climbDest != null) {
            driveClimb(level, maid);
            return;
        }

        // 发呆检测（§9 第六轮）：代码以为在水平掘进、女仆却一步没动。
        // 根因是 vanilla MoveToTargetSink 用 navigation.createPath() 造路，
        // 拿不到路径时会直接抹掉 WALK_TARGET 并停下——于是必须由我们接管。
        tickStationaryWatch(level, maid);

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

    /**
     * 无进展检测。返回 false 表示已经放弃目标并回到 SEARCH，本 tick 不再继续。
     */
    private boolean tickProgressWatchdog(ServerLevel level, EntityMaid maid) {
        double distSqr = maid.blockPosition().distSqr(target);
        if (lastDistanceSqr < 0.0D || distSqr < lastDistanceSqr - 0.25D) {
            lastDistanceSqr = distSqr;
            noProgressTicks = 0;
            return true;
        }
        if (++noProgressTicks > MiningConfig.MAX_NO_PROGRESS_TICKS) {
            abandonTarget(level, maid, "no progress for " + noProgressTicks + " ticks");
            return false;
        }
        // 每 5 秒打一行「卡住快照」：正常情况不会触发，触发时能直接看出卡在哪一步
        // （以前卡住是静默的，只能靠猜——见 §9 第五轮）
        if (noProgressTicks % 100 == 0) {
            MaidMiningMod.LOGGER.info(
                    "[MaidMining] Stuck state={} target={} feet={} dy={} climb={} height={} dist={} maid={}",
                    state, target, maid.blockPosition(),
                    target == null ? 0 : target.getY() - maid.blockPosition().getY(),
                    climbDest, requiredHeight(maid),
                    String.format("%.1f", Math.sqrt(distSqr)), maid.getId());
        }
        return true;
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
        if (found.isEmpty()) {
            return;
        }
        target = found.get();

        // 「基岩邻近就跳过」是 0.1.x 的补偿措施（根因见 §1.5-A），默认已关闭。
        // 保留开关以便在无规划器绕墙时退回旧行为。
        if (MiningConfig.SKIP_NEAR_BEDROCK && MiningValidator.isNearBedrock(level, target)) {
            failedTargets.add(target);
            MaidMiningMod.LOGGER.info("[MaidMining] Skipped bedrock-near ore ({},{},{}) maid={}",
                    target.getX(), target.getY(), target.getZ(), maid.getId());
            target = null;
            return;
        }

        state = State.DIG;
        digTimer = 0;
        stuckCount = 0;
        temporaryFailures = 0;
        endClimb();
        lastDistanceSqr = -1.0D;
        noProgressTicks = 0;
        headroomLogged = false;
        ascendFailLogged = false;
        String tgtName = MiningValidator.targetOreName(maid.getOffhandItem());
        MaidMiningMod.LOGGER.info("[MaidMining] Locked ore ({},{},{}) target={} maid={}",
                target.getX(), target.getY(), target.getZ(),
                tgtName == null ? "all" : tgtName, maid.getId());
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
            if (Math.abs(target.getX() - feet.getX()) >= Math.abs(target.getZ() - feet.getZ())) {
                dz = 0;
            } else {
                dx = 0;
            }
            hDist = Math.abs(target.getX() - feet.getX()) + Math.abs(target.getZ() - feet.getZ());
        }

        // 隧道净高由女仆碰撞箱推导（通常 2），不再硬编码 3（§1.5-B）
        int height = requiredHeight(maid);

        // --- 阶段 1：先把高度差处理掉 ---

        if (dy >= 2) {
            // 目标在上方：走上行原语；连续失败才考虑水平绕行
            if (stepUp(level, maid, feet, dx, dz, height)) {
                stuckCount = 0;
                return;
            }
            if (++stuckCount > MiningConfig.MAX_STUCK_COUNT) {
                stuckCount = 0;
                // 诊断：上行彻底失败时会转入水平绕行（以前是静默的，看不出问题出在上行还是绕行）
                if (!ascendFailLogged) {
                    ascendFailLogged = true;
                    MaidMiningMod.LOGGER.info("[MaidMining] Ascend failed at ({},{},{}) dy={} hDist={} maid={}",
                            feet.getX(), feet.getY(), feet.getZ(), dy, hDist, maid.getId());
                }
                if (hDist > 0) {
                    advanceHorizontal(level, maid, feet, dx, dz, height);
                } else {
                    abandonTarget(level, maid, "cannot ascend to target");
                }
            }
            return;
        }

        if (dy <= -2) {
            // 目标在下方：只做「向下挖」或「向下走」。
            // 旧实现在这里调用 tryClimb()，那是 §1.5-A 无限上爬死循环的直接来源——
            // 恢复动作绝不能把女仆带离目标。
            BlockPos below = feet.below();
            BreakResult result = tryBreak(level, maid, below);
            switch (result) {
                case SUCCESS -> {
                    stuckCount = 0;
                    return;
                }
                case UNBREAKABLE, PROTECTED -> {
                    recordWall(below, result, maid);
                    abandonTarget(level, maid, "unbreakable below (" + result + ")");
                    return;
                }
                case TEMPORARY -> {
                    if (registerTemporaryFailure(level, maid)) {
                        return;
                    }
                }
                case FLUID -> {
                    // 流体不可破坏但可通行，交给下面的 isPassable 分支
                }
            }
            if (isPassable(level, below)) {
                pendingStep = below.immutable();
                BehaviorUtils.setWalkAndLookTargetMemories(maid, below, MiningConfig.MOVE_SPEED, 0);
                return;
            }
            if (++stuckCount > MiningConfig.MAX_STUCK_COUNT) {
                stuckCount = 0;
                if (hDist > 0) {
                    advanceHorizontal(level, maid, feet, dx, dz, height);
                } else {
                    abandonTarget(level, maid, "blocked below");
                }
            }
            return;
        }

        // --- 阶段 2：水平接近（|dy| <= 1） ---

        // 注意：这里**不再**因为「目标高一点」就无条件上台阶。
        // 女仆站在平整地面上就能挖到高一格的矿石（isAdjacent 允许 |dy| <= 1），
        // 为此搭台阶只会在自己的通道里砌墙——实机反馈的"空中放方块把自己挡住"就是这么来的。
        // 上行原语改为只在两处出场：dy >= 2（真需要抬升）、以及水平受阻时的绕行兜底。

        if (hDist > 0) {
            BlockPos next = feet.offset(dx, 0, dz);

            // 1) 先保证脚部那一格能过
            if (!isPassable(level, next)) {
                BreakResult result = tryBreak(level, maid, next);
                switch (result) {
                    case SUCCESS -> {
                    }
                    case FLUID -> {
                        // 流体：继续走（是否可通行由 isPassable 判定）
                    }
                    case UNBREAKABLE, PROTECTED -> {
                        recordWall(next, result, maid);
                        abandonTarget(level, maid, "unbreakable ahead (" + result + ")");
                        return;
                    }
                    case TEMPORARY -> {
                        if (registerTemporaryFailure(level, maid)) {
                            return;
                        }
                        return;
                    }
                }
            }

            // 2) Cliff check: if front is a drop, dig down instead
            if (isPassable(level, next) && isPassable(level, next.below())) {
                tryBreak(level, maid, feet.below());
                return;
            }

            if (!isPassable(level, next)) {
                // 这一 tick 刚尝试过破坏，下一周期继续
                if (++stuckCount > MiningConfig.MAX_STUCK_COUNT) {
                    stuckCount = 0;
                    // 水平方向确实过不去、而目标在上方：退一步尝试上台阶绕行
                    // （这才是上行原语该出场的时候）
                    if (dy > 0 && stepUp(level, maid, feet, dx, dz, height)) {
                        return;
                    }
                    abandonTarget(level, maid, "cannot clear tunnel ahead");
                }
                return;
            }

            // 3) 净空：y+1 .. y+height-1（女仆身高决定，通常只有 y+1 一格）
            for (int i = 1; i < height; i++) {
                BlockPos need = feet.offset(dx, i, dz);
                if (isPassable(level, need)) {
                    continue;
                }
                BreakResult result = tryBreak(level, maid, need);
                if (result == BreakResult.UNBREAKABLE || result == BreakResult.PROTECTED) {
                    recordWall(need, result, maid);
                    abandonTarget(level, maid, "unbreakable overhead (" + result + ")");
                    return;
                }
                return; // 本 tick 只处理一格
            }

            stuckCount = 0;
            pendingStep = next.immutable();
            BehaviorUtils.setWalkAndLookTargetMemories(maid, next, MiningConfig.MOVE_SPEED, 0);
            return;
        }

        // hDist == 0：正上 / 正下
        if (dy > 0) {
            if (stepUp(level, maid, feet, 0, 0, height)) {
                stuckCount = 0;
            } else if (++stuckCount > MiningConfig.MAX_STUCK_COUNT) {
                stuckCount = 0;
                abandonTarget(level, maid, "cannot tower up to target");
            }
            return;
        }

        // dy <= 0：挖脚下
        BreakResult belowResult = tryBreak(level, maid, feet.below());
        if (belowResult == BreakResult.UNBREAKABLE || belowResult == BreakResult.PROTECTED) {
            recordWall(feet.below(), belowResult, maid);
            abandonTarget(level, maid, "floor unbreakable (" + belowResult + ")");
        }
    }

    /**
     * 水平推进一格（仅在高度差过大且上行失败时作为绕行手段）。
     */
    private void advanceHorizontal(ServerLevel level, EntityMaid maid, BlockPos feet, int dx, int dz, int height) {
        BlockPos next = feet.offset(dx, 0, dz);
        BreakResult result = tryBreak(level, maid, next);
        if (result == BreakResult.UNBREAKABLE || result == BreakResult.PROTECTED) {
            recordWall(next, result, maid);
            abandonTarget(level, maid, "wall in bypass path (" + result + ")");
            return;
        }
        if (result == BreakResult.SUCCESS) {
            stuckCount = 0;
        }
        for (int i = 1; i < height; i++) {
            BlockPos need = feet.offset(dx, i, dz);
            if (!isPassable(level, need)) {
                tryBreak(level, maid, need);
            }
        }
        if (isPassable(level, next)) {
            pendingStep = next.immutable();
            BehaviorUtils.setWalkAndLookTargetMemories(maid, next, MiningConfig.MOVE_SPEED, 0);
        }
    }

    // ========== MINE ==========

    private void mining(ServerLevel level, EntityMaid maid) {
        if (target == null) {
            toSearch(maid);
            return;
        }
        ItemStack tool = maid.getMainHandItem();
        if (MiningValidator.isOreBreakable(level, target, tool) && isAdjacent(maid, target)) {
            if (maid.destroyBlock(target)) {
                hurtTool(maid, tool);
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

    /**
     * 破坏一个方块并**分类**结果。
     * <p>
     * 与旧 {@code breakBlock(boolean)} 的区别：世界侧不可破坏（基岩/超硬/流体）、
     * 被保护规则否决、破坏调用失败，三者不再混为一谈（§1.5-A）。
     */
    private BreakResult tryBreak(ServerLevel level, EntityMaid maid, BlockPos pos) {
        BreakResult pre = MiningValidator.classifyDig(level, pos, maid.getMainHandItem());
        if (pre != BreakResult.SUCCESS) {
            return pre;
        }
        if (!maid.canDestroyBlock(pos)) {
            return BreakResult.PROTECTED;
        }
        ItemStack tool = maid.getMainHandItem();
        if (maid.destroyBlock(pos)) {
            hurtTool(maid, tool);
            return BreakResult.SUCCESS;
        }
        return BreakResult.TEMPORARY;
    }

    /**
     * 扣工具耐久。TLM 的 {@code destroyBlock} 不会扣耐久（已核实），所以这一步是必需的；
     * 破损回调必须广播给客户端，否则没有破坏音效/动画（vanilla DiggerItem 同款写法）。
     */
    private static void hurtTool(EntityMaid maid, ItemStack tool) {
        tool.hurtAndBreak(1, maid, e -> e.broadcastBreakEvent(InteractionHand.MAIN_HAND));
        maid.swing(InteractionHand.MAIN_HAND);
    }

    /** 记录一块「永远挖不动」的方块，供后续规划器当墙绕开（§1.5-A）。 */
    private void recordWall(BlockPos pos, BreakResult result, EntityMaid maid) {
        if (wallBlocks.size() < MAX_WALL_RECORDS) {
            wallBlocks.add(pos.immutable());
        }
        MaidMiningMod.LOGGER.info("[MaidMining] Wall {} ({},{},{}) maid={}",
                result, pos.getX(), pos.getY(), pos.getZ(), maid.getId());
    }

    /**
     * 暂时性失败计数。返回 true 表示已经放弃目标、本轮不必继续。
     */
    private boolean registerTemporaryFailure(ServerLevel level, EntityMaid maid) {
        if (++temporaryFailures > MiningConfig.MAX_TEMPORARY_BREAK_FAILURES) {
            abandonTarget(level, maid, "temporary break failure x" + temporaryFailures);
            return true;
        }
        return false;
    }

    /** 隧道净高：由女仆碰撞箱推导，禁止硬编码（§1.5-B）。 */
    private static int requiredHeight(EntityMaid maid) {
        return Math.max(1, Mth.ceil(maid.getBbHeight()));
    }

    /**
     * 女仆能不能**真的站进**这个格子。
     * <p>
     * 用包围盒与世界的方块碰撞求交，而不是「上面几格是不是空气」——
     * 这才是「符合逻辑」的通行判定（§1.5-B）。
     */
    private static boolean canStandAt(ServerLevel level, EntityMaid maid, BlockPos feet) {
        AABB box = maid.getBoundingBox().move(
                feet.getX() + 0.5D - maid.getX(),
                feet.getY() - maid.getY(),
                feet.getZ() + 0.5D - maid.getZ());
        return !level.getBlockCollisions(maid, box).iterator().hasNext();
    }

    /** Whether the maid can pass through this block (air or fluid, no solid collision). */
    private static boolean isPassable(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.isAir() || !state.getFluidState().isEmpty();
    }

    private boolean isAdjacent(EntityMaid maid, BlockPos pos) {
        BlockPos feet = maid.blockPosition();
        return Math.abs(pos.getX() - feet.getX()) <= MiningConfig.ADJACENT_DIST
                && Math.abs(pos.getY() - feet.getY()) <= MiningConfig.ADJACENT_DIST
                && Math.abs(pos.getZ() - feet.getZ()) <= MiningConfig.ADJACENT_DIST;
    }

    /**
     * 上行一格原语（替代旧的 {@code tryClimb}）。
     * <p>
     * 语义严格对齐「玩家怎么上去」（§1.5-B / MM-305）：
     * <ol>
     *   <li>先按碰撞箱净高把 {@code dest} 需要的空间挖出来（每 tick 最多一格）；</li>
     *   <li>保证落脚面存在：横向台阶需要 {@code stepPos} 是实体或垫一块；原地起塔需要脚下方块；</li>
     *   <li>用 {@link #canStandAt} 校验「女仆站得进去」；</li>
     *   <li>先真实跳跃（{@code JumpControl}），并在升到足够高度时把脚下的空位垫上（即玩家的「垫柱」）；</li>
     *   <li>上行由 {@link #driveClimb} <b>每 tick 直接驱动 {@code MoveControl}</b>（绕过寻路），
     *       跳不起来时也会自己起跳；连续 {@link MiningConfig#CLIMB_DRIVE_TIMEOUT_TICKS} tick 未完成，
     *       才做一次校验后的传送兜底。</li>
     * </ol>
     */
    private boolean stepUp(ServerLevel level, EntityMaid maid, BlockPos feet, int dx, int dz, int height) {
        if (dx != 0 && dz != 0) {
            // 反斜角：一次只动一个轴
            if (Math.abs(target.getX() - feet.getX()) >= Math.abs(target.getZ() - feet.getZ())) {
                dz = 0;
            } else {
                dx = 0;
            }
        }
        boolean towerMode = (dx == 0 && dz == 0);
        BlockPos stepPos = towerMode ? feet : feet.offset(dx, 0, dz);
        BlockPos dest = stepPos.above();

        // 1) 净空：dest .. dest+height-1（每 tick 只处理一格，避免一次性大改世界）
        for (int i = 0; i < height; i++) {
            BlockPos need = dest.above(i);
            if (isPassable(level, need)) {
                continue;
            }
            BreakResult result = tryBreak(level, maid, need);
            if (result == BreakResult.UNBREAKABLE || result == BreakResult.PROTECTED) {
                recordWall(need, result, maid);
                return false;
            }
            // 暂时性失败也要计数，否则这里会变成静默无限重试（§9 第五轮）
            if (result == BreakResult.TEMPORARY) {
                registerTemporaryFailure(level, maid);
            }
            return false; // 挖完这一格，下个周期继续
        }

        // 目标在正上方：起塔
        if (towerMode) {
            return towerUp(level, maid, feet, dest);
        }

        // 2) 落脚面：横向台阶前方是空的就垫一块再踩上去。
        //    **只在站在地面上时做**：空中她的碰撞箱高于该格，相交检查会放行，
        //    结果是往自己正要落进去的格子里砌一块方块，把自己挡住（实机反馈）。
        if (isPassable(level, stepPos)) {
            if (!maid.onGround()) {
                // 空中且落脚面还没建好：先等落地，下一周期再从地面垫块
                endClimb();
                return false;
            }
            if (!placeStepBlock(level, maid, stepPos)) {
                return false;
            }
        }

        // 3) 碰撞校验：女仆能否真的站进 dest
        if (!canStandAt(level, maid, dest)) {
            endClimb();
            return false;
        }

        // 3.5) 起跳净空（§1.5-B 的真正根因）：
        //      2 格高的隧道里，天花板会把跳跃截断在 0.5 格以内，1 格台阶永远迈不上去。
        //      所以在起跳这一格多挖 1 格，形成局部"站位凹坑"——玩家上台阶时也是这么做的。
        //      注意只挖这一格，不是把整条矿道加高成 1×3。
        BlockPos jumpSpace = feet.above(height + MiningConfig.JUMP_HEADROOM - 1);
        if (!isPassable(level, jumpSpace)) {
            BreakResult result = tryBreak(level, maid, jumpSpace);
            if (result == BreakResult.UNBREAKABLE || result == BreakResult.PROTECTED) {
                recordWall(jumpSpace, result, maid);
                return false;
            }
            if (result == BreakResult.TEMPORARY) {
                registerTemporaryFailure(level, maid);
            }
            if (!headroomLogged) {
                headroomLogged = true;
                MaidMiningMod.LOGGER.info("[MaidMining] Jump headroom dug ({},{},{}) maid={}",
                        jumpSpace.getX(), jumpSpace.getY(), jumpSpace.getZ(), maid.getId());
            }
            return false; // 挖完这一格，下个周期起跳
        }

        // 4) 上行：交给逐 tick 驱动的 driveClimb（绕开寻路）。
        //    不能只发走位目标——MoveToTargetSink 用 navigation.createPath() 造路，
        //    在 1 格宽隧道里拿不到路径时会直接抹掉 WALK_TARGET 并停止移动，
        //    结果就是"原地蹦跶不向前"（§9 第四轮实机反馈）。
        if (climbDest == null) {
            climbDest = dest.immutable();
            climbDriveTicks = 0;
            climbDriveStartY = maid.getY();
            maid.getJumpControl().jump();
        }
        return true;
    }

    /**
     * 每 tick 驱动一次短距离上行（绕过寻路）。
     * <p>
     * 两个前提（均已核实，见 §9 第四轮）：
     * <ol>
     *   <li>vanilla {@code MoveToTargetSink} 靠 {@code navigation.createPath()} 驱动移动，
     *       拿不到路径时它会 <b>抹掉 WALK_TARGET 并停止导航</b> ⇒ 走位目标在窄隧道里不可靠；</li>
     *   <li>{@code MoveControl} 的 {@code MOVE_TO} 是<b>一次性</b>的（处理完立刻转回 {@code WAIT}）
     *       ⇒ 必须每 tick 重新下发 {@code setWantedPosition}。</li>
     * </ol>
     */
    private void driveClimb(ServerLevel level, EntityMaid maid) {
        climbDriveTicks++;

        // 到位判定：已经站进目标格（水平与垂直通用）
        double dx = maid.getX() - (climbDest.getX() + 0.5D);
        double dz = maid.getZ() - (climbDest.getZ() + 0.5D);
        if (maid.blockPosition().getY() >= climbDest.getY() && dx * dx + dz * dz < 0.25D) {
            endClimb();
            return;
        }

        // 别让 MoveToTargetSink 掺和（它会因为拿不到路径而清掉走位目标、停下导航）
        maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        maid.getNavigation().stop();
        maid.getMoveControl().setWantedPosition(
                climbDest.getX() + 0.5D, climbDest.getY(), climbDest.getZ() + 0.5D, MiningConfig.MOVE_SPEED);

        // 只有目标确实更高时才起跳（水平接管时不该蹦跶）；
        // MoveControl 自己那条自动跳跃在"恰好 1 格外"时不成立，所以必须自己发。
        boolean needUp = climbDest.getY() > maid.blockPosition().getY();
        if (needUp && maid.getY() - climbDriveStartY < 0.4D && maid.onGround()) {
            maid.getJumpControl().jump();
        }

        // 超时兜底：校验碰撞后传送（最后手段，正常情况下不该走到这里）
        if (climbDriveTicks > MiningConfig.CLIMB_DRIVE_TIMEOUT_TICKS) {
            BlockPos dest = climbDest;
            endClimb();
            if (canStandAt(level, maid, dest)) {
                maid.setPos(dest.getX() + 0.5D, dest.getY(), dest.getZ() + 0.5D);
                maid.resetFallDistance();
                MaidMiningMod.LOGGER.info("[MaidMining] Teleport fallback ({},{},{}) maid={}",
                        dest.getX(), dest.getY(), dest.getZ(), maid.getId());
            } else {
                // 静默失败会让"卡住"无从定位，这里必须留痕（§9 第五轮）
                MaidMiningMod.LOGGER.info("[MaidMining] Climb timeout, dest not standable ({},{},{}) maid={}",
                        dest.getX(), dest.getY(), dest.getZ(), maid.getId());
            }
        }
    }

    /** 结束本轮上行驱动。 */
    private void endClimb() {
        climbDest = null;
        climbDriveTicks = 0;
        climbDriveStartY = 0.0D;
    }

    /**
     * 发呆检测：在 DIG 状态下位置连续 {@link MiningConfig#STALL_TAKEOVER_TICKS} tick 没变，
     * 就把最近一次下发的「下一格」接管为直接驱动（绕过寻路）。
     * <p>
     * 为什么需要它（§9 第六轮实机证据）：目标在同层 4 格外、`dy=0`、连续 20 秒 `feet` 完全不变，
     * `climb=null`——代码每 6 tick 都在发走位目标，但 vanilla 的 `MoveToTargetSink`
     * 拿不到路径时会把 `WALK_TARGET` 抹掉并停止移动，女仆于是**原地发呆**。
     */
    private void tickStationaryWatch(ServerLevel level, EntityMaid maid) {
        if (pendingStep == null || state != State.DIG) {
            stationaryTicks = 0;
            lastPosValid = false;
            return;
        }
        if (!lastPosValid) {
            lastX = maid.getX();
            lastY = maid.getY();
            lastZ = maid.getZ();
            lastPosValid = true;
            return;
        }
        double moved = Math.abs(maid.getX() - lastX) + Math.abs(maid.getY() - lastY) + Math.abs(maid.getZ() - lastZ);
        lastX = maid.getX();
        lastY = maid.getY();
        lastZ = maid.getZ();
        if (moved > 0.01D) {
            stationaryTicks = 0;
            return;
        }
        if (++stationaryTicks >= MiningConfig.STALL_TAKEOVER_TICKS) {
            stationaryTicks = 0;
            climbDest = pendingStep.immutable();
            climbDriveTicks = 0;
            climbDriveStartY = maid.getY();
            MaidMiningMod.LOGGER.info("[MaidMining] Stall takeover -> direct drive ({},{},{}) maid={}",
                    climbDest.getX(), climbDest.getY(), climbDest.getZ(), maid.getId());
        }
    }

    /**
     * 原地起塔（目标在正上方、脚下就是唯一通路）。
     * <p>
     * 与玩家「垫柱」产生同样的世界状态，但去掉了「跳跃中放方块」的时序依赖：
     * 先校验并抬升到 {@code dest}，再立刻把刚空出来的 {@code feet} 补上——此时女仆已不在该格，
     * 碰撞校验必然通过，下一 tick 她就有支撑、不会掉回原地。
     * <p>
     * 旧的 {@code setPos}-only 实现因为不补方块，会被重力直接拉回原点（净进度为 0），
     * 这也是「向上卡住」的成因之一（§1.5-B）。
     */
    private boolean towerUp(ServerLevel level, EntityMaid maid, BlockPos feet, BlockPos dest) {
        if (!canStandAt(level, maid, dest)) {
            endClimb();
            return false;
        }
        if (!isPassable(level, feet)) {
            // 应该不会发生（女仆正站在这里）；状态异常时不要盲目改世界
            endClimb();
            return false;
        }
        int slot = findScaffoldSlot(level, maid, feet);
        if (slot < 0) {
            // 没有可用建材：无法起塔。交给调用方累计 stuckCount，最终放弃该目标。
            endClimb();
            return false;
        }
        maid.setPos(dest.getX() + 0.5D, dest.getY(), dest.getZ() + 0.5D);
        maid.resetFallDistance();
        extractAndPlace(level, maid, feet, slot);
        endClimb();
        return true;
    }

    private void pickupDrops(ServerLevel level, EntityMaid maid) {
        AABB area = maid.getBoundingBox().inflate(1.5);
        List<ItemEntity> drops = level.getEntitiesOfClass(ItemEntity.class, area, e -> !e.isRemoved());
        for (ItemEntity drop : drops) {
            ItemStack stack = drop.getItem();
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) continue;
            ItemStackHandler inv = maid.getMaidInv();
            int original = stack.getCount();
            int left = original;
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
            // 原子转移：装进去多少就从掉落物里扣掉多少（含"只装下一部分"的情况）。
            // v1.0.0 只更新了本地计数 left，从不回写实体 —— 背包放不下时物品会
            // 同时存在于背包与地面 = 复制漏洞（见 OPTIMIZATION.md MM-501）。
            if (left == 0) {
                drop.discard();
            } else if (left < original) {
                stack.setCount(left);
                drop.setItem(stack);
            }
        }
    }

    /**
     * 找一格可用作垫脚的建材，返回槽位下标，找不到返回 -1。
     * <p>
     * 只判定「这个方块有碰撞体积、能站上去」，**不做与女仆的相交检查**——
     * 相交检查属于放置步骤：起塔时要放在女仆刚刚腾出来的格子里。
     */
    private int findScaffoldSlot(ServerLevel level, EntityMaid maid, BlockPos pos) {
        ItemStackHandler inv = maid.getMaidInv();
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (s.isEmpty() || !(s.getItem() instanceof BlockItem bi)) {
                continue;
            }
            // 需要「有碰撞体积、能站上去」的方块。
            // （原实现用 BlockStateBase#isSolid()，该方法在 1.20.1 已弃用。）
            if (!bi.getBlock().defaultBlockState().getCollisionShape(level, pos).isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    /** 从指定槽位消耗 1 个并放置。放置前与女仆碰撞箱求交，避免把自己埋进去。 */
    private boolean extractAndPlace(ServerLevel level, EntityMaid maid, BlockPos pos, int slot) {
        ItemStackHandler inv = maid.getMaidInv();
        ItemStack s = inv.getStackInSlot(slot);
        if (s.isEmpty() || !(s.getItem() instanceof BlockItem bi)) {
            return false;
        }
        // 硬性不变式：绝不在女仆自身所在的两格高身体柱里放方块
        // （碰撞检查用 AABB 求交，空中/贴边时可能漏判，这里再加一道）
        BlockPos bodyPos = maid.blockPosition();
        if (pos.equals(bodyPos) || pos.equals(bodyPos.above())) {
            return false;
        }
        BlockState placeState = bi.getBlock().defaultBlockState();
        VoxelShape shape = placeState.getCollisionShape(level, pos);
        if (!shape.isEmpty()) {
            AABB blockBox = shape.bounds().move(pos);
            if (maid.getBoundingBox().intersects(blockBox)) {
                return false; // 放下去会卡住女仆，放弃这一格
            }
        }
        inv.extractItem(slot, 1, false);
        level.setBlock(pos, placeState, 3);
        maid.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    /** 在指定位置垫一块建材（先找建材再放置，含碰撞检查）。 */
    private boolean placeStepBlock(ServerLevel level, EntityMaid maid, BlockPos pos) {
        int slot = findScaffoldSlot(level, maid, pos);
        if (slot < 0) {
            return false;
        }
        return extractAndPlace(level, maid, pos, slot);
    }

    /** 放弃当前目标并回到 SEARCH。用于「硬失败」（不可破坏/被保护/无进展），不再做 8 次无意义重试。 */
    private void abandonTarget(ServerLevel level, EntityMaid maid, String reason) {
        if (target != null) {
            failedTargets.add(target);
            failCounts.remove(target);
            MaidMiningMod.LOGGER.info("[MaidMining] Abandoned ({},{},{}) reason={} maid={}",
                    target.getX(), target.getY(), target.getZ(), reason, maid.getId());
        }
        toSearch(maid);
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
        endClimb();
        temporaryFailures = 0;
        lastDistanceSqr = -1.0D;
        noProgressTicks = 0;
        pendingStep = null;
        stationaryTicks = 0;
        lastPosValid = false;
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
