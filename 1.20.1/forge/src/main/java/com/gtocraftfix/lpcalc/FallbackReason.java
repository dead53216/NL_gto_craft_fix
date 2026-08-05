package com.gtocraftfix.lpcalc;

/**
 * lpcalc 回退樹狀版的原因（完整清單見 DESIGN-lpcalc.md §8）。
 * 伺服器緒觸發（capture/LpEntry）→ 當場建樹狀版；求解期觸發 → LpFallbackQueue（+1 tick）。
 */
public enum FallbackReason {
    /** [鐵則12] -Dgtodiag.lpcalc.enabled=false 一鍵停用 */
    DISABLED,
    /** 快照期任何未預期 Throwable */
    SNAPSHOT_ERROR,
    /** [鐵則14] 閉包超過 maxKeys/maxPatterns 或 nanoTime 預算 */
    CLOSURE_CAP,
    /** 求解期（背景緒）累計耗時超過 solveBudgetNanos（防病態閉包佔死執行緒池） */
    SOLVE_BUDGET,
    /** 任一輸入槽 getRemainingKey != null（容器物品不可線性化） */
    CONTAINER_ITEM,
    /** 主產出 ≤0、輸入量 ≤0 等編譯異常 */
    BAD_PATTERN,
    /** 多候選重導向槽所屬樣板 runs > 0 */
    REDIRECT_AMBIGUOUS,
    /** [重檢10] 唯一候選重導向解出的 key 不在該槽 possibleInputs 內（GTO 執行器只精確比對取料，執行必凍） */
    REDIRECT_UNREACHABLE,
    /** [鐵則3] 循環 SCC 產出 key 承接 PATTERN 來源需求（禁止抵帳） */
    SCC_FEEDS_PATTERN,
    /** 高斯奇異／代入 pin 後仍欠定／負解；自迴圈 b−a≤0 且 d>0 */
    SCC_UNSOLVABLE,
    /** 整數化修補迴圈超過 2×|K(C)| */
    SCC_INT_FIXUP_LIMIT,
    /** SCC 波次證書 3 次啟動料倍增後仍卡死 */
    SCC_WAVE_STUCK,
    /** [鐵則2] 啟動料缺口無外部解；或 missing 落在有樣板／計畫產出 key */
    LOOP_NO_BOOTSTRAP,
    /** [鐵則7] 啟動料外部回灌鏈落入另一個循環 SCC（不遞迴解環套環） */
    LOOP_NESTED,
    /** [鐵則10] missing 非空 且閉包任一 key 候選樣板 ≥2 */
    MULTI_PATH_LOAD_BEARING,
    /** [鐵則10] missing 非空 且閉包任一 key 有模糊變體存量 */
    FUZZY_LOAD_BEARING,
    /** [鐵則10] missing 非空 且任一樣板槽 possibleInputs.length > 1 */
    MULTI_INPUT_SHORT,
    /** long/Rational/BigInteger 任何溢位或 >128 bit */
    OVERFLOW,
    /** [鐵則7] 多趟傳播 4 趟未收斂 */
    PASS_LIMIT,
    /** [鐵則4] 守恆或任一順序波次重放不過 */
    AUDIT_FAIL,
    /** LpCalcTask 兜底：任何未分類 Throwable */
    ANY_THROWABLE
}
