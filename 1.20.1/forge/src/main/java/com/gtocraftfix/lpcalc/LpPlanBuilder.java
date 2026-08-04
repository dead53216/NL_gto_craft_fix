package com.gtocraftfix.lpcalc;

import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingPlan;

/**
 * 計畫回填（§6.9）。必須回 appeng.crafting.CraftingPlan 具體類
 * （Mixin repairPlan 以 instanceof CraftingPlan 放行防線）。
 * gtocore$allocations 恆不設（null → 執行側 INSUFFICIENT_PRIORITY 邏輯整套跳過）。
 */
final class LpPlanBuilder {

    private LpPlanBuilder() {}

    static CraftingPlan build(LpCraftSnapshot snap, LpGraph g, LpLedger ledger,
                              appeng.api.stacks.AEKey what, long delivered) {
        // [鐵則13] 容器用 new Object2LongOpenHashMap；key 一律 getCraftingFor() 回傳的原始
        // IPatternDetails 實例（執行器強轉並靠 equals 對上 NetworkCraftingProviders 註冊實例）
        var patternTimes = new Object2LongOpenHashMap<IPatternDetails>();
        long totalRuns = 0;
        for (var e : ledger.runs.object2LongEntrySet()) {
            if (e.getLongValue() > 0) {
                patternTimes.put(e.getKey(), e.getLongValue());
                totalRuns = LpMath.addX(totalRuns, e.getLongValue());
            }
        }
        // bytes 只准高估不准低估（低估 → 小 CPU 接單爆容量）：流量 8x ＋ 每 craft 1 byte ＋ nodeCount 近似
        double bytes = 0;
        for (var e : ledger.flow.object2LongEntrySet()) {
            bytes += e.getLongValue() * 8.0 / e.getKey().getType().getAmountPerByte();
        }
        bytes += totalRuns;
        bytes += snap.closureKeyCount * 8.0;

        // 本算料器只產出兩種形狀（§6.7）：sim=false＋missing 空、或 sim=true＋missing 非空
        boolean simulation = ledger.missing.size() != 0;

        return new CraftingPlan(
                new GenericStack(what, delivered),
                (long) Math.ceil(bytes),
                simulation,
                g.multiplePaths(),
                copyClean(ledger.used),
                copyClean(ledger.emitted),
                copyClean(ledger.missing),
                patternTimes);
    }

    private static KeyCounter copyClean(KeyCounter src) {
        var out = LpCraftSnapshot.copyOf(src); // 各自 new KeyCounter，不與帳本/快照共用
        out.removeZeros();
        return out;
    }
}
