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
}
