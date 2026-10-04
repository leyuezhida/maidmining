package com.leyue.maidmining.cfg;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 运行时配置门面。
 * <p>
 * 1.0.x 的 {@code MiningConfig} 是一堆 {@code static final} 常量，玩家在游戏里<b>一个都改不了</b>（MM-701）。
 * 现在常量定义搬去了 {@link MiningConfigSpec}（{@link net.minecraftforge.common.ForgeConfigSpec}），
 * 这里只负责<b>把配置读成业务代码好用的类型</b>，并对标签类配置做缓存。
 * <p>
 * <b>为什么缓存标签集合</b>：建材白/黑名单是字符串列表，但每次垫脚判定都要问
 * 「这个方块在名单里吗」。在热路径直接解析标签会让每次垫脚都走一遍注册表与标签查询。
 * 这里首次使用时解析成集合，之后只做哈希查找；配置被改动时调 {@link #invalidateCaches()}。
 * <p>
 * 时间单位：tick（1 秒 = 20 tick）。
 */
public final class MiningConfig {

    private static Set<Block> scaffoldWhitelist;
    private static Set<Block> scaffoldBlacklist;

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
     * 两种写法：以 {@code #} 开头按<b>标签</b>展开（{@code #minecraft:ores}），
     * 否则按方块注册名解析（{@code minecraft:cobblestone}）。
     */
    private static Set<Block> resolveBlocks(List<? extends Object> entries) {
        Set<Block> result = new HashSet<>();
        for (Object raw : entries) {
            if (!(raw instanceof String entry) || entry.isEmpty()) {
                continue;
            }
            boolean isTag = entry.charAt(0) == '#';
            ResourceLocation id = ResourceLocation.tryParse(isTag ? entry.substring(1) : entry);
            if (id == null) {
                continue;
            }
            if (isTag) {
                TagKey<Block> tag = TagKey.create(Registries.BLOCK, id);
                ForgeRegistries.BLOCKS.tags().getTag(tag).forEach(result::add);
                continue;
            }
            if (!ForgeRegistries.BLOCKS.containsKey(id)) {
                continue;
            }
            result.add(ForgeRegistries.BLOCKS.getValue(id));
        }
        return result;
    }

    /** 配置在游戏内被改动后调用，下一次访问时重新解析标签。 */
    public static synchronized void invalidateCaches() {
        scaffoldWhitelist = null;
        scaffoldBlacklist = null;
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

    public static boolean showActionSummary() {
        return safe(() -> MiningConfigSpec.SHOW_ACTION_SUMMARY.get(), Defaults.SHOW_ACTION_SUMMARY);
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
