package com.leyue.maidmining.mining;

/**
 * 挖矿行为的全部可调参数集中于此，方便统一维护与调优。
 * 时间单位：tick（1 秒 = 20 tick）。
 */
public final class MiningConfig {
    private MiningConfig() {
    }

    // ========== 目标搜索 ==========

    /** 搜索半径（区块）。1 区块 = 16 格。 */
    public static final int SEARCH_CHUNK_RADIUS = 3;
    /** 搜索半径（格） = 3 区块 × 16 = 48 格。 */
    public static final int SEARCH_RADIUS_BLOCKS = SEARCH_CHUNK_RADIUS * 16;
    /** 从女仆当前高度向上搜索的高度（格，矿通常在地下，向上搜少量即可）。 */
    public static final int SEARCH_VERTICAL_UP = 3;
    /** 从女仆当前高度向下搜索的深度（格）。大部分矿石在地表下，需向下深挖。 */
    public static final int SEARCH_VERTICAL_DOWN = 48;
    /** 每 tick 扫描的列数（控制性能，避免单 tick 卡顿）。 */
    public static final int COLUMNS_PER_TICK = 128;
    /** 一轮扫描完成后的冷却时间（tick），冷却结束再重新扫描。 */
    public static final int SCAN_COOLDOWN_TICKS = 40;

    // ========== 移动 ==========

    /** 女仆走向目标的移动速度。 */
    public static final float MOVE_SPEED = 0.9F;

    // ========== 隧道挖掘 ==========

    /** 两次挖掘动作之间的间隔（tick），给女仆时间下落/移动，避免挖穿后卡住。 */
    public static final int DIG_INTERVAL_TICKS = 6;
    /** 判定“已贴近矿石、可以直接挖”的相邻距离。 */
    public static final int ADJACENT_DIST = 1;
    /** 连续挖不动（被基岩/岩浆挡住）达到该次数时，放弃当前目标换一个。 */
    public static final int MAX_STUCK_COUNT = 20;
    /** 同一目标反复锁定却始终无法挖掉（够不到/挖不动/破坏失败）达到该次数时，加入黑名单。 */
    public static final int MAX_TARGET_RETRIES = 8;
    /** 可挖硬度上限：{@code >=} 该值视为「永远挖不动」（基岩由单独分支处理）。 */
    public static final float MAX_DIGGABLE_HARDNESS = 50.0F;

    // ========== 上行（VerticalMover 语义，见 OPTIMIZATION.md §1.5-B） ==========

    /**
     * 上行驱动的超时 tick 数。超过则做一次校验后的传送兜底。
     * <p>
     * 上行是<b>每 tick 直接驱动 {@code MoveControl}</b>（绕过寻路，见 §9 第四轮），
     * 正常情况下一格台阶只需几 tick；这个上限是防止任何异常状态下无限驱动。
     */
    public static final int CLIMB_DRIVE_TIMEOUT_TICKS = 60;

    /**
     * 上行起跳所需的额外净空（格）。
     * <p>
     * 为什么必须有它：女仆碰撞箱高 1.5，在 1×2 隧道里身体占 [y, y+1.5]，天花板在 y+2 ⇒
     * 跳起来最多抬高 <b>0.5 格</b>，而台阶有 1.0 格 —— <b>物理上永远迈不上去</b>。
     * 这正是原始版本把整条矿道挖成 1×3 的原因（见 §1.5-B）。
     * <p>
     * 正确做法不是全局加高，而是只在起跳那一格多挖 1 格，形成一个局部"站位凹坑"
     * （玩家在 2 格高巷道里上台阶时也是这么做的）。
     */
    public static final int JUMP_HEADROOM = 1;

    // ========== 受阻与无进展 ==========

    /** 暂时性破坏失败的允许重试次数，超过即放弃目标。 */
    public static final int MAX_TEMPORARY_BREAK_FAILURES = 3;
    /**
     * 无进展看门狗：与目标的距离连续这么多 tick 没有缩短，就放弃该目标。
     * 用于兜住「顶墙空转」「贴着基岩上爬」这类死循环（MM-306 的最小实现）。
     */
    public static final int MAX_NO_PROGRESS_TICKS = 400;

    /**
     * 发呆接管阈值：DIG 状态下位置连续这么多 tick 没变，就接管为"绕过寻路"的直接驱动。
     * <p>
     * 依据（§9 第六轮实机日志）：目标在同层 4 格外、{@code dy=0}，连续 20 秒 {@code feet} 完全不变——
     * 代码每 6 tick 都在发走位目标，但 vanilla {@code MoveToTargetSink} 用
     * {@code navigation.createPath()} 造路，拿不到路径时会直接抹掉 {@code WALK_TARGET} 并停下。
     */
    public static final int STALL_TAKEOVER_TICKS = 20;

    // ========== 历史补偿开关 ==========

    /**
     * 是否跳过「基岩邻近」的矿石。
     * <p>
     * <b>历史的反复</b>：它最初是为掩盖"女仆被基岩卡住"而加的补偿（§1.5-A）；
     * 破坏失败分类 + 立即放弃落地后曾被关掉；但实机发现深层（y ≤ -60）会出现
     * 「连续锁定→撞同一面基岩墙→放弃」的碎裂循环（2026-09-20 第五轮日志：
     * 2.5 秒内 4 次撞同一墙块），女仆表现为站着反复搜索。故默认重新开启。
     * <p>
     * 取舍：开启会丢弃一部分**本来可挖**的深层矿（旧实测丢弃率 37.5%）；
     * 关闭则保留全部候选，但会在基岩层附近出现上述碎裂循环。
     * 规划器（MM-202）落地后应改为"按墙绕行"，届时本开关可以真正删除。
     */
    public static final boolean SKIP_NEAR_BEDROCK = true;
}
