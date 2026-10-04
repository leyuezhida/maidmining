package com.leyue.maidmining.cfg;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

/**
 * 模组配置定义（MM-701 / MM-702 / MM-704）。
 * <p>
 * <b>为什么改成 ForgeConfigSpec</b>：1.0.x 的全部参数都是 {@code static final} 常量
 * （{@code MiningConfig.SEARCH_RADIUS_BLOCKS} 之类），玩家在游戏里<b>一个都改不了</b>——
 * 搜索半径、扫描预算、是否跳过基岩邻近的矿，全都硬编码。配置化之后这些都能按服务器口味调。
 * <p>
 * <b>类型选 SERVER</b>：挖矿是服务端行为（女仆 AI 在服务端 tick），配置必须两侧一致，
 * 否则客户端显示与服务端实际行为会脱节。
 * <p>
 * <b>默认值体现"保守默认"原则（MM-704）</b>：涉及玩家资产与女仆性命的行为一律取最保守值——
 * 不进岩浆、不挖玩家方块、垫脚只用廉价方块、空扫指数退避。
 * <p>
 * 运行时读取走 {@link MiningConfig}（快照门面），业务代码不直接依赖本类，
 * 这样单元测试可以整体替换配置源。
 */
public final class MiningConfigSpec {

    public static final ForgeConfigSpec SPEC;

    // ==================== 搜索 ====================

    public static final ForgeConfigSpec.IntValue SEARCH_RADIUS;
    public static final ForgeConfigSpec.IntValue SEARCH_HEIGHT_UP;
    public static final ForgeConfigSpec.IntValue SEARCH_HEIGHT_DOWN;
    public static final ForgeConfigSpec.IntValue COLUMNS_PER_TICK;
    public static final ForgeConfigSpec.IntValue SCAN_COOLDOWN_TICKS;
    public static final ForgeConfigSpec.IntValue EMPTY_BACKOFF_MAX_TICKS;
    public static final ForgeConfigSpec.BooleanValue SKIP_NEAR_BEDROCK;
    public static final ForgeConfigSpec.BooleanValue RESPECT_RESTRICTION;

    // ==================== 移动 ====================

    public static final ForgeConfigSpec.DoubleValue SPEED_MULTIPLIER;
    public static final ForgeConfigSpec.BooleanValue ALLOW_TELEPORT_FALLBACK;
    public static final ForgeConfigSpec.IntValue CLIMB_DRIVE_TIMEOUT_TICKS;
    public static final ForgeConfigSpec.IntValue JUMP_HEADROOM;
    public static final ForgeConfigSpec.BooleanValue FOLLOW_OWNER_WHEN_IDLE;

    // ==================== 挖掘 ====================

    public static final ForgeConfigSpec.IntValue DIG_INTERVAL_TICKS;
    public static final ForgeConfigSpec.IntValue MAX_STUCK_COUNT;
    public static final ForgeConfigSpec.IntValue MAX_TEMPORARY_BREAK_FAILURES;
    public static final ForgeConfigSpec.IntValue MAX_NO_PROGRESS_TICKS;
    public static final ForgeConfigSpec.IntValue STALL_TAKEOVER_TICKS;
    public static final ForgeConfigSpec.DoubleValue MAX_DIGGABLE_HARDNESS;
    /** 判定"已贴近矿石、可以直接挖"的相邻距离（格）。 */
    public static final ForgeConfigSpec.IntValue ADJACENT_DIST;
    /** 同一目标反复锁定却始终挖不掉，达到该次数后进黑名单。 */
    public static final ForgeConfigSpec.IntValue MAX_TARGET_RETRIES;
    /** 挖矿是否消耗镐子耐久。默认 false（1.02 起按需求取消消耗）。 */
    public static final ForgeConfigSpec.BooleanValue DAMAGE_TOOL;
    /** 掉落是否按真实工具栈计算（时运 / 精准采集）。关掉则退回 TLM 的空手掉落。 */
    public static final ForgeConfigSpec.BooleanValue USE_REAL_TOOL_FOR_DROPS;

    // ==================== 物品 ====================

    public static final ForgeConfigSpec.BooleanValue PICKUP_OVERFLOW;
    public static final ForgeConfigSpec.IntValue TOOL_MIN_DURABILITY;
    public static final ForgeConfigSpec.BooleanValue SCAFFOLD_ENABLED;
    public static final ForgeConfigSpec.ConfigValue<List<? extends Object>> SCAFFOLD_WHITELIST;
    public static final ForgeConfigSpec.ConfigValue<List<? extends Object>> SCAFFOLD_BLACKLIST;

    // ==================== 安全 ====================

    public static final ForgeConfigSpec.BooleanValue ALLOW_LAVA;
    public static final ForgeConfigSpec.BooleanValue ALLOW_WATER;
    public static final ForgeConfigSpec.DoubleValue MIN_HEALTH_RETREAT;

    // ==================== 观测 ====================

    public static final ForgeConfigSpec.EnumValue<LogLevel> LOG_LEVEL;
    public static final ForgeConfigSpec.BooleanValue SHOW_ACTION_SUMMARY;

    // ==================== 兼容 ====================

    public static final ForgeConfigSpec.EnumValue<CompatMode> FAKE_PLAYER_MODE;

    /** 日志级别（MM-801）。 */
    public enum LogLevel {
        DEBUG, INFO, WARN, ERROR
    }

    /**
     * 兼容模式（MM-303b）。
     * <p>
     * 破坏默认走 {@code maid.destroyBlock}（尊重 {@code onEntityDestroyBlock}，TLM 生态行为不变）。
     * 只有显式选择 {@link #FAKE_PLAYER} 才会切到玩家破坏路径。
     */
    public enum CompatMode {
        /** 默认。走 TLM 的女仆破坏路径，保护 mod 通过 {@code LivingDestroyBlockEvent} 拦截。 */
        MAID,
        /** 用 FakePlayer 走玩家破坏路径，绝大多数领地 mod 的 {@code BlockEvent.BreakEvent} 能拦截。 */
        FAKE_PLAYER
    }

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        // ---------- 搜索 ----------
        builder.comment("Search: how the maid looks for ores.").push("search");
        SEARCH_RADIUS = builder
                .comment("Horizontal search radius in blocks.",
                        "The effective radius is clamped to the chunks the server has loaded,",
                        "so a large value never force-loads chunks on a low simulation-distance server.")
                .defineInRange("radiusBlocks", 48, 8, 128);
        SEARCH_HEIGHT_UP = builder
                .comment("How many blocks above the maid to search. Ores are underground, so this is small.")
                .defineInRange("heightUp", 3, 0, 64);
        SEARCH_HEIGHT_DOWN = builder
                .comment("How many blocks below the maid to search.")
                .defineInRange("heightDown", 48, 8, 128);
        COLUMNS_PER_TICK = builder
                .comment("Block-query budget per tick while scanning. Lower = gentler on TPS, slower to find ore.")
                .defineInRange("columnsPerTick", 128, 8, 1024);
        SCAN_COOLDOWN_TICKS = builder
                .comment("Cooldown after one full scan pass completes.")
                .defineInRange("scanCooldownTicks", 40, 0, 1200);
        EMPTY_BACKOFF_MAX_TICKS = builder
                .comment("When a full pass finds nothing, the cooldown grows exponentially up to this ceiling.",
                        "Stops the maid from burning hundreds of thousands of block queries per second",
                        "in an ore-free region.")
                .defineInRange("emptyBackoffMaxTicks", 600, 40, 6000);
        SKIP_NEAR_BEDROCK = builder
                .comment("Skip ores with bedrock within 3x3x3.",
                        "Kept ON by default: turning it off was tried and produced a fracture loop",
                        "near y=-60 (lock -> hit the same bedrock wall -> abandon, 4 times in 2.5s).",
                        "See OPTIMIZATION.md 1.5-A. Will be replaced by 'path around walls' in a later release.")
                .define("skipNearBedrock", true);
        RESPECT_RESTRICTION = builder
                .comment("Respect the maid's TLM restriction (stay-home radius).",
                        "Highly recommended: keeps a home-bound maid from tunnelling out of its area.")
                .define("respectRestriction", true);
        builder.pop();

        // ---------- 移动 ----------
        builder.comment("Movement: how the maid travels through the tunnel it digs.").push("travel");
        SPEED_MULTIPLIER = builder
                .comment("Movement speed MULTIPLIER applied to minecraft:generic.movement_speed (base 0.7).",
                        "1.0 = vanilla walking speed. This is not blocks-per-tick.",
                        "The 1.0.x constant was named MOVE_SPEED=0.9, which was ambiguous.")
                .defineInRange("speedMultiplier", 0.9D, 0.1D, 3.0D);
        ALLOW_TELEPORT_FALLBACK = builder
                .comment("Allow a collision-checked teleport as a last resort when a jump fails to make progress.",
                        "Leave ON: without it a 1-block-wide vertical shaft can deadlock the maid forever.")
                .define("allowTeleportFallback", true);
        CLIMB_DRIVE_TIMEOUT_TICKS = builder
                .comment("Ticks to keep driving a single step-up before falling back to a teleport.",
                        "Normal steps take a few ticks; this is only an anti-hang guard.")
                .defineInRange("climbDriveTimeoutTicks", 60, 10, 600);
        JUMP_HEADROOM = builder
                .comment("Extra headroom dug at the step being jumped into.",
                        "Do not set to 0 unless you are certain: a maid is 1.5 blocks tall, so in a 2-block",
                        "tunnel the ceiling caps a jump at ~0.5 blocks and a 1-block step becomes",
                        "physically unclimbable. See OPTIMIZATION.md 1.5-B.")
                .defineInRange("jumpHeadroom", 1, 0, 2);
        FOLLOW_OWNER_WHEN_IDLE = builder
                .comment("Let TLM's follow-owner behaviour win when the maid has no ore to dig.")
                .define("followOwnerWhenIdle", true);
        builder.pop();

        // ---------- 挖掘 ----------
        builder.comment("Digging: block breaking, drops and tool handling.").push("dig");
        DIG_INTERVAL_TICKS = builder
                .comment("Ticks between two dig actions. Gives the maid time to fall/move after breaking a block.")
                .defineInRange("intervalTicks", 6, 1, 40);
        MAX_STUCK_COUNT = builder
                .comment("Consecutive failed advance attempts before trying another route.")
                .defineInRange("maxStuckCount", 20, 1, 200);
        MAX_TEMPORARY_BREAK_FAILURES = builder
                .comment("Temporary break failures tolerated before abandoning the target.")
                .defineInRange("maxTemporaryBreakFailures", 3, 1, 50);
        MAX_NO_PROGRESS_TICKS = builder
                .comment("Abandon the target if the distance to it does not shrink for this many ticks.",
                        "Catches head-banging and bedrock-climbing loops.")
                .defineInRange("maxNoProgressTicks", 400, 40, 2400);
        STALL_TAKEOVER_TICKS = builder
                .comment("Take over horizontal movement directly after the maid has stood still this long.",
                        "vanilla MoveToTargetSink drops WALK_TARGET when it cannot path, which is the",
                        "usual reason a maid 'thinks' it is digging while standing still.")
                .defineInRange("stallTakeoverTicks", 20, 5, 200);
        MAX_DIGGABLE_HARDNESS = builder
                .comment("Blocks with getDestroySpeed >= this are treated as unbreakable (obsidian, etc.).",
                        "A heuristic for 'not worth digging', not a physical fact.")
                .defineInRange("maxDiggableHardness", 50.0D, 1.0D, 1000.0D);
        ADJACENT_DIST = builder
                .comment("How close the maid must be to break the ore directly (Chebyshev distance, in blocks).",
                        "1 means she can mine an ore one block up or sideways without stepping up.")
                .defineInRange("adjacentDist", 1, 1, 3);
        MAX_TARGET_RETRIES = builder
                .comment("Blacklist an ore after it has been locked this many times without being mined.")
                .defineInRange("maxTargetRetries", 8, 1, 100);
        DAMAGE_TOOL = builder
                .comment("Consume pickaxe durability while mining.",
                        "OFF since 1.02: the maid is expected to keep mining indefinitely, and TLM's",
                        "destroyBlock does not touch durability anyway, so the only cost was ours.",
                        "Turn ON if you want the tool to wear out the way it would for a player.")
                .define("damageTool", false);
        USE_REAL_TOOL_FOR_DROPS = builder
                .comment("Compute drops with the actual pickaxe in hand.",
                        "ON => Fortune multiplies ore yield and Silk Touch keeps the block itself.",
                        "OFF => falls back to TLM's path, which hardcodes an EMPTY tool stack and",
                        "therefore silently ignores both enchantments.")
                .define("useRealToolForDrops", true);
        builder.pop();

        // ---------- 物品 ----------
        builder.comment("Items: pickaxe selection, scaffolding blocks and overflow pickups.").push("items");
        PICKUP_OVERFLOW = builder
                .comment("Collect items that spilled on the ground because the backpack was full.")
                .define("pickupOverflow", true);
        TOOL_MIN_DURABILITY = builder
                .comment("Swap to another pickaxe when the current one has this much durability left.",
                        "Only has an effect while damageTool is ON.")
                .defineInRange("toolMinDurability", 10, 0, 200);
        SCAFFOLD_ENABLED = builder
                .comment("Allow placing blocks from the backpack to climb.")
                .define("scaffoldEnabled", true);
        SCAFFOLD_WHITELIST = builder
                .comment("Block items the maid may consume as scaffolding, best first.",
                        "Cheap, plentiful blocks only -- she is building a staircase, not decorating.")
                .defineListAllowEmpty("scaffoldWhitelist",
                        List.of("minecraft:cobblestone", "minecraft:cobbled_deepslate",
                                "minecraft:dirt", "minecraft:deepslate", "minecraft:andesite",
                                "minecraft:diorite", "minecraft:granite", "minecraft:tuff",
                                "minecraft:netherrack", "minecraft:blackstone"),
                        o -> o instanceof String);
        SCAFFOLD_BLACKLIST = builder
                .comment("Never consumed as scaffolding, whatever the whitelist says.",
                        "Protects the player's valuables and the maid's display slot.")
                .defineListAllowEmpty("scaffoldBlacklist",
                        List.of("#minecraft:ores", "#forge:ores", "minecraft:diamond_block",
                                "minecraft:emerald_block", "minecraft:gold_block",
                                "minecraft:iron_block", "minecraft:chest", "minecraft:barrel",
                                "minecraft:furnace", "minecraft:crafting_table"),
                        o -> o instanceof String);
        builder.pop();

        // ---------- 安全 ----------
        builder.comment("Safety: what the maid refuses to touch, and when she gives up.").push("safety");
        ALLOW_LAVA = builder
                .comment("Allow walking into lava. Strongly recommended to keep OFF:")
                .define("allowLava", false);
        ALLOW_WATER = builder
                .comment("Allow swimming through water. 1.20.1 aquifers are common underground.")
                .define("allowWater", true);
        MIN_HEALTH_RETREAT = builder
                .comment("Retreat to the owner at or below this health. 0 disables retreating.")
                .defineInRange("minHealthRetreat", 8.0D, 0.0D, 40.0D);
        builder.pop();

        // ---------- 观测 ----------
        builder.comment("Diagnostics: logging and player-facing feedback.").push("diag");
        LOG_LEVEL = builder
                .comment("Lowest level of maidmining log lines to emit. INFO is verbose (one line per ore);",
                        "WARN keeps only what a player would actually want to know.")
                .defineEnum("logLevel", LogLevel.WARN);
        SHOW_ACTION_SUMMARY = builder
                .comment("Report what the maid is currently doing through TLM's action summary,",
                        "so the player can read it in the maid GUI instead of guessing.")
                .define("showActionSummary", true);
        builder.pop();

        // ---------- 兼容 ----------
        builder.comment("Compatibility: how blocks are broken with other mods installed.").push("compat");
        FAKE_PLAYER_MODE = builder
                .comment("MAID (default): break via the maid, so protection mods see",
                        "LivingDestroyBlockEvent and TLM's ecosystem behaves exactly as before.",
                        "FAKE_PLAYER: break via a FakePlayer on the player code path, so most",
                        "territory/claim mods (which hook BlockEvent.BreakEvent) can intercept.",
                        "Trade-off: fake players can trigger stat/progress side effects.")
                .defineEnum("fakePlayerMode", CompatMode.MAID);
        builder.pop();

        SPEC = builder.build();
    }

    private MiningConfigSpec() {
    }
}
