package com.gtocraftfix.lpcalc;

import it.unimi.dsi.fastutil.objects.Object2LongLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;

/** 帳本（§4.7）。跨趟只增不減（鐵則 7）；audit 對總帳跑一次全量檢查。 */
final class LpLedger {

    /** [鐵則15] usedItems 語意＝「相對網路起始庫存的最大下探水位」：直接吃庫存的毛量＋SCC 啟動料 */
    final KeyCounter used = new KeyCounter();
    final KeyCounter emitted = new KeyCounter();
    final KeyCounter missing = new KeyCounter();
    /** 折抵池總量鏡像（來源明細在傳播器；循環 SCC 產物一律不入，鐵則 3） */
    final KeyCounter surplus = new KeyCounter();
    /** SCC 啟動料（已含在 used 內，另記供 log/audit） */
    final KeyCounter bootstrap = new KeyCounter();
    /** 插入序 = 宣告序（audit 波次重放順序之一）；LinkedOpenHashMap 保插入序 */
    final Object2LongLinkedOpenHashMap<IPatternDetails> runs = new Object2LongLinkedOpenHashMap<>();
    /** 每 key 毛需求流量（bytes 用；只准偏大） */
    final Object2LongOpenHashMap<AEKey> flow = new Object2LongOpenHashMap<>();

    void addRuns(IPatternDetails p, long t) {
        if (t <= 0) {
            return; // runs==0 的樣板不進 patternTimes（§6.5）
        }
        long old = runs.getLong(p);
        runs.put(p, LpMath.addX(old, t));
    }

    void addFlow(AEKey k, long amt) {
        if (amt > 0) {
            flow.put(k, LpMath.addX(flow.getLong(k), amt));
        }
    }
}
