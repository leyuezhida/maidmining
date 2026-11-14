package com.leyue.maidmining.gametest;

import com.leyue.maidmining.MaidMiningMod;
import com.leyue.maidmining.cfg.ConfigIdList;
import com.leyue.maidmining.cfg.MiningConfig;
import com.leyue.maidmining.cfg.MiningConfigSpec;
import com.leyue.maidmining.mining.BreakResult;
import com.leyue.maidmining.mining.MiningTunnelBehavior;
import com.leyue.maidmining.mining.MiningTunnelFinder;
import com.leyue.maidmining.mining.MiningValidator;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;
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
        helper.assertTrue(MiningValidator.isTargetedBy(Blocks.DIAMOND_ORE.defaultBlockState(), diamond),
                "副手放钻石时应命中钻石矿");
        helper.assertTrue(MiningValidator.isTargetedBy(Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState(), diamond),
                "深层钻石矿也应命中");
        helper.assertTrue(!MiningValidator.isTargetedBy(Blocks.IRON_ORE.defaultBlockState(), diamond),
                "副手放钻石时不应命中铁矿");
        helper.assertTrue(MiningValidator.isTargetedBy(Blocks.IRON_ORE.defaultBlockState(), ItemStack.EMPTY),
                "副手为空时应匹配所有矿石");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void unrecognizedOffhandFallsBackToAllOres(GameTestHelper helper) {
        ItemStack stick = new ItemStack(Items.STICK);
        String resolved = MiningValidator.targetOreName(stick);
        helper.assertTrue(resolved == null, "无法识别的副手物品应返回 null，实际=" + resolved);
        helper.assertTrue(MiningValidator.isTargetedBy(Blocks.IRON_ORE.defaultBlockState(), stick),
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
        helper.assertTrue(MiningValidator.isTargetedBy(Blocks.ANCIENT_DEBRIS.defaultBlockState(),
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

    private static void assertTool(GameTestHelper helper, Item item, boolean expected) {
        boolean actual = MiningValidator.isTool(new ItemStack(item));
        helper.assertTrue(actual == expected, item + " isTool 应为 " + expected + "，实际=" + actual);
    }

    /**
     * 工具识别（默认配置）：内置规则 = 原版标签 + ToolActions + "能正确挖石头"三层兜底，
     * 覆盖 mod 镐；镐以外的原版工具默认<b>不</b>认，与 1.0.2 一致。
     * <p>
     * 想放开铲子等非镐工具由 {@code tools.whitelist} 决定，见 {@link #toolWhitelistReplacesBuiltinRule}。
     */
    @GameTest(template = TEMPLATE)
    public static void toolDetectionWithDefaultConfig(GameTestHelper helper) {
        assertTool(helper, Items.WOODEN_PICKAXE, true);
        assertTool(helper, Items.STONE_PICKAXE, true);
        assertTool(helper, Items.IRON_PICKAXE, true);
        assertTool(helper, Items.GOLDEN_PICKAXE, true);
        assertTool(helper, Items.DIAMOND_PICKAXE, true);
        assertTool(helper, Items.NETHERITE_PICKAXE, true);
        assertTool(helper, Items.IRON_SHOVEL, false);
        assertTool(helper, Items.IRON_AXE, false);
        assertTool(helper, Items.IRON_SWORD, false);
        assertTool(helper, Items.IRON_HOE, false);
        assertTool(helper, Items.SHEARS, false);
        assertTool(helper, Items.STICK, false);
        helper.succeed();
    }

    // ===== 1.1.0：可配置的工具与目标（MM-1101 / MM-1102）=====
    // 规则以**参数**形式传给判定方法，绝不改写全局 ForgeConfigSpec。
    // 原因：GameTest 是并行执行的，改全局配置会与其它用例互相干扰
    //（实测过一次随机变红：whitelistModeIgnoresOres 读到了别的用例的配置）。

    /** 造一份工具规则；{@code configured} 表示玩家是否真的写过白名单。 */
    private static MiningConfig.ToolRules toolRules(Set<Item> whitelist, Set<Item> blacklist,
                                                    boolean configured, boolean requireCorrect) {
        return new MiningConfig.ToolRules(whitelist, blacklist, configured, requireCorrect, false);
    }

    /** 同上，但显式指定"白名单里是否含镐类标签"（决定要不要保留"任何镐"兜底）。 */
    private static MiningConfig.ToolRules toolRules(Set<Item> whitelist, Set<Item> blacklist,
                                                    boolean configured, boolean requireCorrect,
                                                    boolean acceptAnyPickaxe) {
        return new MiningConfig.ToolRules(whitelist, blacklist, configured, requireCorrect, acceptAnyPickaxe);
    }

    /** 造一份目标规则；副手过滤固定开启（它有独立开关，与本组用例无关）。 */
    private static MiningConfig.TargetRules targetRules(MiningConfigSpec.TargetMode mode,
                                                        Set<Block> whitelist, Set<Block> blacklist) {
        return new MiningConfig.TargetRules(whitelist, blacklist, mode, true);
    }

    /** 白名单一旦填内容，就<b>取代</b>内置规则：镐子不再自动可用。 */
    @GameTest(template = TEMPLATE)
    public static void toolWhitelistReplacesBuiltinRule(GameTestHelper helper) {
        MiningConfig.ToolRules rules = toolRules(Set.of(Items.IRON_SHOVEL), Set.of(), true, true);
        helper.assertTrue(MiningValidator.isTool(new ItemStack(Items.IRON_SHOVEL), rules),
                "白名单里的铲子应当可用");
        helper.assertTrue(!MiningValidator.isTool(new ItemStack(Items.DIAMOND_PICKAXE), rules),
                "白名单未包含镐子时，镐子不应再被自动选中");
        helper.succeed();
    }

    /**
     * 默认白名单含镐类标签时，<b>"任何镐"的兜底识别仍然保留</b>。
     * <p>
     * 默认值写的是 {@code #minecraft:pickaxes} 等标签，但模组镐不一定进这些标签；
     * 只要标签还在列表里，就继续用"标签 / ToolActions / 能挖石头"三层识别兜底，
     * 避免默认配置反而认不出模组镐。玩家把镐标签删掉后才严格只认列表项。
     */
    @GameTest(template = TEMPLATE)
    public static void pickaxeTagKeepsFallbackDetection(GameTestHelper helper) {
        MiningConfig.ToolRules withTag = toolRules(Set.of(Items.DIAMOND_SWORD), Set.of(), true, true, true);
        helper.assertTrue(MiningValidator.isTool(new ItemStack(Items.NETHERITE_PICKAXE), withTag),
                "白名单含镐标签时，未显式列出的镐也应被内置规则认出来");
        helper.assertTrue(MiningValidator.isTool(new ItemStack(Items.DIAMOND_SWORD), withTag),
                "白名单里显式列出的物品当然可用");
        helper.assertTrue(!MiningValidator.isTool(new ItemStack(Items.IRON_SHOVEL), withTag),
                "铲子既不在白名单、也不是镐，应被拒绝");

        MiningConfig.ToolRules strict = toolRules(Set.of(Items.DIAMOND_SWORD), Set.of(), true, true, false);
        helper.assertTrue(!MiningValidator.isTool(new ItemStack(Items.NETHERITE_PICKAXE), strict),
                "没有镐标签时才是严格模式：列表外的镐不能用");
        helper.succeed();
    }

    /** 黑名单压过白名单：这是"别让她捡垃圾镐"的实现方式。 */
    @GameTest(template = TEMPLATE)
    public static void toolBlacklistWinsOverWhitelist(GameTestHelper helper) {
        Set<Item> pickaxes = ConfigIdList.resolve(ForgeRegistries.ITEMS, List.of("#minecraft:pickaxes"));
        MiningConfig.ToolRules rules = toolRules(pickaxes, Set.of(Items.WOODEN_PICKAXE), true, true);
        helper.assertTrue(!MiningValidator.isTool(new ItemStack(Items.WOODEN_PICKAXE), rules),
                "黑名单里的木镐必须被排除");
        helper.assertTrue(MiningValidator.isTool(new ItemStack(Items.IRON_PICKAXE), rules),
                "未被拉黑的铁镐仍应可用");
        helper.succeed();
    }

    /**
     * 非镐工具要真正能干活，靠的是 {@code canBreak} 不再有"必须是镐"的硬门槛，
     * 但仍按 {@code requireCorrectTool} 遵守"工具—方块匹配"（铲子挖不动石头）。
     * <p>
     * 用沙砾做例子：本模组始终是挖矿任务，目标扩展的对象是沙砾、黏土这类可挖方块。
     */
    @GameTest(template = TEMPLATE)
    public static void nonPickaxeToolRespectsCorrectToolRule(GameTestHelper helper) {
        ItemStack shovel = new ItemStack(Items.IRON_SHOVEL);

        MiningConfig.ToolRules strict = toolRules(Set.of(Items.IRON_SHOVEL), Set.of(), true, true);
        helper.assertTrue(MiningValidator.canBreak(Blocks.GRAVEL.defaultBlockState(), shovel, strict),
                "铲子应当能挖沙砾");
        helper.assertTrue(!MiningValidator.canBreak(Blocks.STONE.defaultBlockState(), shovel, strict),
                "requireCorrectTool=true 时铲子不应能挖石头");

        MiningConfig.ToolRules loose = toolRules(Set.of(Items.IRON_SHOVEL), Set.of(), true, false);
        helper.assertTrue(MiningValidator.canBreak(Blocks.STONE.defaultBlockState(), shovel, loose),
                "requireCorrectTool=false 时白名单工具可以破坏任意方块");
        helper.succeed();
    }

    /** 默认模式仍是"只找矿石"：非矿石方块（石头、沙砾）一律不是目标。 */
    @GameTest(template = TEMPLATE)
    public static void defaultTargetModeIsOresOnly(GameTestHelper helper) {
        helper.assertTrue(MiningValidator.isTarget(Blocks.IRON_ORE.defaultBlockState()),
                "默认配置下铁矿是目标");
        helper.assertTrue(!MiningValidator.isTarget(Blocks.STONE.defaultBlockState()),
                "默认配置下石头不是目标");
        helper.assertTrue(!MiningValidator.isTarget(Blocks.GRAVEL.defaultBlockState()),
                "默认配置下沙砾不是目标");
        helper.succeed();
    }

    /**
     * 契约测试：<b>出厂默认只挖矿石</b>。
     * <p>
     * 1.1.0 把目标集合做成了可配置，但"默认"必须与 1.0.2 行为一致：
     * {@code mode=ORES}、目标名单为空、工具白名单里写入镐类标签（等价于"任何镐"）、
     * 副手过滤与工具—方块匹配都开着。
     * 谁把默认值改成"顺便再挖点别的"，这条会立刻变红 —— 玩家要扩展得自己去写配置，
     * 而不是被动接受一套更宽的默认行为。
     * <p>
     * 不修改配置，因此与其它用例的执行顺序无关（会改配置的用例都在 {@code finally} 里恢复默认）。
     */
    @GameTest(template = TEMPLATE)
    public static void defaultConfigMinesOresOnly(GameTestHelper helper) {
        // 断言的是"出厂默认值"（spec 的 default），不是当前文件里的值：
        // 配置文件一旦生成就不会跟着默认值变，测试环境里的文件可能还是旧版。
        helper.assertTrue(MiningConfigSpec.TARGET_MODE.getDefault() == MiningConfigSpec.TargetMode.ORES,
                "出厂默认的目标模式必须是 ORES，实际=" + MiningConfigSpec.TARGET_MODE.getDefault());
        helper.assertTrue(((java.util.List<?>) MiningConfigSpec.TARGET_WHITELIST.getDefault()).isEmpty(),
                "出厂默认的目标白名单必须为空");
        helper.assertTrue(((java.util.List<?>) MiningConfigSpec.TARGET_BLACKLIST.getDefault()).isEmpty(),
                "出厂默认的目标黑名单必须为空");

        java.util.List<?> toolsDefault = (java.util.List<?>) MiningConfigSpec.TOOL_WHITELIST.getDefault();
        helper.assertTrue(toolsDefault.stream().anyMatch(entry -> String.valueOf(entry).contains("pickaxe")),
                "出厂默认的工具白名单应包含镐类标签（否则玩家加一项就会把镐挤掉），实际=" + toolsDefault);

        helper.assertTrue(MiningConfig.requireCorrectTool(), "出厂默认应保留工具—方块匹配");
        helper.assertTrue(MiningConfig.offhandTargeting(), "出厂默认应保留副手过滤");
        helper.succeed();
    }

    /**
     * 契约测试：<b>不要求"正确工具"的方块必须仍可挖</b>。
     * <p>
     * 刷怪笼、玻璃、树叶这类方块，原版规则是"拿什么都能破坏"（掉落由战利品表决定，
     * 多数需要精准采集）。1.1.0 实测里，玩家把刷怪笼加进 {@code targets.whitelist}
     * 后女仆完全不动手：{@code requireCorrectTool=true} 且镐子的
     * {@code isCorrectToolForDrops} 对刷怪笼为 false，被工具规则挡掉了。
     * <p>
     * 反面也在这里锁住：真正有工具/等级要求的方块（钻石矿 vs 木镐）仍然要被拦住。
     */
    @GameTest(template = TEMPLATE)
    public static void noToolRequirementBlocksStayMineable(GameTestHelper helper) {
        MiningConfig.ToolRules rule = toolRules(Set.of(), Set.of(), false, true);
        ItemStack pickaxe = new ItemStack(Items.IRON_PICKAXE);

        BlockState spawner = Blocks.SPAWNER.defaultBlockState();
        helper.assertTrue(MiningValidator.canBreak(spawner, pickaxe, rule),
                "刷怪笼必须能被镐子挖。requires=" + spawner.requiresCorrectToolForDrops()
                        + " mineableWithPickaxe=" + spawner.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_PICKAXE)
                        + " correctToolForDrops=" + pickaxe.isCorrectToolForDrops(spawner));
        helper.assertTrue(MiningValidator.canBreak(Blocks.GLASS.defaultBlockState(), pickaxe, rule),
                "玻璃不要求工具，应可挖");

        helper.assertTrue(!MiningValidator.canBreak(Blocks.DIAMOND_ORE.defaultBlockState(),
                        new ItemStack(Items.WOODEN_PICKAXE), rule),
                "木镐挖不动钻石矿：requireCorrectTool 的等级规则必须保留");
        helper.succeed();
    }

    /**
     * 契约测试：<b>区块段下标必须相对世界最低点计算</b>。
     * <p>
     * 1.1.0 实机 bug：女仆站在 y=-60，旁边就有可挖目标，整轮扫描却"什么都没找到"。
     * 原因是扫描器直接用 {@code y >> 4} 当数组下标——1.18+ 主世界最低点是 -64
     * （段坐标 -4），正确下标要减 4；地下算出来是负数，循环一次都不执行。
     * <p>
     * 这里把换算固定下来：主世界（minSection=-4）与 0 基准世界（超平坦）都要对。
     */
    @GameTest(template = TEMPLATE)
    public static void sectionIndexRespectsMinBuildHeight(GameTestHelper helper) {
        // 1.20.1 主世界：数组 0 号段 = y -64..-49
        helper.assertTrue(MiningTunnelFinder.sectionIndexFor(-64, -4) == 0, "y=-64 应为段 0");
        helper.assertTrue(MiningTunnelFinder.sectionIndexFor(-60, -4) == 0, "y=-60 应为段 0");
        helper.assertTrue(MiningTunnelFinder.sectionIndexFor(-48, -4) == 1, "y=-48 应为段 1");
        helper.assertTrue(MiningTunnelFinder.sectionIndexFor(0, -4) == 4, "y=0 应为段 4");
        helper.assertTrue(MiningTunnelFinder.sectionIndexFor(64, -4) == 8, "y=64 应为段 8");
        helper.assertTrue(MiningTunnelFinder.sectionIndexFor(319, -4) == 23, "y=319 应为段 23");

        // 超平坦（minY=0）的换算不能被顺手改坏
        helper.assertTrue(MiningTunnelFinder.sectionIndexFor(0, 0) == 0, "y=0 应为段 0");
        helper.assertTrue(MiningTunnelFinder.sectionIndexFor(64, 0) == 4, "y=64 应为段 4");

        // 旧实现的直接特征：地下得到负数下标
        helper.assertTrue(MiningTunnelFinder.sectionIndexFor(-60, -4) >= 0,
                "地下不得出现负数下标（旧实现正是这里出错）");
        helper.succeed();
    }

    /**
     * 契约测试：<b>白名单优先于副手过滤</b>。
     * <p>
     * 副手是"顺手放在那儿"的临时限制，而白名单是玩家明确写下的意图。
     * 1.1.0 实测：白名单写了制箭台、副手放着刷怪笼，结果制箭台被副手挡掉，女仆站着不动。
     * 副手仍应在"只挖矿"模式下筛矿种。
     */
    @GameTest(template = TEMPLATE)
    public static void whitelistWinsOverOffhandFilter(GameTestHelper helper) {
        MiningConfig.ToolRules tools = toolRules(Set.of(), Set.of(), false, true);
        ItemStack pickaxe = new ItemStack(Items.IRON_PICKAXE);
        ItemStack offhandSpawner = new ItemStack(Items.SPAWNER);

        helper.setBlock(1, 1, 1, Blocks.FLETCHING_TABLE);
        BlockPos table = helper.absolutePos(new BlockPos(1, 1, 1));
        MiningConfig.TargetRules withTable = targetRules(
                MiningConfigSpec.TargetMode.ORES_AND_WHITELIST, Set.of(Blocks.FLETCHING_TABLE), Set.of());
        helper.assertTrue(MiningValidator.isTargetBreakable(helper.getLevel(), table, pickaxe,
                        offhandSpawner, withTable, tools),
                "白名单里的制箭台不应被副手（刷怪笼）挡住");

        helper.setBlock(2, 1, 1, Blocks.IRON_ORE);
        BlockPos ore = helper.absolutePos(new BlockPos(2, 1, 1));
        MiningConfig.TargetRules oresOnly = targetRules(MiningConfigSpec.TargetMode.ORES, Set.of(), Set.of());
        helper.assertTrue(!MiningValidator.isTargetBreakable(helper.getLevel(), ore, pickaxe,
                        offhandSpawner, oresOnly, tools),
                "只挖矿模式下，副手放刷怪笼仍应把铁矿排除（副手功能本身不能被改坏）");
        helper.succeed();
    }

    /**
     * 契约测试：<b>头顶 2 格的目标不需要爬上去</b>。
     * <p>
     * 1.1.0 实测：目标在正上方 2 格（{@code dy=2 hDist=0}），旧判定"三轴都在 reach 内"
     * 不成立 → 先去起塔 → 没有垫脚建材 → 卡满次数后放弃目标
     * （日志 {@code Ascend failed ... cannot ascend}）。玩家站在下面本来就能挖到头顶方块，
     * 这里把"正上方多一格"固定下来；向下不放开（会悬空掉落，属下降逻辑）。
     */
    @GameTest(template = TEMPLATE)
    public static void verticalReachCoversBlockAboveHead(GameTestHelper helper) {
        BlockPos feet = new BlockPos(0, 0, 0);
        helper.assertTrue(MiningTunnelBehavior.isWithinReach(feet, new BlockPos(0, 1, 0), 1),
                "正上方 1 格应可挖");
        helper.assertTrue(MiningTunnelBehavior.isWithinReach(feet, new BlockPos(0, 2, 0), 1),
                "正上方 2 格（头顶）应可挖");
        helper.assertTrue(!MiningTunnelBehavior.isWithinReach(feet, new BlockPos(0, 3, 0), 1),
                "正上方 3 格超出挖掘距离");
        helper.assertTrue(!MiningTunnelBehavior.isWithinReach(feet, new BlockPos(0, -2, 0), 1),
                "正下方 2 格不放开（会悬空掉落）");
        helper.assertTrue(!MiningTunnelBehavior.isWithinReach(feet, new BlockPos(1, 2, 0), 1),
                "斜上方不算，仍要求先靠近");
        helper.assertTrue(MiningTunnelBehavior.isWithinReach(feet, new BlockPos(1, 1, 1), 1),
                "常规相邻仍要成立");
        helper.succeed();
    }

    /** 黑名单压过矿石标签：想"只挖钻石"时用它排除干扰矿。 */
    @GameTest(template = TEMPLATE)
    public static void targetBlacklistWinsOverOreTags(GameTestHelper helper) {
        MiningConfig.TargetRules rules = targetRules(MiningConfigSpec.TargetMode.ORES,
                Set.of(), Set.of(Blocks.COPPER_ORE));
        helper.assertTrue(!MiningValidator.isTarget(Blocks.COPPER_ORE.defaultBlockState(), rules),
                "黑名单里的铜矿必须被排除");
        helper.assertTrue(MiningValidator.isTarget(Blocks.IRON_ORE.defaultBlockState(), rules),
                "未拉黑的铁矿仍应是目标");
        helper.succeed();
    }

    /** {@code WHITELIST} 模式：只看名单，连矿石也不挖。 */
    @GameTest(template = TEMPLATE)
    public static void whitelistModeIgnoresOres(GameTestHelper helper) {
        MiningConfig.TargetRules rules = targetRules(MiningConfigSpec.TargetMode.WHITELIST,
                Set.of(Blocks.GRAVEL), Set.of());
        helper.assertTrue(MiningValidator.isTarget(Blocks.GRAVEL.defaultBlockState(), rules),
                "白名单里的沙砾应成为目标");
        helper.assertTrue(!MiningValidator.isTarget(Blocks.IRON_ORE.defaultBlockState(), rules),
                "WHITELIST 模式下矿石不在名单里就不该挖");
        helper.assertTrue(!MiningValidator.isTarget(Blocks.STONE.defaultBlockState(), rules),
                "不在名单里的石头不是目标");
        helper.succeed();
    }

    /** {@code ORES_AND_WHITELIST}：矿石 ∪ 白名单，想顺带挖沙砾/黏土用这一档。 */
    @GameTest(template = TEMPLATE)
    public static void oresAndWhitelistModeIsUnion(GameTestHelper helper) {
        MiningConfig.TargetRules rules = targetRules(MiningConfigSpec.TargetMode.ORES_AND_WHITELIST,
                Set.of(Blocks.GRAVEL), Set.of());
        helper.assertTrue(MiningValidator.isTarget(Blocks.IRON_ORE.defaultBlockState(), rules),
                "并集模式仍应包含矿石");
        helper.assertTrue(MiningValidator.isTarget(Blocks.GRAVEL.defaultBlockState(), rules),
                "并集模式应包含白名单方块");
        helper.assertTrue(!MiningValidator.isTarget(Blocks.STONE.defaultBlockState(), rules),
                "不在白名单里的方块仍不是目标");
        helper.succeed();
    }

    /**
     * 副手按方块匹配（1.1.0 新增通道）：放什么方块就只挖什么方块，
     * 而放矿石材料仍走 1.0.x 的材料通道（铁矿同时覆盖深板岩铁矿）。
     */
    @GameTest(template = TEMPLATE)
    public static void offhandTargetsExactBlock(GameTestHelper helper) {
        ItemStack gravel = new ItemStack(Items.GRAVEL);
        helper.assertTrue(MiningValidator.isTargetedBy(Blocks.GRAVEL.defaultBlockState(), gravel),
                "副手放沙砾应命中沙砾");
        helper.assertTrue(!MiningValidator.isTargetedBy(Blocks.CLAY.defaultBlockState(), gravel),
                "副手放沙砾不应命中黏土");
        helper.assertTrue(!MiningValidator.isTargetedBy(Blocks.STONE.defaultBlockState(), gravel),
                "副手放沙砾不应命中石头");
        // 材料通道未被破坏：铁矿物品 → 材料 iron → 深板岩变体也算
        helper.assertTrue(MiningValidator.isTargetedBy(Blocks.DEEPSLATE_IRON_ORE.defaultBlockState(),
                        new ItemStack(Items.IRON_ORE)),
                "副手放铁矿仍应命中的深板岩铁矿（材料通道）");
        helper.succeed();
    }

    /** 条目解析：{@code #标签} 与 {@code ns:*} 通配都要能用。 */
    @GameTest(template = TEMPLATE)
    public static void configIdListResolvesTagsAndWildcards(GameTestHelper helper) {
        Set<Item> pickaxes = ConfigIdList.resolve(ForgeRegistries.ITEMS, List.of("#minecraft:pickaxes"));
        helper.assertTrue(pickaxes.contains(Items.IRON_PICKAXE), "#minecraft:pickaxes 应包含铁镐");
        helper.assertTrue(!pickaxes.contains(Items.IRON_SHOVEL), "#minecraft:pickaxes 不应包含铲子");

        Set<Item> vanillaSwords = ConfigIdList.resolve(ForgeRegistries.ITEMS, List.of("minecraft:*_sword"));
        helper.assertTrue(vanillaSwords.contains(Items.IRON_SWORD), "minecraft:*_sword 应包含铁剑");
        helper.assertTrue(!vanillaSwords.contains(Items.STICK), "minecraft:*_sword 不应包含木棍");

        Set<Block> blocks = ConfigIdList.resolve(ForgeRegistries.BLOCKS,
                List.of("minecraft:gravel", "minecraft:not_a_real_block"));
        helper.assertTrue(blocks.size() == 1 && blocks.contains(Blocks.GRAVEL),
                "无效 id 应被静默跳过，只留下沙砾");
        helper.succeed();
    }
}
