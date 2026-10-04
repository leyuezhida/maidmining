package com.leyue.maidmining.inv;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.util.TaskEquipUtil;
import com.leyue.maidmining.cfg.MiningConfig;
import com.leyue.maidmining.mining.MiningValidator;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.items.IItemHandler;

import java.util.List;
import java.util.stream.IntStream;

/**
 * 镐子的选择与更换（MM-505）。
 * <p>
 * <b>旧实现的问题</b>：{@code equipPickaxe} 用
 * {@code tryEquipFromBackpack(maid, stack -> stack.is(ItemTags.PICKAXES))} 拿<b>第一个</b>匹配的镐子
 * 就换手上 —— 完全不看等级、不看附魔、不看耐久。
 * 于是背包里同时有木镐和钻石镐时，她可能一直用木镐挖不动钻石矿，
 * 而挖掘失败又被当成"够不到"，最后把目标拉黑。
 * <p>
 * <b>1.02 的评分依据</b>（{@link #score}）：
 * <ol>
 *   <li><b>挖矿相关附魔</b> —— 精准采集（产物从原矿变矿石方块）、时运（多出产物）、
 *       效率、耐久修补；</li>
 *   <li><b>基础等级</b> —— 钻石镐挖不动的东西木镐也挖不动，等级是硬门槛；</li>
 *   <li><b>剩余耐久</b> —— 越耐用越优先。</li>
 * </ol>
 * 换手语义完全对齐 {@link TaskEquipUtil}（整叠取出 + 与主手交换，天然无复制），
 * 只是把"第一个匹配"换成"分数最高"。
 * <p>
 * <b>展示位保护</b>：全程跳过 {@link MaidInventory#DISPLAY_SLOT} ——
 * 那是女仆展示给主人看的背包外观，不能被换成一把镐子。
 */
public final class ToolManager {

    /** 挖矿场景下值得关注的附魔。 */
    private static final Enchantment[] RELEVANT_ENCHANTMENTS = {
            Enchantments.SILK_TOUCH,
            Enchantments.BLOCK_FORTUNE,
            Enchantments.BLOCK_EFFICIENCY,
            Enchantments.UNBREAKING,
    };

    private ToolManager() {
    }

    /**
     * 确保主手是当前最优的镐子。
     * <p>
     * 只有当手上这把"够用且没有更好的候选"时才什么都不做 ——
     * 否则每 tick 都会触发一次换手，物品在背包与主手之间反复抖动。
     */
    public static void ensureBestPickaxe(EntityMaid maid) {
        ItemStack current = maid.getMainHandItem();
        if (MiningValidator.isPickaxe(current)) {
            int bestScore = scoreOfBest(maid);
            if (bestScore <= score(current)) {
                return; // 手上这把已经最优
            }
        }
        equipBest(maid);
    }

    /** 把最优镐子换到主手。 */
    public static void equipBest(EntityMaid maid) {
        int threshold = scoreOfBest(maid);
        if (threshold == Integer.MIN_VALUE) {
            return; // 背包里一把镐都没有
        }
        // 优先用 TLM 的换手工具：整叠取出 + 与主手交换，语义正确且无复制
        if (TaskEquipUtil.tryEquipFromBackpack(maid,
                stack -> MiningValidator.isPickaxe(stack) && score(stack) >= threshold)) {
            return;
        }
        manualSwap(maid, threshold);
    }

    /** 手工换手：找到最佳槽位，取出整叠并把原主手放回该槽位（同样不复制）。 */
    private static void manualSwap(EntityMaid maid, int threshold) {
        IItemHandler inv = maid.getMaidInv();
        int bestSlot = -1;
        int bestScore = Integer.MIN_VALUE;
        for (int slot = 0; slot < inv.getSlots(); slot++) {
            if (slot == MaidInventory.DISPLAY_SLOT) {
                continue;
            }
            ItemStack stack = inv.getStackInSlot(slot);
            if (!MiningValidator.isPickaxe(stack)) {
                continue;
            }
            int value = score(stack);
            if (value > bestScore && value >= threshold) {
                bestScore = value;
                bestSlot = slot;
            }
        }
        if (bestSlot < 0) {
            return;
        }
        ItemStack picked = inv.extractItem(bestSlot, inv.getStackInSlot(bestSlot).getCount(), false);
        if (picked.isEmpty()) {
            return;
        }
        ItemStack previous = maid.getMainHandItem();
        if (!previous.isEmpty()) {
            inv.insertItem(bestSlot, previous, false);
        }
        maid.setItemInHand(InteractionHand.MAIN_HAND, picked);
    }

    /**
     * 给一把镐子打分，分数越高越优先。
     * <p>
     * 权重乘以 100 是为了让附魔分压过耐久分（耐久分上限 99）：
     * <ul>
     *   <li>精准采集 +400 × 等级 —— 挖矿时收益最大：产物从"原矿"变成"矿石方块"，可再生；</li>
     *   <li>时运 +300 × 等级 —— 直接增加产量；</li>
     *   <li>效率 +20 × 等级 —— 为将来的挖掘时间模型留位；</li>
     *   <li>耐久修补 +15 × 等级；</li>
     *   <li>基础等级 × 100；</li>
     *   <li>剩余耐久占比 0..99。</li>
     * </ul>
     */
    public static int score(ItemStack pickaxe) {
        if (pickaxe.isEmpty()) {
            return Integer.MIN_VALUE;
        }
        int result = tierOf(pickaxe) * 100;
        for (Enchantment enchantment : RELEVANT_ENCHANTMENTS) {
            result += enchantmentWeight(enchantment) * pickaxe.getEnchantmentLevel(enchantment);
        }
        return result + durabilityScore(pickaxe);
    }

    private static int enchantmentWeight(Enchantment enchantment) {
        if (enchantment == Enchantments.SILK_TOUCH) {
            return 400;
        }
        if (enchantment == Enchantments.BLOCK_FORTUNE) {
            return 300;
        }
        if (enchantment == Enchantments.BLOCK_EFFICIENCY) {
            return 20;
        }
        if (enchantment == Enchantments.UNBREAKING) {
            return 15;
        }
        return 0;
    }

    /** 基础等级排序值；非原版镐按"能挖石头"兜底给 1 分。 */
    @SuppressWarnings("deprecation") // Tier#getLevel 在 1.20.1 已弃用，但仍是唯一能拿到原版镐等级的方式
    private static int tierOf(ItemStack pickaxe) {
        if (pickaxe.getItem() instanceof PickaxeItem vanilla) {
            // 原版镐的等级恰好对应排序值（0=木 1=石 2=铁 3=金 4=钻石 5=下界合金）
            return Math.max(0, vanilla.getTier().getLevel());
        }
        // mod 镐：能挖石头至少 1 分，挖不了给 0
        return pickaxe.isCorrectToolForDrops(Blocks.STONE.defaultBlockState()) ? 1 : 0;
    }

    /** 剩余耐久占比映射到 0..99。 */
    private static int durabilityScore(ItemStack pickaxe) {
        int max = pickaxe.getMaxDamage();
        if (max <= 0) {
            return 99; // 无限耐久的工具视为满耐久
        }
        int remaining = max - pickaxe.getDamageValue();
        return Math.max(0, Math.min(99, remaining * 99 / max));
    }

    /** 背包里最优镐子的分数；一把都没有时返回 {@link Integer#MIN_VALUE}。 */
    private static int scoreOfBest(EntityMaid maid) {
        IItemHandler inv = maid.getMaidInv();
        int best = Integer.MIN_VALUE;
        for (int slot = 0; slot < inv.getSlots(); slot++) {
            if (slot == MaidInventory.DISPLAY_SLOT) {
                continue;
            }
            ItemStack stack = inv.getStackInSlot(slot);
            if (stack.isEmpty() || !MiningValidator.isPickaxe(stack)) {
                continue;
            }
            best = Math.max(best, score(stack));
        }
        return best;
    }

    /** 任务结束时把镐子还回背包（MM-604：停止时不该把玩家的镐子留在手上）。 */
    public static void putBack(EntityMaid maid) {
        if (!maid.getMainHandItem().isEmpty()) {
            TaskEquipUtil.putMainHandBack(maid);
        }
    }

    /** 剩余耐久是否已低于换镐阈值（仅在开启耐久消耗时有意义）。 */
    public static boolean shouldReplaceWorn(EntityMaid maid) {
        if (!MiningConfig.damageTool()) {
            return false;
        }
        ItemStack current = maid.getMainHandItem();
        if (current.isEmpty()) {
            return false;
        }
        int max = current.getMaxDamage();
        if (max <= 0) {
            return false;
        }
        return (max - current.getDamageValue()) <= MiningConfig.toolMinDurability();
    }

    /** 诊断用：列出候选镐子及其分数（从高分到低分）。 */
    public static List<String> describeCandidates(EntityMaid maid) {
        IItemHandler inv = maid.getMaidInv();
        return IntStream.range(0, inv.getSlots())
                .filter(slot -> slot != MaidInventory.DISPLAY_SLOT)
                .mapToObj(inv::getStackInSlot)
                .filter(s -> !s.isEmpty() && MiningValidator.isPickaxe(s))
                .sorted((a, b) -> Integer.compare(score(b), score(a)))
                .map(s -> s.getHoverName().getString()
                        + "(score=" + score(s)
                        + ", durability=" + (s.getMaxDamage() - s.getDamageValue()) + ")")
                .toList();
    }
}
