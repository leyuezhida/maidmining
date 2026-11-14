package com.leyue.maidmining.cfg;

import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 运行时配置门面。
 * <p>
 * 1.0.x 的 {@code MiningConfig} 是一堆 {@code static final} 常量，玩家在游戏里<b>一个都改不了</b>（MM-701）。
 * 现在常量定义搬去了 {@link MiningConfigSpec}（{@link net.minecraftforge.common.ForgeConfigSpec}），
 * 这里只负责<b>把配置读成业务代码好用的类型</b>，并对标签类配置做缓存。
 * <p>
 * <b>为什么缓存标签集合</b>：建材白/黑名单、工具白/黑名单、目标白/黑名单都是字符串列表，
 * 但热路径每次判定都要问「这个方块/物品在名单里吗」。直接解析会让每次判定都走一遍
 * 注册表与标签查询。这里首次使用时解析成集合，之后只做哈希查找；
 * 配置或标签被改动时调 {@link #invalidateCaches()}。
 * <p>
 * 1.1.0 起工具与目标名单改用<b>不可变快照 + volatile 双检</b>（见 {@link ToolRules}）：
 * {@code isTarget} 在扫描时每方块都会调用，{@code synchronized} 方法即使无竞争
 * 也要付监视器开销，而一次 volatile 读几乎免费。
 * <p>
 * 时间单位：tick（1 秒 = 20 tick）。
 */
public final class MiningConfig {

    private static Set<Block> scaffoldWhitelist;
    private static Set<Block> scaffoldBlacklist;

    /**
     * 工具名单快照（1.1.0）。
     * <p>
     * <b>为什么用 record + volatile 而不是"几个字段 + synchronized getter"</b>：
     * {@code isTool} 在扫描热路径上会被频繁调用，{@code synchronized} 方法即使没有竞争
     * 也要付一次监视器进入/退出的代价；这里用"不可变快照 + 一次 volatile 读"，
     * 热路径开销只有字段访问，重建时才加锁。
     * <p>
     * <b>为什么是 public</b>：{@code MiningValidator} 的判定方法都接受一个显式规则快照
     * （而不是自己再去读配置）。这样 GameTest 可以直接构造规则去测每种组合，
     * 完全不必改写全局 {@code ForgeConfigSpec} —— GameTest 是并行跑的，
     * 改全局配置会和其他用例互相干扰。
     */
    public record ToolRules(Set<Item> whitelist, Set<Item> blacklist,
                            boolean whitelistConfigured, boolean requireCorrect,
                            boolean acceptAnyPickaxe) {
    }

    /** 目标名单快照（1.1.0）。原因同 {@link ToolRules}。 */
    public record TargetRules(Set<Block> whitelist, Set<Block> blacklist,
                              MiningConfigSpec.TargetMode mode, boolean offhandTargeting) {
    }

    private static volatile ToolRules toolRulesSnapshot;
    private static volatile TargetRules targetRulesSnapshot;

    /**
     * 配置"代数"：每次缓存失效就 +1。
     * <p>
     * 扫描器（{@code MiningTunnelFinder}）用它判断"玩家是否刚改过配置"——
     * 改完必须从头重扫，否则它会继续沿着旧的游标往前走，把改动前已经扫过的近处
     * 那一列跳过（1.1.0 实测：加了刷怪笼目标，女仆要等一整轮扫描 + 30 秒退避才回头）。
     */
    private static volatile int configGeneration;

    private MiningConfig() {
    }

    /**
     * 安全读取配置值：配置尚未加载时返回默认值而不是抛异常。
     * <p>
     * <b>为什么需要这一层</b>：Forge 的 {@code ConfigValue#get()} 在配置加载完成前会抛
     * {@code IllegalStateException("Cannot get config value before config is loaded")}。
     * 实机踩过一次：{@code FMLCommonSetupEvent} 里读配置就直接把 GameTest 服务端炸了
     * ——而 COMMON_SETUP 早于配置加载。除了"别在早期阶段读配置"这条纪律之外，
     * 这里再加一道兜底，让误用退化为"用默认值"而不是"崩溃"。
     *
     * @param reader 读取动作
     * @param fallback 配置不可用时的默认值（必须与 spec 里的 default 一致）
     */
    private static <T> T safe(java.util.function.Supplier<T> reader, T fallback) {
        try {
            return reader.get();
        } catch (IllegalStateException notLoadedYet) {
            return fallback;
        }
    }

    // ==================== 搜索 ====================

    public static int searchRadiusBlocks() {
        return safe(() -> MiningConfigSpec.SEARCH_RADIUS.get(), Defaults.SEARCH_RADIUS);
    }

    public static int searchHeightUp() {
        return safe(() -> MiningConfigSpec.SEARCH_HEIGHT_UP.get(), Defaults.SEARCH_HEIGHT_UP);
    }

    public static int searchHeightDown() {
        return safe(() -> MiningConfigSpec.SEARCH_HEIGHT_DOWN.get(), Defaults.SEARCH_HEIGHT_DOWN);
    }

    public static int columnsPerTick() {
        return safe(() -> MiningConfigSpec.COLUMNS_PER_TICK.get(), Defaults.COLUMNS_PER_TICK);
    }

    public static int scanCooldownTicks() {
        return safe(() -> MiningConfigSpec.SCAN_COOLDOWN_TICKS.get(), Defaults.SCAN_COOLDOWN_TICKS);
    }

    public static int emptyBackoffMaxTicks() {
        return safe(() -> MiningConfigSpec.EMPTY_BACKOFF_MAX_TICKS.get(), Defaults.EMPTY_BACKOFF_MAX_TICKS);
    }

    public static boolean skipNearBedrock() {
        return safe(() -> MiningConfigSpec.SKIP_NEAR_BEDROCK.get(), Defaults.SKIP_NEAR_BEDROCK);
    }

    public static boolean respectRestriction() {
        return safe(() -> MiningConfigSpec.RESPECT_RESTRICTION.get(), Defaults.RESPECT_RESTRICTION);
    }

    // ==================== 移动 ====================

    /** {@code minecraft:generic.movement_speed} 的倍率（属性基准 0.7），不是"格/tick"。 */
    public static float moveSpeed() {
        return safe(() -> MiningConfigSpec.SPEED_MULTIPLIER.get().floatValue(), Defaults.SPEED_MULTIPLIER);
    }

    public static boolean allowTeleportFallback() {
        return safe(() -> MiningConfigSpec.ALLOW_TELEPORT_FALLBACK.get(), Defaults.ALLOW_TELEPORT_FALLBACK);
    }

    public static int climbDriveTimeoutTicks() {
        return safe(() -> MiningConfigSpec.CLIMB_DRIVE_TIMEOUT_TICKS.get(), Defaults.CLIMB_DRIVE_TIMEOUT_TICKS);
    }

    public static int jumpHeadroom() {
        return safe(() -> MiningConfigSpec.JUMP_HEADROOM.get(), Defaults.JUMP_HEADROOM);
    }

    public static boolean followOwnerWhenIdle() {
        return safe(() -> MiningConfigSpec.FOLLOW_OWNER_WHEN_IDLE.get(), Defaults.FOLLOW_OWNER_WHEN_IDLE);
    }

    // ==================== 挖掘 ====================

    public static int digIntervalTicks() {
        return safe(() -> MiningConfigSpec.DIG_INTERVAL_TICKS.get(), Defaults.DIG_INTERVAL_TICKS);
    }

    public static int maxStuckCount() {
        return safe(() -> MiningConfigSpec.MAX_STUCK_COUNT.get(), Defaults.MAX_STUCK_COUNT);
    }

    public static int maxTemporaryBreakFailures() {
        return safe(() -> MiningConfigSpec.MAX_TEMPORARY_BREAK_FAILURES.get(), Defaults.MAX_TEMPORARY_BREAK_FAILURES);
    }

    public static int maxNoProgressTicks() {
        return safe(() -> MiningConfigSpec.MAX_NO_PROGRESS_TICKS.get(), Defaults.MAX_NO_PROGRESS_TICKS);
    }

    public static int stallTakeoverTicks() {
        return safe(() -> MiningConfigSpec.STALL_TAKEOVER_TICKS.get(), Defaults.STALL_TAKEOVER_TICKS);
    }

    public static float maxDiggableHardness() {
        return safe(() -> MiningConfigSpec.MAX_DIGGABLE_HARDNESS.get().floatValue(), Defaults.MAX_DIGGABLE_HARDNESS);
    }

    public static int adjacentDist() {
        return safe(() -> MiningConfigSpec.ADJACENT_DIST.get(), Defaults.ADJACENT_DIST);
    }

    public static int maxTargetRetries() {
        return safe(() -> MiningConfigSpec.MAX_TARGET_RETRIES.get(), Defaults.MAX_TARGET_RETRIES);
    }

    /** 是否消耗镐子耐久。1.02 起默认 false（按需求取消消耗）。 */
    public static boolean damageTool() {
        return safe(() -> MiningConfigSpec.DAMAGE_TOOL.get(), Defaults.DAMAGE_TOOL);
    }

    /** 掉落是否按真实工具栈计算（时运 / 精准采集）。 */
    public static boolean useRealToolForDrops() {
        return safe(() -> MiningConfigSpec.USE_REAL_TOOL_FOR_DROPS.get(), Defaults.USE_REAL_TOOL_FOR_DROPS);
    }

    // ==================== 物品 ====================

    public static boolean pickupOverflow() {
        return safe(() -> MiningConfigSpec.PICKUP_OVERFLOW.get(), Defaults.PICKUP_OVERFLOW);
    }

    public static int toolMinDurability() {
        return safe(() -> MiningConfigSpec.TOOL_MIN_DURABILITY.get(), Defaults.TOOL_MIN_DURABILITY);
    }

    public static boolean scaffoldEnabled() {
        return safe(() -> MiningConfigSpec.SCAFFOLD_ENABLED.get(), Defaults.SCAFFOLD_ENABLED);
    }

    /**
     * 是否可作垫脚建材。<b>黑名单优先于白名单</b>。
     * <p>
     * 旧实现只问「是不是有碰撞体积的方块」，于是会挑中主人背包里的钻石块与矿石块
     * （MM-502）。现在默认只允许廉价建材，且矿石/贵重块/容器一律拒绝。
     */
    public static boolean isScaffoldAllowed(Block block) {
        if (scaffoldWhitelist == null) {
            resolveScaffoldSets();
        }
        return !scaffoldBlacklist.contains(block) && scaffoldWhitelist.contains(block);
    }

    private static synchronized void resolveScaffoldSets() {
        scaffoldWhitelist = resolveBlocks(
                safe(() -> MiningConfigSpec.SCAFFOLD_WHITELIST.get(), Defaults.SCAFFOLD_WHITELIST));
        scaffoldBlacklist = resolveBlocks(
                safe(() -> MiningConfigSpec.SCAFFOLD_BLACKLIST.get(), Defaults.SCAFFOLD_BLACKLIST));
    }

    /**
     * 把配置里的字符串解析成方块集合。
     * <p>
     * 解析规则（{@code #标签} / {@code ns:*} / 注册名）统一在
     * {@link ConfigIdList} 里，这里只负责喂数据。
     */
    private static Set<Block> resolveBlocks(List<? extends Object> entries) {
        return ConfigIdList.resolve(ForgeRegistries.BLOCKS, entries);
    }

    // ==================== 工具（1.1.0，MM-1102） ====================

    /**
     * 当前工具规则快照。<b>生产代码与 {@code MiningValidator} 都从这里取规则</b>，
     * 再传进判定方法，避免判定方法内部反复读配置。
     */
    public static ToolRules currentToolRules() {
        ToolRules rules = toolRulesSnapshot;
        return rules != null ? rules : buildToolRules();
    }

    /**
     * 白名单是否被显式写过内容。
     * <p>
     * 判据是<b>原始配置条目非空</b>而不是"解析结果非空"：如果玩家写了 5 个
     * 拼错的 id，解析结果是空集，但意图明显是"只允许这几个"。这时应当表现为
     * 女仆找不到可用工具（可见的故障），而不是悄悄退回"允许所有镐子"。
     */
    public static boolean toolWhitelistConfigured() {
        return currentToolRules().whitelistConfigured();
    }

    /** 是否要求工具"能正确采掘目标方块"（含挖掘等级）。 */
    public static boolean requireCorrectTool() {
        return currentToolRules().requireCorrect();
    }

    /** 解析后的工具白名单（只读，诊断与测试用）。 */
    public static Set<Item> toolWhitelist() {
        return currentToolRules().whitelist();
    }

    /** 解析后的工具黑名单（只读，诊断与测试用）。 */
    public static Set<Item> toolBlacklist() {
        return currentToolRules().blacklist();
    }

    private static synchronized ToolRules buildToolRules() {
        if (toolRulesSnapshot != null) {
            return toolRulesSnapshot; // 双检：另一个线程可能已经建好了
        }
        List<? extends Object> rawWhitelist =
                safe(() -> MiningConfigSpec.TOOL_WHITELIST.get(), Defaults.TOOL_WHITELIST);
        ToolRules built = new ToolRules(
                ConfigIdList.resolve(ForgeRegistries.ITEMS, rawWhitelist),
                ConfigIdList.resolve(ForgeRegistries.ITEMS,
                        safe(() -> MiningConfigSpec.TOOL_BLACKLIST.get(), Defaults.TOOL_BLACKLIST)),
                hasAnyEntry(rawWhitelist),
                safe(() -> MiningConfigSpec.REQUIRE_CORRECT_TOOL.get(), Defaults.REQUIRE_CORRECT_TOOL),
                listsPickaxeTag(rawWhitelist));
        toolRulesSnapshot = built;
        return built;
    }

    // ==================== 目标（1.1.0，MM-1101） ====================

    /** 目标范围模式。{@code ORES} 即 1.0.x 的行为。 */
    /**
     * 当前目标规则快照。语义同 {@link #currentToolRules()}：
     * 生产代码取一次快照，判定方法接受它作为参数。
     */
    public static TargetRules currentTargetRules() {
        TargetRules rules = targetRulesSnapshot;
        return rules != null ? rules : buildTargetRules();
    }

    public static MiningConfigSpec.TargetMode targetMode() {
        return currentTargetRules().mode();
    }

    public static boolean isTargetWhitelisted(Block block) {
        return currentTargetRules().whitelist().contains(block);
    }

    public static boolean isTargetBlacklisted(Block block) {
        return currentTargetRules().blacklist().contains(block);
    }

    /** 解析后的目标白名单（只读，诊断与测试用）。 */
    public static Set<Block> targetWhitelist() {
        return currentTargetRules().whitelist();
    }

    /** 解析后的目标黑名单（只读，诊断与测试用）。 */
    public static Set<Block> targetBlacklist() {
        return currentTargetRules().blacklist();
    }

    /** 副手是否参与目标过滤（1.1.0 起副手也能指定非矿石方块）。 */
    public static boolean offhandTargeting() {
        return currentTargetRules().offhandTargeting();
    }

    private static synchronized TargetRules buildTargetRules() {
        if (targetRulesSnapshot != null) {
            return targetRulesSnapshot;
        }
        TargetRules built = new TargetRules(
                resolveBlocks(safe(() -> MiningConfigSpec.TARGET_WHITELIST.get(), Defaults.TARGET_WHITELIST)),
                resolveBlocks(safe(() -> MiningConfigSpec.TARGET_BLACKLIST.get(), Defaults.TARGET_BLACKLIST)),
                safe(() -> MiningConfigSpec.TARGET_MODE.get(), Defaults.TARGET_MODE),
                safe(() -> MiningConfigSpec.OFFHAND_TARGETING.get(), Defaults.OFFHAND_TARGETING));
        targetRulesSnapshot = built;
        return built;
    }

    /**
     * 白名单里是否显式列了"镐"类标签。
     * <p>
     * 默认白名单就是 {@code #minecraft:pickaxes} 等标签。只要它还留在列表里，
     * 就继续启用内置的"任何镐"识别（{@code ToolActions} / "能挖石头"兜底），
     * 这样没打标签的模组镐不会被漏掉；玩家把镐标签删干净时，才严格只认列表内的物品。
     */
    private static boolean listsPickaxeTag(List<? extends Object> entries) {
        for (Object raw : entries) {
            if (raw instanceof String text) {
                String entry = text.trim().toLowerCase(java.util.Locale.ROOT);
                if (entry.startsWith("#") && entry.contains("pickaxe")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 列表里是否有至少一个非空字符串条目。 */
    private static boolean hasAnyEntry(List<? extends Object> entries) {
        for (Object raw : entries) {
            if (raw instanceof String text && !text.trim().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 配置在游戏内被改动后调用，下一次访问时重新解析标签。
     * <p>
     * 1.1.0 起工具/目标名单也走同一套缓存，所以这里必须一并清掉——
     * 否则玩家在服务器上改了白名单，得重启才生效。
     */
    public static synchronized void invalidateCaches() {
        scaffoldWhitelist = null;
        scaffoldBlacklist = null;
        toolRulesSnapshot = null;
        targetRulesSnapshot = null;
        configGeneration++;
    }

    /** 当前配置代数；扫描器据此决定是否重头开始。 */
    public static int configGeneration() {
        return configGeneration;
    }

    // ==================== 安全 ====================

    public static boolean allowLava() {
        return safe(() -> MiningConfigSpec.ALLOW_LAVA.get(), Defaults.ALLOW_LAVA);
    }

    public static boolean allowWater() {
        return safe(() -> MiningConfigSpec.ALLOW_WATER.get(), Defaults.ALLOW_WATER);
    }

    public static float minHealthRetreat() {
        return safe(() -> MiningConfigSpec.MIN_HEALTH_RETREAT.get().floatValue(), Defaults.MIN_HEALTH_RETREAT);
    }

    // ==================== 观测 ====================

    public static boolean logDebugEnabled() {
        return logLevel().ordinal() <= MiningConfigSpec.LogLevel.DEBUG.ordinal();
    }

    public static boolean logInfoEnabled() {
        return logLevel().ordinal() <= MiningConfigSpec.LogLevel.INFO.ordinal();
    }

    private static MiningConfigSpec.LogLevel logLevel() {
        return safe(() -> MiningConfigSpec.LOG_LEVEL.get(), Defaults.LOG_LEVEL);
    }


    // ==================== 兼容 ====================

    public static boolean useFakePlayer() {
        MiningConfigSpec.CompatMode mode =
                safe(() -> MiningConfigSpec.FAKE_PLAYER_MODE.get(), Defaults.FAKE_PLAYER_MODE);
        return mode == MiningConfigSpec.CompatMode.FAKE_PLAYER;
    }

    /** 供 GameTest 断言配置边界用。 */
    public static List<Object> scaffoldWhitelistRaw() {
        return new ArrayList<>(safe(() -> MiningConfigSpec.SCAFFOLD_WHITELIST.get(),
                Defaults.SCAFFOLD_WHITELIST));
    }
}
