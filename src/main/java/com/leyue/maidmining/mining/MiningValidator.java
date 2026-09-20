package com.leyue.maidmining.mining;

import com.leyue.maidmining.MaidMiningMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
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
 * 目标方块的校验规则：
 * 1. 必须是矿石；
 * 2. 必须能被当前手持镐子（挖掘等级）正确破坏。
 * <p>
 * 注意：某些环境下 {@code #minecraft:ores} 标签可能无法命中，
 * 因此矿石识别采用「标签优先 + 方块注册名兜底」的双保险策略。
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
     * （见 {@link #isPickaxe}）。这两个额外标签是给遵循该约定的 mod 留的入口，不存在也不会报错。
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
    private static String cachedMaterial;
    private static boolean cachedValid;

    /** 目标站立点周围需要检测的偏移（覆盖脚下、头顶、四周墙壁与地板矿石）。 */
    private static final BlockPos[] NEARBY_OFFSETS = {
            new BlockPos(0, 0, 0), new BlockPos(0, 1, 0), new BlockPos(0, -1, 0),
            new BlockPos(1, 0, 0), new BlockPos(-1, 0, 0), new BlockPos(0, 0, 1), new BlockPos(0, 0, -1),
            new BlockPos(1, 1, 0), new BlockPos(-1, 1, 0), new BlockPos(0, 1, 1), new BlockPos(0, 1, -1),
    };

    private MiningValidator() {
    }

    /**
     * 是否为矿石方块。
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

    /**
     * 手持物品是否为镐子。
     * <p>
     * 依次判定：{@code #minecraft:pickaxes}（原版 6 把）→ {@code forge/c:tools/pickaxes} →
     * {@link ToolActions#PICKAXE_DIG}（mod 镐的主力：任何继承 {@code DiggerItem} 的工具都会命中）→
     * **能正确开采石头**（覆盖既没进标签、也没实现 ToolActions 的自定义工具）。
     * 这样"所有带镐标签的工具"以及"行为上就是镐的工具"都能用。
     */
    public static boolean isPickaxe(ItemStack tool) {
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
     * 当前工具能否正确破坏该方块。
     * 内部会校验：方块可用镐子挖掘，且镐子的挖掘等级 ≥ 方块要求等级
     * （即石镐挖不了钻石矿、木镐挖不了铁矿这类规则）。
     */
    public static boolean canBreak(BlockState state, ItemStack tool) {
        if (state.isAir()) {
            return false;
        }
        if (!isPickaxe(tool)) {
            return false;
        }
        return tool.isCorrectToolForDrops(state);
    }

    /** 指定位置是否为「可挖的矿石」（不限定类型）。 */
    public static boolean isOreBreakable(ServerLevel level, BlockPos pos, ItemStack tool) {
        return isOreBreakable(level, pos, tool, ItemStack.EMPTY);
    }

    /**
     * 指定位置是否为「可挖的矿石」。
     * 若副手指定了原矿石，则只匹配对应类型的矿石（如副手放粗铁/铁矿石 → 只挖铁矿）。
     */
    public static boolean isOreBreakable(ServerLevel level, BlockPos pos, ItemStack tool, ItemStack offhand) {
        BlockState state = level.getBlockState(pos);
        return isOre(state) && isTargetedOre(state, offhand) && canBreak(state, tool);
    }

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
     * 解析结果与「世界里真实存在的矿石材料」核对；核对失败时**明确告警**并按"挖全部"处理。
     */
    public static String targetOreName(ItemStack offhand) {
        if (offhand.isEmpty()) {
            return null;
        }
        if (cachedValid && ItemStack.matches(cachedOffhand, offhand)) {
            return cachedMaterial;
        }
        String resolved = resolveTargetMaterial(offhand);
        cachedOffhand = offhand.copy();
        cachedMaterial = resolved;
        cachedValid = true;
        return resolved;
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
        if (WARNED_OFFHAND.add(path)) {
            MaidMiningMod.LOGGER.warn(
                    "[MaidMining] Offhand item {} can't be matched to any known ore material; mining all ores", key);
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

    /** 该方块是否属于副手指定的矿石类型（副手未指定/无法识别时匹配所有矿石）。 */
    public static boolean isTargetedOre(BlockState state, ItemStack offhand) {
        String target = targetOreName(offhand);
        if (target == null) {
            return true;
        }
        return target.equals(oreMaterialOf(state));
    }

    /** 目标站立点周围是否存在可挖的矿石。 */
    public static boolean hasOreNear(ServerLevel level, BlockPos standPos, ItemStack tool) {
        for (BlockPos offset : NEARBY_OFFSETS) {
            if (isOreBreakable(level, standPos.offset(offset), tool)) {
                return true;
            }
        }
        return false;
    }

    /** 返回目标站立点周围全部可挖矿石的位置。 */
    public static List<BlockPos> orePositionsNear(ServerLevel level, BlockPos standPos, ItemStack tool) {
        List<BlockPos> result = new ArrayList<>();
        for (BlockPos offset : NEARBY_OFFSETS) {
            BlockPos pos = standPos.offset(offset);
            if (isOreBreakable(level, pos, tool)) {
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
        if (speed < 0.0F || speed >= MiningConfig.MAX_DIGGABLE_HARDNESS) {
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
