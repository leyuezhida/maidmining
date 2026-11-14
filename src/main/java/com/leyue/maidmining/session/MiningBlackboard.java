package com.leyue.maidmining.session;

import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 单个女仆的挖矿会话状态（MM-601 拆分的"黑板"）。
 * <p>
 * <b>为什么要抽出来</b>：1.0.x 的 {@code MiningTunnelBehavior} 涨到 954 行，
 * 状态字段与状态迁移逻辑、挖掘、移动、拾取全混在一起。
 * 把<b>可变状态</b>收进一个对象后，状态迁移代码就能单独阅读与测试，
 * 也为将来的 NBT 持久化（MM-602）准备好了落点 —— 届时只需给本类加读写方法。
 * <p>
 * <b>有界性</b>：黑名单与墙集合都带上限（MM-111），否则长期运行的女仆
 * 会把整张地图的基岩坐标都攒在内存里。
 */
public final class MiningBlackboard {

    /** 黑名单容量上限：够挡住反复撞同一面墙，又不会无界增长。 */
    private static final int MAX_FAILED_TARGETS = 512;
    /** 墙记录容量上限。 */
    private static final int MAX_WALLS = 4096;
    /** 失败计数里最多保留多少个目标的计数。 */
    private static final int MAX_FAIL_COUNTS = 512;

    // ===== 目标与状态 =====

    private MiningState state = MiningState.SEARCH;
    private BlockPos target;
    private BlockPos lastMined;

    // ===== 计时 =====

    /** 全局 tick 计数，用于周期性维护动作。 */
    private int timer;
    /** DIG 状态的挖掘节流计数。 */
    private int digTimer;
    /** 连续没能前进的次数（跨分支共享，是"卡住"的粗判据）。 */
    private int stuckCount;
    /** 暂时性破坏失败次数。 */
    private int temporaryFailures;

    // ===== 上行 =====

    /** 正在执行的上行目标。非 null 时由 {@code VerticalMover} 每 tick 直接驱动。 */
    private BlockPos climbDest;
    /** 本轮上行已经驱动了多少 tick。 */
    private int climbDriveTicks;
    /** 本轮上行开始时的 Y。 */
    private double climbDriveStartY;
    /** 上行是否由"发呆接管"触发（决定要不要起跳）。 */
    private boolean climbIsStallTakeover;

    // ===== 无进展看门狗 =====

    /** 上一次"有进展"时与目标的距离平方。 */
    private double lastDistanceSqr = -1.0D;
    /** 距离连续没有缩短的 tick 数。 */
    private int noProgressTicks;
    /** 为够到下方目标却持续上升的 tick 数（§1.5-A 死循环的直接判据）。 */
    private int upwardDriftTicks;

    // ===== 发呆接管 =====

    /** 最近一次下发的水平"下一格"。 */
    private BlockPos pendingStep;
    /** 位置连续未变的 tick 数。 */
    private int stationaryTicks;
    private double lastX;
    private double lastY;
    private double lastZ;
    private boolean lastPosValid;

    // ===== 诊断去重 =====

    private boolean headroomLogged;
    private boolean ascendFailLogged;
    private boolean noPickaxeWarned;

    // ===== 统计（MM-805 的最小实现）=====

    private int oresMined;
    private int targetsAbandoned;
    private int oresSkippedNearBedrock;

    // ===== 集合 =====

    /**
     * 黑名单（挖不到的目标），带容量上限。
     * <p>
     * 满了就淘汰最早加入的一条 —— 黑名单的价值随时间衰减
     * （当初挖不到可能是工具等级不够，现在换了更好的镐也许就能挖了）。
     */
    private final Set<BlockPos> failedTargets = new LinkedHashSet<>() {
        @Override
        public boolean add(BlockPos pos) {
            if (size() >= MAX_FAILED_TARGETS) {
                var it = iterator();
                if (it.hasNext()) {
                    var oldest = it.next();
                    it.remove();
                    failCounts.remove(oldest);
                }
            }
            return super.add(pos);
        }
    };
    private final Map<BlockPos, Integer> failCounts = new HashMap<>();
    /** 本会话确认"永远挖不动"的方块（基岩等），规划时当作墙。 */
    private final Set<BlockPos> wallBlocks = new HashSet<>();

    // ===== 访问器 =====

    public MiningState state() {
        return state;
    }

    public void state(MiningState next) {
        this.state = next;
    }

    public BlockPos target() {
        return target;
    }

    public void target(BlockPos pos) {
        this.target = pos;
    }

    public BlockPos lastMined() {
        return lastMined;
    }

    public void lastMined(BlockPos pos) {
        this.lastMined = pos;
    }

    public int timer() {
        return timer;
    }

    public void tickTimer() {
        timer++;
    }

    public void resetTimer() {
        timer = 0;
    }

    public int digTimer() {
        return digTimer;
    }

    public int bumpDigTimer() {
        return ++digTimer;
    }

    public void resetDigTimer() {
        digTimer = 0;
    }

    public int stuckCount() {
        return stuckCount;
    }

    /** 累加卡住计数；返回累加后的值。 */
    public int bumpStuck() {
        return ++stuckCount;
    }

    public void resetStuck() {
        stuckCount = 0;
    }

    public int temporaryFailures() {
        return temporaryFailures;
    }

    public int bumpTemporaryFailures() {
        return ++temporaryFailures;
    }

    public void resetTemporaryFailures() {
        temporaryFailures = 0;
    }

    public BlockPos climbDest() {
        return climbDest;
    }

    public int climbDriveTicks() {
        return climbDriveTicks;
    }

    public void bumpClimbDriveTicks() {
        climbDriveTicks++;
    }

    public double climbDriveStartY() {
        return climbDriveStartY;
    }

    public boolean climbIsStallTakeover() {
        return climbIsStallTakeover;
    }

    public void beginClimb(BlockPos dest, double startY, boolean stallTakeover) {
        this.climbDest = dest.immutable();
        this.climbDriveTicks = 0;
        this.climbDriveStartY = startY;
        this.climbIsStallTakeover = stallTakeover;
    }

    public void endClimb() {
        this.climbDest = null;
        this.climbDriveTicks = 0;
        this.climbDriveStartY = 0.0D;
        this.climbIsStallTakeover = false;
    }

    public double lastDistanceSqr() {
        return lastDistanceSqr;
    }

    public void lastDistanceSqr(double value) {
        this.lastDistanceSqr = value;
    }

    public int noProgressTicks() {
        return noProgressTicks;
    }

    public void resetNoProgress() {
        noProgressTicks = 0;
        upwardDriftTicks = 0;
    }

    public int bumpNoProgress() {
        return ++noProgressTicks;
    }

    public int upwardDriftTicks() {
        return upwardDriftTicks;
    }

    public int bumpUpwardDrift() {
        return ++upwardDriftTicks;
    }

    public void resetUpwardDrift() {
        upwardDriftTicks = 0;
    }

    public BlockPos pendingStep() {
        return pendingStep;
    }

    public void pendingStep(BlockPos pos) {
        this.pendingStep = pos == null ? null : pos.immutable();
    }

    public int stationaryTicks() {
        return stationaryTicks;
    }

    public int bumpStationary() {
        return ++stationaryTicks;
    }

    public void resetStationary() {
        stationaryTicks = 0;
        lastPosValid = false;
    }

    public double lastX() {
        return lastX;
    }

    public double lastY() {
        return lastY;
    }

    public double lastZ() {
        return lastZ;
    }

    public void lastPos(double x, double y, double z) {
        this.lastX = x;
        this.lastY = y;
        this.lastZ = z;
        this.lastPosValid = true;
    }

    public boolean lastPosValid() {
        return lastPosValid;
    }

    public void invalidateLastPos() {
        lastPosValid = false;
    }

    public boolean headroomLogged() {
        return headroomLogged;
    }

    public void markHeadroomLogged() {
        headroomLogged = true;
    }

    public boolean ascendFailLogged() {
        return ascendFailLogged;
    }

    public void markAscendFailLogged() {
        ascendFailLogged = true;
    }

    public boolean noPickaxeWarned() {
        return noPickaxeWarned;
    }

    public void markNoPickaxeWarned() {
        noPickaxeWarned = true;
    }

    public int oresMined() {
        return oresMined;
    }

    public int targetsAbandoned() {
        return targetsAbandoned;
    }

    public int oresSkippedNearBedrock() {
        return oresSkippedNearBedrock;
    }

    public void countMined() {
        oresMined++;
    }

    public void countAbandoned() {
        targetsAbandoned++;
    }

    public void countSkippedNearBedrock() {
        oresSkippedNearBedrock++;
    }

    public Set<BlockPos> failedTargets() {
        return failedTargets;
    }

    public Set<BlockPos> wallBlocks() {
        return wallBlocks;
    }

    /** 记录一块"永远挖不动"的方块；集合满时不再增长（避免无界内存）。 */
    public void recordWall(BlockPos pos) {
        if (wallBlocks.size() < MAX_WALLS) {
            wallBlocks.add(pos.immutable());
        }
    }

    public Map<BlockPos, Integer> failCounts() {
        return failCounts;
    }

    /**
     * 累加某目标的失败次数，返回累加后的值。
     * <p>
     * 计数表同样有界：{@code failCounts} 满了就清掉一半，
     * 否则它会比黑名单更难回收。
     */
    public int bumpFailCount(BlockPos pos) {
        if (failCounts.size() > MAX_FAIL_COUNTS) {
            failCounts.clear();
        }
        return failCounts.merge(pos, 1, Integer::sum);
    }

    /**
     * 锁定一个新目标：切到 DIG 并重置所有单目标计数器。
     * <p>
     * 刻意保留黑名单与失败计数——它们跨目标有效（见 {@link #backToSearch()}）。
     */
    public void lockTarget(BlockPos pos) {
        this.target = pos;
        this.state = MiningState.DIG;
        this.digTimer = 0;
        this.stuckCount = 0;
        this.temporaryFailures = 0;
        this.lastDistanceSqr = -1.0D;
        this.noProgressTicks = 0;
        this.upwardDriftTicks = 0;
        this.climbDest = null;
        this.climbDriveTicks = 0;
        this.climbDriveStartY = 0.0D;
        this.climbIsStallTakeover = false;
        this.headroomLogged = false;
        this.ascendFailLogged = false;
        this.lastPosValid = false;
    }

    /**
     * 回到「搜索新目标」状态：清空目标与所有单目标计数器。
     * <p>
     * <b>刻意不清</b>：{@link #failedTargets} 与 {@link #failCounts}。
     * 黑名单是跨目标的知识（这个矿挖不到），单目标失败计数同理 ——
     * 只有真正挖到了（{@code lastMined}）或被正式放弃时才更新它们。
     * 这也是「挖掉一个矿后重扫不会立刻又把同一个矿挖一遍」的依据。
     */
    public void backToSearch() {
        state = MiningState.SEARCH;
        timer = 0;
        target = null;
        lastMined = null;
        digTimer = 0;
        stuckCount = 0;
        climbDest = null;
        climbDriveTicks = 0;
        climbDriveStartY = 0.0D;
        climbIsStallTakeover = false;
        temporaryFailures = 0;
        lastDistanceSqr = -1.0D;
        noProgressTicks = 0;
        upwardDriftTicks = 0;
        pendingStep = null;
        stationaryTicks = 0;
        lastPosValid = false;
        headroomLogged = false;
        ascendFailLogged = false;
    }

    /**
     * 会话彻底结束（切任务 / 存档重载）：连黑名单与墙集合一起清空。
     * <p>
     * 与 {@link #backToSearch()} 的区别就是黑名单——会话结束后这些知识作废。
     */
    public void reset() {
        state = MiningState.SEARCH;
        target = null;
        lastMined = null;
        timer = 0;
        digTimer = 0;
        stuckCount = 0;
        temporaryFailures = 0;
        climbDest = null;
        climbDriveTicks = 0;
        climbDriveStartY = 0.0D;
        climbIsStallTakeover = false;
        lastDistanceSqr = -1.0D;
        noProgressTicks = 0;
        upwardDriftTicks = 0;
        pendingStep = null;
        stationaryTicks = 0;
        lastPosValid = false;
        headroomLogged = false;
        ascendFailLogged = false;
        noPickaxeWarned = false;
        failedTargets.clear();
        failCounts.clear();
        wallBlocks.clear();
    }
}
