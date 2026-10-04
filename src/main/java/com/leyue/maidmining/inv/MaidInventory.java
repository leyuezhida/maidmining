package com.leyue.maidmining.inv;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.leyue.maidmining.cfg.MiningConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.items.wrapper.CombinedInvWrapper;

/**
 * 女仆背包读写的<b>唯一出口</b>（MM-502 / MM-509）。
 * <p>
 * <b>为什么必须有这一层</b>：1.0.x 到处直接 {@code maid.getMaidInv()} +
 * {@code setStackInSlot}，而 {@code getMaidInv()} 是 36 格整包，其中
 * <b>槽位 5（{@code MaidBackpackHandler.BACKPACK_ITEM_SLOT}，已核实常量值 == 5）是女仆的背包展示位</b>——
 * {@code onContentsChanged} 会在该槽变动时改写女仆展示的物品。
 * 旧代码有三处问题：
 * <ol>
 *   <li>垫脚建材从 0..getSlots() 无差别取用 → 会吃掉主人背包里的钻石块与矿石块；</li>
 *   <li>可能读到/覆盖槽位 5 的展示物品；</li>
 *   <li>裸 {@code setStackInSlot} <b>绕过</b> {@code EntityMaid.canInsertItem}
 *       （内含 {@code MaidConfig.MAID_BACKPACK_BLACKLIST}），
 *       能把 TLM 明令禁止入包的物品（如潜影盒）硬塞进去。</li>
 * </ol>
 * 本类的所有写操作统一走 {@link ItemHandlerHelper#insertItemStacked}，
 * 它会经过 {@code canInsertItem} 检查，因而尊重 TLM 的全部背包语义。
 */
public final class MaidInventory {

    /**
     * 女仆背包展示位（TLM 的 {@code MaidBackpackHandler.BACKPACK_ITEM_SLOT}，已核实为 5）。
     * <b>任何路径都不得把它当作可读写的普通格子。</b>
     */
    public static final int DISPLAY_SLOT = 5;

    private MaidInventory() {
    }

    /**
     * 可写背包：{@code getAvailableInv(false)}。
     * <p>
     * 已核实的字节码：它返回 {@code MaidInvWrapper(handsInvWrapper, RangedWrapper(maidInv, 0, N))}，
     * 其中 N 是当前背包类型的可用槽位数——<b>不是 36</b>。
     * 这正是 TLM 自己塞战利品用的容器，用它可以避免写入玩家不可见的槽位。
     */
    public static CombinedInvWrapper writableInv(EntityMaid maid) {
        return maid.getAvailableInv(false);
    }

    /** 只读的背包视图（垫脚建材挑选等场景要用到全部槽位，但展示位必须排除）。 */
    public static IItemHandler rawBackpack(EntityMaid maid) {
        return maid.getMaidInv();
    }

    /**
     * 插入物品，返回<b>没能装下的余量</b>。
     * <p>
     * 物品守恒的关键：调用方必须处理返回值（落地或保留），
     * <b>不能</b>以为"插进去了"就把源物品丢弃——那正是 1.0.0 的复制漏洞（MM-501）。
     */
    public static ItemStack insert(EntityMaid maid, ItemStack stack) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        return ItemHandlerHelper.insertItemStacked(writableInv(maid), stack, false);
    }

    /** 原子拾取一个掉落物实体：装进去多少就从实体扣多少，剩余量回写。 */
    public static boolean pickupItemEntity(EntityMaid maid, ItemEntity drop) {
        ItemStack stack = drop.getItem();
        if (stack.isEmpty()) {
            drop.discard();
            return true;
        }
        int original = stack.getCount();
        ItemStack remainder = insert(maid, stack.copy());
        int moved = original - remainder.getCount();
        if (moved <= 0) {
            // 背包完全放不下：什么都不动，物品仍在地上（这是正确行为，不是漏洞）
            return false;
        }
        if (remainder.isEmpty()) {
            drop.discard();
        } else {
            drop.setItem(remainder);
        }
        return true;
    }

    /**
     * 找出最适合当垫脚建材的槽位。
     * <p>
     * 判定顺序（MM-502）：
     * <ol>
     *   <li>跳过展示位——它显示给主人看，不能吃掉；</li>
     *   <li>跳过副手过滤物与食物（MM-506）：那些有明确用途，垫掉等于破坏玩家的意图；</li>
     *   <li>黑名单（矿石 / 贵重块 / 容器）一律拒绝；</li>
     *   <li>白名单里的廉价方块，按配置顺序取第一个（配置即优先级）。</li>
     * </ol>
     *
     * @param avoid 额外要避开的物品（如副手过滤物）
     * @return 槽位下标，没有可用建材时返回 -1
     */
    public static int findScaffoldSlot(EntityMaid maid, ServerLevel level,
                                        BlockPos pos, ItemStack avoid) {
        IItemHandler inv = rawBackpack(maid);
        for (int slot = 0; slot < inv.getSlots(); slot++) {
            if (slot == DISPLAY_SLOT) {
                continue;
            }
            ItemStack stack = inv.getStackInSlot(slot);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
                continue;
            }
            if (!avoid.isEmpty() && ItemStack.isSameItemSameTags(avoid, stack)) {
                continue;
            }
            Block block = blockItem.getBlock();
            if (!MiningConfig.isScaffoldAllowed(block)) {
                continue;
            }
            // 需要「有碰撞体积、能站上去」的方块
            if (block.defaultBlockState().getCollisionShape(level, pos).isEmpty()) {
                continue;
            }
            return slot;
        }
        return -1;
    }

    /** 取出指定槽位的一格建材（返回空栈表示失败）。 */
    public static ItemStack extractOne(EntityMaid maid, int slot) {
        if (slot < 0) {
            return ItemStack.EMPTY;
        }
        return rawBackpack(maid).extractItem(slot, 1, false);
    }

    /** 统计背包里某个物品的总数量（GameTest 物品守恒断言用）。 */
    public static int countItem(EntityMaid maid, Item item) {
        int total = 0;
        IItemHandler inv = rawBackpack(maid);
        for (int slot = 0; slot < inv.getSlots(); slot++) {
            ItemStack stack = inv.getStackInSlot(slot);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }
}
