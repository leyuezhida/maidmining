package com.leyue.maidmining.gametest;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.leyue.maidmining.MaidMiningMod;
import com.leyue.maidmining.act.DigExecutor;
import com.leyue.maidmining.cfg.MiningConfig;
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
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * <b>镐子附魔适配</b>的自动化验证 —— OPTIMIZATION.md MM-303 的核心用例。
 * <p>
 * <b>为什么这些用例是本轮最重要的回归</b>：
 * TLM 的 {@code EntityMaid.destroyBlock} 在字节码里硬编码把 {@code ItemStack.EMPTY}
 * 传给 {@code dropResourcesToMaidInv}，所以在 1.0.x 与 1.01 上
 * <b>时运与精准采集 100% 不生效</b> —— 玩家给女仆附了好镐，产出却毫无变化。
 * 用例把"附魔必须生效"钉死，防止哪天又退回 TLM 的空工具路径。
 * <p>
 * 运行方式：{@code ./gradlew runGameTestServer}（无需客户端）。
 */
@GameTestHolder(MaidMiningMod.MOD_ID)
@PrefixGameTestTemplate(false)
public class EnchantmentGameTests {

    private static final String TEMPLATE = "empty5";
    private static final ResourceLocation MAID_ID =
            ResourceLocation.tryBuild("touhou_little_maid", "maid");

    /**
     * 生成一个带背包的女仆，并让她站到指定相对坐标。
     * <p>
     * 不走 NBT：TLM 的女仆实体靠 SynchronizedEntityData 维护外观，
     * 直接 {@code type.create} 出来的实例同样有完整背包与手部容器，
     * 而这正是本组用例要测的部分。
     */
    @SuppressWarnings("deprecation") // BuiltInRegistries 在新版迁移到 Registries，1.20.1 上仍可用
    private static EntityMaid spawnMaid(GameTestHelper helper, int x, int y, int z) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(MAID_ID)
                .orElseThrow(() -> new IllegalStateException(
                        "TLM maid entity not found; is touhou_little_maid loaded?"));
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

    /** 给镐子附魔并放上主手。 */
    private static void equip(EntityMaid maid, ItemStack pickaxe) {
        maid.setItemInHand(InteractionHand.MAIN_HAND, pickaxe);
    }

    /** 挖一个方块，返回结果。 */
    private static BreakResult dig(GameTestHelper helper, EntityMaid maid, int x, int y, int z) {
        return DigExecutor.dig(helper.getLevel(), maid, helper.absolutePos(new BlockPos(x, y, z)));
    }

    private static int count(EntityMaid maid, Item item) {
        return MaidInventory.countItem(maid, item);
    }

    // ===== 时运 =====

    /**
     * 时运镐挖钻石矿的<b>平均产量</b>必须高于普通镐。
     * <p>
     * 1.0.x 的行为：一镐一个（TLM 传空工具，时运分支永不命中）⇒ 平均值恒为 1.0。
     * <p>
     * <b>为什么必须多次采样而不是断言单次</b>：时运走
     * {@code apply_bonus → ApplyBonusCount$UniformBonusCount}，
     * 内部是 {@code random.nextInt(bonusMultiplier + 1)}（已核实字节码），
     * 所以<b>单次挖掘的结果是随机的</b>——时运 III 完全可能只掉 1 个。
     * 断言"单次产量 &gt; 1"会随机变红（第一版就是这么写错的）。
     * 采样 40 次取总量，期望值差异（1.0 vs ≈2.5）足够大，判定才稳定。
     */
    @GameTest(template = TEMPLATE)
    public static void fortuneIncreasesDiamondYield(GameTestHelper helper) {
        int plainTotal = mineMany(helper, false);
        int fortuneTotal = mineMany(helper, true);
        double plainAvg = plainTotal / (double) FORTUNE_SAMPLES;
        double fortuneAvg = fortuneTotal / (double) FORTUNE_SAMPLES;

        helper.assertTrue(fortuneAvg > plainAvg,
                "时运镐的平均产量应高于普通镐：plain=" + plainAvg + " (" + plainTotal + "/" + FORTUNE_SAMPLES
                        + "), fortune=" + fortuneAvg + " (" + fortuneTotal + "/" + FORTUNE_SAMPLES + ")"
                        + "（若两者相等，说明掉落仍走了空工具路径）");
        helper.succeed();
    }

    private static final int FORTUNE_SAMPLES = 40;

    /** 反复挖钻石矿，返回总产量。每次都用不同坐标，避免重复挖已挖过的方块。 */
    private static int mineMany(GameTestHelper helper, boolean withFortune) {
        EntityMaid maid = spawnMaid(helper, 4, 1, 4);
        ItemStack pickaxe = new ItemStack(Items.DIAMOND_PICKAXE);
        if (withFortune) {
            pickaxe.enchant(Enchantments.BLOCK_FORTUNE, 3);
        }
        equip(maid, pickaxe);

        int total = 0;
        // empty5 模板是 5×5×5，把矿石铺在 maid 周围一圈，每轮换一批坐标
        int[][] spots = {
                {0, 1, 0}, {1, 1, 0}, {3, 1, 0}, {4, 1, 0},
                {0, 1, 1}, {1, 1, 1}, {3, 1, 1}, {4, 1, 1},
                {0, 1, 3}, {1, 1, 3}, {3, 1, 3}, {4, 1, 3},
                {0, 1, 4}, {1, 1, 4}, {3, 1, 4}, {4, 1, 4},
                {2, 1, 0}, {2, 1, 4}, {0, 1, 2}, {4, 1, 2},
        };
        for (int i = 0; i < FORTUNE_SAMPLES; i++) {
            int[] spot = spots[i % spots.length];
            helper.setBlock(spot[0], spot[1], spot[2], Blocks.DIAMOND_ORE);
            if (dig(helper, maid, spot[0], spot[1], spot[2]) == BreakResult.SUCCESS) {
                total += count(maid, Items.DIAMOND);
            }
        }
        return total;
    }

    // ===== 精准采集 =====

    /**
     * 精准采集挖钻石矿必须拿到<b>钻石矿石方块</b>而不是钻石粒。
     * <p>
     * 这是附魔适配最有价值的一条：原矿可再生，矿石方块直接是建材。
     * 1.0.x 永远拿不到方块。
     */
    @GameTest(template = TEMPLATE)
    public static void silkTouchKeepsTheOreBlock(GameTestHelper helper) {
        helper.setBlock(1, 1, 1, Blocks.DIAMOND_ORE);

        EntityMaid maid = spawnMaid(helper, 4, 1, 4);
        ItemStack silk = new ItemStack(Items.DIAMOND_PICKAXE);
        silk.enchant(Enchantments.SILK_TOUCH, 1);
        equip(maid, silk);

        BreakResult result = dig(helper, maid, 1, 1, 1);

        helper.assertTrue(result == BreakResult.SUCCESS, "破坏应成功，实际=" + result);
        helper.assertTrue(count(maid, Items.DIAMOND_ORE) == 1,
                "精准采集应拿到 1 个钻石矿石方块，实际=" + count(maid, Items.DIAMOND_ORE));
        helper.assertTrue(count(maid, Items.DIAMOND) == 0,
                "精准采集不应额外产出钻石粒，实际=" + count(maid, Items.DIAMOND));
        helper.succeed();
    }

    /** 对照组：不带附魔的镐子挖钻石矿应当得到钻石粒，而不是方块。 */
    @GameTest(template = TEMPLATE)
    public static void plainPickaxeDropsRawDiamond(GameTestHelper helper) {
        helper.setBlock(1, 1, 1, Blocks.DIAMOND_ORE);
        EntityMaid maid = spawnMaid(helper, 4, 1, 4);
        equip(maid, new ItemStack(Items.DIAMOND_PICKAXE));

        dig(helper, maid, 1, 1, 1);

        helper.assertTrue(count(maid, Items.DIAMOND) >= 1,
                "普通镐挖钻石矿应得到钻石粒，实际=" + count(maid, Items.DIAMOND));
        helper.assertTrue(count(maid, Items.DIAMOND_ORE) == 0,
                "普通镐不应拿到矿石方块，实际=" + count(maid, Items.DIAMOND_ORE));
        helper.succeed();
    }

    // ===== 耐久 =====

    /**
     * 默认配置（{@code dig.damageTool=false}）下挖矿<b>不消耗耐久</b>。
     * <p>
     * 1.02 的需求：女仆应能长期连续挖矿，不该把主人的镐子耗光。
     * 耐久真要按玩家规则消耗时打开配置即可，逻辑在 {@code DigExecutor#afterDig}。
     */
    @GameTest(template = TEMPLATE)
    public static void miningDoesNotConsumeDurabilityByDefault(GameTestHelper helper) {
        helper.assertTrue(!MiningConfig.damageTool(),
                "dig.damageTool 默认应为 false（1.02 需求：挖矿不消耗镐子耐久）");

        helper.setBlock(1, 1, 1, Blocks.STONE);
        helper.setBlock(2, 1, 1, Blocks.STONE);
        EntityMaid maid = spawnMaid(helper, 4, 1, 4);
        ItemStack pickaxe = new ItemStack(Items.IRON_PICKAXE);
        equip(maid, pickaxe);

        dig(helper, maid, 1, 1, 1);
        dig(helper, maid, 2, 1, 1);

        helper.assertTrue(pickaxe.getDamageValue() == 0,
                "挖 2 个方块后耐久应为 0，实际=" + pickaxe.getDamageValue());
        helper.assertTrue(pickaxe.getCount() == 1,
                "镐子不应消失，实际剩余=" + pickaxe.getCount());
        helper.succeed();
    }

    // ===== 物品守恒 =====

    /**
     * 物品守恒（MM-1003）：挖矿全程不允许凭空增殖。
     * <p>
     * 这是 1.0.x 的 P0 复制漏洞（MM-501）留下的教训：
     * 只更新本地计数、不回写实体，背包满时物品同时存在于背包与地面。
     */
    @GameTest(template = TEMPLATE)
    public static void miningConservesItemCount(GameTestHelper helper) {
        helper.setBlock(1, 1, 1, Blocks.DIAMOND_ORE);
        EntityMaid maid = spawnMaid(helper, 4, 1, 4);
        equip(maid, new ItemStack(Items.DIAMOND_PICKAXE));

        dig(helper, maid, 1, 1, 1);

        int inInventory = count(maid, Items.DIAMOND) + count(maid, Items.DIAMOND_ORE);
        BlockPos center = helper.absolutePos(new BlockPos(2, 1, 2));
        int onGround = helper.getLevel().getEntitiesOfClass(
                        ItemEntity.class, new AABB(center).inflate(8))
                .stream()
                .filter(e -> e.getItem().is(Items.DIAMOND) || e.getItem().is(Items.DIAMOND_ORE))
                .mapToInt(e -> e.getItem().getCount())
                .sum();
        helper.assertTrue(inInventory + onGround >= 1,
                "钻石必须存在于背包或地面：背包=" + inInventory + " 地面=" + onGround);
        // 正常情况下溢出为 0：背包有 36 格，装得下
        helper.assertTrue(onGround == 0,
                "背包空旷时不应有掉落物留在地面，实际=" + onGround);
        helper.succeed();
    }

    // ===== 展示位保护 =====

    /**
     * 展示位（槽位 5）里的物品不能被当成镐子换到手上，也不能被垫脚消耗。
     * <p>
     * {@code MaidBackpackHandler.BACKPACK_ITEM_SLOT == 5} 已用 javap 核实，
     * {@code onContentsChanged} 会在该槽变动时改写女仆展示的物品。
     */
    @GameTest(template = TEMPLATE)
    public static void displaySlotIsNeverUsedAsTool(GameTestHelper helper) {
        helper.assertTrue(MaidInventory.DISPLAY_SLOT == 5,
                "展示位常量必须与 TLM 的 BACKPACK_ITEM_SLOT 一致，实际=" + MaidInventory.DISPLAY_SLOT);

        EntityMaid maid = spawnMaid(helper, 4, 1, 4);
        ItemStack displayPick = new ItemStack(Items.DIAMOND_PICKAXE);
        maid.getMaidInv().setStackInSlot(MaidInventory.DISPLAY_SLOT, displayPick);
        // 槽位 0 放一把木镐，木镐分数低于钻石镐 —— 若实现读了展示位就会选错
        maid.getMaidInv().setStackInSlot(0, new ItemStack(Items.WOODEN_PICKAXE));

        ToolManager.ensureBestPickaxe(maid);

        helper.assertTrue(count(maid, Items.DIAMOND_PICKAXE) == 1,
                "展示位里的钻石镐必须原样保留（数量仍为 1），实际=" + count(maid, Items.DIAMOND_PICKAXE));
        helper.assertTrue(!maid.getMainHandItem().is(Items.DIAMOND_PICKAXE)
                        || maid.getMainHandItem().getCount() > 1,
                "换镐不得把展示位里的物品拔走");
        helper.succeed();
    }
}
