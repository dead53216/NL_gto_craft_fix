package com.gtocraftfix.calc;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.minecraftforge.server.ServerLifecycleHooks;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 原版 AE2 TickHandler 的算料預算泵替代品（fork 把該 hook 刪了）。
 * 算料在背景執行緒跑，但只有伺服器主執行緒透過 {@link CraftingCalculation#simulateFor} 發預算時
 * 才會前進——這個握手保證算料讀取 grid 時主執行緒正在等待，執行緒安全與原版一致。
 */
public final class CalcTicker {

    private static final Queue<CraftingCalculation> ACTIVE = new ConcurrentLinkedQueue<>();
    /** 每個 grid 的 CraftingService.onServerEndTick 都會呼叫 tick()，但 ACTIVE 是全域佇列：
     *  同一伺服器 tick 只泵一次（否則 ~10ms 預算與伺服器緒阻塞被 grid 數量倍乘，20+ grid
     *  的 GTO 基地一有算料進行中 TPS 就崩）。呼叫點不可動（鐵則13），在此以 tick 計數去重；
     *  只在伺服器緒讀寫，無需 volatile。 */
    private static long lastTickStamp = Long.MIN_VALUE;

    private static final Logger LOG = LoggerFactory.getLogger("gtocraftfix");
    /** 看門狗：每 calc 已泵 tick 數（只在伺服器緒讀寫）。卡在「永遠計算中」的請求不會產生任務、
     *  CPU 探針完全看不到，只有這裡看得到——60 秒與 300 秒各警告一次。 */
    private static final Map<CraftingCalculation, Integer> AGE = new IdentityHashMap<>();

    private CalcTicker() {}

    static void register(CraftingCalculation calc) {
        ACTIVE.add(calc);
    }

    /** 伺服器主執行緒每 tick 呼叫：分發時間預算（總量約 10ms/tick，平分給進行中的算料）。 */
    public static void tick() {
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            long stamp = server.getTickCount();
            if (stamp == lastTickStamp) {
                return; // 本 tick 已由別的 grid 泵過
            }
            lastTickStamp = stamp;
        }
        int n = ACTIVE.size();
        if (n == 0) {
            return;
        }
        int budget = Math.max(2000, 10000 / n);
        for (var it = ACTIVE.iterator(); it.hasNext();) {
            var calc = it.next();
            try {
                // 跨世界殘留：舊伺服器的 calc 不得再泵（會對死 grid 發預算）→ 中斷退出
                var lvl = calc.getLevel();
                if (server != null && lvl != null && lvl.getServer() != null
                        && lvl.getServer() != server) {
                    calc.cancel();
                    it.remove();
                    AGE.remove(calc);
                    continue;
                }
                int age = AGE.merge(calc, 1, Integer::sum);
                if (age == 1200 || age == 6000) {
                    LOG.warn("[craftfix] 算料看門狗：out={} 已算 {} 秒仍未完成（樹狀版持續發預算中）",
                            calc.getOutput(), age / 20);
                }
                if (!calc.simulateFor(budget)) {
                    it.remove();
                    AGE.remove(calc);
                }
            } catch (Throwable t) {
                it.remove();
                AGE.remove(calc);
            }
        }
    }

    /** ServerStoppedEvent：清空並中斷進行中算料（handlePausing 的 monitor.wait 會拋
     *  InterruptedException 退出），否則卡在等預算的 calc 佔住 2 緒池直到下個世界開檔。 */
    public static void clearOnServerStopped() {
        CraftingCalculation calc;
        while ((calc = ACTIVE.poll()) != null) {
            try {
                calc.cancel();
            } catch (Throwable ignored) {
            }
        }
        AGE.clear();
    }
}
