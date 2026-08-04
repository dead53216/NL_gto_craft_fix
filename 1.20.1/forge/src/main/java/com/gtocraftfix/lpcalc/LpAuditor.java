package com.gtocraftfix.lpcalc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import appeng.api.stacks.AEKey;

/**
 * 守恆＋雙順序波次重放（§6.8）。違反任一 → AUDIT_FAIL（絕不輸出爛帳）。
 * 內部全用 int 索引與 long[]（純數字，無 grid 存取）。
 */
final class LpAuditor {

    private LpAuditor() {}

    /** [鐵則4] 至少兩種順序（宣告序＋對抗序）都 PASS 才算過；起始庫存只灌 usedItems。 */
    static void verify(LpCraftSnapshot snap, LpGraph g, LpLedger ledger,
                       AEKey what, long amountDelivered, LpBudget budget) throws LpFallbackException {
        // ---- 密集索引 ----
        var keyIdx = new HashMap<AEKey, Integer>();
        var keyList = new ArrayList<AEKey>();
        indexCounter(ledger.used, keyIdx, keyList);
        indexCounter(ledger.emitted, keyIdx, keyList);
        indexCounter(ledger.missing, keyIdx, keyList);
        intern(what, keyIdx, keyList);

        var pats = new ArrayList<LpPattern>();
        var runsArr = new ArrayList<Long>();
        for (var e : ledger.runs.object2LongEntrySet()) { // LinkedOpenHashMap：插入序 = 宣告序
            var lp = snap.compiled.get(e.getKey());
            if (lp == null || e.getLongValue() <= 0) {
                throw new LpFallbackException(FallbackReason.AUDIT_FAIL, "runs entry invalid"); // (c)
            }
            pats.add(lp);
            runsArr.add(e.getLongValue());
            for (var k : lp.inKey) {
                intern(k, keyIdx, keyList);
            }
            for (var k : lp.outKey) {
                intern(k, keyIdx, keyList);
            }
        }
        int nk = keyList.size();
        int np = pats.size();
        int whatIdx = keyIdx.get(what);

        long[] used = new long[nk];
        long[] emitted = new long[nk];
        long[] missing = new long[nk];
        fill(ledger.used, keyIdx, used);
        fill(ledger.emitted, keyIdx, emitted);
        fill(ledger.missing, keyIdx, missing);
        boolean sim = ledger.missing.size() != 0;

        boolean[] emitableKey = new boolean[nk];
        for (int i = 0; i < nk; i++) {
            emitableKey[i] = snap.emitable.contains(keyList.get(i));
        }

        int[][] inIdx = new int[np][];
        long[][] inAmt = new long[np][];
        int[][] outIdx = new int[np][];
        long[][] outAmt = new long[np][];
        long[] runs = new long[np];
        boolean[] parallel = new boolean[np];
        boolean[] cyclicPat = new boolean[np];
        for (int j = 0; j < np; j++) {
            var p = pats.get(j);
            runs[j] = runsArr.get(j);
            parallel[j] = p.parallelOrUnknown;
            cyclicPat[j] = g.isCyclicScc(g.sccOfPattern(p.details));
            inIdx[j] = new int[p.inKey.length];
            inAmt[j] = p.inAmt.clone();
            for (int s = 0; s < p.inKey.length; s++) {
                inIdx[j][s] = keyIdx.get(p.inKey[s]);
            }
            outIdx[j] = new int[p.outKey.length];
            outAmt[j] = p.outAmt.clone();
            for (int s = 0; s < p.outKey.length; s++) {
                outIdx[j][s] = keyIdx.get(p.outKey[s]);
            }
        }

        // ---- (a) 守恆：used+emitted+Σout·runs ≥ Σin·runs + 交付量 − missing ----
        long[] outSum = new long[nk];
        long[] inSum = new long[nk];
        for (int j = 0; j < np; j++) {
            for (int s = 0; s < inIdx[j].length; s++) {
                inSum[inIdx[j][s]] = LpMath.addX(inSum[inIdx[j][s]], LpMath.mulX(inAmt[j][s], runs[j]));
            }
            for (int s = 0; s < outIdx[j].length; s++) {
                outSum[outIdx[j][s]] = LpMath.addX(outSum[outIdx[j][s]], LpMath.mulX(outAmt[j][s], runs[j]));
            }
        }
        for (int i = 0; i < nk; i++) {
            long lhs = LpMath.addX(LpMath.addX(used[i], emitted[i]), outSum[i]);
            long rhs = LpMath.addX(inSum[i], i == whatIdx ? amountDelivered : 0) - missing[i];
            if (emitableKey[i]) {
                continue; // emitable 輸入視為恆足（外部發射器語意），不受守恆限制
            }
            if (lhs < rhs) {
                throw new LpFallbackException(FallbackReason.AUDIT_FAIL, "conservation " + keyList.get(i));
            }
        }
        // ---- (b) 上界：used ≤ availOriginal（否則 repairPlan 當幻影缺口加料）----
        for (var e : ledger.used) {
            if (e.getLongValue() > snap.availOriginal.get(e.getKey())) {
                throw new LpFallbackException(FallbackReason.AUDIT_FAIL, "used>avail " + e.getKey());
            }
        }
        // ---- (d) finalOutput 供給（先自查過關 ⇒ repairPlan deficits 掃描必然無事可做）----
        long supply = LpMath.addX(LpMath.addX(used[whatIdx], emitted[whatIdx]), outSum[whatIdx]);
        if (!sim && supply < amountDelivered) {
            throw new LpFallbackException(FallbackReason.AUDIT_FAIL, "finalOutput short");
        }

        if (np == 0) {
            return; // 純現貨/emitable 計畫：無任務可重放
        }

        // ---- (e) 波次重放：宣告序＋對抗序 ----
        int[] declared = new int[np];
        for (int j = 0; j < np; j++) {
            declared[j] = j;
        }
        // 對抗序：每波先跑非循環 SCC 樣板（SCC 外部消費者），再跑循環 SCC 樣板；組內宣告序。
        // 這是淨產型循環搶料死鎖的針對性反例序（hash 序先跑消費者吃掉啟動料）。
        var adv = new ArrayList<Integer>(np);
        for (int j = 0; j < np; j++) {
            if (!cyclicPat[j]) {
                adv.add(j);
            }
        }
        for (int j = 0; j < np; j++) {
            if (cyclicPat[j]) {
                adv.add(j);
            }
        }
        int[] adversarial = new int[np];
        for (int j = 0; j < np; j++) {
            adversarial[j] = adv.get(j);
        }

        replay(declared, nk, np, whatIdx, amountDelivered, used, missing, sim, emitted[whatIdx],
                emitableKey, inIdx, inAmt, outIdx, outAmt, runs, parallel, keyList, "declared", budget);
        replay(adversarial, nk, np, whatIdx, amountDelivered, used, missing, sim, emitted[whatIdx],
                emitableKey, inIdx, inAmt, outIdx, outAmt, runs, parallel, keyList, "adversarial", budget);
    }

    private static void replay(int[] order, int nk, int np, int whatIdx, long amountDelivered,
            long[] used, long[] missing, boolean sim, long emittedWhat,
            boolean[] emitableKey, int[][] inIdx, long[][] inAmt, int[][] outIdx, long[][] outAmt,
            long[] runsIn, boolean[] parallel, List<AEKey> keyList, String tag, LpBudget budget) {
        // [鐵則4] 起始庫存只灌 usedItems——絕不預灌任何折抵副產物
        long[] inv = used.clone();
        if (sim) {
            // 誠實 sim：missing 視為起始庫存追加（IgnoreMissing 下由 waitingFor 回流；只驗帳目自洽）
            for (int i = 0; i < nk; i++) {
                inv[i] = LpMath.addX(inv[i], missing[i]);
            }
        }
        long[] remaining = runsIn.clone();
        long producedWhat = 0;
        long startWhat = inv[whatIdx];
        long[] prevExec = null;
        long[] minInv = new long[nk]; // 每 key 本波軌跡最低水位（只在消耗點更新；產出只會抬高）
        for (int wave = 0; wave < LpConfig.maxWaves(); wave++) {
            budget.check("audit " + tag);
            long[] start = inv.clone();
            System.arraycopy(inv, 0, minInv, 0, nk);
            long[] exec = new long[np];
            long waveWhat = 0;
            boolean any = false;
            for (int oi = 0; oi < np; oi++) {
                int j = order[oi];
                if (remaining[j] <= 0) {
                    continue;
                }
                long can = remaining[j];
                for (int s = 0; s < inIdx[j].length && can > 0; s++) {
                    if (emitableKey[inIdx[j][s]]) {
                        continue; // emitable 輸入恆足
                    }
                    can = Math.min(can, inv[inIdx[j][s]] / inAmt[j][s]);
                }
                if (can <= 0) {
                    continue;
                }
                // [鐵則1] parallel==1 死角：並行(或偵測不確定)且剩餘>1 且只夠 1 輪 → 本波 0 輪
                if (parallel[j] && remaining[j] > 1 && can == 1) {
                    continue;
                }
                for (int s = 0; s < inIdx[j].length; s++) {
                    int ki = inIdx[j][s];
                    if (!emitableKey[ki]) {
                        inv[ki] -= LpMath.mulX(inAmt[j][s], can);
                        if (inv[ki] < minInv[ki]) {
                            minInv[ki] = inv[ki];
                        }
                    }
                }
                for (int s = 0; s < outIdx[j].length; s++) {
                    // 全部輸出（含副產物）回收進 CPU 庫存（執行器 expectedOutputs→waitingFor 語意）
                    inv[outIdx[j][s]] = LpMath.addX(inv[outIdx[j][s]], LpMath.mulX(outAmt[j][s], can));
                    if (outIdx[j][s] == whatIdx) {
                        waveWhat = LpMath.addX(waveWhat, LpMath.mulX(outAmt[j][s], can));
                    }
                }
                remaining[j] -= can;
                exec[j] = can;
                any = true;
            }
            producedWhat = LpMath.addX(producedWhat, waveWhat);
            boolean done = true;
            for (long v : remaining) {
                if (v > 0) {
                    done = false;
                    break;
                }
            }
            if (done) {
                // 完成判定：累計產出＋used 起始量（sim 含 missing 追加）≥ 交付量
                long total = LpMath.addX(LpMath.addX(startWhat, producedWhat), emittedWhat);
                if (total < amountDelivered) {
                    throw new LpFallbackException(FallbackReason.AUDIT_FAIL, tag + " delivered short");
                }
                return;
            }
            if (!any) {
                throw new LpFallbackException(FallbackReason.AUDIT_FAIL,
                        tag + " stuck wave=" + wave + " at " + firstStuck(remaining, keyList));
            }
            // 穩態快轉（取代舊「連續兩波庫存向量相等」外插證書——該證書 (a) 對任何有淨進度的計畫
            // 永不觸發：外部原料每波遞減使庫存向量不可能相等，64 波上限成為大量循環請求的硬牆；
            // (b) 缺「每個未完成任務都在推進」守衛，凍結任務可被外插放行）。改為：連續兩波 exec
            // 向量相同 → 本波軌跡可精確重複 ff 波（逐波平移論證：淨遞增 key 只會更寬裕；淨遞減
            // key 以本波最低水位除以每波淨減量兜底；剩餘輪數確保每個執行任務跳完仍 ≥0）。這是
            // 模擬壓縮而非證書跳躍——完成仍由 done、卡死仍由 !any／64 波裁決，凍結任務
            //（exec==0 且 remaining>0）不會被放行。
            if (prevExec != null && Arrays.equals(exec, prevExec)) {
                long ff = Long.MAX_VALUE;
                for (int j = 0; j < np && ff > 0; j++) {
                    if (exec[j] > 0) {
                        ff = Math.min(ff, remaining[j] / exec[j]);
                    }
                }
                for (int i = 0; i < nk && ff > 0; i++) {
                    long delta = inv[i] - start[i];
                    if (delta < 0) {
                        ff = Math.min(ff, minInv[i] / -delta);
                    }
                }
                if (ff > 0) {
                    for (int j = 0; j < np; j++) {
                        if (exec[j] > 0) {
                            remaining[j] -= LpMath.mulX(exec[j], ff);
                        }
                    }
                    for (int i = 0; i < nk; i++) {
                        inv[i] = LpMath.addX(inv[i], LpMath.mulX(inv[i] - start[i], ff));
                    }
                    producedWhat = LpMath.addX(producedWhat, LpMath.mulX(waveWhat, ff));
                    prevExec = null; // 快轉後任務組成可能改變 → 重新偵測穩態
                    continue;
                }
            }
            prevExec = exec;
        }
        throw new LpFallbackException(FallbackReason.AUDIT_FAIL, tag + " waves>64");
    }

    private static String firstStuck(long[] remaining, List<AEKey> keyList) {
        for (int j = 0; j < remaining.length; j++) {
            if (remaining[j] > 0) {
                return "task#" + j;
            }
        }
        return "?";
    }

    private static void indexCounter(appeng.api.stacks.KeyCounter c,
            Map<AEKey, Integer> idx, List<AEKey> list) {
        for (var e : c) {
            if (e.getLongValue() != 0) {
                intern(e.getKey(), idx, list);
            }
        }
    }

    private static void intern(AEKey k, Map<AEKey, Integer> idx, List<AEKey> list) {
        idx.computeIfAbsent(k, kk -> {
            list.add(kk);
            return list.size() - 1;
        });
    }

    private static void fill(appeng.api.stacks.KeyCounter c, Map<AEKey, Integer> idx, long[] arr) {
        for (var e : c) {
            var i = idx.get(e.getKey());
            if (i != null && e.getLongValue() > 0) {
                arr[i] = e.getLongValue();
            }
        }
    }
}
