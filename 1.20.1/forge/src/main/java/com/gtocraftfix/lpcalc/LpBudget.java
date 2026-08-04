package com.gtocraftfix.lpcalc;

/**
 * 求解期（背景緒）nanoTime 硬預算。快照期 1ms 預算擋不住求解期複雜度
 * （gaussian O(np²·rows) BigInteger、CRAFT_LESS 最多 6 次 attempt 全重建）——
 * 病態閉包若無上限會佔死 2 執行緒 FIFO 池，讓所有機器算料排隊數分鐘。
 * 超限拋 SOLVE_BUDGET → LpFallbackQueue 回退樹狀版（樹狀版有每 tick 預算泵，不佔死池）。
 */
final class LpBudget {

    private final long deadline;

    LpBudget(long budgetNanos) {
        this.deadline = System.nanoTime() + budgetNanos;
    }

    void check(String where) throws LpFallbackException {
        if (System.nanoTime() > deadline) {
            throw new LpFallbackException(FallbackReason.SOLVE_BUDGET, where);
        }
    }
}
