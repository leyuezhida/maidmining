package com.leyue.maidmining.mining;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.leyue.maidmining.MaidMiningMod;
import com.leyue.maidmining.act.DigExecutor;
import com.leyue.maidmining.act.Scaffolder;
import com.leyue.maidmining.act.TunnelAdvancer;
import com.leyue.maidmining.act.VerticalMover;
import com.leyue.maidmining.cfg.MiningConfig;
import com.leyue.maidmining.inv.MaidInventory;
import com.leyue.maidmining.inv.ToolManager;
import com.leyue.maidmining.session.MiningBlackboard;
import com.leyue.maidmining.session.MiningState;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BehaviorUtils;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 挖矿核心行为：{@code SEARCH → DIG(逐格挖隧道) → MINE}。
 * 以女仆为中心搜索矿石，挖竖井与巷道抵达后挖掉。
 * <p>
 * <b>放弃原生寻路的原因</b>：Minecraft Pathfinder 在地底矿洞场景几乎不可用
 * （{@code createPath/moveTo} 对石头包围的矿石位置反复返回 null），
 * 逐格挖隧道是最可靠的接近方式。
 * <p>
 * <b>1.02 重构（MM-601）</b>：本类只保留<b>状态迁移编排</b>，
 * 具体动作用专职组件承担：
 * <ul>
 *   <li>{@link TunnelAdvancer} —— DIG 状态的逐格推进决策（上行/下降/水平/绕行）；</li>
 *   <li>{@link DigExecutor} —— 破坏与掉落（时运 / 精准采集在此生效，MM-303）；</li>
 *   <li>{@link VerticalMover} —— 上行与净空（跳跃优先、传送兜底，MM-304/305）；</li>
 *   <li>{@link Scaffolder} —— 垫脚建材（白名单 + 排除展示位，MM-502）；</li>
 *   <li>{@link ToolManager} —— 选最优镐（等级 / 附魔 / 耐久，MM-505）；</li>
 *   <li>{@link MaidInventory} —— 背包读写唯一出口（物品守恒 + 展示位保护，MM-501/509）；</li>
 *   <li>{@link MiningBlackboard} —— 会话可变状态（MM-601）。</li>
 * </ul>
 * <b>行为保持等价</b>：状态机语义、失败分类、上行原语语义都与 1.0.x 一致，
 * 本轮不引入新决策逻辑。
 */
public class MiningTunnelBehavior extends Behavior<EntityMaid> implements TunnelAdvancer.StepOutcome {

    private final MiningTunnelFinder finder = new MiningTunnelFinder();
    private final VerticalMover mover = new VerticalMover();
    private final MiningBlackboard board = new MiningBlackboard();
    /**
     * 本 tick 的女仆。
     * <p>
     * {@link TunnelAdvancer.StepOutcome} 的回调签名刻意不传 level/maid（那几个动作确实不需要），
     * 但 {@code toSearch()} 要用到女仆来清走位目标与导航，所以在这里留一个引用。
     */
    private EntityMaid currentMaid;

    public MiningTunnelBehavior() {
        super(Map.<net.minecraft.world.entity.ai.memory.MemoryModuleType<?>, MemoryStatus>of(),
                Integer.MAX_VALUE);
    }

    /** 供 GameTest / 诊断读取当前会话状态。 */
    public MiningBlackboard blackboard() {
        return board;
    }

    @Override
    protected boolean canStillUse(ServerLevel level, EntityMaid maid, long gameTime) {
        return true;
    }

    @Override
    protected void start(ServerLevel level, EntityMaid maid, long gameTime) {
        currentMaid = maid;
        board.reset();
        // 记录碰撞箱与推导出的隧道净高：既便于实机核对，也是 §1.5-B 的可观测证据
        MaidMiningMod.LOGGER.info("[MaidMining] Session start maid={} bbHeight={} tunnelHeight={}",
                maid.getId(), maid.getBbHeight(), VerticalMover.requiredHeight(maid));
        toSearch();
    }

    @Override
    protected void stop(ServerLevel level, EntityMaid maid, long gameTime) {
        maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        maid.getNavigation().stop();
        // 工具是"借"到主手的，停止时归还（MM-604）
        ToolManager.putBack(maid);
    }

    @Override
    protected void tick(ServerLevel level, EntityMaid maid, long gameTime) {
        currentMaid = maid;
        board.tickTimer();

        // 无进展看门狗：兜住「顶墙空转」「贴着基岩上爬」这类死循环（MM-306）
        if (board.target() != null && !tickProgressWatchdog(level, maid)) {
            return;
        }

        // 上行 / 发呆接管进行中：每 tick 直接驱动移动，期间暂停状态机
        if (mover.isClimbing()) {
            mover.drive(level, maid, board.climbIsStallTakeover());
            return;
        }

        // 发呆检测：代码以为在掘进、女仆却一步没动（根因是寻路器抹掉走位目标）
        tickStationaryWatch(level, maid);

        if (board.timer() % 20 == 0) {
            if (!MiningValidator.isPickaxe(maid.getMainHandItem())) {
                ToolManager.ensureBestPickaxe(maid);
            } else if (ToolManager.shouldReplaceWorn(maid)) {
                // 镐快坏了就提前换（仅在开启耐久消耗时有意义）
                ToolManager.ensureBestPickaxe(maid);
            }
        }
        if (board.timer() % 10 == 0) {
            pickupDrops(level, maid);
        }

        switch (board.state()) {
            case SEARCH -> searching(level, maid);
            case DIG -> digging(level, maid);
            case MINE -> mining(level, maid);
        }
    }

    // ========== 看门狗 ==========

    /**
     * 无进展检测。返回 false 表示已放弃目标并回到 SEARCH，本 tick 不再继续。
     * <p>
     * 两个判据（MM-306）：
     * <ol>
     *   <li><b>距离不缩短</b>：连续 {@code maxNoProgressTicks} 没有任何靠近；</li>
     *   <li><b>净 Y 漂移反向</b>：目标在下方却持续上升 —— 这是 §1.5-A 那个
     *       「贴着基岩无限上爬」死循环的直接特征（每轮恢复动作都把她抬高一格、
     *       同时把卡住计数清零，于是距离判据永远不触发）。</li>
     * </ol>
     */
    private boolean tickProgressWatchdog(ServerLevel level, EntityMaid maid) {
        BlockPos target = board.target();
        if (target == null) {
            return true;
        }
        double distSqr = maid.blockPosition().distSqr(target);
        double last = board.lastDistanceSqr();
        if (last < 0.0D || distSqr < last - 0.25D) {
            board.lastDistanceSqr(distSqr);
            board.resetNoProgress();
            return true;
        }
        // 目标在下方（或同层）却不断上升 → 反向漂移
        if (target.getY() <= maid.blockPosition().getY() + 1) {
            board.bumpUpwardDrift();
        } else {
            board.resetUpwardDrift();
        }
        if (board.bumpNoProgress() > MiningConfig.maxNoProgressTicks()
                || board.upwardDriftTicks() > MiningConfig.maxNoProgressTicks() / 2) {
            abandonTarget("no progress for " + board.noProgressTicks()
                    + " ticks (upwardDrift=" + board.upwardDriftTicks() + ")");
            return false;
        }
        // 每 5 秒打一行"卡住快照"：触发时能直接看出卡在哪一步
        if (board.noProgressTicks() % 100 == 0) {
            MaidMiningMod.LOGGER.info(
                    "[MaidMining] Stuck state={} target={} feet={} dy={} climb={} height={} dist={} maid={}",
                    board.state(), target, maid.blockPosition(),
                    target.getY() - maid.blockPosition().getY(),
                    board.climbDest(), VerticalMover.requiredHeight(maid),
                    String.format("%.1f", Math.sqrt(distSqr)), maid.getId());
        }
        return true;
    }

    // ========== SEARCH ==========

    private void searching(ServerLevel level, EntityMaid maid) {
        if (!MiningValidator.isPickaxe(maid.getMainHandItem())) {
            ToolManager.ensureBestPickaxe(maid);
            if (!MiningValidator.isPickaxe(maid.getMainHandItem()) && !board.noPickaxeWarned()) {
                board.markNoPickaxeWarned();
                MaidMiningMod.LOGGER.warn("[MaidMining] No usable pickaxe in backpack, maid={}", maid.getId());
            }
            return;
        }

        Optional<BlockPos> found = finder.findNearestOre(level, maid, maid.getMainHandItem(),
                maid.getOffhandItem(), board.failedTargets());
        if (found.isEmpty()) {
            return;
        }
        BlockPos candidate = found.get();

        // 「基岩邻近就跳过」是为掩盖"被基岩卡住"而加的补偿（§1.5-A），默认开启。
        if (MiningConfig.skipNearBedrock() && MiningValidator.isNearBedrock(level, candidate)) {
            board.failedTargets().add(candidate);
            board.countSkippedNearBedrock();
            MaidMiningMod.LOGGER.info("[MaidMining] Skipped bedrock-near ore ({},{},{}) maid={}",
                    candidate.getX(), candidate.getY(), candidate.getZ(), maid.getId());
            return;
        }

        board.lockTarget(candidate);
        mover.end();

        if (MiningConfig.logInfoEnabled()) {
            String targetName = MiningValidator.targetOreName(maid.getOffhandItem());
            MaidMiningMod.LOGGER.info("[MaidMining] Locked ore ({},{},{}) target={} maid={}",
                    candidate.getX(), candidate.getY(), candidate.getZ(),
                    targetName == null ? "all" : targetName, maid.getId());
        }
    }

    // ========== DIG ==========

    private void digging(ServerLevel level, EntityMaid maid) {
        BlockPos target = board.target();
        if (target == null || !MiningValidator.isOreBreakable(level, target,
                maid.getMainHandItem(), maid.getOffhandItem())) {
            if (target != null && MiningConfig.logDebugEnabled()) {
                BlockState state = level.getBlockState(target);
                MaidMiningMod.LOGGER.info("[MaidMining] Target invalid ({},{},{}) block={} maid={}",
                        target.getX(), target.getY(), target.getZ(),
                        state.isAir() ? "air" : String.valueOf(ForgeRegistries.BLOCKS.getKey(state.getBlock())),
                        maid.getId());
            }
            toSearch();
            return;
        }
        if (isAdjacent(maid, target)) {
            board.state(MiningState.MINE);
            return;
        }
        // 挖掘节流：避免挖穿后女仆还没落稳就继续挖
        if (board.bumpDigTimer() < MiningConfig.digIntervalTicks()) {
            return;
        }
        board.resetDigTimer();

        // 推进决策交给 TunnelAdvancer；返回 true 表示已放弃目标
        if (TunnelAdvancer.advance(level, maid, board, this)) {
            toSearch();
        }
    }

    // ========== MINE ==========

    private void mining(ServerLevel level, EntityMaid maid) {
        BlockPos target = board.target();
        if (target == null) {
            toSearch();
            return;
        }
        if (MiningValidator.isOreBreakable(level, target, maid.getMainHandItem(), maid.getOffhandItem())
                && isAdjacent(maid, target)) {
            BreakResult result = DigExecutor.dig(level, maid, target);
            if (result == BreakResult.SUCCESS) {
                board.lastMined(target);
                board.countMined();
                if (MiningConfig.logInfoEnabled()) {
                    MaidMiningMod.LOGGER.info("[MaidMining] Mined ({},{},{}) maid={}",
                            target.getX(), target.getY(), target.getZ(), maid.getId());
                }
            } else if (MiningConfig.logDebugEnabled()) {
                MaidMiningMod.LOGGER.info("[MaidMining] Mine failed ({},{},{}) result={} maid={}",
                        target.getX(), target.getY(), target.getZ(), result, maid.getId());
            }
        }
        toSearch();
    }

    // ========== 上行原语（供 TunnelAdvancer 回调） ==========

    /**
     * 上行一格（MM-304 / MM-305）。
     * <p>
     * 本方法只做<b>方向决策</b>（横向台阶 vs 原地起塔）与反斜角对齐，
     * 空间准备（净空 / 起跳净空 / 落脚面 / 碰撞校验）全在 {@link VerticalMover} 里 ——
     * 那里才是这些物理约束该待的地方，且它们改一行不会碰到状态机。
     */
    @Override
    public boolean stepUp(ServerLevel level, EntityMaid maid, BlockPos feet,
                          int dx, int dz, int height) {
        // 反斜角只走一个轴：窄隧道里同时动 X 与 Z 会卡在角上
        BlockPos target = board.target();
        if (target != null && dx != 0 && dz != 0) {
            if (Math.abs(target.getX() - feet.getX()) >= Math.abs(target.getZ() - feet.getZ())) {
                dz = 0;
            } else {
                dx = 0;
            }
        }
        boolean towerMode = (dx == 0 && dz == 0);
        BlockPos stepPos = towerMode ? feet : feet.offset(dx, 0, dz);
        BlockPos dest = stepPos.above();

        if (towerMode) {
            boolean done = mover.towerUp(level, maid, feet, dest, maid.getOffhandItem());
            if (!done && !Scaffolder.hasScaffoldMaterial(level, maid) && !board.headroomLogged()) {
                // 没有可用建材时给出可区分的诊断：起不了塔 ≠ 卡住
                board.markHeadroomLogged();
                MaidMiningMod.LOGGER.info("[MaidMining] No scaffold material, cannot tower up maid={}",
                        maid.getId());
            }
            return done;
        }
        boolean advanced = mover.stepUpHorizontal(level, maid, feet, stepPos, height,
                maid.getOffhandItem());
        if (advanced && mover.isClimbing()) {
            // VerticalMover 内部已 begin()，这里把黑板的爬升状态同步上，
            // 这样 board 与 mover 不会各自记一份而走偏
            board.beginClimb(mover.climbDest(), maid.getY(), false);
        }
        return advanced;
    }

    @Override
    public void wall(BlockPos pos, BreakResult result) {
        MaidMiningMod.LOGGER.info("[MaidMining] Wall {} ({},{},{})",
                result, pos.getX(), pos.getY(), pos.getZ());
    }

    /** 返回 true 表示已放弃目标。 */
    @Override
    public boolean temporaryFailure() {
        if (board.bumpTemporaryFailures() > MiningConfig.maxTemporaryBreakFailures()) {
            abandonTarget("temporary break failure x" + board.temporaryFailures());
            return true;
        }
        return false;
    }

    @Override
    public void abandon(String reason) {
        abandonTarget(reason);
    }

    // ========== 移动辅助 ==========

    /**
     * 发呆检测：DIG 状态下位置连续 {@code stallTakeoverTicks} 没变，
     * 就把最近下发的"下一格"接管为直接驱动。
     * <p>
     * 根因（§9 第六轮实机证据）：代码每 6 tick 都在发走位目标，但 vanilla
     * {@code MoveToTargetSink} 拿不到路径时会把 {@code WALK_TARGET} 抹掉并停止移动，
     * 女仆于是<b>原地发呆</b>。
     */
    private void tickStationaryWatch(ServerLevel level, EntityMaid maid) {
        if (board.pendingStep() == null || board.state() != MiningState.DIG) {
            board.resetStationary();
            return;
        }
        if (!board.lastPosValid()) {
            board.lastPos(maid.getX(), maid.getY(), maid.getZ());
            return;
        }
        double moved = Math.abs(maid.getX() - board.lastX())
                + Math.abs(maid.getY() - board.lastY())
                + Math.abs(maid.getZ() - board.lastZ());
        board.lastPos(maid.getX(), maid.getY(), maid.getZ());
        if (moved > 0.01D) {
            board.resetStationary();
            return;
        }
        if (board.bumpStationary() >= MiningConfig.stallTakeoverTicks()) {
            board.resetStationary();
            BlockPos dest = board.pendingStep();
            mover.begin(dest, maid);
            board.beginClimb(dest, maid.getY(), true);
            MaidMiningMod.LOGGER.info("[MaidMining] Stall takeover -> direct drive ({},{},{}) maid={}",
                    dest.getX(), dest.getY(), dest.getZ(), maid.getId());
        }
    }

    // ========== 掉落 ==========

    /**
     * 收集溢出的掉落物。
     * <p>
     * 主掉落已由 {@link DigExecutor} 直接入包，本方法只处理"背包满而落地"的部分。
     * 转移是<b>原子的</b>：写入多少就从实体扣多少（MM-501 的复制漏洞修复）。
     */
    private void pickupDrops(ServerLevel level, EntityMaid maid) {
        if (!MiningConfig.pickupOverflow()) {
            return;
        }
        AABB area = maid.getBoundingBox().inflate(1.5);
        List<ItemEntity> drops = level.getEntitiesOfClass(ItemEntity.class, area, e -> !e.isRemoved());
        for (ItemEntity drop : drops) {
            ItemStack stack = drop.getItem();
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) {
                continue;
            }
            // 原子转移：装进去多少就从实体扣多少，背包满则完全不动实体
            MaidInventory.pickupItemEntity(maid, drop);
        }
    }

    // ========== 失败处理 ==========

    /** 硬失败（不可破坏 / 被保护 / 无进展）时放弃目标，不再做无意义重试。 */
    private void abandonTarget(String reason) {
        BlockPos target = board.target();
        if (target != null) {
            board.failedTargets().add(target);
            board.failCounts().remove(target);
            board.countAbandoned();
            MaidMiningMod.LOGGER.info("[MaidMining] Abandoned ({},{},{}) reason={}",
                    target.getX(), target.getY(), target.getZ(), reason);
        }
        toSearch();
    }

    private void toSearch() {
        BlockPos target = board.target();
        if (target != null) {
            if (target.equals(board.lastMined())) {
                board.failCounts().remove(target);
            } else if (board.bumpFailCount(target) >= MiningConfig.maxTargetRetries()) {
                board.failedTargets().add(target);
                board.failCounts().remove(target);
                MaidMiningMod.LOGGER.info("[MaidMining] Blacklisted ({},{},{})",
                        target.getX(), target.getY(), target.getZ());
            }
        }
        board.backToSearch();
        mover.end();
        finder.reset();
        if (currentMaid != null) {
            currentMaid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            currentMaid.getNavigation().stop();
        }
    }

    private boolean isAdjacent(EntityMaid maid, BlockPos pos) {
        BlockPos feet = maid.blockPosition();
        int reach = MiningConfig.adjacentDist();
        return Math.abs(pos.getX() - feet.getX()) <= reach
                && Math.abs(pos.getY() - feet.getY()) <= reach
                && Math.abs(pos.getZ() - feet.getZ()) <= reach;
    }

    /** 供 i18n 提示使用的"当前在做什么"。 */
    public Component actionSummary() {
        return Component.translatable(board.state().translationKey());
    }
}
