package com.leyue.maidmining.mining;

/**
 * 破坏方块的结果分类。
 * <p>
 * 存在的理由（见 OPTIMIZATION.md §1.5-A）：旧实现把「永不可破 / 流体 / 被保护 / 暂时失败」
 * 全部压成一个 {@code boolean}，调用侧只能盲目重试——对着基岩空转约 120 tick（6 秒），
 * 再叠加错误的恢复动作（向上传送）就形成了「贴着基岩无限上爬」的死循环。
 * <p>
 * 分类之后：{@link #UNBREAKABLE} / {@link #PROTECTED} 立刻放弃目标，只有
 * {@link #TEMPORARY} 才做有限次重试。
 */
public enum BreakResult {
    /** 已成功破坏（对空气也算「已通过」，调用方无需再处理）。 */
    SUCCESS,
    /** 女仆永远挖不动：基岩、硬度超限、负硬度等。写入「墙」集合并立刻放弃目标。 */
    UNBREAKABLE,
    /** 目标是流体（水/岩浆）。不走破坏路径，是否可通行由 {@code isPassable} 决定。 */
    FLUID,
    /** 被保护规则否决（{@code Block.canEntityDestroy} / {@code LivingDestroyBlockEvent} / mobGriefing=false）。 */
    PROTECTED,
    /** 本次没成功，但可能只是暂时。允许有限次重试，超过上限则放弃目标。 */
    TEMPORARY
}
