package com.gtocraftfix.lpcalc;

import appeng.api.networking.crafting.CalculationStrategy;
import appeng.crafting.CraftingPlan;

/**
 * 背景執行緒協調器（§6）。全程只讀 snapshot 內資料——零 grid/level 存取（鐵則 5）。
 * 成功回 CraftingPlan；一切回退拋 LpFallbackException。
 */
final class LpSolver {

    private LpSolver() {}

    static CraftingPlan solve(LpCraftSnapshot snap) throws LpFallbackException {
        // 求解期硬預算：整個 solve（含 CRAFT_LESS 全部候選）共用一份——病態閉包不得佔死 2 緒池
        var budget = new LpBudget(LpConfig.solveBudgetNanos());
        long amount = snap.amount();
        var full = attempt(snap, amount, budget);
        if (full.ledger.missing.size() == 0) {
            return LpPlanBuilder.build(snap, full.g, full.ledger, snap.what(), amount);
        }
        if (snap.strategy() == CalculationStrategy.CRAFT_LESS) {
            var less = craftLess(snap, full, budget);
            if (less != null) {
                return less;
            }
        }
        // 誠實 sim（§6.7 兩關已在 attempt 內過；真缺料且無樣板無變體不回退——回退只會更慢得出同一結論）
        return LpPlanBuilder.build(snap, full.g, full.ledger, snap.what(), amount);
    }

    private record Attempt(LpGraph g, LpLedger ledger) {}

    /** 單次完整求解：圖 → 傳播（含 SCC）→ §6.7 檢查 → audit。等待邊狀態不跨 attempt（圖重建）。 */
    private static Attempt attempt(LpCraftSnapshot snap, long amount, LpBudget budget) throws LpFallbackException {
        var g = LpGraph.build(snap);
        budget.check("graph");
        var ledger = LpDemandPropagator.propagate(snap, g, amount, budget);
        checkMissingInvariants(snap, ledger);
        LpAuditor.verify(snap, g, ledger, snap.what(), amount, budget);
        return new Attempt(g, ledger);
    }

    /** §6.7：missing 不變量（鐵則 2）與粗化溯源（鐵則 10），audit 之前依序檢查。 */
    private static void checkMissingInvariants(LpCraftSnapshot snap, LpLedger ledger) {
        if (ledger.missing.size() == 0) {
            return;
        }
        // [鐵則2] missing 每個 key 必須 getCraftingFor 空、且非計畫內任何 runs>0 樣板的主/副產出；
        // 違反 → 禁止出 sim 計畫（repairPlan 會對有樣板的 missing 自動加 runs 沖銷並把 sim 翻 false）
        for (var e : ledger.missing) {
            if (e.getLongValue() <= 0) {
                continue;
            }
            var k = e.getKey();
            var pats = snap.patternsByKey.get(k);
            if (pats != null && !pats.isEmpty()) {
                throw new LpFallbackException(FallbackReason.LOOP_NO_BOOTSTRAP, "missing craftable " + k);
            }
            for (var re : ledger.runs.object2LongEntrySet()) {
                if (re.getLongValue() > 0) {
                    var lp = snap.compiled.get(re.getKey());
                    if (lp != null && lp.outputOf(k) > 0) {
                        throw new LpFallbackException(FallbackReason.LOOP_NO_BOOTSTRAP, "missing produced " + k);
                    }
                }
            }
        }
        // [鐵則10] 粗化溯源：不做精確缺口鏈——寧可多回退（樹狀版貪婪多分支/模糊模板可能救得回來）
        if (!snap.multiCandidate.isEmpty()) {
            throw new LpFallbackException(FallbackReason.MULTI_PATH_LOAD_BEARING, "");
        }
        if (!snap.fuzzyStocked.isEmpty()) {
            throw new LpFallbackException(FallbackReason.FUZZY_LOAD_BEARING, "");
        }
        if (snap.anyMultiInput) {
            throw new LpFallbackException(FallbackReason.MULTI_INPUT_SHORT, "");
        }
    }

    /**
     * CRAFT_LESS 有界搜尋（§7）。[鐵則9] 每個候選 R 都過精確整數傳播＋§6.7＋完整雙序 audit
     * 才可回傳；期間任何非 missing 類回退原因 → 整體回退（往外拋）。
     * 已知次優性：回傳 R 可能比理論最大小 ≤ max(c_K/r_K)——每個回傳值都實測可行（見 README）。
     */
    private static CraftingPlan craftLess(LpCraftSnapshot snap, Attempt full, LpBudget budget)
            throws LpFallbackException {
        long amount = snap.amount();
        if (amount <= 1) {
            return null;
        }
        var rates = LpRates.compute(snap, full.ledger, amount);
        long r = Math.min(rates.rHi(snap.availOriginal), amount - 1);
        long lastTried = -1;
        for (int fix = 0; fix < 4 && r >= 1; fix++) {
            lastTried = r;
            var att = attempt(snap, r, budget);
            if (att.ledger.missing.size() == 0) {
                // 做多少先交多少（追蹤器下輪補餘量）
                return LpPlanBuilder.build(snap, att.g, att.ledger, snap.what(), r);
            }
            r = rates.reduce(r, att.ledger.missing);
        }
        long rLo = Math.min(rates.rLo(snap.availOriginal), amount - 1);
        if (rLo >= 1 && rLo != lastTried) {
            var att = attempt(snap, rLo, budget);
            if (att.ledger.missing.size() == 0) {
                return LpPlanBuilder.build(snap, att.g, att.ledger, snap.what(), rLo);
            }
        }
        // R_max == 0 → 呼叫端對原始 amount 出誠實 sim（同受鐵則 2/10 約束——已在 attempt 內檢查）
        return null;
    }
}
