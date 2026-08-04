package com.gtocraftfix.lpcalc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import appeng.api.stacks.AEKey;

/**
 * 循環 SCC 求解（§6.3）。輪到縮點 C 時拓撲序保證外部消費者已處理完、外部需求已聚齊。
 * SCC 產出只交付 finalOutput（pin）或當超產留在 CPU 庫存回網（鐵則 3：絕不入折抵池、
 * 絕不承接 PATTERN 來源需求）。
 */
final class LpCycleSolver {

    private LpCycleSolver() {}

    static void solve(LpDemandPropagator ctx, int sccId) throws LpFallbackException {
        var g = ctx.g;
        var snap = ctx.snap;
        List<AEKey> keys = g.keysOfScc(sccId);
        var internal = new HashSet<>(keys);
        var pats = new ArrayList<LpPattern>();
        for (var pd : g.patternsOfScc(sccId)) {
            var lp = snap.compiled.get(pd);
            if (lp != null) {
                pats.add(lp);
            }
        }
        int nk = keys.size();
        int np = pats.size();
        if (np == 0) {
            throw new LpFallbackException(FallbackReason.SCC_UNSOLVABLE, "cyclic scc without patterns");
        }
        for (var p : pats) {
            if (p.ambiguousRedirect) {
                throw new LpFallbackException(FallbackReason.REDIRECT_AMBIGUOUS, "scc pattern");
            }
        }

        // ---- 內部 key 的外源需求 d[K]（來源必須全為 FINAL；順序同 §6.2：折抵 → 庫存 → 方程）----
        long[] d = new long[nk];
        for (int i = 0; i < nk; i++) {
            var k = keys.get(i);
            var q = ctx.queueOf(k);
            while (!q.isEmpty()) {
                var e = q.poll();
                long amt = e[0];
                int origin = (int) e[1];
                if (origin == LpDemandPropagator.ORIGIN_PATTERN) {
                    // [鐵則3] 循環 SCC 產出 key 承接同計畫外部樣板需求 → 禁止抵帳，回退
                    throw new LpFallbackException(FallbackReason.SCC_FEEDS_PATTERN, String.valueOf(k));
                }
                if (origin == LpDemandPropagator.ORIGIN_BOOTSTRAP) {
                    // [鐵則7] 啟動料回灌鏈落回循環 SCC → 不遞迴解環套環
                    throw new LpFallbackException(FallbackReason.LOOP_NESTED, String.valueOf(k));
                }
                long d0 = amt;
                amt = ctx.foldSurplus(k, amt, sccId);
                amt = ctx.takeAvail(k, amt);
                d[i] = LpMath.addX(d[i], amt);
                ctx.ledger.addFlow(k, d0);
            }
        }

        // ---- 步驟 1：外部 pin（樣板橫跨 SCC 內外，如 Kroll 的 Ti）----
        long[] pin = new long[np];
        var pins = ctx.pinDemand.remove(sccId);
        if (pins != null) {
            for (var e : pins.entrySet()) {
                var kExt = e.getKey();
                long need = e.getValue();
                int j = pinTarget(g, pats, kExt);
                long outOf = pats.get(j).outputOf(kExt);
                if (outOf <= 0) {
                    throw new LpFallbackException(FallbackReason.SCC_UNSOLVABLE, "pin no output " + kExt);
                }
                pin[j] = Math.max(pin[j], LpMath.ceilDiv(need, outOf)); // 多外部 key pin 同樣板取 max
            }
        }

        boolean anyWork = false;
        for (long v : d) {
            if (v > 0) {
                anyWork = true;
            }
        }
        for (long v : pin) {
            if (v > 0) {
                anyWork = true;
            }
        }
        if (!anyWork) {
            return;
        }

        // 淨係數 net[j][i] = out_pj(K_i) − in_pj(K_i)
        long[][] net = new long[np][nk];
        for (int j = 0; j < np; j++) {
            for (int i = 0; i < nk; i++) {
                net[j][i] = pats.get(j).outputOf(keys.get(i)) - pats.get(j).inputOf(keys.get(i));
            }
        }

        // ---- 步驟 2-3：解 x（自迴圈閉式 / 高斯）----
        long[] xi;
        if (np == 1) {
            // 自迴圈特例閉式解：b−a>0 → x=ceilDiv(d, b−a)；b−a≤0 且 d>0 → SCC_UNSOLVABLE
            long x = pin[0];
            for (int i = 0; i < nk; i++) {
                if (d[i] > 0) {
                    if (net[0][i] <= 0) {
                        throw new LpFallbackException(FallbackReason.SCC_UNSOLVABLE, "self-loop net<=0");
                    }
                    x = Math.max(x, LpMath.ceilDiv(d[i], net[0][i]));
                }
            }
            xi = new long[] { x };
        } else {
            xi = gaussian(nk, np, net, d, pin);
        }

        // ---- 步驟 4-5：整數化修補迴圈＋內部淨耗外部補給 ----
        long[] extraSupply = new long[nk];
        int limit = Math.max(2, 2 * nk);
        for (int iter = 0;; iter++) {
            int deficitKey = -1;
            long deficit = 0;
            for (int i = 0; i < nk; i++) {
                long slack = LpMath.addX(-d[i], extraSupply[i]);
                for (int j = 0; j < np; j++) {
                    slack = LpMath.addX(slack, LpMath.mulX(net[j][i], xi[j]));
                }
                if (slack < 0) {
                    deficitKey = i;
                    deficit = -slack;
                    break;
                }
            }
            if (deficitKey < 0) {
                break;
            }
            if (iter >= limit) {
                throw new LpFallbackException(FallbackReason.SCC_INT_FIXUP_LIMIT, "iter=" + iter);
            }
            int producer = -1;
            for (int j = 0; j < np; j++) {
                if (net[j][deficitKey] > 0) {
                    producer = j;
                    break;
                }
            }
            if (producer >= 0) {
                xi[producer] = LpMath.addX(xi[producer], LpMath.ceilDiv(deficit, net[producer][deficitKey]));
            } else {
                // 淨耗環（每輪損耗觸媒）：無內部生產者 → 外部補給以 BOOTSTRAP 回灌（步驟 7 同路徑）
                extraSupply[deficitKey] = LpMath.addX(extraSupply[deficitKey], deficit);
            }
        }

        // ---- runs 入帳（宣告序）＋外部輸入毛量傳播 [鐵則15] ----
        for (int j = 0; j < np; j++) {
            if (xi[j] > 0) {
                ctx.ledger.addRuns(pats.get(j).details, xi[j]);
            }
        }
        for (int j = 0; j < np; j++) {
            if (xi[j] <= 0) {
                continue;
            }
            var p = pats.get(j);
            for (int s = 0; s < p.inKey.length; s++) {
                if (!internal.contains(p.inKey[s])) {
                    // SCC 外部輸入不算 bootstrap：全額毛量以 PATTERN 來源下傳
                    ctx.addDemand(p.inKey[s], LpMath.mulX(p.inAmt[s], xi[j]),
                            LpDemandPropagator.ORIGIN_PATTERN, -1);
                }
            }
        }
        // 內部毛流量入 flow（bytes 只准偏大）
        for (int i = 0; i < nk; i++) {
            long gross = 0;
            for (int j = 0; j < np; j++) {
                gross = LpMath.addX(gross, LpMath.mulX(pats.get(j).inputOf(keys.get(i)), xi[j]));
            }
            ctx.ledger.addFlow(keys.get(i), gross);
        }

        // ---- 步驟 6：啟動料（執行可行性核心）----
        // [鐵則1] w_P = min(x_P, parallelOrUnknown ? 2 : 1)：並行（或偵測不確定）樣板至少備 2 輪料，
        // 繞開執行器 parallel==1 死角（isParallel && 剩餘>1 && 庫存只夠 1 輪 → 永久跳過）
        long[] boot = new long[nk];
        for (int j = 0; j < np; j++) {
            long w = Math.min(xi[j], pats.get(j).parallelOrUnknown ? 2 : 1);
            if (w <= 0) {
                continue;
            }
            for (int i = 0; i < nk; i++) {
                boot[i] = LpMath.addX(boot[i], LpMath.mulX(pats.get(j).inputOf(keys.get(i)), w));
            }
        }

        // ---- 步驟 7：啟動料來源（avail → used；不足 → BOOTSTRAP 回灌交 SCC 外樣板）----
        for (int i = 0; i < nk; i++) {
            long total = LpMath.addX(boot[i], extraSupply[i]);
            if (total > 0) {
                acquireBootstrap(ctx, sccId, keys.get(i), total);
            }
        }

        // ---- 步驟 8：SCC 波次可行性證書（局部預檢；最終仍以全計畫 audit 為準）----
        for (int doubling = 0;; doubling++) {
            if (waveCertificate(keys, internal, pats, xi, boot, extraSupply)) {
                break;
            }
            if (doubling >= LpConfig.maxBootstrapDoublings()) {
                throw new LpFallbackException(FallbackReason.SCC_WAVE_STUCK, "doublings=" + doubling);
            }
            // 卡死 → bootstrap 全體 ×2 重試（used/bootstrap 同步加，超出 avail 的部分走步驟 7 回灌）
            for (int i = 0; i < nk; i++) {
                if (boot[i] > 0) {
                    acquireBootstrap(ctx, sccId, keys.get(i), boot[i]);
                    boot[i] = LpMath.mulX(boot[i], 2);
                }
            }
        }
    }

    /** pin 目標樣板：K' 的 selectedPattern 若屬本 SCC 用它，否則取第一張有產出的 SCC 樣板。 */
    private static int pinTarget(LpGraph g, List<LpPattern> pats, AEKey kExt) {
        var sel = g.selectedPattern(kExt);
        if (sel != null) {
            for (int j = 0; j < pats.size(); j++) {
                if (pats.get(j) == sel) {
                    return j;
                }
            }
        }
        for (int j = 0; j < pats.size(); j++) {
            if (pats.get(j).outputOf(kExt) > 0) {
                return j;
            }
        }
        throw new LpFallbackException(FallbackReason.SCC_UNSOLVABLE, "no pin target " + kExt);
    }

    private static void acquireBootstrap(LpDemandPropagator ctx, int sccId, AEKey k, long need) {
        ctx.ledger.bootstrap.add(k, need); // 全額記錄供 log/audit（已含在 used 或回灌鏈內）
        ctx.ledger.addFlow(k, need);
        long rem = ctx.takeAvail(k, need);
        if (rem > 0) {
            ctx.addDemand(k, rem, LpDemandPropagator.ORIGIN_BOOTSTRAP, sccId);
        }
    }

    /**
     * 高斯消去（Rational）：內部 key 守恆方程＋pin 等式列（線性相依列由 pin 代入消元）。
     * 代入後仍欠定 / 不一致 / 負解 → SCC_UNSOLVABLE（樹狀版此時也算不動，回退不丟功能）。
     */
    private static long[] gaussian(int nk, int np, long[][] net, long[] d, long[] pin) {
        int pinRows = 0;
        for (long v : pin) {
            if (v > 0) {
                pinRows++;
            }
        }
        int rows = nk + pinRows;
        var m = new Rational[rows][np + 1];
        for (int i = 0; i < nk; i++) {
            for (int j = 0; j < np; j++) {
                m[i][j] = Rational.of(net[j][i], 1);
            }
            m[i][np] = Rational.of(d[i], 1);
        }
        int r = nk;
        for (int j = 0; j < np; j++) {
            if (pin[j] > 0) {
                for (int c = 0; c <= np; c++) {
                    m[r][c] = Rational.ZERO;
                }
                m[r][j] = Rational.ONE;
                m[r][np] = Rational.of(pin[j], 1);
                r++;
            }
        }

        int[] pivotRowOfCol = new int[np];
        Arrays.fill(pivotRowOfCol, -1);
        int row = 0;
        for (int col = 0; col < np && row < rows; col++) {
            int sel = -1;
            for (int rr = row; rr < rows; rr++) {
                if (!m[rr][col].isZero()) {
                    sel = rr;
                    break;
                }
            }
            if (sel < 0) {
                continue;
            }
            var tmp = m[sel];
            m[sel] = m[row];
            m[row] = tmp;
            var inv = Rational.ONE.div(m[row][col]);
            for (int c = col; c <= np; c++) {
                m[row][c] = m[row][c].mul(inv);
            }
            for (int rr = 0; rr < rows; rr++) {
                if (rr != row && !m[rr][col].isZero()) {
                    var f = m[rr][col];
                    for (int c = col; c <= np; c++) {
                        m[rr][c] = m[rr][c].sub(f.mul(m[row][c]));
                    }
                }
            }
            pivotRowOfCol[col] = row;
            row++;
        }
        // 不一致列（0 = 非零）→ 奇異
        for (int rr = row; rr < rows; rr++) {
            if (!m[rr][np].isZero()) {
                throw new LpFallbackException(FallbackReason.SCC_UNSOLVABLE, "inconsistent");
            }
        }
        var xi = new long[np];
        for (int j = 0; j < np; j++) {
            if (pivotRowOfCol[j] < 0) {
                // 代入 pin 後仍欠定
                throw new LpFallbackException(FallbackReason.SCC_UNSOLVABLE, "underdetermined");
            }
            var x = m[pivotRowOfCol[j]][np];
            if (x.isNegative()) {
                throw new LpFallbackException(FallbackReason.SCC_UNSOLVABLE, "negative");
            }
            long v = x.ceilToLong(); // 離開有理數域的唯一出口
            xi[j] = Math.max(v, pin[j]); // 解出後套 x_P = max(x_P, pin[P])
        }
        return xi;
    }

    /** 批次波模擬證書：可完成 → true；卡死 → false（呼叫端倍增啟動料重試）。 */
    private static boolean waveCertificate(List<AEKey> keys, HashSet<AEKey> internal,
            List<LpPattern> pats, long[] xi, long[] boot, long[] extraSupply) {
        var inv = new HashMap<AEKey, Long>();
        for (int i = 0; i < keys.size(); i++) {
            long v = LpMath.addX(boot[i], extraSupply[i]);
            if (v > 0) {
                inv.merge(keys.get(i), v, LpMath::addX);
            }
        }
        // 起始庫存＝bootstrap＋SCC 外部輸入毛量（外部輸入由下游全額供給，證書視為已到位）
        for (int j = 0; j < pats.size(); j++) {
            if (xi[j] <= 0) {
                continue;
            }
            var p = pats.get(j);
            for (int s = 0; s < p.inKey.length; s++) {
                if (!internal.contains(p.inKey[s])) {
                    inv.merge(p.inKey[s], LpMath.mulX(p.inAmt[s], xi[j]), LpMath::addX);
                }
            }
        }
        long[] remaining = xi.clone();
        long[] prevExec = null;
        var minInv = new HashMap<AEKey, Long>(); // 每 key 本波軌跡最低水位（只在消耗點更新）
        for (int wave = 0; wave < LpConfig.maxWaves(); wave++) {
            var start = new HashMap<>(inv);
            minInv.clear();
            var exec = new long[pats.size()];
            boolean any = false;
            for (int j = 0; j < pats.size(); j++) {
                if (remaining[j] <= 0) {
                    continue;
                }
                var p = pats.get(j);
                long can = remaining[j];
                for (int s = 0; s < p.inKey.length && can > 0; s++) {
                    can = Math.min(can, inv.getOrDefault(p.inKey[s], 0L) / p.inAmt[s]);
                }
                if (can <= 0) {
                    continue;
                }
                // [鐵則1] parallel==1 死角規則：並行(或未知)且剩餘>1 且庫存只夠 1 輪 → 本波 0 輪
                if (p.parallelOrUnknown && remaining[j] > 1 && can == 1) {
                    continue;
                }
                for (int s = 0; s < p.inKey.length; s++) {
                    long nv = inv.merge(p.inKey[s], -LpMath.mulX(p.inAmt[s], can), LpMath::addX);
                    minInv.merge(p.inKey[s], nv, Math::min);
                }
                for (int s = 0; s < p.outKey.length; s++) {
                    inv.merge(p.outKey[s], LpMath.mulX(p.outAmt[s], can), LpMath::addX);
                }
                remaining[j] -= can;
                exec[j] = can;
                any = true;
            }
            boolean done = true;
            for (long v : remaining) {
                if (v > 0) {
                    done = false;
                    break;
                }
            }
            if (done) {
                return true;
            }
            if (!any) {
                return false; // 卡死
            }
            // 穩態快轉（與 LpAuditor.replay 同法；取代舊「連續兩波庫存相等」外插證書——該證書對
            // 有淨進度的計畫永不觸發、且缺凍結任務守衛）：連續兩波 exec 相同 → 本波軌跡精確重複
            // ff 波（淨遞減 key 以本波最低水位兜底）。模擬壓縮而非證書跳躍——完成/卡死仍由
            // done/!any 裁決，凍結任務不會被放行。
            if (prevExec != null && Arrays.equals(exec, prevExec)) {
                long ff = Long.MAX_VALUE;
                for (int j = 0; j < exec.length && ff > 0; j++) {
                    if (exec[j] > 0) {
                        ff = Math.min(ff, remaining[j] / exec[j]);
                    }
                }
                if (ff > 0) {
                    for (var e : inv.entrySet()) {
                        long delta = e.getValue() - start.getOrDefault(e.getKey(), 0L);
                        if (delta < 0) {
                            long mn = minInv.getOrDefault(e.getKey(),
                                    Math.min(e.getValue(), start.getOrDefault(e.getKey(), 0L)));
                            ff = Math.min(ff, mn / -delta);
                            if (ff <= 0) {
                                break;
                            }
                        }
                    }
                }
                if (ff > 0) {
                    for (int j = 0; j < exec.length; j++) {
                        if (exec[j] > 0) {
                            remaining[j] -= LpMath.mulX(exec[j], ff);
                        }
                    }
                    for (var e : inv.entrySet()) {
                        long delta = e.getValue() - start.getOrDefault(e.getKey(), 0L);
                        if (delta != 0) {
                            e.setValue(LpMath.addX(e.getValue(), LpMath.mulX(delta, ff)));
                        }
                    }
                    prevExec = null; // 快轉後任務組成可能改變 → 重新偵測穩態
                    continue;
                }
            }
            prevExec = exec;
        }
        return false;
    }
}
