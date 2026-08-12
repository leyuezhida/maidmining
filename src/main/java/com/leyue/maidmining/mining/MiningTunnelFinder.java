package com.leyue.maidmining.mining;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 目标搜索器：以女仆为中心，在 {@link MiningConfig#SEARCH_RADIUS_BLOCKS}（3 区块）范围内
 * 寻找最近的、可挖的矿石方块（包括埋在地下的矿石）。
 * <p>
 * 与露天搜索不同，这里不做“可达性”过滤——找到的矿即使被岩石埋住，
 * 也会交给隧道挖掘行为去挖开一条巷道抵达。
 * 采用分帧扫描避免单 tick 卡顿。
 */
public class MiningTunnelFinder {
    /** 预生成好的、按水平距离从近到远排列的偏移 (dx, dz)。 */
    private final List<int[]> offsets = new ArrayList<>();
    /** 当前扫描到的偏移下标。 */
    private int cursor;
    /** 整轮扫描完成后的冷却倒计时。 */
    private int cooldown;

    public MiningTunnelFinder() {
        generateOffsets();
    }

    /** 重新开始一轮搜索。 */
    public void reset() {
        cursor = 0;
        cooldown = 0;
    }

    /** 每 tick 调用一次。找到最近的矿石返回其坐标，否则返回空。exclude 为要跳过的位置（挖不到的目标）。 */
    public Optional<BlockPos> findNearestOre(ServerLevel level, EntityMaid maid, ItemStack tool,
                                             ItemStack offhand, Set<BlockPos> exclude) {
        if (cooldown > 0) {
            cooldown--;
            return Optional.empty();
        }

        int baseX = maid.getBlockX();
        int baseY = maid.getBlockY();
        int baseZ = maid.getBlockZ();
        int yTop = baseY + MiningConfig.SEARCH_VERTICAL_UP;
        int yBottom = Math.max(baseY - MiningConfig.SEARCH_VERTICAL_DOWN,
                level.getMinBuildHeight() + 1);

        for (int i = 0; i < MiningConfig.COLUMNS_PER_TICK; i++) {
            if (cursor >= offsets.size()) {
                // 整轮扫完：进入冷却，等待下一轮
                cooldown = MiningConfig.SCAN_COOLDOWN_TICKS;
                cursor = 0;
                return Optional.empty();
            }
            int[] off = offsets.get(cursor++);
            // 只找在女仆活动范围内的目标（居家模式下不超出家门范围）
            int x = baseX + off[0];
            int z = baseZ + off[1];
            if (maid.hasRestriction() && !maid.isWithinRestriction(new BlockPos(x, baseY, z))) {
                continue;
            }
            for (int y = yTop; y >= yBottom; y--) {
                BlockPos pos = new BlockPos(x, y, z);
                if (exclude.contains(pos)) {
                    continue;
                }
                if (MiningValidator.isOreBreakable(level, pos, tool, offhand)) {
                    return Optional.of(pos);
                }
            }
        }
        return Optional.empty();
    }

    /** 生成半径内的全部水平偏移，并按距离升序排列。 */
    private void generateOffsets() {
        int radius = MiningConfig.SEARCH_RADIUS_BLOCKS;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                offsets.add(new int[]{dx, dz});
            }
        }
        offsets.sort(Comparator.comparingInt(o -> o[0] * o[0] + o[1] * o[1]));
    }
}
