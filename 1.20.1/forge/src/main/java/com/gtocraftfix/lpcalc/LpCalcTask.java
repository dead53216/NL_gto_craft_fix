package com.gtocraftfix.lpcalc;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import appeng.api.networking.crafting.ICraftingPlan;

/**
 * 背景求解任務（§4.9）。絕不在此執行緒建 CraftingCalculation 或讀 grid（鐵則 5）——
 * 求解期回退一律入 LpFallbackQueue，由伺服器 tick 建樹狀版（+1 tick 可接受）。
 */
public final class LpCalcTask implements Runnable {

    private static final Logger LOG = LogManager.getLogger("gtocraftfix");
    private static final AtomicInteger THROWABLE_LOGGED = new AtomicInteger();

    private final LpCraftSnapshot snap;
    private final LpFallbackQueue.Request req;
    private final CompletableFuture<ICraftingPlan> future;

    public LpCalcTask(LpCraftSnapshot snap, LpFallbackQueue.Request req,
                      CompletableFuture<ICraftingPlan> future) {
        this.snap = snap;
        this.req = req;
        this.future = future;
    }

    @Override
    public void run() {
        try {
            var plan = LpSolver.solve(snap);
            if (plan.simulation() && LpConfig.shadowVerifyOnMissing()) {
                // [鐵則8] 誠實 sim（§6.7 全過）→ 影子跑樹狀版：樹狀版可行則採用並記分歧。
                // hit 於 shadow 落定（complete(lpSimPlan)）時才計——入列即計會把
                // 之後被樹狀版推翻（shadowAdopted）的也灌進 hit%，污染 §11 決策數據
                LpFallbackQueue.enqueueShadow(req, plan, future);
            } else {
                future.complete(plan);
                LpStats.hit();
            }
        } catch (LpFallbackException e) {
            LpStats.fallback(e.reason(), req.what(), req.amount());
            LpFallbackQueue.enqueue(req, e.reason(), future);
        } catch (Throwable t) {
            if (THROWABLE_LOGGED.incrementAndGet() <= 20) {
                LOG.error("[craftfix][lp] 未分類例外，回退樹狀版 out={}", req.what(), t);
            }
            LpStats.fallback(FallbackReason.ANY_THROWABLE, req.what(), req.amount());
            LpFallbackQueue.enqueue(req, FallbackReason.ANY_THROWABLE, future);
        }
    }
}
