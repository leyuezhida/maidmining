package com.leyue.maidmining.gametest;

import com.leyue.maidmining.MaidMiningMod;
import com.leyue.maidmining.cfg.MiningConfig;
import com.leyue.maidmining.mining.BreakResult;
import com.leyue.maidmining.mining.MiningValidator;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.Set;

/**
 * 破坏失败分类（{@link BreakResult}）的自动化验证 —— OPTIMIZATION.md MM-1002 的第一批用例，
 * 锁定 §1.5-A 的修复语义。
 * <p>
 * 为什么这些用例重要：旧实现把「永不可破 / 流体 / 被保护 / 暂时失败」压成一个 boolean，
 * 调用侧只能盲目重试，于是对基岩空转约 120 tick 并叠加错误的恢复动作，形成
 * 「贴着基岩无限上爬」的死循环。分类一旦被改回二值语义，这里必须立刻变红。
 * <p>
 * 运行方式：{@code ./gradlew runGameTestServer}（无需客户端，25 秒左右）。
 */
@GameTestHolder(MaidMiningMod.MOD_ID)
@PrefixGameTestTemplate(false)
public class MiningValidatorGameTests {
    /**
     * 模板名**不带命名空间**：{@code @GameTestHolder} 会自动补上 mod 的命名空间，
     * 写成 "maidmining:empty5" 会变成 "maidmining:maidmining:empty5" 而报 ResourceLocationException。
     * 对应文件：src/main/resources/data/maidmining/structures/empty5.nbt
     */
    private static final String TEMPLATE = "empty5";

    private static ItemStack ironPickaxe() {
        return new ItemStack(Items.IRON_PICKAXE);
    }

    private static BreakResult classifyAt(GameTestHelper helper, int x, int y, int z) {
        return MiningValidator.classifyDig(helper.getLevel(), helper.absolutePos(new BlockPos(x, y, z)), ironPickaxe());
    }

    /** 基岩必须被识别为「永远挖不动」——这是终止空转循环的前提。 */
    @GameTest(template = TEMPLATE)
    public static void bedrockIsUnbreakable(GameTestHelper helper) {
        helper.setBlock(1, 1, 1, Blocks.BEDROCK);
        BreakResult result = classifyAt(helper, 1, 1, 1);
        helper.assertTrue(result == BreakResult.UNBREAKABLE,
                "基岩必须分类为 UNBREAKABLE，实际=" + result);
        helper.succeed();
    }

    /** 普通石头应当可挖。 */
    @GameTest(template = TEMPLATE)
    public static void stoneIsBreakable(GameTestHelper helper) {
        helper.setBlock(2, 1, 1, Blocks.STONE);
        BreakResult result = classifyAt(helper, 2, 1, 1);
        helper.assertTrue(result == BreakResult.SUCCESS,
                "石头必须分类为 SUCCESS，实际=" + result);
        helper.succeed();
    }

    /** 流体不能走破坏路径，必须单独分类（后续 MM-401 的岩浆策略依赖它）。 */
    @GameTest(template = TEMPLATE)
    public static void waterIsFluid(GameTestHelper helper) {
        helper.setBlock(3, 1, 1, Blocks.WATER);
        BreakResult result = classifyAt(helper, 3, 1, 1);
        helper.assertTrue(result == BreakResult.FLUID,
                "水必须分类为 FLUID，实际=" + result);
        helper.succeed();
    }

    /**
     * 黑曜石（硬度 50）当前被 {@code dig.maxDiggableHardness} 判为不可挖。
     * 这是「不值得挖」的启发式而非物理事实，用例把它固定下来，
     * 以便 MM-303（挖掘时间模型）改动时能看见行为变化。
     */
    @GameTest(template = TEMPLATE)
    public static void obsidianIsUnbreakableByCurrentHeuristic(GameTestHelper helper) {
        helper.setBlock(1, 1, 2, Blocks.OBSIDIAN);
        BreakResult result = classifyAt(helper, 1, 1, 2);
        helper.assertTrue(result == BreakResult.UNBREAKABLE,
                "黑曜石按当前硬度阈值应为 UNBREAKABLE，实际=" + result);
        helper.succeed();
    }

    /** 空气的语义是「此处已通过、无需破坏」，不是「挖不动」。 */
    @GameTest(template = TEMPLATE)
    public static void airIsAlreadyClear(GameTestHelper helper) {
        BreakResult result = classifyAt(helper, 4, 1, 4);
        helper.assertTrue(result == BreakResult.SUCCESS,
                "空气应视为已通过（SUCCESS），实际=" + result);
        helper.succeed();
    }

    /** 贴着基岩的矿石仍要被识别出来（规则保留但默认关闭，MM-110② 会删除它）。 */
    @GameTest(template = TEMPLATE)
    public static void bedrockAdjacencyIsDetected(GameTestHelper helper) {
        helper.setBlock(2, 2, 2, Blocks.BEDROCK);
        boolean near = MiningValidator.isNearBedrock(helper.getLevel(), helper.absolutePos(new BlockPos(2, 2, 3)));
        boolean far = MiningValidator.isNearBedrock(helper.getLevel(), helper.absolutePos(new BlockPos(4, 2, 4)));
        helper.assertTrue(near, "紧邻基岩的矿石必须被判定为 bedrock-near");
        helper.assertTrue(!far, "远离基岩的矿石不应被判定为 bedrock-near");
        helper.succeed();
    }

    /**
     * 契约测试：{@link MiningConfig#SKIP_NEAR_BEDROCK} 目前<b>刻意保持开启</b>。
     * <p>
     * 这条开关反复过：最初是为掩盖"被基岩卡住"而加（§1.5-A）；破坏失败分类落地后曾关闭；
     * 但 2026-09-20 第五轮的实机日志显示，关闭后深层（y ≤ -60）会出现
     * 「连续锁定 → 撞同一面基岩墙 → 放弃」的碎裂循环（2.5 秒内 4 次撞同一墙块），
     * 女仆表现为站着反复搜索。故按实机反馈重新开启。
     * <p>
     * 长期方案是 MM-202（把已知墙块纳入规划、绕行），届时这条开关应该被真正删除。
     * 如果有人再次翻转它，这里会立刻变红，提醒先读 §1.5-A 与 §9 第五轮。
     */
    @GameTest(template = TEMPLATE)
    public static void bedrockSkipRuleIsCurrentlyEnabled(GameTestHelper helper) {
        helper.assertTrue(MiningConfig.skipNearBedrock(),
                "skipNearBedrock 目前刻意开启（见 §1.5-A / §9 第五轮）；要关闭请先读那两节并同步更新本用例");
        helper.succeed();
    }

    // ===== 副手定向过滤（MM-108）=====
    // 用例覆盖旧实现的两个已知缺陷：raw_copper_block → "copper_block"（永远匹配不到），
    // 以及 diamond/coal/redstone 等直接返回 null（静默变成"挖全部"）。

    private static void assertResolves(GameTestHelper helper, Item item, String expected) {
        String actual = MiningValidator.targetOreName(new ItemStack(item));
        helper.assertTrue(expected.equals(actual),
                item + " 应解析为 " + expected + "，实际=" + actual);
    }

    @GameTest(template = TEMPLATE)
    public static void offhandOreResolution(GameTestHelper helper) {
        assertResolves(helper, Items.RAW_IRON, "iron");
        assertResolves(helper, Items.IRON_ORE, "iron");
        assertResolves(helper, Items.DEEPSLATE_IRON_ORE, "iron");
        assertResolves(helper, Items.IRON_INGOT, "iron");
        assertResolves(helper, Items.RAW_COPPER_BLOCK, "copper");   // 旧实现得到 "copper_block"
        assertResolves(helper, Items.DIAMOND, "diamond");           // 旧实现返回 null
        assertResolves(helper, Items.COAL, "coal");                 // 旧实现返回 null
        assertResolves(helper, Items.REDSTONE, "redstone");         // 旧实现返回 null
        assertResolves(helper, Items.EMERALD, "emerald");
        assertResolves(helper, Items.LAPIS_LAZULI, "lapis");        // 物品名与矿石名不一致 → 别名
        assertResolves(helper, Items.QUARTZ, "nether_quartz");      // 同上
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void offhandFilterOnlyTargetsThatOre(GameTestHelper helper) {
        ItemStack diamond = new ItemStack(Items.DIAMOND);
        helper.assertTrue(MiningValidator.isTargetedOre(Blocks.DIAMOND_ORE.defaultBlockState(), diamond),
                "副手放钻石时应命中钻石矿");
        helper.assertTrue(MiningValidator.isTargetedOre(Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState(), diamond),
                "深层钻石矿也应命中");
        helper.assertTrue(!MiningValidator.isTargetedOre(Blocks.IRON_ORE.defaultBlockState(), diamond),
                "副手放钻石时不应命中铁矿");
        helper.assertTrue(MiningValidator.isTargetedOre(Blocks.IRON_ORE.defaultBlockState(), ItemStack.EMPTY),
                "副手为空时应匹配所有矿石");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void unrecognizedOffhandFallsBackToAllOres(GameTestHelper helper) {
        ItemStack stick = new ItemStack(Items.STICK);
        String resolved = MiningValidator.targetOreName(stick);
        helper.assertTrue(resolved == null, "无法识别的副手物品应返回 null，实际=" + resolved);
        helper.assertTrue(MiningValidator.isTargetedOre(Blocks.IRON_ORE.defaultBlockState(), stick),
                "无法识别时不能把矿石全排除（否则女仆什么都不挖）");
        helper.succeed();
    }

    /**
     * 远古残骸：Forge 给它的标签是 {@code forge:ores/netherite_scrap}
     * （已核实 Forge 源码 {@code Tags.Blocks.ORES_NETHERITE_SCRAP}），
     * 因此材料名取 {@code netherite_scrap}，副手放残骸/远古残骸方块都应能定向。
     */
    @GameTest(template = TEMPLATE)
    public static void ancientDebrisIsTargetable(GameTestHelper helper) {
        helper.assertTrue("netherite_scrap".equals(
                        MiningValidator.targetOreName(new ItemStack(Items.NETHERITE_SCRAP))),
                "netherite_scrap 应解析为 netherite_scrap，实际="
                        + MiningValidator.targetOreName(new ItemStack(Items.NETHERITE_SCRAP)));
        helper.assertTrue("netherite_scrap".equals(
                        MiningValidator.targetOreName(new ItemStack(Items.ANCIENT_DEBRIS))),
                "远古残骸方块应解析为 netherite_scrap，实际="
                        + MiningValidator.targetOreName(new ItemStack(Items.ANCIENT_DEBRIS)));
        helper.assertTrue(MiningValidator.isOre(Blocks.ANCIENT_DEBRIS.defaultBlockState()),
                "远古残骸必须被认作矿石");
        helper.assertTrue(MiningValidator.isTargetedOre(Blocks.ANCIENT_DEBRIS.defaultBlockState(),
                        new ItemStack(Items.NETHERITE_SCRAP)),
                "副手放 netherite_scrap 时应命中远古残骸");
        helper.succeed();
    }

    /** mod 矿石靠 {@code forge/c:ores/<材料>} 标签进入材料集合——这是支持镍/铝等模组矿石的关键。 */
    @GameTest(template = TEMPLATE)
    public static void oreMaterialsCoverVanillaAndSpecialCases(GameTestHelper helper) {
        Set<String> materials = MiningValidator.oreMaterials();
        for (String expected : new String[]{"iron", "copper", "gold", "coal", "diamond", "emerald",
                "lapis", "redstone", "nether_quartz", "netherite_scrap"}) {
            helper.assertTrue(materials.contains(expected),
                    "矿石材料集合应包含 " + expected + "，实际=" + materials);
        }
        helper.succeed();
    }

    private static void assertPickaxe(GameTestHelper helper, Item item, boolean expected) {
        boolean actual = MiningValidator.isPickaxe(new ItemStack(item));
        helper.assertTrue(actual == expected, item + " isPickaxe 应为 " + expected + "，实际=" + actual);
    }

    /** 镐子识别：原版标签 + ToolActions + "能正确挖石头"三层兜底，覆盖 mod 镐。 */
    @GameTest(template = TEMPLATE)
    public static void pickaxeDetection(GameTestHelper helper) {
        assertPickaxe(helper, Items.WOODEN_PICKAXE, true);
        assertPickaxe(helper, Items.STONE_PICKAXE, true);
        assertPickaxe(helper, Items.IRON_PICKAXE, true);
        assertPickaxe(helper, Items.GOLDEN_PICKAXE, true);
        assertPickaxe(helper, Items.DIAMOND_PICKAXE, true);
        assertPickaxe(helper, Items.NETHERITE_PICKAXE, true);
        assertPickaxe(helper, Items.IRON_SHOVEL, false);
        assertPickaxe(helper, Items.IRON_AXE, false);
        assertPickaxe(helper, Items.IRON_SWORD, false);
        assertPickaxe(helper, Items.IRON_HOE, false);
        assertPickaxe(helper, Items.SHEARS, false);
        assertPickaxe(helper, Items.STICK, false);
        helper.succeed();
    }
}
