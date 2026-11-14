package com.leyue.maidmining.mining;

import com.leyue.maidmining.MaidMiningMod;
import com.leyue.maidmining.cfg.MiningConfig;
import com.leyue.maidmining.cfg.MiningConfigSpec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.ToolActions;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 目标方块与工具的校验规则。
 * <p>
 * <b>1.1.0 的变化（MM-1101 / MM-1102）</b>：1.0.x 把"什么是矿石"和"什么是镐子"
 * 都写死在这里，玩家只能改数值、不能改集合。现在这两件事都先问配置
 * （{@link MiningConfig}），配置没写时才退回内置规则，因此：
 * <ul>
 *   <li><b>工具</b>：{@code tools.whitelist} 可以列任意物品（铲子、模组工具…），
 *       黑名单优先级最高；留空则沿用"任何镐子"的老判定；</li>
 *   <li><b>目标</b>：{@code targets.mode} 三档（仅矿石 / 仅白名单 / 两者并集），
 *       外加黑名单兜底；默认 {@code ORES} 与 1.0.2 完全等价；</li>
 *   <li><b>副手</b>：除了原来的"矿石材料名"匹配，现在还支持"放什么方块就挖什么方块"。</li>
 * </ul>
 * <p>
 * <b>为什么矿石识别仍保留双保险</b>：某些环境下 {@code #minecraft:ores} 标签可能无法命中，
 * 因此采用「标签优先 + 方块注册名兜底」的策略，见 {@link #looksLikeOre}。
 */
public final class MiningValidator {
    /** 矿石方块标签，覆盖原版 + Forge + Fabric 三方矿石。 */
    public static final TagKey<Block> ORES_TAG = TagKey.create(Registries.BLOCK, oreTag("minecraft", "ores"));
    public static final TagKey<Block> FORGE_ORES_TAG = TagKey.create(Registries.BLOCK, oreTag("forge", "ores"));
    public static final TagKey<Block> C_ORES_TAG = TagKey.create(Registries.BLOCK, oreTag("c", "ores"));

    /**
     * 镐子标签。
     * <p>
     * 已核实：原版 {@code minecraft:pickaxes} 只列了 6 把原版镐；Forge 1.20.1 也不提供统一的镐标签。
     * mod 镐的识别主要靠 {@link ToolActions#PICKAXE_DIG} 与"能否正确开采石头"兜底
     * （见 {@link #isBuiltinPickaxe}）。这两个额外标签是给遵循该约定的 mod 留的入口，不存在也不会报错。
     */
    public static final TagKey<Item> FORGE_PICKAXES_TAG =
            TagKey.create(Registries.ITEM, oreTag("forge", "tools/pickaxes"));
    public static final TagKey<Item> C_PICKAXES_TAG =
            TagKey.create(Registries.ITEM, oreTag("c", "tools/pickaxes"));

    /** 构造标签 id。用 tryBuild 取代已标记为删除的 ResourceLocation(String,String)。 */
    private static ResourceLocation oreTag(String namespace, String path) {
        ResourceLocation id = ResourceLocation.tryBuild(namespace, path);
        if (id == null) {
            throw new IllegalStateException("Invalid tag id: " + namespace + ":" + path);
        }
        return id;
    }

    /**
     * 少数「名字不遵循 {@code <mat>_ore}、又没有 {@code ores/<mat>} 子标签」的方块 → 材料名。
     * <p>
     * {@code ancient_debris} 是典型：Forge 给它的标签是 <b>{@code forge:ores/netherite_scrap}</b>
     * （已核实 Forge 源码 {@code Tags.Blocks.ORES_NETHERITE_SCRAP}），所以材料名取 {@code netherite_scrap}，
     * 副手放 {@code netherite_scrap} 或远古残骸方块都能对上。
     */
    private static final Map<String, String> ORE_BLOCK_MATERIAL_OVERRIDES = Map.of(
            "ancient_debris", "netherite_scrap");

    /** 原版/常见 mod 里「物品名 ≠ 矿石材料名」的特例。 */
    private static final Map<String, String> MATERIAL_ALIASES = Map.of(
            "lapis_lazuli", "lapis",
            "ancient_debris", "netherite_scrap",
            "quartz", "nether_quartz",
            "amethyst_shard", "amethyst");

    /** 物品侧的材料标签前缀：{@code forge:raw_materials/nickel} ⇒ nickel。 */
    private static final String[] ITEM_TAG_PREFIXES = {
            "raw_materials/", "ores/", "gems/", "dusts/", "ingots/", "nuggets/", "raw_blocks/", "storage_blocks/"};

    /** 已经告警过的副手物品（避免刷屏）。 */
    private static final Set<String> WARNED_OFFHAND = new HashSet<>();

    // 懒加载缓存：扫描热路径每方块都会问"是不是矿石 / 是什么材料"，不能每次都做标签与注册表查询（MM-103）
    private static Set<Block> oreBlocks;
    private static Map<Block, String> oreBlockMaterials;
    private static Set<String> oreMaterials;

    // 副手解析缓存：扫描热路径里也会反复问同一个副手物品
    private static ItemStack cachedOffhand = ItemStack.EMPTY;
    private static OffhandTarget cachedTarget;
    private static boolean cachedValid;

    /**
     * 副手指定的目标。两个字段互斥地生效：
     * <ul>
     *   <li>{@code material} 非空 → 按矿石材料名匹配（1.0.x 的语义，放原矿/锭/宝石）；</li>
     *   <li>{@code block} 非空 → 按方块精确匹配（1.1.0 新增，放沙砾就只挖沙砾）；</li>
     *   <li>两者都空 → 无法识别，按"不限制"处理。</li>
     * </ul>
     */
    private record OffhandTarget(String material, Block block) {
    }

    /** 目标站立点周围需要检测的偏移（覆盖脚下、头顶、四周墙壁与地板矿石）。 */
    private static final BlockPos[] NEARBY_OFFSETS = {
            new BlockPos(0, 0, 0), new BlockPos(0, 1, 0), new BlockPos(0, -1, 0),
            new BlockPos(1, 0, 0), new BlockPos(-1, 0, 0), new BlockPos(0, 0, 1), new BlockPos(0, 0, -1),
            new BlockPos(1, 1, 0), new BlockPos(-1, 1, 0), new BlockPos(0, 1, 1), new BlockPos(0, 1, -1),
    };

    private MiningValidator() {
    }

    // ==================== 目标方块 ====================

    /**
     * 是否为矿石方块（<b>内置规则</b>，不看配置）。
     * <p>
     * 判定顺序：{@code minecraft:ores} / {@code forge:ores} / {@code c:ores} 三大标签 →
     * <b>任意 {@code <ns>:ores/<材料>} 子标签</b>（这是 mod 矿石的主力来源：镍、铝、铅、锡…
     * 都遵循 {@code forge:ores/<材料>} 约定） → 显式覆盖表（远古残骸）→ {@code _ore} 命名兜底。
     * <p>
     * 结果在首次调用时建成 {@code Set<Block>} 缓存，热路径只做一次集合查找。
     */
    public static boolean isOre(BlockState state) {
        return oreBlockSet().contains(state.getBlock());
    }

    /**
     * 是否是女仆该去找的目标方块（1.1.0，配置驱动）。
     * <p>
     * 判定顺序：<b>黑名单</b>（最高优先级，连矿石标签也压得住）→ 白名单（仅非 {@code ORES} 模式）
     * → 矿石集合（仅非 {@code WHITELIST} 模式）。
     * <p>
     * 这样写的好处是默认配置（{@code mode=ORES}、两个名单都空）下只剩
     * 「黑名单查空集 + 矿石集合查表」两次哈希，与 1.0.2 的开销基本一致。
     */
    public static boolean isTarget(BlockState state) {
        return isTarget(state, MiningConfig.currentTargetRules());
    }

    /**
     * 用<b>显式规则</b>判定目标（测试与诊断用）。
     * <p>
     * 把规则作为参数而不是每次都读全局配置，是为了让 GameTest 能直接构造
     * "只挖沙砾"这类规则去验证每种模式 —— GameTest 并行执行，改写全局
     * {@code ForgeConfigSpec} 会干扰其他用例。
     */
    public static boolean isTarget(BlockState state, MiningConfig.TargetRules rules) {
        Block block = state.getBlock();
        if (rules.blacklist().contains(block)) {
            return false;
        }
        if (rules.mode() != MiningConfigSpec.TargetMode.ORES && rules.whitelist().contains(block)) {
            return true;
        }
        return rules.mode() != MiningConfigSpec.TargetMode.WHITELIST && isOre(state);
    }

    /** 标签更新（数据包重载）后调用：清掉矿石与副手缓存，下一次访问时重建。 */
    public static synchronized void invalidateCaches() {
        oreBlocks = null;
        oreBlockMaterials = null;
        oreMaterials = null;
        cachedValid = false;
        cachedOffhand = ItemStack.EMPTY;
        cachedTarget = null;
    }

    private static Set<Block> oreBlockSet() {
        if (oreBlocks == null) {
            buildOreCaches();
        }
        return oreBlocks;
    }

    private static Map<Block, String> oreBlockMaterialMap() {
        if (oreBlockMaterials == null) {
            buildOreCaches();
        }
        return oreBlockMaterials;
    }

    private static void buildOreCaches() {
        Set<Block> blocks = new HashSet<>();
        Map<Block, String> materials = new HashMap<>();
        for (Block block : ForgeRegistries.BLOCKS) {
            ResourceLocation id = ForgeRegistries.BLOCKS.getKey(block);
            if (id == null) {
                continue;
            }
            BlockState state = block.defaultBlockState();
            if (!looksLikeOre(state, id.getPath())) {
                continue;
            }
            blocks.add(block);
            String material = materialOfOreBlock(state, id.getPath());
            if (material != null) {
                materials.put(block, material);
            }
        }
        oreBlocks = blocks;
        oreBlockMaterials = materials;
        oreMaterials = new HashSet<>(materials.values());
    }

    private static boolean looksLikeOre(BlockState state, String path) {
        if (state.is(ORES_TAG) || state.is(FORGE_ORES_TAG) || state.is(C_ORES_TAG)) {
            return true;
        }
        if (ORE_BLOCK_MATERIAL_OVERRIDES.containsKey(path)) {
            return true;
        }
        if (path.endsWith("_ore")) {
            return true;
        }
        for (TagKey<Block> tag : state.getTags().toList()) {
            if (tag.location().getPath().startsWith("ores/")) {
                return true;
            }
        }
        return false;
    }

    private static String materialOfOreBlock(BlockState state, String path) {
        String override = ORE_BLOCK_MATERIAL_OVERRIDES.get(path);
        if (override != null) {
            return override;
        }
        String base = path.startsWith("deepslate_") ? path.substring("deepslate_".length()) : path;
        if (base.endsWith("_ore")) {
            return base.substring(0, base.length() - "_ore".length());
        }
        for (TagKey<Block> tag : state.getTags().toList()) {
            String tagPath = tag.location().getPath();
            if (tagPath.startsWith("ores/")) {
                return tagPath.substring("ores/".length());
            }
        }
        return null;
    }

    // ==================== 工具 ====================

    /**
     * 这个物品能不能当工具用（1.1.0，配置驱动）。
     * <p>
     * 判定顺序：<b>黑名单</b> → 白名单（写过就只认白名单）→ 内置镐子规则。
     * 因此：
     * <ul>
     *   <li>不配置任何东西 → 与 1.0.2 完全一致（任何镐子都可以）；</li>
     *   <li>{@code tools.whitelist = ["minecraft:iron_axe"]} → 背包里的镐子不再被拿起；</li>
     *   <li>白名单写 {@code #minecraft:pickaxes} 再补几个 mod 工具 → 两者都认。</li>
     * </ul>
     */
    public static boolean isTool(ItemStack stack) {
        return isTool(stack, MiningConfig.currentToolRules());
    }

    /**
     * 用<b>显式规则</b>判定工具（测试与诊断用）。语义同 {@link #isTool(ItemStack)}，
     * 只是规则由调用方给，不读全局配置。
     */
    public static boolean isTool(ItemStack stack, MiningConfig.ToolRules rules) {
        if (stack.isEmpty()) {
            return false;
        }
        if (rules.blacklist().contains(stack.getItem())) {
            return false;
        }
        if (rules.whitelistConfigured()) {
            // 白名单里有镐类标签时，仍保留"任何镐"的兜底识别（覆盖没打标签的模组镐）
            return rules.whitelist().contains(stack.getItem())
                    || (rules.acceptAnyPickaxe() && isBuiltinPickaxe(stack));
        }
        return isBuiltinPickaxe(stack);
    }

    /**
     * 内置"是不是镐子"的判定。
     * <p>
     * 依次判定：{@code #minecraft:pickaxes}（原版 6 把）→ {@code forge/c:tools/pickaxes} →
     * {@link ToolActions#PICKAXE_DIG}（mod 镐的主力：任何继承 {@code DiggerItem} 的工具都会命中）→
     * **能正确开采石头**（覆盖既没进标签、也没实现 ToolActions 的自定义工具）。
     */
    private static boolean isBuiltinPickaxe(ItemStack tool) {
        if (tool.isEmpty()) {
            return false;
        }
        if (tool.is(ItemTags.PICKAXES) || tool.is(FORGE_PICKAXES_TAG) || tool.is(C_PICKAXES_TAG)) {
            return true;
        }
        if (tool.canPerformAction(ToolActions.PICKAXE_DIG)) {
            return true;
        }
        return tool.isCorrectToolForDrops(Blocks.STONE.defaultBlockState());
    }

    /**
     * 旧名字，保留给外部调用（例如附属模组或旧测试）。
     *
     * @deprecated 1.1.0 起工具集合由配置决定，请用 {@link #isTool(ItemStack)}。
     *         注意本方法<b>只看内置规则、不看配置</b>，语义是"这是不是一把镐"。
     */
    @Deprecated
    public static boolean isPickaxe(ItemStack tool) {
        return isBuiltinPickaxe(tool);
    }

    /**
     * 当前工具能否正确破坏该方块。
     * <p>
     * 1.0.x 这里有一道 {@code isPickaxe} 硬门槛，导致铲子等非镐工具永远通不过。
     * 1.1.0 改为：<b>先过工具名单</b>，再按 {@code requireCorrectTool} 决定是否要求
     * "正确工具 + 足够挖掘等级"（石镐挖不动钻石矿这条规则在这个开关为 ON 时依然生效）。
     */
    public static boolean canBreak(BlockState state, ItemStack tool) {
        return canBreak(state, tool, MiningConfig.currentToolRules());
    }

    /**
     * 用<b>显式规则</b>判定"当前工具能否正确破坏该方块"（测试与诊断用）。
     *
     * @param rules 工具规则；{@code requireCorrect} 决定是否强制"正确工具 + 足够等级"
     */
    public static boolean canBreak(BlockState state, ItemStack tool, MiningConfig.ToolRules rules) {
        if (state.isAir()) {
            return false;
        }
        if (!isTool(tool, rules)) {
            return false;
        }
        if (!rules.requireCorrect()) {
            return true;
        }
        // 方块本身不要求"正确工具"时（刷怪笼、玻璃、树叶…），原版规则是"拿什么都能挖"，
        // 只是掉落由战利品表决定（多数需要精准采集）。这里不能再用工具类型把它挡掉，
        // 否则把刷怪笼加进目标名单会完全无效（1.1.0 实测反馈）。
        if (!state.requiresCorrectToolForDrops()) {
            return true;
        }
        return tool.isCorrectToolForDrops(state);
    }

    // ==================== 可挖判定 ====================

    /** 指定位置是否为「可挖的目标」（不限定类型、不限定副手）。 */
    public static boolean isTargetBreakable(ServerLevel level, BlockPos pos, ItemStack tool) {
        return isTargetBreakable(level, pos, tool, ItemStack.EMPTY);
    }

    /**
     * 指定位置是否为「可挖的目标」。
     * <p>
     * 三个条件同时成立：是目标方块（{@code targets.*}）→ 符合副手指定（{@code isTargetedBy}）
     * → 当前工具挖得动（{@code canBreak}）。
     */
    public static boolean isTargetBreakable(ServerLevel level, BlockPos pos, ItemStack tool, ItemStack offhand) {
        return isTargetBreakable(level, pos, tool, offhand,
                MiningConfig.currentTargetRules(), MiningConfig.currentToolRules());
    }

    /**
     * 用显式规则判定"这个位置可挖"（测试用）。
     * <p>
     * <b>白名单优先于副手</b>：玩家显式写进 {@code targets.whitelist} 的方块不会被副手过滤掉。
     * 副手往往只是之前测试留在那的东西（1.1.0 实测：副手放着刷怪笼，之后把制箭台加进白名单，
     * 她死活不挖），而白名单才是明确意图。副手仍然会在"只挖矿"模式下筛选矿种。
     */
    public static boolean isTargetBreakable(ServerLevel level, BlockPos pos, ItemStack tool, ItemStack offhand,
                                            MiningConfig.TargetRules targets, MiningConfig.ToolRules tools) {
        BlockState state = level.getBlockState(pos);
        if (!isTarget(state, targets) || !canBreak(state, tool, tools)) {
            return false;
        }
        boolean explicit = targets.mode() != MiningConfigSpec.TargetMode.ORES
                && targets.whitelist().contains(state.getBlock());
        return explicit || isTargetedBy(state, offhand);
    }

    // ==================== 副手过滤 ====================

    /**
     * 副手物品 → 目标矿石材料名（如 "iron"、"nickel"、"diamond"）；null = 未指定或无法识别。
     * <p>
     * 解析顺序（见 OPTIMIZATION.md MM-108，覆盖旧实现只认 {@code raw_*}/{@code *_ore} 的缺陷）：
     * <ol>
     *   <li><b>命名模式</b>：{@code raw_iron} / {@code raw_iron_block} / {@code iron_ore} /
     *       {@code deepslate_iron_ore} / {@code iron_ingot} / {@code iron_dust} / {@code iron_block}</li>
     *   <li><b>物品标签</b>：{@code forge:raw_materials/nickel}、{@code forge:ingots/aluminum}、
     *       {@code forge:gems/…}、{@code c:…} —— 这是 <b>mod 材料的主力来源</b>，
     *       能覆盖 {@code mekanism:ingot_tin} 这类"名字不按套路"的物品</li>
     *   <li><b>别名表</b>：{@code lapis_lazuli→lapis}、{@code ancient_debris→netherite_scrap}、
     *       {@code quartz→nether_quartz}、{@code amethyst_shard→amethyst}</li>
     *   <li>兜底：物品名本身就是材料名（{@code diamond} / {@code coal} / {@code redstone}）</li>
     * </ol>
     * 解析结果与「世界里真实存在的矿石材料」核对；核对失败返回 null，
     * 由 {@link #offhandTarget} 决定是否退化为"按方块匹配"。
     */
    public static String targetOreName(ItemStack offhand) {
        if (offhand.isEmpty()) {
            return null;
        }
        return offhandTarget(offhand).material();
    }

    private static OffhandTarget offhandTarget(ItemStack offhand) {
        if (cachedValid && ItemStack.matches(cachedOffhand, offhand)) {
            return cachedTarget;
        }
        String material = resolveTargetMaterial(offhand);
        Block block = blockOf(offhand);
        if (material == null && block == null) {
            warnUnresolvableOffhand(offhand);
        }
        cachedOffhand = offhand.copy();
        cachedTarget = new OffhandTarget(material, block);
        cachedValid = true;
        return cachedTarget;
    }

    private static Block blockOf(ItemStack stack) {
        return stack.getItem() instanceof BlockItem blockItem ? blockItem.getBlock() : null;
    }

    private static void warnUnresolvableOffhand(ItemStack offhand) {
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(offhand.getItem());
        if (key == null || !WARNED_OFFHAND.add(key.getPath())) {
            return;
        }
        MaidMiningMod.LOGGER.warn(
                "[MaidMining] Offhand item {} is neither an ore material nor a placeable block; "
                        + "it will not narrow the target", key);
    }

    private static String resolveTargetMaterial(ItemStack offhand) {
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(offhand.getItem());
        if (key == null) {
            return null;
        }
        String path = key.getPath();
        // 逐个候选核对，取第一个"世界里真有这种矿石"的：
        // 顺序很重要——物品标签可能给出一个无效材料（如 quartz），而别名才是对的（nether_quartz）。
        String[] candidates = {
                materialFromItemName(path),
                materialFromItemTags(offhand),
                MATERIAL_ALIASES.get(path),
                path};
        Set<String> known = oreMaterials();
        for (String candidate : candidates) {
            if (candidate != null && known.contains(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** 物品注册名 → 材料名（raw_* / *_ore / deepslate_* / *_ingot / *_gem / *_dust / *_nugget / *_block）。 */
    private static String materialFromItemName(String path) {
        if (path.startsWith("deepslate_")) {
            path = path.substring("deepslate_".length());
        }
        if (path.startsWith("raw_")) {
            String rest = path.substring("raw_".length());
            if (rest.endsWith("_block")) {
                rest = rest.substring(0, rest.length() - "_block".length());
            }
            return rest;
        }
        for (String suffix : new String[]{"_ore", "_ingot", "_gem", "_dust", "_nugget", "_block"}) {
            if (path.endsWith(suffix)) {
                return path.substring(0, path.length() - suffix.length());
            }
        }
        return null;
    }

    /**
     * 物品标签 → 材料名（{@code forge:raw_materials/nickel} ⇒ {@code nickel}）。
     * <p>
     * 这是支持"各种魔法/科技模组矿石"的关键：只要该 mod 遵循 Forge/Common 的
     * {@code <type>/<material>} 约定，无需给它写任何代码就能被定向。
     */
    private static String materialFromItemTags(ItemStack stack) {
        for (TagKey<Item> tag : stack.getTags().toList()) {
            String tagPath = tag.location().getPath();
            for (String prefix : ITEM_TAG_PREFIXES) {
                if (tagPath.startsWith(prefix)) {
                    return tagPath.substring(prefix.length());
                }
            }
        }
        return null;
    }

    /** 方块 → 矿石材料名（缓存查表；来源见 {@link #materialOfOreBlock}）。 */
    public static String oreMaterialOf(BlockState state) {
        return oreBlockMaterialMap().get(state.getBlock());
    }

    /** 世界里真实存在的矿石材料集合（懒加载；矿石不随运行期动态增删，故不失效）。 */
    public static Set<String> oreMaterials() {
        if (oreMaterials == null) {
            buildOreCaches();
        }
        return oreMaterials;
    }

    /**
     * 该方块是否符合副手的指定（1.1.0 起有两条通道）。
     * <p>
     * 1. <b>材料通道</b>：副手能解析出矿石材料（粗铁/铁矿/铁锭…）→ 只匹配同材料的矿石。
     *    这是 1.0.x 的行为，保持不变；放铁矿仍然覆盖深板岩铁矿。
     * 2. <b>方块通道</b>：副手是方块物品（沙砾/黏土/石头…）→ 只匹配该方块本身。
     * 3. 两条都不成立（木棍、食物…）→ <b>不限制</b>。这一点是刻意的：
     *    认不出来的东西不能把目标全排除，否则女仆会站着不动。
     */
    public static boolean isTargetedBy(BlockState state, ItemStack offhand) {
        if (offhand.isEmpty() || !MiningConfig.offhandTargeting()) {
            return true;
        }
        OffhandTarget target = offhandTarget(offhand);
        if (target.material() != null) {
            return target.material().equals(oreMaterialOf(state));
        }
        if (target.block() != null) {
            return target.block() == state.getBlock();
        }
        return true;
    }

    /**
     * 旧名字，保留给外部调用与旧测试。
     *
     * @deprecated 1.1.0 起副手还能按方块匹配，请用 {@link #isTargetedBy(BlockState, ItemStack)}。
     */
    @Deprecated
    public static boolean isTargetedOre(BlockState state, ItemStack offhand) {
        return isTargetedBy(state, offhand);
    }

    /** 目标站立点周围是否存在可挖的目标。 */
    public static boolean hasTargetNear(ServerLevel level, BlockPos standPos, ItemStack tool) {
        for (BlockPos offset : NEARBY_OFFSETS) {
            if (isTargetBreakable(level, standPos.offset(offset), tool)) {
                return true;
            }
        }
        return false;
    }

    /** 返回目标站立点周围全部可挖目标的位置。 */
    public static List<BlockPos> targetPositionsNear(ServerLevel level, BlockPos standPos, ItemStack tool) {
        List<BlockPos> result = new ArrayList<>();
        for (BlockPos offset : NEARBY_OFFSETS) {
            BlockPos pos = standPos.offset(offset);
            if (isTargetBreakable(level, pos, tool)) {
                result.add(pos);
            }
        }
        return result;
    }

    /**
     * 把「这个位置能不能挖」**分类**，而不是只回答 true/false。
     * <p>
     * 见 OPTIMIZATION.md §1.5-A：旧实现只返回 boolean，导致调用侧把基岩（永不可破）
     * 与暂时失败一视同仁地重试，配合错误的恢复动作形成死循环。
     * <p>
     * 注意：空气返回 {@link BreakResult#SUCCESS}，语义是「此处已通过、无需破坏」，
     * 调用方应先自行判断空气/可通行。
     */
    public static BreakResult classifyDig(ServerLevel level, BlockPos pos, ItemStack tool) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return BreakResult.SUCCESS;
        }
        if (state.getBlock() == Blocks.BEDROCK) {
            return BreakResult.UNBREAKABLE;
        }
        if (!state.getFluidState().isEmpty()) {
            return BreakResult.FLUID;
        }
        float speed = state.getDestroySpeed(level, pos);
        if (speed < 0.0F || speed >= MiningConfig.maxDiggableHardness()) {
            return BreakResult.UNBREAKABLE;
        }
        return BreakResult.SUCCESS;
    }

    /** 矿石周围 3x3x3 是否有基岩。基岩不可破坏，靠近基岩的矿无法挖到，应直接跳过。 */
    public static boolean isNearBedrock(ServerLevel level, BlockPos orePos) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (level.getBlockState(orePos.offset(dx, dy, dz)).getBlock() == Blocks.BEDROCK) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
