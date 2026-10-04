package com.leyue.maidmining.mining;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.leyue.maidmining.cfg.MiningConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 目标搜索器：以女仆为中心，在配置半径内寻找最近的、可挖的矿石方块（包括埋在地下的）。
 * <p>
 * 与露天搜索不同，这里不做"可达性"过滤 —— 找到的矿即使被岩石埋住，
 * 也会交给隧道挖掘行为去挖开一条巷道抵达。
 * <p>
 * <b>1.02 的三处性能修正</b>（OPTIMIZATION.md MM-101 / MM-102 / MM-105）：
 * <ol>
 *   <li><b>绝不触碰未加载的区块</b>（最重要）。{@code level.getBlockState} 在未加载区块上会
 *       <b>同步加载甚至生成</b>区块：{@code getBlockState → getChunk(x,z,FULL) → requireChunk=true
 *       → ServerChunkCache.addTicket + managedBlock 阻塞等待主线程}。
 *       在低 {@code simulation-distance} 服务器上，旧实现每轮扫描都可能强制生成数十个区块。
 *       现在只用 {@code getChunkNow(x,z)}（已核实：不加载、不生成，拿不到就返回 null）；</li>
 *   <li><b>段级剪枝</b>：先用 {@code LevelChunkSection.hasOnlyAir()} 跳过整段空气，
 *       再用 {@code maybeHas} 走调色板预过滤（{@code false} ⇒ 该段确定没有匹配）。
 *       旧实现是逐 y 逐方块读，搜索区 52 层里绝大多数段全是石头，等于白读；</li>
 *   <li><b>热路径零分配</b>：复用 {@link BlockPos.MutableBlockPos}，
 *       旧实现一轮 ≈ 9,409 列 × 52 层 = <b>48.9 万次 {@code new BlockPos}</b>。</li>
 * </ol>
 * 另加<b>空扫指数退避</b>：扫不到东西时冷却逐次翻倍到上限，
 * 而不是每 5.7 秒空转一轮近 50 万次方块查询。
 */
public class MiningTunnelFinder {

    /** 预生成好的、按水平距离从近到远排列的偏移 (dx, dz)。 */
    private final List<int[]> offsets = new ArrayList<>();
    /** 生成偏移表时的半径，用于检测配置变化。 */
    private int offsetsRadius = -1;
    /** 当前扫描到的偏移下标。 */
    private int cursor;
    /** 冷却倒计时。 */
    private int cooldown;
    /** 连续空扫次数，驱动指数退避。 */
    private int emptyPasses;
    /** 因未加载而跳过的段数（MM-805 计数器；>0 说明前置检查确实在起作用）。 */
    private int skippedUnloadedSegments;

    /** 复用的可变坐标：避免每格分配新 BlockPos。 */
    private final BlockPos.MutableBlockPos cursorPos = new BlockPos.MutableBlockPos();
    /** 复用的目标结果缓冲。 */
    private BlockPos result;

    public MiningTunnelFinder() {
        rebuildOffsetsIfNeeded();
    }

    /** 重新开始一轮搜索（冷却清零；退避次数保留 —— 那是"这片区域确实没矿"的证据）。 */
    public void reset() {
        cursor = 0;
        cooldown = 0;
    }

    /**
     * 每 tick 调用一次。找到最近的矿石返回其坐标，否则返回空。
     * <p>
     * {@code exclude} 为要跳过的位置（挖不到的目标）。
     */
    public Optional<BlockPos> findNearestOre(ServerLevel level, EntityMaid maid, ItemStack tool,
                                             ItemStack offhand, Set<BlockPos> exclude) {
        if (cooldown > 0) {
            cooldown--;
            return Optional.empty();
        }
        // 配置可能被改，半径变了要重建偏移表
        rebuildOffsetsIfNeeded();

        int baseX = maid.getBlockX();
        int baseY = maid.getBlockY();
        int baseZ = maid.getBlockZ();
        int yTop = Math.min(baseY + MiningConfig.searchHeightUp(), level.getMaxBuildHeight() - 1);
        int yBottom = Math.max(baseY - MiningConfig.searchHeightDown(), level.getMinBuildHeight() + 1);
        if (yTop < yBottom) {
            return Optional.empty();
        }

        int budget = MiningConfig.columnsPerTick();
        for (int i = 0; i < budget; i++) {
            if (cursor >= offsets.size()) {
                // 整轮扫完：进入冷却，等待下一轮
                cursor = 0;
                startCooldown();
                return Optional.empty();
            }
            int[] off = offsets.get(cursor++);
            int x = baseX + off[0];
            int z = baseZ + off[1];

            // 只找在女仆活动范围内的目标（居家模式下不超出家门范围）
            if (MiningConfig.respectRestriction() && maid.hasRestriction()) {
                cursorPos.set(x, baseY, z);
                if (!maid.isWithinRestriction(cursorPos)) {
                    continue;
                }
            }

            // getChunkNow 不加载、不生成：拿不到就是没加载，直接跳过，绝不触碰它
            LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
            if (chunk == null) {
                skippedUnloadedSegments++;
                continue;
            }
            BlockPos found = searchColumn(level, chunk, x, z, yTop, yBottom, exclude, tool, offhand);
            if (found != null) {
                emptyPasses = 0;
                return Optional.of(found);
            }
        }
        return Optional.empty();
    }

    /**
     * 在一列里自上而下找矿。
     * <p>
     * 段级剪枝：先按 16 高的段跳过 {@code hasOnlyAir()} 的整段，
     * 再用 {@code maybeHas} 走调色板预过滤 —— 两者都能把「读 16 次方块状态」压成「读 1 次计数」。
     */
    private BlockPos searchColumn(ServerLevel level, LevelChunk chunk, int x, int z,
                                  int yTop, int yBottom, Set<BlockPos> exclude,
                                  ItemStack tool, ItemStack offhand) {
        LevelChunkSection[] sections = chunk.getSections();
        int minSection = Math.max(0, yBottom >> 4);
        int maxSection = Math.min(sections.length - 1, yTop >> 4);

        for (int sectionIndex = maxSection; sectionIndex >= minSection; sectionIndex--) {
            LevelChunkSection section = sections[sectionIndex];
            if (section == null || section.hasOnlyAir()) {
                continue;
            }
            // maybeHas 走调色板：false ⇒ 该段确定没有匹配方块（true 只是"可能有"）
            if (!section.maybeHas(state -> MiningValidator.isOre(state))) {
                continue;
            }
            int segTop = Math.min(yTop, (sectionIndex << 4) + 15);
            int segBottom = Math.max(yBottom, sectionIndex << 4);
            for (int y = segTop; y >= segBottom; y--) {
                cursorPos.set(x, y, z);
                if (exclude.contains(cursorPos)) {
                    continue;
                }
                if (MiningValidator.isOreBreakable(level, cursorPos, tool, offhand)) {
                    result = cursorPos.immutable();
                    return result;
                }
            }
        }
        return null;
    }

    /** 冷却策略：连续空扫时指数退避（MM-105），上限由配置决定。 */
    private void startCooldown() {
        emptyPasses = Math.min(emptyPasses + 1, 16);
        int base = MiningConfig.scanCooldownTicks();
        int ceiling = MiningConfig.emptyBackoffMaxTicks();
        int scaled = base << (emptyPasses - 1);
        // 左移溢出时 scaled 会变成负数或 0，这时直接用上限
        cooldown = (scaled <= 0 || scaled > ceiling) ? ceiling : scaled;
    }

    /** 因未加载而跳过的段数（MM-805 计数器；>0 说明前置检查确实在起作用）。 */
    public int skippedUnloadedSegments() {
        return skippedUnloadedSegments;
    }

    /** 连续空扫轮数（诊断用：长期居高不下说明该换搜索半径或等玩家挖矿脉了）。 */
    public int emptyPasses() {
        return emptyPasses;
    }

    /** 搜索半径可能在配置里被改过，偏移表需要重建。 */
    private void rebuildOffsetsIfNeeded() {
        int radius = MiningConfig.searchRadiusBlocks();
        if (offsetsRadius != radius) {
            offsetsRadius = radius;
            generateOffsets();
        }
    }

    /** 生成半径内的全部水平偏移，并按距离升序排列。 */
    private void generateOffsets() {
        offsets.clear();
        int radius = offsetsRadius;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                offsets.add(new int[]{dx, dz});
            }
        }
        offsets.sort(Comparator.comparingInt(o -> o[0] * o[0] + o[1] * o[1]));
        cursor = 0;
    }
}
