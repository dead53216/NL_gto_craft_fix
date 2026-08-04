package com.gtocraftfix.lpcalc;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;

/**
 * CRAFT_LESS 有界搜尋的上下界（§7）。速率取自「全量求解失敗帳本」的毛流量：
 * r_K = flow_K / amount（有理數語意，BigInteger 精確計算避免溢位）。
 * 上下界只是候選產生器——[鐵則9] 每個候選 R 都要過精確整數傳播＋完整 LpAuditor 才可回傳，
 * 故此處寬鬆無礙正確性。
 */
final class LpRates {

    /** 受限 key（末端原料：無樣板且非 emitable、毛流量 > 0）：{key, flow, cK} */
    private final List<Object[]> constraints = new ArrayList<>();
    private final long amount;

    private LpRates(long amount) {
        this.amount = amount;
    }

    /** 從全量 attempt 的帳本建構（fullLedger 含 missing——正是要降量的原因）。 */
    static LpRates compute(LpCraftSnapshot snap, LpLedger fullLedger, long amount) {
        var r = new LpRates(amount);
        for (var e : fullLedger.flow.object2LongEntrySet()) {
            var k = e.getKey();
            long flow = e.getLongValue();
            if (flow <= 0 || snap.emitable.contains(k)) {
                continue;
            }
            var pats = snap.patternsByKey.get(k);
            if (pats != null && !pats.isEmpty()) {
                continue; // 可合成的中間物不設限（庫存只會幫忙，實測驗證兜底）
            }
            // 常數鬆弛上界 c_K：沿路 ceil 餘數（每樣板 ≤1 輪輸入）＋ SCC 啟動料；只與圖大小有關
            long cK = fullLedger.bootstrap.get(k);
            for (var lp : snap.compiled.values()) {
                cK = LpMath.addX(cK, LpMath.mulX(lp.inputOf(k), 2));
            }
            r.constraints.add(new Object[] { k, flow, cK });
        }
        return r;
    }

    /** R_hi = min over 受限 key floor(供給 × amount / flow)。 */
    long rHi(KeyCounter avail) {
        return bound(avail, false);
    }

    /** R_lo = min over 受限 key floor((供給 − c_K) × amount / flow)，負值取 0。 */
    long rLo(KeyCounter avail) {
        return bound(avail, true);
    }

    private long bound(KeyCounter avail, boolean slack) {
        long best = amount;
        for (var c : constraints) {
            long supply = avail.get((AEKey) c[0]);
            if (slack) {
                supply -= (Long) c[2];
            }
            if (supply <= 0) {
                return 0;
            }
            long flow = (Long) c[1];
            var r = BigInteger.valueOf(supply).multiply(BigInteger.valueOf(amount))
                    .divide(BigInteger.valueOf(flow));
            long v = r.bitLength() > 62 ? Long.MAX_VALUE / 4 : r.longValue();
            best = Math.min(best, v);
        }
        return Math.max(best, 0);
    }

    /** 未過的候選：取瓶頸 key 缺額換算降幅（至少 −1），供下一輪候選。 */
    long reduce(long r, KeyCounter missing) {
        long worstCut = 1;
        for (var e : missing) {
            long shortAmt = e.getLongValue();
            if (shortAmt <= 0) {
                continue;
            }
            for (var c : constraints) {
                if (c[0].equals(e.getKey())) {
                    long flow = (Long) c[1];
                    // ceil(缺額 × amount / flow)（Rational ceil 語意）
                    var cut = BigInteger.valueOf(shortAmt).multiply(BigInteger.valueOf(amount))
                            .add(BigInteger.valueOf(flow - 1)).divide(BigInteger.valueOf(flow));
                    long cutL = cut.bitLength() > 62 ? Long.MAX_VALUE / 4 : cut.longValue();
                    worstCut = Math.max(worstCut, cutL);
                }
            }
        }
        return r - worstCut;
    }
}
