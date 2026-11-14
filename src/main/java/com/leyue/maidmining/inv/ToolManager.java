package com.leyue.maidmining.inv;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.util.TaskEquipUtil;
import com.leyue.maidmining.cfg.MiningConfig;
import com.leyue.maidmining.mining.MiningValidator;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.items.IItemHandler;

import java.util.List;
import java.util.stream.IntStream;

/**
 * 工具的选择与更换（MM-505 / MM-1102）。
 * <p>
 * <b>1.0.x 的问题</b>：{@code equipPickaxe} 用
 * {@code tryEquipFromBackpack(maid, stack -> stack.is(ItemTags.PICKAXES))} 拿<b>第一个</b>匹配的镐子
 * 就换手上 —— 完全不看等级、不看附魔、不看耐久。
 * <p>
 * <b>1.02 的评分依据</b>（{@link #score}）：
 * <ol>
 *   <li><b>挖矿相关附魔</b> —— 精准采集、时运、效率、耐久修补；</li>
 *   <li><b>基础等级</b> —— 钻石镐挖不动的东西木镐也挖不动，等级是硬门槛；</li>
 *   <li><b>剩余耐久</b> —— 越耐用越优先。</li>
 * </ol>
 * <b>1.1.0 新增两项</b>：
 * <ul>
 *   <li><b>工具集合由配置决定</b>（{@link MiningValidator#isTool}），不再写死镐子；</li>
 *   <li><b>按目标挑工具</b>：{@link #ensureBestTool(EntityMaid, BlockState)} 会把
 *       "能正确采掘当前目标"的工具顶到最前（{@value #CORRECT_TOOL_BONUS} 分）。
 *       没有这一条，白名单里同时有铲子和钻石镐时，她永远拿评分更高的镐去挖沙砾，
 *       掉落会按镐计算 —— 也就是什么都拿不到。</li>
 * </ul>
 * 换手语义完全对齐 {@link TaskEquipUtil}（整叠取出 + 与主手交换，天然无复制），
 * 只是把"第一个匹配"换成"分数最高"。
 * <p>
 * <b>展示位保护</b>：全程跳过 {@link MaidInventory#DISPLAY_SLOT} ——
 * 那是女仆展示给主人看的背包外观，不能被换成一把工具。
 */
public final class ToolManager {

    /** 挖矿场景下值得关注的附魔。 */
    private static final Enchantment[] RELEVANT_ENCHANTMENTS = {
            Enchantments.SILK_TOUCH,
            Enchantments.BLOCK_FORTUNE,
            Enchantments.BLOCK_EFFICIENCY,
            Enchantments.UNBREAKING,
    };

    /**
     * "正确工具"的加权分值。
     * <p>
     * 必须压过一切附魔与等级分，否则"带精准采集的镐"会赢过"能挖沙砾的铲子"。
     * 上界估算：精准采集 400 + 时运 300×3 + 效率 20×5 + 耐久 15×3 ≈ 1445，
     * 再加等级 500 与耐久 99 ≈ 2044，所以 5000 足够安全。
     */
    private static final int CORRECT_TOOL_BONUS = 5000;

    private ToolManager() {
    }

    /**
     * 确保主手是当前最优的工具（不针对特定目标）。
     * <p>
     * 只有当手上这把"够用且没有更好的候选"时才什么都不做 ——
     * 否则每 tick 都会触发一次换手，物品在背包与主手之间反复抖动。
     */
    public static void ensureBestTool(EntityMaid maid) {
        ensureBestTool(maid, null);
    }

    /**
     * 确保主手是"针对该目标的最优工具"。
     *
     * @param targetState 即将采掘的方块；{@code null} 表示还没锁定目标（搜索阶段），
     *                    此时只按等级/附魔/耐久排序
     */
    public static void ensureBestTool(EntityMaid maid, BlockState targetState) {
        ItemStack current = maid.getMainHandItem();
        if (MiningValidator.isTool(current)) {
            // 搜索阶段（还没有目标）：手上能用就别折腾。否则会在"评分更高的镐"与
            // "当前目标的正确工具"之间每锁定/放弃一次目标就来回换手。
            if (targetState == null) {
                return;
            }
            int best = scoreOfBest(maid, targetState);
            int currentScore = score(current, targetState);
            // 背包里没有别的工具（best 为 MIN_VALUE）时不要动：否则会把手上这把
            // 和它自己"交换"，每 20 tick 抖一次。
            if (best == Integer.MIN_VALUE || (!shouldReplaceWorn(maid) && best <= currentScore)) {
                return;
            }
        }
        equipBest(maid, targetState);
    }

    /** 把最优工具换到主手。 */
    public static void equipBest(EntityMaid maid, BlockState targetState) {
        int threshold = scoreOfBest(maid, targetState);
        if (threshold == Integer.MIN_VALUE) {
            return; // 背包里一件可用工具都没有
        }
        // 优先用 TLM 的换手工具：整叠取出 + 与主手交换，语义正确且无复制
        if (TaskEquipUtil.tryEquipFromBackpack(maid,
                stack -> MiningValidator.isTool(stack) && score(stack, targetState) >= threshold)) {
            return;
        }
        manualSwap(maid, threshold, targetState);
    }

    /** 手工换手：找到最佳槽位，取出整叠并把原主手放回该槽位（同样不复制）。 */
    private static void manualSwap(EntityMaid maid, int threshold, BlockState targetState) {
        IItemHandler inv = maid.getMaidInv();
        int bestSlot = -1;
        int bestScore = Integer.MIN_VALUE;
        for (int slot = 0; slot < inv.getSlots(); slot++) {
            if (slot == MaidInventory.DISPLAY_SLOT) {
                continue;
            }
            ItemStack stack = inv.getStackInSlot(slot);
            if (!MiningValidator.isTool(stack)) {
                continue;
            }
            int value = score(stack, targetState);
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

    /** 不针对特定目标打分（搜索阶段、诊断用）。 */
    public static int score(ItemStack tool) {
        return score(tool, null);
    }

    /**
     * 给一件工具打分，分数越高越优先。
     * <p>
     * 权重乘以 100 是为了让附魔分压过耐久分（耐久分上限 99）：
     * <ul>
     *   <li>精准采集 +400 × 等级 —— 收益最大：产物从"原矿"变成"矿石方块"，可再生；</li>
     *   <li>时运 +300 × 等级 —— 直接增加产量；</li>
     *   <li>效率 +20 × 等级 —— 为将来的挖掘时间模型留位；</li>
     *   <li>耐久修补 +15 × 等级；</li>
     *   <li>基础等级 × 100；</li>
     *   <li>剩余耐久占比 0..99；</li>
     *   <li>"是当前目标的正确工具" +{@value #CORRECT_TOOL_BONUS}（见类注释）。</li>
     * </ul>
     *
     * @param targetState 目标方块；{@code null} 表示不打"正确工具"这一项
     */
    public static int score(ItemStack tool, BlockState targetState) {
        if (tool.isEmpty()) {
            return Integer.MIN_VALUE;
        }
        int result = tierOf(tool) * 100;
        for (Enchantment enchantment : RELEVANT_ENCHANTMENTS) {
            result += enchantmentWeight(enchantment) * tool.getEnchantmentLevel(enchantment);
        }
        if (targetState != null && tool.isCorrectToolForDrops(targetState)) {
            result += CORRECT_TOOL_BONUS;
        }
        return result + durabilityScore(tool);
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

    /**
     * 基础等级排序值。
     * <p>
     * 1.0.x 只认 {@code PickaxeItem}，于是白名单里的铲子、模组工具一律得 0 分，
     * 永远排在任何镐子后面。现在改为所有 {@link TieredItem} 统一看 {@code Tier#getLevel()}
     * （原版木/金 0、石 1、铁 2、钻石 3、下界合金 4）；非分级工具按"能挖石头"兜底给 1 分。
     */
    @SuppressWarnings("deprecation") // Tier#getLevel 在 1.20.1 已弃用，但仍是唯一能拿到工具等级的方式
    private static int tierOf(ItemStack tool) {
        if (tool.getItem() instanceof TieredItem tiered) {
            return Math.max(0, tiered.getTier().getLevel());
        }
        // mod 工具：能挖石头至少 1 分，挖不了给 0
        return tool.isCorrectToolForDrops(Blocks.STONE.defaultBlockState()) ? 1 : 0;
    }

    /** 剩余耐久占比映射到 0..99。 */
    private static int durabilityScore(ItemStack tool) {
        int max = tool.getMaxDamage();
        if (max <= 0) {
            return 99; // 无限耐久的工具视为满耐久
        }
        int remaining = max - tool.getDamageValue();
        return Math.max(0, Math.min(99, remaining * 99 / max));
    }

    /** 背包里最优工具（不含手上这把）的分数；一件都没有时返回 {@link Integer#MIN_VALUE}。 */
    private static int scoreOfBest(EntityMaid maid, BlockState targetState) {
        IItemHandler inv = maid.getMaidInv();
        int best = Integer.MIN_VALUE;
        for (int slot = 0; slot < inv.getSlots(); slot++) {
            if (slot == MaidInventory.DISPLAY_SLOT) {
                continue;
            }
            ItemStack stack = inv.getStackInSlot(slot);
            if (stack.isEmpty() || !MiningValidator.isTool(stack)) {
                continue;
            }
            best = Math.max(best, score(stack, targetState));
        }
        return best;
    }

    /** 任务结束时把工具还回背包（MM-604：停止时不该把玩家的工具留在手上）。 */
    public static void putBack(EntityMaid maid) {
        if (!maid.getMainHandItem().isEmpty()) {
            TaskEquipUtil.putMainHandBack(maid);
        }
    }

    /** 剩余耐久是否已低于换工具阈值（仅在开启耐久消耗时有意义）。 */
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

    /** 诊断用：列出候选工具及其分数（从高分到低分）。 */
    public static List<String> describeCandidates(EntityMaid maid) {
        return describeCandidates(maid, null);
    }

    /** 诊断用：列出候选工具在指定目标下的分数（从高分到低分）。 */
    public static List<String> describeCandidates(EntityMaid maid, BlockState targetState) {
        IItemHandler inv = maid.getMaidInv();
        return IntStream.range(0, inv.getSlots())
                .filter(slot -> slot != MaidInventory.DISPLAY_SLOT)
                .mapToObj(inv::getStackInSlot)
                .filter(s -> !s.isEmpty() && MiningValidator.isTool(s))
                .sorted((a, b) -> Integer.compare(score(b, targetState), score(a, targetState)))
                .map(s -> s.getHoverName().getString()
                        + "(score=" + score(s, targetState)
                        + ", durability=" + (s.getMaxDamage() - s.getDamageValue()) + ")")
                .toList();
    }
}
