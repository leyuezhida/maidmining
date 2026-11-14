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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.core.registries.BuiltInRegistries;

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
    /** 已经打过"空扫"日志的轮次号，避免每轮都刷。 */
    private int loggedEmptyPass;
    /** 是否处于低血撤退状态（只在状态切换时提示一次）。 */
    private boolean retreating;

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

        // 安全优先：低血时停止一切挖掘（safety.minHealthRetreat，此前只有配置没有实现）
        if (retreatIfHurt(maid)) {
            return;
        }

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
            // 按「当前目标」挑工具（MM-1102）：挖沙砾要铲、挖石头要镐。
            // 没有目标（搜索阶段）时传 null，只按等级/附魔/耐久排序。
            BlockPos locked = board.target();
            ToolManager.ensureBestTool(maid, locked == null ? null : level.getBlockState(locked));
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
        if (!MiningValidator.isTool(maid.getMainHandItem())) {
            ToolManager.ensureBestTool(maid);
            if (!MiningValidator.isTool(maid.getMainHandItem()) && !board.noPickaxeWarned()) {
                board.markNoPickaxeWarned();
                MaidMiningMod.LOGGER.warn("[MaidMining] No usable tool in backpack, maid={} "
                        + "(check tools.whitelist / tools.blacklist)", maid.getId());
                // 同时告诉主人一声：否则表现就是"她站着不动"，只能翻日志才看得出来。
                // 每个会话只提示一次（board.noPickaxeWarned 已经保证）。
                if (maid.getOwner() instanceof Player owner) {
                    owner.sendSystemMessage(noToolMessage());
                }
            }
            return;
        }

        Optional<BlockPos> found = finder.findNearestTarget(level, maid, maid.getMainHandItem(),
                maid.getOffhandItem(), board.failedTargets());
        if (found.isEmpty()) {
            warnEmptyScanOnce(level, maid);
            followOwnerIfIdle(maid);
            return;
        }
        BlockPos candidate = found.get();

        // 「基岩邻近就跳过」是为掩盖"被基岩卡住"而加的补偿（§1.5-A），默认开启。
        if (MiningConfig.skipNearBedrock() && MiningValidator.isNearBedrock(level, candidate)) {
            board.failedTargets().add(candidate);
            board.countSkippedNearBedrock();
            MaidMiningMod.LOGGER.info("[MaidMining] Skipped bedrock-near target ({},{},{}) maid={}",
                    candidate.getX(), candidate.getY(), candidate.getZ(), maid.getId());
            return;
        }

        board.lockTarget(candidate);
        mover.end();

        if (MiningConfig.logInfoEnabled()) {
            String targetName = MiningValidator.targetOreName(maid.getOffhandItem());
            MaidMiningMod.LOGGER.info("[MaidMining] Locked target ({},{},{}) block={} filter={} maid={}",
                    candidate.getX(), candidate.getY(), candidate.getZ(),
                    String.valueOf(BuiltInRegistries.BLOCK.getKey(level.getBlockState(candidate).getBlock())),
                    targetName == null ? "all" : targetName, maid.getId());
        }
    }

    /**
     * "没有可用工具"的提示文案。
     * <p>
     * 白名单非空时<b>取代</b>内置的"任何镐子"规则，所以最常见的原因是白名单里只有别的东西
     * （实测：玩家把一把钻石剑写进了白名单，然后奇怪下界合金镐为什么不能用）。
     * 这条消息直接把当前白名单内容说出来，省得再去翻配置。
     */
    private static Component noToolMessage() {
        MiningConfig.ToolRules rules = MiningConfig.currentToolRules();
        if (rules.whitelistConfigured() && !rules.whitelist().isEmpty()) {
            String allowed = rules.whitelist().stream()
                    .map(item -> String.valueOf(BuiltInRegistries.ITEM.getKey(item)))
                    .sorted()
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("");
            return Component.translatable("maidmining.msg.no_pickaxe_listed", allowed);
        }
        return Component.translatable("maidmining.msg.no_pickaxe");
    }

    /**
     * 低血撤退：血量 ≤ {@code safety.minHealthRetreat} 时停止挖掘，并往主人那边靠。
     * <p>
     * <b>自查发现</b>：这个配置项此前只有声明、没有实现——玩家把阈值调高以为有保护，
     * 实际上她照样往岩浆里挖。{@code 0} 表示关闭撤退。
     *
     * @return true 表示本 tick 已被撤退逻辑接管，不再推进状态机
     */
    private boolean retreatIfHurt(EntityMaid maid) {
        float threshold = MiningConfig.minHealthRetreat();
        if (threshold <= 0.0F || maid.getHealth() > threshold) {
            this.retreating = false;
            return false;
        }
        mover.end();
        board.backToSearch();
        finder.reset();
        if (!this.retreating) {
            this.retreating = true;
            MaidMiningMod.LOGGER.info("[MaidMining] Retreat: health={} <= {} maid={}",
                    maid.getHealth(), threshold, maid.getId());
            if (maid.getOwner() instanceof Player owner) {
                owner.sendSystemMessage(Component.translatable("maidmining.msg.low_health"));
            }
        }
        LivingEntity owner = maid.getOwner();
        if (owner != null && maid.distanceTo(owner) > 4.0F) {
            maid.getNavigation().moveTo(owner, MiningConfig.moveSpeed());
        }
        return true;
    }

    /**
     * 空闲时跟随主人（{@code travel.followOwnerWhenIdle}）。
     * <p>
     * 同样属于"只有配置没有实现"的一项。只在<b>离得很远</b>（超过 12 格）且每 20 tick
     * 检查一次时才重新下发走位目标，避免和搜索/挖掘抢移动控制。
     */
    private void followOwnerIfIdle(EntityMaid maid) {
        if (!MiningConfig.followOwnerWhenIdle()) {
            return;
        }
        LivingEntity owner = maid.getOwner();
        if (owner == null) {
            return;
        }
        if (maid.distanceTo(owner) < 12.0F || board.timer() % 20 != 0) {
            return;
        }
        maid.getNavigation().moveTo(owner, MiningConfig.moveSpeed());
    }

    /**
     * 整轮扫描没找到任何目标时，把<b>当时的判定条件</b>打出来。
     * <p>
     * 没有这行日志，"女仆不挖"只能靠猜：目标没配进去、工具不对、副手把目标限死了，
     * 三种情况在游戏里表现一模一样（1.1.0 实测就卡在这里）。每个会话只在第一轮空扫时
     * 打 WARN，后续轮次降到 INFO，避免刷屏。
     */
    private void warnEmptyScanOnce(ServerLevel level, EntityMaid maid) {
        int passes = finder.emptyPasses();
        if (passes <= this.loggedEmptyPass) {
            return;
        }
        this.loggedEmptyPass = passes;

        // 近距离探针：区分"根本没认成目标"与"认出来了但挖不动"，
        // 这两类问题在游戏里表现完全一样（女仆站着不动）。
        int nearbyTargets = 0;
        int nearbyBreakable = 0;
        BlockPos center = maid.blockPosition();
        for (int dx = -4; dx <= 4; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dz = -4; dz <= 4; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    // 1.21：hasChunkAt(int,int) 也已弃用，直接问区块坐标
                    if (!level.hasChunk(net.minecraft.core.SectionPos.blockToSectionCoord(pos.getX()),
                            net.minecraft.core.SectionPos.blockToSectionCoord(pos.getZ()))) {
                        continue;
                    }
                    BlockState state = level.getBlockState(pos);
                    if (!MiningValidator.isTarget(state)) {
                        continue;
                    }
                    nearbyTargets++;
                    if (MiningValidator.isTargetBreakable(level, pos, maid.getMainHandItem(),
                            maid.getOffhandItem())) {
                        nearbyBreakable++;
                    }
                }
            }
        }

        String message = "No target found after a full scan (pass #{}) mode={} whitelist={} blacklist={} "
                + "tool={} offhand={} maid={} restrict=[has={} center={} radius={} within={}] "
                + "nearbyTargets={} nearbyBreakable={} excluded={}";
        Object[] args = {
                passes,
                MiningConfig.currentTargetRules().mode(),
                MiningConfig.currentTargetRules().whitelist(),
                MiningConfig.currentTargetRules().blacklist(),
                BuiltInRegistries.ITEM.getKey(maid.getMainHandItem().getItem()),
                BuiltInRegistries.ITEM.getKey(maid.getOffhandItem().getItem()),
                center,
                maid.hasRestriction(),
                maid.hasRestriction() ? maid.getRestrictCenter() : "-",
                maid.hasRestriction() ? maid.getRestrictRadius() : "-",
                maid.hasRestriction() && maid.isWithinRestriction(center),
                nearbyTargets,
                nearbyBreakable,
                board.failedTargets().size()
        };
        if (passes == 1) {
            MaidMiningMod.LOGGER.warn("[MaidMining] " + message, args);
        } else if (MiningConfig.logInfoEnabled()) {
            MaidMiningMod.LOGGER.info("[MaidMining] " + message, args);
        }
    }

    // ========== DIG ==========

    private void digging(ServerLevel level, EntityMaid maid) {
        BlockPos target = board.target();
        if (target == null || !MiningValidator.isTargetBreakable(level, target,
                maid.getMainHandItem(), maid.getOffhandItem())) {
            if (target != null && MiningConfig.logDebugEnabled()) {
                BlockState state = level.getBlockState(target);
                MaidMiningMod.LOGGER.info("[MaidMining] Target invalid ({},{},{}) block={} maid={}",
                        target.getX(), target.getY(), target.getZ(),
                        state.isAir() ? "air" : String.valueOf(BuiltInRegistries.BLOCK.getKey(state.getBlock())),
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
        if (MiningValidator.isTargetBreakable(level, target, maid.getMainHandItem(), maid.getOffhandItem())
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
        return isWithinReach(maid.blockPosition(), pos, MiningConfig.adjacentDist());
    }

    /**
     * 女仆站在 {@code feet} 时能否直接挖到 {@code pos}。
     * <p>
     * 除了三轴都在 {@code reach} 内，额外允许<b>正上方多一格</b>：
     * 玩家站在下面本来就挖得到头顶 2 格的方块。1.1.0 实测里，头顶的目标因为
     * "不相邻"只能走去起塔，而手里没有垫脚建材时会一直卡到放弃目标
     * （日志：{@code Ascend failed dy=2 hDist=0 -> cannot ascend}）。
     * <p>
     * 下方不放开：向下够到会让她悬空后掉落，属于另一套下降逻辑，别在这里顺手改。
     */
    public static boolean isWithinReach(BlockPos feet, BlockPos pos, int reach) {
        int dx = Math.abs(pos.getX() - feet.getX());
        int dy = pos.getY() - feet.getY();
        int dz = Math.abs(pos.getZ() - feet.getZ());
        if (dx <= reach && dz <= reach && Math.abs(dy) <= reach) {
            return true;
        }
        return dx == 0 && dz == 0 && dy > 0 && dy <= reach + 1;
    }

    /** 供 i18n 提示使用的"当前在做什么"。 */
    public Component actionSummary() {
        return Component.translatable(board.state().translationKey());
    }
}
