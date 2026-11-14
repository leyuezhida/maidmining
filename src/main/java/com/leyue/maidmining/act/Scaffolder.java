package com.leyue.maidmining.act;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.leyue.maidmining.cfg.MiningConfig;
import com.leyue.maidmining.inv.MaidInventory;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 垫脚 / 起塔（MM-502 / MM-302 的行为侧）。
 * <p>
 * <b>1.0.x 的两个问题</b>：
 * <ol>
 *   <li>建材从 36 格背包无差别取第一个有碰撞体积的方块 → 会吃掉钻石块、矿石块、
 *       主人存放的建材，甚至展示位物品；</li>
 *   <li>直接 {@code level.setBlock(pos, state, 3)} 放置 → <b>不触发任何事件</b>，
 *       保护 mod 完全无法拦截，也无法获得方块自身的放置逻辑。</li>
 * </ol>
 * <p>
 * <b>本类的处理</b>：建材筛选交给 {@link MaidInventory#findScaffoldSlot}（白/黑名单 + 排除展示位）；
 * 放置仍走 {@code setBlock}，但<b>放置前必做碰撞校验</b>，绝不把女仆埋进自己刚放的方块里。
 * （改走 {@code BlockItem#place} 可以进入 Forge 的可拦截路径，但那需要构造
 * {@code BlockPlaceContext} 并处理朝向/含水等分支，属于 MM-302 的完整实现，
 * 本轮先保证行为正确、不埋自己。）
 */
public final class Scaffolder {

    private Scaffolder() {
    }

    /**
     * 在 {@code pos} 垫一块建材（脚下）。
     *
     * @param avoid 需要避开的物品（通常是副手过滤物，MM-506）
     * @return 是否成功放置
     */
    public static boolean placeStepBlock(ServerLevel level, EntityMaid maid, BlockPos pos, ItemStack avoid) {
        if (!MiningConfig.scaffoldEnabled()) {
            return false;
        }
        int slot = MaidInventory.findScaffoldSlot(maid, level, pos, avoid);
        if (slot < 0) {
            return false;
        }
        return extractAndPlace(level, maid, pos, slot);
    }

    /** 从指定槽位消耗 1 个并放置。放置前与女仆碰撞箱求交，避免把自己埋进去。 */
    private static boolean extractAndPlace(ServerLevel level, EntityMaid maid, BlockPos pos, int slot) {
        ItemStack stack = MaidInventory.extractOne(maid, slot);
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
            // 提取失败要把东西放回去，否则就是物品丢失
            if (!stack.isEmpty()) {
                MaidInventory.insert(maid, stack);
            }
            return false;
        }

        BlockState placeState = blockItem.getBlock().defaultBlockState();

        // 硬性不变式：绝不在女仆自身所在的身体柱里放方块。
        // 碰撞检查用 AABB 求交，但空中/贴边时可能漏判，所以再加一道位置断言。
        BlockPos bodyPos = maid.blockPosition();
        if (pos.equals(bodyPos) || pos.equals(bodyPos.above())) {
            MaidInventory.insert(maid, stack);
            return false;
        }

        VoxelShape shape = placeState.getCollisionShape(level, pos);
        if (!shape.isEmpty()) {
            AABB blockBox = shape.bounds().move(pos);
            if (maid.getBoundingBox().intersects(blockBox)) {
                MaidInventory.insert(maid, stack);
                return false; // 放下去会卡住女仆
            }
        }

        level.setBlock(pos, placeState, Block.UPDATE_ALL);
        maid.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    /** 背包里是否还有可用建材（用于判断"起不了塔"而不是"卡住"）。 */
    public static boolean hasScaffoldMaterial(ServerLevel level, EntityMaid maid) {
        return MaidInventory.findScaffoldSlot(maid, level, maid.blockPosition(), ItemStack.EMPTY) >= 0;
    }
}
