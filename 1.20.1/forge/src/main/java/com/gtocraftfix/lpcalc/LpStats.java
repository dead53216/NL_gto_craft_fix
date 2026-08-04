package com.gtocraftfix.lpcalc;

import java.util.EnumMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import appeng.api.stacks.AEKey;

/** [鐵則11] FallbackReason 逐項計數＋fastPathHit 率＋節流 log（沿用 [craftfix] 風格，§11）。 */
public final class LpStats {

    private static final Logger LOG = LogManager.getLogger("gtocraftfix");

    private static final LongAdder HIT = new LongAdder();
    private static final LongAdder SHADOW_ADOPTED = new LongAdder();
    /** shadow 節流/佇列上限跳過（直接採用 LP sim 計畫）的次數 */
    private static final LongAdder SHADOW_SKIPPED = new LongAdder();
    private static final EnumMap<FallbackReason, LongAdder> FALLBACKS = new EnumMap<>(FallbackReason.class);
    /** 逐筆回退 log 節流（前 200 次） */
    private static final AtomicInteger DETAIL = new AtomicInteger();
    /** shadow 分歧 log 節流（前 200 次） */
    private static final AtomicInteger SHADOW_DETAIL = new AtomicInteger();
    /** 彙總節流：每 512 個事件輸出一次 */
    private static final AtomicInteger EVENTS = new AtomicInteger();

    static {
        for (var r : FallbackReason.values()) {
            FALLBACKS.put(r, new LongAdder());
        }
    }

    private LpStats() {}

    public static void hit() {
        HIT.increment();
        maybeLog();
    }

    public static void fallback(FallbackReason r) {
        fallback(r, null, 0);
    }

    public static void fallback(FallbackReason r, AEKey what, long amount) {
        FALLBACKS.get(r).increment();
        if (DETAIL.incrementAndGet() <= 200) {
            LOG.info("[craftfix][lp] 回退 {} out={} x{}", r, what, amount);
        }
        maybeLog();
    }

    public static void shadowAdopted() {
        shadowAdopted(null);
    }

    public static void shadowAdopted(AEKey what) {
        SHADOW_ADOPTED.increment();
        if (SHADOW_DETAIL.incrementAndGet() <= 200) {
            // 模型誤報缺料的直接證據：以此數據決定 Phase 2 是否投資（§11）
            LOG.info("[craftfix][lp] shadow 分歧：LP 判缺料、樹狀版可行 out={}", what);
        }
    }

    public static void shadowSkipped() {
        SHADOW_SKIPPED.increment();
    }

    public static void maybeLog() {
        if (EVENTS.incrementAndGet() % 512 != 0) {
            return;
        }
        long hit = HIT.sum();
        long fb = 0;
        StringBuilder detail = new StringBuilder();
        for (var e : FALLBACKS.entrySet()) {
            long n = e.getValue().sum();
            if (n > 0) {
                fb += n;
                detail.append(e.getKey()).append('=').append(n).append(' ');
            }
        }
        long total = hit + fb;
        long pct = total == 0 ? 0 : hit * 100 / total;
        LOG.info("[craftfix][lp] 統計 hit={} ({}%) fallback={} [{}] shadowAdopted={} shadowSkipped={}",
                hit, pct, fb, detail.toString().trim(), SHADOW_ADOPTED.sum(), SHADOW_SKIPPED.sum());
    }
}
