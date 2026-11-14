package com.leyue.maidmining.session;

/**
 * 挖矿会话状态。
 * <p>
 * 仍然只有三态 —— 1.0.x 的 SEARCH/DIG/MINE 在实机上是能跑通的，
 * 本轮重构<b>不引入八态状态机</b>（那是 OPTIMIZATION.md §4.2 的 1.2.0 目标）：
 * 决策质量（可达性探测、矿脉延续、价值评分）还没落地，提前扩状态只会增加迁移风险。
 * 这里只把状态<b>命名与职责</b>显式化，作为后续拆分的地基。
 */
public enum MiningState {
    /** 扫描周围寻找矿石。 */
    SEARCH,
    /** 逐格挖隧道接近目标。 */
    DIG,
    /** 已贴近，挖掉矿石。 */
    MINE;

    /** i18n 键后缀，供 {@code getMaidActionSummary()} 使用（MM-804）。 */
    public String translationKey() {
        return switch (this) {
            case SEARCH -> "maidmining.state.searching";
            case DIG -> "maidmining.state.travelling";
            case MINE -> "maidmining.state.mining";
        };
    }
}
