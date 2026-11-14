package com.leyue.maidmining.act;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.leyue.maidmining.MaidMiningMod;
import com.leyue.maidmining.cfg.MiningConfig;
import com.leyue.maidmining.mining.BreakResult;
import com.leyue.maidmining.session.MiningBlackboard;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.BehaviorUtils;
import net.minecraft.world.item.ItemStack;

/**
 * DIG 状态下的「逐格推进」决策：决定下一步往哪走、要不要挖哪一格。
 * <p>
 * <b>为什么从状态机里抽出来</b>（MM-601）：1.0.x 的 {@code MiningTunnelBehavior}
 * 把「状态迁移」和「几何推进」混在一起，涨到 954 行。这里的推进规则本身有清晰的
 * 几何含义（上行 / 下降 / 水平 / 绕行），单独成类后可以脱离游戏单独推演。
 * <p>
 * <b>行为等价</b>：本类是从原方法<b>原样搬运</b>过来的，逻辑与调用顺序均未改变，
 * 包括那些看起来奇怪但经过实机验证的分支（反斜角只走一个轴、前方是落差就往下挖、
 * 净空每 tick 只处理一格）。
 * <p>
 * <b>不负责</b>：世界改动（归 {@link DigExecutor}）、垂直移动（归 {@link VerticalMover}）、
 * 垫脚（归 {@link Scaffolder}）、背包（归 {@code inv/*}）。
 */
public final class TunnelAdvancer {

    private TunnelAdvancer() {
    }

    /** 推进的结果，供调用方决定是否切状态或放弃目标。 */
    public enum Step {
        /** 已推进一格（或正在推进）。 */
        ADVANCED,
        /** 目标不可达，应当放弃。 */
        ABANDON
    }

    /**
     * 执行一次逐格推进。调用方需先完成目标校验与节流。
     *
     * @param board     会话状态
     * @param abandon   放弃目标的回调（记录原因、拉黑、写日志）
     * @return 是否应当放弃该目标
     */
    public static boolean advance(ServerLevel level, EntityMaid maid, MiningBlackboard board,
                                  StepOutcome outcome) {
        BlockPos target = board.target();
        BlockPos feet = maid.blockPosition();
        int dx = Integer.signum(target.getX() - feet.getX());
        int dy = target.getY() - feet.getY();
        int dz = Integer.signum(target.getZ() - feet.getZ());
        int hDist = Math.abs(target.getX() - feet.getX()) + Math.abs(target.getZ() - feet.getZ());

        // 反斜角：窄隧道里同时动 X 与 Z 会卡在角上，一次只对齐一个轴
        if (dx != 0 && dz != 0) {
            if (Math.abs(target.getX() - feet.getX()) >= Math.abs(target.getZ() - feet.getZ())) {
                dz = 0;
            } else {
                dx = 0;
            }
        }
        int height = VerticalMover.requiredHeight(maid);

        // --- 阶段 1：先处理高度差 ---
        if (dy >= 2) {
            return ascend(level, maid, board, outcome, feet, dx, dz, dy, hDist, height);
        }
        if (dy <= -2) {
            return descend(level, maid, board, outcome, feet, dx, dz, hDist, height);
        }

        // --- 阶段 2：水平接近（|dy| <= 1） ---
        //
        // 注意：不再因为"目标高一点"就无条件上台阶 —— 女仆站在平地上就能挖到高一格的矿
        //（isAdjacent 允许 |dy| <= 1），为此搭台阶只会在自己的通道里砌墙（实机反馈）。
        if (hDist > 0) {
            return horizontal(level, maid, board, outcome, feet, dx, dz, dy, height);
        }

        // hDist == 0：正上 / 正下
        if (dy > 0) {
            if (outcome.stepUp(level, maid, feet, 0, 0, height)) {
                board.resetStuck();
            } else if (board.bumpStuck() > MiningConfig.maxStuckCount()) {
                board.resetStuck();
                outcome.abandon("cannot tower up to target");
                return true;
            }
            return false;
        }
        BreakResult below = DigExecutor.dig(level, maid, feet.below());
        if (below == BreakResult.UNBREAKABLE || below == BreakResult.PROTECTED) {
            outcome.wall(feet.below(), below);
            outcome.abandon("floor unbreakable (" + below + ")");
            return true;
        }
        return false;
    }

    /** 目标在上方：走上行原语；连续失败才考虑水平绕行。 */
    private static boolean ascend(ServerLevel level, EntityMaid maid, MiningBlackboard board,
                                  StepOutcome outcome, BlockPos feet, int dx, int dz,
                                  int dy, int hDist, int height) {
        if (outcome.stepUp(level, maid, feet, dx, dz, height)) {
            board.resetStuck();
            return false;
        }
        if (board.bumpStuck() <= MiningConfig.maxStuckCount()) {
            return false;
        }
        board.resetStuck();
        if (!board.ascendFailLogged()) {
            board.markAscendFailLogged();
            MaidMiningMod.LOGGER.info("[MaidMining] Ascend failed at ({},{},{}) dy={} hDist={} maid={}",
                    feet.getX(), feet.getY(), feet.getZ(), dy, hDist, maid.getId());
        }
        if (hDist > 0) {
            bypassHorizontal(level, maid, board, outcome, feet, dx, dz, height);
            return false;
        }
        outcome.abandon("cannot ascend to target");
        return true;
    }

    /**
     * 目标在下方：只做"向下挖"或"向下走"。
     * <p>
     * 旧实现在这里调用 {@code tryClimb()}，那是 §1.5-A 无限上爬死循环的直接来源 ——
     * <b>恢复动作绝不能把女仆带离目标</b>。
     */
    private static boolean descend(ServerLevel level, EntityMaid maid, MiningBlackboard board,
                                   StepOutcome outcome, BlockPos feet, int dx, int dz,
                                   int hDist, int height) {
        BlockPos below = feet.below();
        BreakResult result = DigExecutor.dig(level, maid, below);
        switch (result) {
            case SUCCESS -> {
                board.resetStuck();
                return false;
            }
            case UNBREAKABLE, PROTECTED -> {
                outcome.wall(below, result);
                outcome.abandon("unbreakable below (" + result + ")");
                return true;
            }
            case TEMPORARY -> {
                if (outcome.temporaryFailure()) {
                    return true;
                }
            }
            case FLUID -> {
                // 流体不可破坏但可能可通行，交给 isPassable 判断
            }
        }
        if (VerticalMover.isPassable(level, below)) {
            walkTo(level, maid, board, below);
            return false;
        }
        if (board.bumpStuck() <= MiningConfig.maxStuckCount()) {
            return false;
        }
        board.resetStuck();
        if (hDist > 0) {
            bypassHorizontal(level, maid, board, outcome, feet, dx, dz, height);
            return false;
        }
        outcome.abandon("blocked below");
        return true;
    }

    /** 水平推进一格：清脚部 → 检查落差 → 清净空 → 下发走位目标。 */
    private static boolean horizontal(ServerLevel level, EntityMaid maid, MiningBlackboard board,
                                      StepOutcome outcome, BlockPos feet, int dx, int dz,
                                      int dy, int height) {
        BlockPos next = feet.offset(dx, 0, dz);

        // 1) 先保证脚部那一格能过
        if (!VerticalMover.isPassable(level, next)) {
            BreakResult result = DigExecutor.dig(level, maid, next);
            if (result == BreakResult.UNBREAKABLE || result == BreakResult.PROTECTED) {
                outcome.wall(next, result);
                outcome.abandon("unbreakable ahead (" + result + ")");
                return true;
            }
            return false;
        }

        // 2) 前方是落差就往下挖，而不是试图走进去
        if (VerticalMover.isPassable(level, next.below())) {
            DigExecutor.dig(level, maid, feet.below());
            return false;
        }

        // 3) 净空：y+1 .. y+height-1（通常只有 y+1 一格）
        for (int i = 1; i < height; i++) {
            BlockPos need = feet.offset(dx, i, dz);
            if (VerticalMover.isPassable(level, need)) {
                continue;
            }
            BreakResult result = DigExecutor.dig(level, maid, need);
            if (result == BreakResult.UNBREAKABLE || result == BreakResult.PROTECTED) {
                outcome.wall(need, result);
                outcome.abandon("unbreakable overhead (" + result + ")");
                return true;
            }
            return false; // 本 tick 只处理一格
        }

        board.resetStuck();
        walkTo(level, maid, board, next);
        return false;
    }

    /** 水平推进一格（仅在上行失败时作为绕行手段）。 */
    private static void bypassHorizontal(ServerLevel level, EntityMaid maid, MiningBlackboard board,
                                         StepOutcome outcome, BlockPos feet, int dx, int dz,
                                         int height) {
        BlockPos next = feet.offset(dx, 0, dz);
        BreakResult result = DigExecutor.dig(level, maid, next);
        if (result == BreakResult.UNBREAKABLE || result == BreakResult.PROTECTED) {
            outcome.wall(next, result);
            outcome.abandon("wall in bypass path (" + result + ")");
            return;
        }
        if (result == BreakResult.SUCCESS) {
            board.resetStuck();
        }
        for (int i = 1; i < height; i++) {
            BlockPos need = feet.offset(dx, i, dz);
            if (!VerticalMover.isPassable(level, need)) {
                DigExecutor.dig(level, maid, need);
            }
        }
        if (VerticalMover.isPassable(level, next)) {
            walkTo(level, maid, board, next);
        }
    }

    /** 下发走位目标并记录"下一格"（供发呆接管使用）。 */
    private static void walkTo(ServerLevel level, EntityMaid maid, MiningBlackboard board, BlockPos dest) {
        board.pendingStep(dest);
        BehaviorUtils.setWalkAndLookTargetMemories(maid, dest, MiningConfig.moveSpeed(), 0);
    }

    /**
     * 推进过程中需要回调到状态机的动作。
     * <p>
     * 用接口而不是直接持有状态机，是为了避免循环依赖，也便于单独测试推进逻辑。
     * <p>
     * <b>注意</b>：回调不接收 level/maid —— 它们都是无状态或只需女仆 id 的动作，
     * 状态机自己持有上下文。不要为了"统一"而传 null 占位。
     */
    public interface StepOutcome {
        /** 上行一格。返回 false 表示这一步暂时做不到。 */
        boolean stepUp(ServerLevel level, EntityMaid maid, BlockPos feet, int dx, int dz, int height);

        /** 记录一块"永远挖不动"的方块。 */
        void wall(BlockPos pos, BreakResult result);

        /** 暂时性破坏失败计数。返回 true 表示已放弃目标。 */
        boolean temporaryFailure();

        /** 放弃当前目标并记录原因。 */
        void abandon(String reason);
    }
}
