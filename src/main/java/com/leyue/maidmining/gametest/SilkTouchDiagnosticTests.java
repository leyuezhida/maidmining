package com.leyue.maidmining.gametest;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.leyue.maidmining.MaidMiningMod;
import com.leyue.maidmining.act.DigExecutor;
import com.leyue.maidmining.inv.MaidInventory;
import com.leyue.maidmining.mining.BreakResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * <b>精准采集诊断</b>：实机反馈「时运有效，精准采集无效」时的定位用例。
 * <p>
 * <b>矛盾点</b>：时运走 {@code apply_bonus} 函数、精准采集走 {@code match_tool} 谓词，
 * 两者读的是<b>同一个</b> {@code LootContextParams.TOOL}。既然时运生效，
 * 说明工具栈确实传进了掉落上下文，那精准采集为何没命中？
 * <p>
 * 本组用例把变量逐个拆开，分别验证，从而定位到具体是哪个环节：
 * <ol>
 *   <li>普通钻石镐 + 精准采集 → 是否拿到方块（基线，已知通过）；</li>
 *   <li><b>下界合金镐</b> + 精准采集 → 排除"高等级镐"这一变量；</li>
 *   <li><b>时运 + 精准采集同时</b> → 排除"两条分支互相干扰"；</li>
 *   <li><b>附魔镐在背包里、由 ToolManager 换手上</b> → 复现实机路径
 *       （实机是玩家把镐子放背包，不是放主手）；</li>
 *   <li>诊断输出：把工具栈的附魔标签与掉落表分支实际命中情况打出来。</li>
 * </ol>
 * 运行：{@code ./gradlew runGameTestServer}
 */
@GameTestHolder(MaidMiningMod.MOD_ID)
@PrefixGameTestTemplate(false)
public class SilkTouchDiagnosticTests {

    private static final String TEMPLATE = "empty5";
    private static final ResourceLocation MAID_ID =
            ResourceLocation.tryBuild("touhou_little_maid", "maid");

    @SuppressWarnings("deprecation")
    private static EntityMaid spawnMaid(GameTestHelper helper, int x, int y, int z) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(MAID_ID)
                .orElseThrow(() -> new IllegalStateException("TLM maid entity not found"));
        EntityMaid maid = (EntityMaid) type.create(helper.getLevel());
        if (maid == null) {
            throw new IllegalStateException("Failed to create maid entity");
        }
        BlockPos pos = helper.absolutePos(new BlockPos(x, y, z));
        maid.moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D);
        maid.finalizeSpawn(helper.getLevel(), helper.getLevel().getCurrentDifficultyAt(pos),
                MobSpawnType.MOB_SUMMONED, null, null);
        helper.getLevel().addFreshEntity(maid);
        return maid;
    }

    private static BreakResult dig(GameTestHelper helper, EntityMaid maid, int x, int y, int z) {
        return DigExecutor.dig(helper.getLevel(), maid, helper.absolutePos(new BlockPos(x, y, z)));
    }

    private static int count(EntityMaid maid, Item item) {
        return MaidInventory.countItem(maid, item);
    }

    /** 挖深板岩钻石矿（实机最常见的深层矿），返回方块数。 */
    private static int mineDeepslateDiamond(EntityMaid maid) {
        return count(maid, Items.DEEPSLATE_DIAMOND_ORE) + count(maid, Items.DIAMOND_ORE);
    }

    // ===== 诊断输出：附魔到底有没有被读到 =====

    /**
     * 直接检查"主手那把镐的附魔是否可读"，以及掉落表在该工具下会给出什么。
     * <p>
     * 这个用例不判定成败，只<b>把事实打印出来</b>——
     * 实机现象与单测矛盾时，先确认观察到的是不是同一个东西。
     */
    @GameTest(template = TEMPLATE)
    public static void diagnoseToolEnchantmentVisibility(GameTestHelper helper) {
        helper.setBlock(1, 1, 1, Blocks.DEEPSLATE_DIAMOND_ORE);
        EntityMaid maid = spawnMaid(helper, 4, 1, 4);

        ItemStack silk = new ItemStack(Items.DIAMOND_PICKAXE);
        silk.enchant(Enchantments.SILK_TOUCH, 1);
        silk.enchant(Enchantments.BLOCK_FORTUNE, 3);
        maid.setItemInHand(InteractionHand.MAIN_HAND, silk);

        ItemStack hand = maid.getMainHandItem();
        MaidMiningMod.LOGGER.info("[DIAG] hand item = {} (empty={})", hand, hand.isEmpty());
        MaidMiningMod.LOGGER.info("[DIAG] getEnchantmentLevel(SILK_TOUCH) = {}",
                hand.getEnchantmentLevel(Enchantments.SILK_TOUCH));
        MaidMiningMod.LOGGER.info("[DIAG] getEnchantmentLevel(FORTUNE)    = {}",
                hand.getEnchantmentLevel(Enchantments.BLOCK_FORTUNE));
        MaidMiningMod.LOGGER.info("[DIAG] raw NBT = {}", hand.getTag());

        BreakResult result = dig(helper, maid, 1, 1, 1);
        MaidMiningMod.LOGGER.info("[DIAG] dig result = {}", result);
        MaidMiningMod.LOGGER.info("[DIAG] deepslate_diamond_ore={} diamond_ore={} diamond={}",
                count(maid, Items.DEEPSLATE_DIAMOND_ORE), count(maid, Items.DIAMOND_ORE),
                count(maid, Items.DIAMOND));

        // 只断言破坏成功，掉落形态由上面打印决定
        helper.assertTrue(result == BreakResult.SUCCESS, "破坏应成功，实际=" + result);
        helper.succeed();
    }

    // ===== 变量隔离：逐个排除 =====

    /** 基线：钻石镐 + 精准采集 + 深板岩钻石矿。 */
    @GameTest(template = TEMPLATE)
    public static void diamondPickaxeSilkOnDeepslate(GameTestHelper helper) {
        helper.setBlock(1, 1, 1, Blocks.DEEPSLATE_DIAMOND_ORE);
        EntityMaid maid = spawnMaid(helper, 4, 1, 4);
        ItemStack silk = new ItemStack(Items.DIAMOND_PICKAXE);
        silk.enchant(Enchantments.SILK_TOUCH, 1);
        maid.setItemInHand(InteractionHand.MAIN_HAND, silk);

        dig(helper, maid, 1, 1, 1);
        helper.assertTrue(mineDeepslateDiamond(maid) == 1,
                "钻石镐+精准采集应拿到 1 个深板岩钻石矿石方块，实际=" + mineDeepslateDiamond(maid)
                        + " (diamond=" + count(maid, Items.DIAMOND) + ")");
        helper.succeed();
    }

    /** 下界合金镐 + 精准采集：排除"镐等级"这一变量。 */
    @GameTest(template = TEMPLATE)
    public static void netheritePickaxeSilkOnDeepslate(GameTestHelper helper) {
        helper.setBlock(1, 1, 1, Blocks.DEEPSLATE_DIAMOND_ORE);
        EntityMaid maid = spawnMaid(helper, 4, 1, 4);
        ItemStack silk = new ItemStack(Items.NETHERITE_PICKAXE);
        silk.enchant(Enchantments.SILK_TOUCH, 1);
        maid.setItemInHand(InteractionHand.MAIN_HAND, silk);

        dig(helper, maid, 1, 1, 1);
        helper.assertTrue(mineDeepslateDiamond(maid) == 1,
                "下界合金镐+精准采集应拿到矿石方块，实际=" + mineDeepslateDiamond(maid));
        helper.succeed();
    }

    /**
     * 时运 + 精准采集<b>同时</b>存在：排除"两条分支互相干扰"。
     * <p>
     * 掉落表是 {@code alternatives}：silk_touch 分支在前，命中它就不会再走时运分支。
     * 因此带精准采集时应得到<b>恰好 1 个方块</b>（不是 1 个方块 + 时运加成）。
     */
    @GameTest(template = TEMPLATE)
    public static void silkWinsOverFortuneWhenBothPresent(GameTestHelper helper) {
        helper.setBlock(1, 1, 1, Blocks.DEEPSLATE_DIAMOND_ORE);
        EntityMaid maid = spawnMaid(helper, 4, 1, 4);
        ItemStack both = new ItemStack(Items.DIAMOND_PICKAXE);
        both.enchant(Enchantments.SILK_TOUCH, 1);
        both.enchant(Enchantments.BLOCK_FORTUNE, 3);
        maid.setItemInHand(InteractionHand.MAIN_HAND, both);

        dig(helper, maid, 1, 1, 1);
        helper.assertTrue(mineDeepslateDiamond(maid) == 1,
                "同时有时运与精准采集时，silk_touch 分支优先，应恰好 1 个方块，实际="
                        + mineDeepslateDiamond(maid));
        helper.succeed();
    }

    /**
     * <b>复现实机路径</b>：镐子在<b>背包里</b>（玩家给女仆装备的做法），由 ToolManager 换手。
     * <p>
     * 前面几个用例都把镐子直接放主手。如果实机无效而它们都通过，
     * 那问题很可能在<b>换手之后</b>附魔丢失——例如换手时复制/重建了 ItemStack。
     */
    @GameTest(template = TEMPLATE)
    public static void silkPickaxeEquippedFromBackpack(GameTestHelper helper) {
        helper.setBlock(1, 1, 1, Blocks.DEEPSLATE_DIAMOND_ORE);
        EntityMaid maid = spawnMaid(helper, 4, 1, 4);

        // 放进背包第 0 格，主手留空 —— 模拟玩家把附魔镐交给女仆
        ItemStack silk = new ItemStack(Items.DIAMOND_PICKAXE);
        silk.enchant(Enchantments.SILK_TOUCH, 1);
        maid.getMaidInv().setStackInSlot(0, silk);
        maid.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);

        com.leyue.maidmining.inv.ToolManager.ensureBestPickaxe(maid);

        ItemStack hand = maid.getMainHandItem();
        MaidMiningMod.LOGGER.info("[DIAG-backpack] main hand after equip = {} silkLevel={}",
                hand, hand.getEnchantmentLevel(Enchantments.SILK_TOUCH));
        helper.assertTrue(hand.getEnchantmentLevel(Enchantments.SILK_TOUCH) >= 1,
                "从背包换手后主手必须仍带精准采集（附魔在换手过程中丢了），实际等级="
                        + hand.getEnchantmentLevel(Enchantments.SILK_TOUCH));

        dig(helper, maid, 1, 1, 1);
        helper.assertTrue(mineDeepslateDiamond(maid) == 1,
                "背包换手路径也应拿到矿石方块，实际=" + mineDeepslateDiamond(maid)
                        + " (diamond=" + count(maid, Items.DIAMOND) + ")");
        helper.succeed();
    }

    /** 非矿石方块（石头）也能拿到方块 —— 精准采集不是只在矿石上生效。 */
    @GameTest(template = TEMPLATE)
    public static void silkTouchKeepsStoneBlock(GameTestHelper helper) {
        helper.setBlock(1, 1, 1, Blocks.STONE);
        EntityMaid maid = spawnMaid(helper, 4, 1, 4);
        ItemStack silk = new ItemStack(Items.DIAMOND_PICKAXE);
        silk.enchant(Enchantments.SILK_TOUCH, 1);
        maid.setItemInHand(InteractionHand.MAIN_HAND, silk);

        dig(helper, maid, 1, 1, 1);
        helper.assertTrue(count(maid, Items.STONE) >= 1,
                "精准采集挖石头应得到石头方块，实际=" + count(maid, Items.STONE)
                        + " (cobblestone=" + count(maid, Items.COBBLESTONE) + ")");
        helper.succeed();
    }

    /** 附魔等级为 0 的情况下不应命中 silk 分支（对照，确认谓词确实在工作）。 */
    @GameTest(template = TEMPLATE)
    public static void plainPickaxeStillDropsRawMaterial(GameTestHelper helper) {
        helper.setBlock(1, 1, 1, Blocks.DEEPSLATE_DIAMOND_ORE);
        EntityMaid maid = spawnMaid(helper, 4, 1, 4);
        maid.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_PICKAXE));

        dig(helper, maid, 1, 1, 1);
        helper.assertTrue(count(maid, Items.DIAMOND) >= 1,
                "无精准采集时应得到钻石粒，实际方块=" + mineDeepslateDiamond(maid)
                        + " diamond=" + count(maid, Items.DIAMOND));
        helper.succeed();
    }
}
