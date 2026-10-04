package com.leyue.maidmining.cfg;

import java.util.List;

/**
 * 配置默认值。
 * <p>
 * <b>这份清单必须与 {@link MiningConfigSpec} 里的 {@code default} 逐项一致</b>，
 * 否则"配置加载前退化为默认值"与"加载后用配置"会给出两套不同的行为。
 * <ul>
 *   <li>本类只服务于 {@link MiningConfig#safe}：配置尚未加载时（COMMON_SETUP 等早期阶段、
 *       或 GameTest 启动窗口）读取退化为默认值而不是抛异常；</li>
 *   <li>改 spec 的默认值时<b>必须</b>同步改这里 —— GameTest 断言配置语义时依赖一致性。</li>
 * </ul>
 */
final class Defaults {

    private Defaults() {
    }

    // 搜索
    static final int SEARCH_RADIUS = 48;
    static final int SEARCH_HEIGHT_UP = 3;
    static final int SEARCH_HEIGHT_DOWN = 48;
    static final int COLUMNS_PER_TICK = 128;
    static final int SCAN_COOLDOWN_TICKS = 40;
    static final int EMPTY_BACKOFF_MAX_TICKS = 600;
    static final boolean SKIP_NEAR_BEDROCK = true;
    static final boolean RESPECT_RESTRICTION = true;

    // 移动
    static final float SPEED_MULTIPLIER = 0.9F;
    static final boolean ALLOW_TELEPORT_FALLBACK = true;
    static final int CLIMB_DRIVE_TIMEOUT_TICKS = 60;
    static final int JUMP_HEADROOM = 1;
    static final boolean FOLLOW_OWNER_WHEN_IDLE = true;

    // 挖掘
    static final int DIG_INTERVAL_TICKS = 6;
    static final int MAX_STUCK_COUNT = 20;
    static final int MAX_TEMPORARY_BREAK_FAILURES = 3;
    static final int MAX_NO_PROGRESS_TICKS = 400;
    static final int STALL_TAKEOVER_TICKS = 20;
    static final float MAX_DIGGABLE_HARDNESS = 50.0F;
    static final int ADJACENT_DIST = 1;
    static final int MAX_TARGET_RETRIES = 8;
    static final boolean DAMAGE_TOOL = false;
    static final boolean USE_REAL_TOOL_FOR_DROPS = true;

    // 物品
    static final boolean PICKUP_OVERFLOW = true;
    static final int TOOL_MIN_DURABILITY = 10;
    static final boolean SCAFFOLD_ENABLED = true;

    // 安全
    static final boolean ALLOW_LAVA = false;
    static final boolean ALLOW_WATER = true;
    static final float MIN_HEALTH_RETREAT = 8.0F;

    // 观测
    static final MiningConfigSpec.LogLevel LOG_LEVEL = MiningConfigSpec.LogLevel.WARN;
    static final boolean SHOW_ACTION_SUMMARY = true;

    // 兼容
    static final MiningConfigSpec.CompatMode FAKE_PLAYER_MODE = MiningConfigSpec.CompatMode.MAID;

    /** 建材白名单（与 spec 一致）。 */
    static final List<String> SCAFFOLD_WHITELIST = List.of(
            "minecraft:cobblestone", "minecraft:cobbled_deepslate",
            "minecraft:dirt", "minecraft:deepslate", "minecraft:andesite",
            "minecraft:diorite", "minecraft:granite", "minecraft:tuff",
            "minecraft:netherrack", "minecraft:blackstone");

    /** 建材黑名单（与 spec 一致）。 */
    static final List<String> SCAFFOLD_BLACKLIST = List.of(
            "#minecraft:ores", "#forge:ores", "minecraft:diamond_block",
            "minecraft:emerald_block", "minecraft:gold_block",
            "minecraft:iron_block", "minecraft:chest", "minecraft:barrel",
            "minecraft:furnace", "minecraft:crafting_table");
}
