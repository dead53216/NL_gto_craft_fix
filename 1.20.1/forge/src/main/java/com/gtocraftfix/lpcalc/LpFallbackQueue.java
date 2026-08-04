package com.gtocraftfix.lpcalc;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.minecraft.world.level.Level;

import net.minecraftforge.server.ServerLifecycleHooks;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;

/**
 * 伺服器 tick 回退佇列（§4.10、鐵則 5 後半）。背景緒只入列；出列（樹狀版建構）
 * 只在伺服器執行緒——Mixin onServerEndTick 的 CalcTicker.tick() 之後呼叫 drainOnServerTick()。
 */
public final class LpFallbackQueue {

    public record Request(Level level, IGrid grid, ICraftingSimulationRequester simRequester,
                          AEKey what, long amount, CalculationStrategy strategy,
                          java.util.concurrent.ExecutorService pool) {}

    /** reason 與 lpSimPlan 恰一非 null：reason=一般回退、lpSimPlan=shadow 驗證 */
    private record Entry(Request req, FallbackReason reason, ICraftingPlan lpSimPlan,
                         CompletableFuture<ICraftingPlan> future) {}

    private static final ConcurrentLinkedQueue<Entry> QUEUE = new ConcurrentLinkedQueue<>();
    /** shadow 節流：同 key 一段時間內只跑一次完整樹狀影子驗證。穩態缺料下 stocking 機器每隔
     *  數十 tick 重發同 key 請求，每發一次就整棵樹狀重算會把 2 緒池塞爆、佇列無界成長。 */
    private static final ConcurrentHashMap<AEKey, Long> SHADOW_LAST = new ConcurrentHashMap<>();
    private static final long SHADOW_THROTTLE_NANOS = 10_000_000_000L; // 10s
    /** 佇列深度上限（只約束 shadow 入列；一般回退不丟——丟了請求就永不完成） */
    private static final int SHADOW_QUEUE_CAP = 64;

    private LpFallbackQueue() {}

    public static void enqueue(Request req, FallbackReason reason,
                               CompletableFuture<ICraftingPlan> future) {
        QUEUE.add(new Entry(req, reason, null, future));
    }

    public static void enqueueShadow(Request req, ICraftingPlan lpSimPlan,
                                     CompletableFuture<ICraftingPlan> future) {
        long now = System.nanoTime();
        Long last = SHADOW_LAST.get(req.what());
        if ((last != null && now - last < SHADOW_THROTTLE_NANOS) || QUEUE.size() > SHADOW_QUEUE_CAP) {
            // 節流窗內同 key 重複請求／佇列積壓 → 直接採用 LP sim 計畫（sim 會被 blockSubmit 擋單，
            // 語意同「LP 缺料結論成立」；樹狀影子留給節流窗外的下一次驗證）
            future.complete(lpSimPlan);
            LpStats.hit();
            LpStats.shadowSkipped();
            return;
        }
        if (SHADOW_LAST.size() > 256) {
            SHADOW_LAST.clear();
        }
        SHADOW_LAST.put(req.what(), now);
        QUEUE.add(new Entry(req, null, lpSimPlan, future));
    }

    /** 只在伺服器執行緒呼叫（Mixin onServerEndTick 新增行）。 */
    public static void drainOnServerTick() {
        Entry e;
        while ((e = QUEUE.poll()) != null) {
            // 跨世界殘留守衛：條目屬於已停機伺服器（入列瞬間玩家退出存檔）→ 絕不對死 grid
            // 建構樹狀版（會在新世界的伺服器緒讀舊世界殘骸），逐筆失敗釋放 Level/IGrid 強引用
            var cur = ServerLifecycleHooks.getCurrentServer();
            if (cur == null || e.req().level().getServer() != cur) {
                e.future().completeExceptionally(new IllegalStateException("server stopped"));
                continue;
            }
            try {
                // 樹狀版 ctor 的 grid 讀取就在此刻、伺服器執行緒完成（鐵則 5：禁止背景緒建構）
                var calc = new com.gtocraftfix.calc.CraftingCalculation(
                        e.req().level(), e.req().grid(), e.req().simRequester(),
                        new GenericStack(e.req().what(), e.req().amount()), e.req().strategy());
                var future = e.future();
                if (e.lpSimPlan() == null) {
                    // 一般回退：calc.run() 自行 register CalcTicker，預算由既有 Mixin 泵發
                    e.req().pool().submit(() -> {
                        try {
                            future.complete(calc.run());
                        } catch (Throwable t) {
                            future.completeExceptionally(t);
                        }
                    });
                } else {
                    // [鐵則8] shadow：樹狀版可執行 → 採用＋記分歧；樹狀版也 sim/拋例外 → LP 缺料結論被佐證
                    var lpSimPlan = e.lpSimPlan();
                    var what = e.req().what();
                    e.req().pool().submit(() -> {
                        try {
                            var treePlan = calc.run();
                            if (treePlan != null && !treePlan.simulation()) {
                                future.complete(treePlan);
                                LpStats.shadowAdopted(what);
                            } else {
                                future.complete(lpSimPlan);
                                LpStats.hit();
                            }
                        } catch (Throwable t) {
                            future.complete(lpSimPlan);
                            LpStats.hit();
                        }
                    });
                }
            } catch (Throwable t) {
                // 伺服器關閉／grid 失效等：與現行 run() 包 RuntimeException 進 Future 的語意等價
                e.future().completeExceptionally(t);
            }
        }
    }

    /** ServerStoppedEvent：清佇列並逐筆失敗——否則 static 佇列強持 Level/IGrid，
     *  整個舊 ServerLevel 在主選單期間無法 GC，且新世界首個 tick 會對死 grid 建構樹狀版。 */
    public static void clearOnServerStopped() {
        Entry e;
        while ((e = QUEUE.poll()) != null) {
            e.future().completeExceptionally(new IllegalStateException("server stopped"));
        }
        SHADOW_LAST.clear();
    }
}
