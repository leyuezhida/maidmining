package com.leyue.maidmining.gametest;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.leyue.maidmining.MaidMiningMod;
import com.leyue.maidmining.act.DigExecutor;
import com.leyue.maidmining.inv.MaidInventory;
import com.leyue.maidmining.inv.ToolManager;
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
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * 精准采集的<b>场景覆盖</b>：把"在什么条件下生效"钉死。
 * <p>
 * <b>为什么需要这一组</b>：2026-10-04 实机排查过一次"时运有效、精准采集无效"，
 * 结论是测试用的镐子没附上精准采集 —— 代码本身没问题。但那次排查暴露了一个
 * 真实风险：<b>附魔类问题从外部现象根本得不出结论</b>
 * （"挖石头掉圆石"既可能是没附魔，也可能是附魔没读到，还可能是掉落表被改）。
 * {@code DigExecutor} 的 DIAG 日志正是为这种情况准备的。
 * <p>
 * 下面这些用例覆盖容易被未来重构改坏的变体：
 * <ul>
 *   <li><b>深板岩矿</b> —— 实机挖的绝大多数是深层变体，它与普通矿石是
 *       <b>两份独立的掉落表文件</b>；</li>
 *   <li><b>下界合金镐</b> —— 排除"镐等级"这一变量；</li>
 *   <li><b>时运 + 精准采集并存</b> —— 掉落表是 {@code alternatives}，
 *       silk_touch 分支在前命中即返回，两者<b>不会叠加</b>；</li>
 *   <li><b>从背包换手</b> —— 实机里玩家是把镐子放背包的，换手过程最可能丢附魔；</li>
 *   <li><b>非矿石方块</b> —— 精准采集对石头同样生效，不只是矿石。</li>
 * </ul>
 * 运行：{@code ./gradlew runGameTestServer}
 */
@GameTestHolder(MaidMiningMod.MOD_ID)
@PrefixGameTestTemplate(false)
public class EnchantmentCoverageTests {

    private static final String TEMPLATE = "empty5";
    private static final ResourceLocation MAID_ID =
            ResourceLocation.tryBuild("touhou_little_maid", "maid");

    @SuppressWarnings("deprecation") // BuiltInRegistries 在新版迁移到 Registries，1.20.1 上仍可用
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

    /** 深板岩钻石矿的产出（普通矿石方块也算 —— 变体只影响掉落表，不影响附魔判定）。 */
    private static int deepslateDiamondBlocks(EntityMaid maid) {
        return count(maid, Items.DEEPSLATE_DIAMOND_ORE) + count(maid, Items.DIAMOND_ORE);
    }

    private static ItemStack silkTouchPickaxe(Item pickaxe) {
        ItemStack stack = new ItemStack(pickaxe);
        stack.enchant(Enchantments.SILK_TOUCH, 1);
        return stack;
    }

    /**
     * 深板岩钻石矿 —— 实机挖的绝大多数是深层变体，
     * 它与 {@code diamond_ore} 是<b>两份独立的掉落表文件</b>。
     */
    @GameTest(template = TEMPLATE)
    public static void silkTouchWorksOnDeepslateDiamondOre(GameTestHelper helper) {
        helper.setBlock(1, 1, 1, Blocks.DEEPSLATE_DIAMOND_ORE);
        EntityMaid maid = spawnMaid(helper, 4, 1, 4);
        maid.setItemInHand(InteractionHand.MAIN_HAND, silkTouchPickaxe(Items.DIAMOND_PICKAXE));

        BreakResult result = dig(helper, maid, 1, 1, 1);

        helper.assertTrue(result == BreakResult.SUCCESS, "破坏应成功，实际=" + result);
        helper.assertTrue(deepslateDiamondBlocks(maid) == 1,
                "精准采集应拿到 1 个矿石方块，实际=" + deepslateDiamondBlocks(maid)
                        + " (diamond=" + count(maid, Items.DIAMOND) + ")");
        helper.succeed();
    }

    /** 下界合金镐 —— 排除"镐等级"这一变量。 */
    @GameTest(template = TEMPLATE)
    public static void silkTouchWorksWithNetheritePickaxe(GameTestHelper helper) {
        helper.setBlock(1, 1, 1, Blocks.DEEPSLATE_DIAMOND_ORE);
        EntityMaid maid = spawnMaid(helper, 4, 1, 4);
        maid.setItemInHand(InteractionHand.MAIN_HAND, silkTouchPickaxe(Items.NETHERITE_PICKAXE));

        dig(helper, maid, 1, 1, 1);
        helper.assertTrue(deepslateDiamondBlocks(maid) == 1,
                "下界合金镐+精准采集应拿到矿石方块，实际=" + deepslateDiamondBlocks(maid));
        helper.succeed();
    }

    /**
     * 时运与精准采集同时存在时，<b>精准采集优先且不叠加</b>。
     * <p>
     * 掉落表结构是 {@code alternatives}：silk_touch 分支在前且命中即返回，
     * 因此结果是<b>恰好 1 个方块</b>，而不是 1 个方块外加时运加成。
     * 将来若有人"优化"成先结算时运，这条会立刻变红。
     */
    @GameTest(template = TEMPLATE)
    public static void silkTouchTakesPriorityOverFortune(GameTestHelper helper) {
        helper.setBlock(1, 1, 1, Blocks.DEEPSLATE_DIAMOND_ORE);
        EntityMaid maid = spawnMaid(helper, 4, 1, 4);
        ItemStack both = silkTouchPickaxe(Items.DIAMOND_PICKAXE);
        both.enchant(Enchantments.BLOCK_FORTUNE, 3);
        maid.setItemInHand(InteractionHand.MAIN_HAND, both);

        dig(helper, maid, 1, 1, 1);
        helper.assertTrue(deepslateDiamondBlocks(maid) == 1,
                "同时有时运与精准采集时应恰好 1 个方块（silk_touch 分支优先），实际="
                        + deepslateDiamondBlocks(maid));
        helper.succeed();
    }

    /**
     * <b>实机真实路径</b>：镐子在背包里（玩家给女仆装备的做法），由 {@link ToolManager} 换手。
     * <p>
     * 其他用例都把镐子直接放主手。这条专门盯住换手环节 ——
     * 附魔写在 NBT 上，任何"取出/放回"实现出 bug 都会在这里暴露。
     */
    @GameTest(template = TEMPLATE)
    public static void silkTouchSurvivesEquippingFromBackpack(GameTestHelper helper) {
        helper.setBlock(1, 1, 1, Blocks.DEEPSLATE_DIAMOND_ORE);
        EntityMaid maid = spawnMaid(helper, 4, 1, 4);

        ItemStack silk = silkTouchPickaxe(Items.DIAMOND_PICKAXE);
        maid.getMaidInv().setStackInSlot(0, silk);
        maid.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);

        ToolManager.ensureBestPickaxe(maid);

        ItemStack hand = maid.getMainHandItem();
        helper.assertTrue(hand.getEnchantmentLevel(Enchantments.SILK_TOUCH) >= 1,
                "从背包换手后主手必须仍带精准采集（附魔在换手过程中丢了），实际等级="
                        + hand.getEnchantmentLevel(Enchantments.SILK_TOUCH));

        dig(helper, maid, 1, 1, 1);
        helper.assertTrue(deepslateDiamondBlocks(maid) == 1,
                "背包换手路径也应拿到矿石方块，实际=" + deepslateDiamondBlocks(maid));
        helper.succeed();
    }

    /** 非矿石方块同样生效 —— 精准采集不是矿石专属。 */
    @GameTest(template = TEMPLATE)
    public static void silkTouchKeepsStoneBlock(GameTestHelper helper) {
        helper.setBlock(1, 1, 1, Blocks.STONE);
        EntityMaid maid = spawnMaid(helper, 4, 1, 4);
        maid.setItemInHand(InteractionHand.MAIN_HAND, silkTouchPickaxe(Items.DIAMOND_PICKAXE));

        dig(helper, maid, 1, 1, 1);
        helper.assertTrue(count(maid, Items.STONE) >= 1,
                "精准采集挖石头应得到石头方块（而非圆石），实际 stone=" + count(maid, Items.STONE)
                        + " cobblestone=" + count(maid, Items.COBBLESTONE));
        helper.succeed();
    }
}
