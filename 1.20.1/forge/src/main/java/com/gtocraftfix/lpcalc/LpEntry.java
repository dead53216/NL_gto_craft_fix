package com.gtocraftfix.lpcalc;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import net.minecraft.world.level.Level;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;

/**
 * mixin 唯一入口（§4.11），讓 mixin diff 最小化。內部自行 try-catch 一切（含 capture），
 * 理論上不外拋；外層 mixin 的 catch（不 setReturnValue → 退 GTO async）照舊當最後防線。
 */
public final class LpEntry {

    private LpEntry() {}

    /** 只在伺服器執行緒呼叫（beginCraftingCalculation HEAD 內、machineSrc0 分支）。 */
    public static Future<ICraftingPlan> beginMachineCalc(Level level, IGrid grid,
            ICraftingService svc, ICraftingSimulationRequester simRequester,
            AEKey what, long amount, CalculationStrategy strategy,
            java.util.concurrent.ExecutorService calcPool) {
        try {
            if (!LpConfig.enabled()) {
                // [鐵則12] 一鍵停用 → 與現行機器路徑完全相同（樹狀版）
                LpStats.fallback(FallbackReason.DISABLED, what, amount);
                return treeCalc(level, grid, simRequester, what, amount, strategy, calcPool);
            }
            var snap = LpCraftSnapshot.capture(level, grid, svc, simRequester, what, amount, strategy);
            if (snap.fallbackReason() != null) {
                // [鐵則5] 快照期偵測到不支援 → 當場（伺服器執行緒）建樹狀版；LP 快照丟棄，單份快照
                LpStats.fallback(snap.fallbackReason(), what, amount);
                return treeCalc(level, grid, simRequester, what, amount, strategy, calcPool);
            }
            var future = new CompletableFuture<ICraftingPlan>();
            var req = new LpFallbackQueue.Request(level, grid, simRequester, what, amount, strategy, calcPool);
            calcPool.submit(new LpCalcTask(snap, req, future));
            return future;
        } catch (Throwable t) {
            // 理論到不了（capture 不外拋）；最後防線：伺服器執行緒建樹狀版
            LpStats.fallback(FallbackReason.ANY_THROWABLE, what, amount);
            return treeCalc(level, grid, simRequester, what, amount, strategy, calcPool);
        }
    }

    private static Future<ICraftingPlan> treeCalc(Level level, IGrid grid,
            ICraftingSimulationRequester simRequester, AEKey what, long amount,
            CalculationStrategy strategy, java.util.concurrent.ExecutorService calcPool) {
        var calc = new com.gtocraftfix.calc.CraftingCalculation(level, grid, simRequester,
                new GenericStack(what, amount), strategy);
        return calcPool.submit(calc::run);
    }
}
