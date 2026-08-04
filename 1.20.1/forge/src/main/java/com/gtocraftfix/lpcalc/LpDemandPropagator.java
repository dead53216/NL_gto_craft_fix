package com.gtocraftfix.lpcalc;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;

/**
 * 反拓撲批量傳播（§6.2）。成本只與樣板圖大小成正比，與請求數量無關。
 * 多趟語意（鐵則 7）：pass 2+ 只處理新入佇列的增量；used/avail/runs/flow 跨趟只增不減；
 * surplus 折抵扣過即不可重算。
 */
final class LpDemandPropagator {

    static final int ORIGIN_FINAL = 0;
    static final int ORIGIN_PATTERN = 1;
    static final int ORIGIN_BOOTSTRAP = 2;

    final LpCraftSnapshot snap;
    final LpGraph g;
    final LpLedger ledger = new LpLedger();
    /** 工作拷貝（母本 snap.avail 不動——CRAFT_LESS 候選要重跑） */
    final KeyCounter avail;
    /** 每 key 需求佇列：{amt, origin, originScc} */
    private final Map<AEKey, ArrayDeque<long[]>> queues = new HashMap<>();
    /** 折抵池明細：key → list of {amt, producerScc}（守衛要知道生產者） */
    private final Map<AEKey, List<long[]>> surplusPool = new HashMap<>();
    /** SCC 啟動料回灌待路由（§6.4 樣板選擇排除本 SCC）：{key, amt, homeScc} */
    private final List<Object[]> bootstrapPending = new ArrayList<>();
    /** 副產供給鏈 key（無自己樣板、僅靠閉包樣板副產出）的未滿足需求：本 pass 末重試折抵。 */
    private final List<Object[]> parkedByproduct = new ArrayList<>();
    /** 循環 SCC 的外部 pin 需求（§6.3 步驟 1）：sccId → (外部 key → 需求) */
    final Map<Integer, LinkedHashMap<AEKey, Long>> pinDemand = new HashMap<>();
    private final LpBudget budget;

    private LpDemandPropagator(LpCraftSnapshot snap, LpGraph g, LpBudget budget) {
        this.snap = snap;
        this.g = g;
        this.budget = budget;
        this.avail = LpCraftSnapshot.copyOf(snap.avail);
    }

    static LpLedger propagate(LpCraftSnapshot snap, LpGraph g, long amount, LpBudget budget)
            throws LpFallbackException {
        var p = new LpDemandPropagator(snap, g, budget);
        p.run(amount);
        return p.ledger;
    }

    private void run(long amount) {
        addDemand(snap.what(), amount, ORIGIN_FINAL, -1);
        for (int pass = 1; pass <= LpConfig.maxPasses(); pass++) { // [鐵則7] 上限 4 趟
            int drained = 0;
            for (int scc : g.topoOrder()) {
                if (g.isCyclicScc(scc)) {
                    budget.check("scc"); // 高斯/波次證書是求解期最重的單元
                    if (hasSccWork(scc)) {
                        LpCycleSolver.solve(this, scc);
                    }
                } else {
                    var k = g.keyOfSingletonScc(scc);
                    if (k != null) {
                        if ((++drained & 63) == 0) {
                            budget.check("drain");
                        }
                        drainKey(k);
                    }
                }
            }
            drainParkedByproduct();
            drainBootstrapPending();
            if (!anyPending()) {
                return;
            }
        }
        throw new LpFallbackException(FallbackReason.PASS_LIMIT, "4 passes"); // [鐵則7]
    }

    // ---- 需求入列 ----

    void addDemand(AEKey k, long amt, int origin, int originScc) {
        if (amt <= 0) {
            return;
        }
        if (origin == ORIGIN_BOOTSTRAP) {
            // 啟動料回灌不走 SCC 節點佇列：pass 尾端以 §6.4 規則路由（排除本 SCC 樣板）
            bootstrapPending.add(new Object[] { k, amt, originScc });
            return;
        }
        queues.computeIfAbsent(k, kk -> new ArrayDeque<>()).add(new long[] { amt, origin, originScc });
    }

    boolean queueNonEmpty(AEKey k) {
        var q = queues.get(k);
        return q != null && !q.isEmpty();
    }

    ArrayDeque<long[]> queueOf(AEKey k) {
        return queues.computeIfAbsent(k, kk -> new ArrayDeque<>());
    }

    private boolean hasSccWork(int scc) {
        var pins = pinDemand.get(scc);
        if (pins != null && !pins.isEmpty()) {
            return true;
        }
        for (var k : g.keysOfScc(scc)) {
            if (queueNonEmpty(k)) {
                return true;
            }
        }
        return false;
    }

    private boolean anyPending() {
        if (!bootstrapPending.isEmpty()) {
            return true;
        }
        for (var e : pinDemand.entrySet()) {
            if (!e.getValue().isEmpty()) {
                return true;
            }
        }
        for (var q : queues.values()) {
            if (!q.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    // ---- 單 key 佇列處理（非循環節點；順序比照 Node.request：折抵 → 庫存 → emit → 樣板 → 缺料）----

    private void drainKey(AEKey k) {
        var q = queues.get(k);
        if (q == null) {
            return;
        }
        while (!q.isEmpty()) {
            var e = q.poll();
            long d = e[0];
            int origin = (int) e[1];
            long d0 = d;

            // (1) surplus 折抵（僅非循環 SCC 來源；扣掉就沒了，單次計入 [鐵則7]；無環守衛 [鐵則3]）
            d = foldSurplus(k, d, g.sccOf(k));
            // (2) 庫存
            d = takeAvail(k, d);
            if (d > 0) {
                if (snap.emitable.contains(k)) {
                    // (3) emitable：外部發射器語意，全額放行
                    ledger.emitted.add(k, d);
                } else {
                    // (4) 樣板
                    var p = g.selectedPattern(k);
                    if (p == null) {
                        if (g.byproductSupplied(k)) {
                            // 副產供給 key 與其生產樣板的主產出在 Kahn 拓撲互無約束（平序）：
                            // 先被 drain 時 surplus 池可能尚空 → 掛起，本 pass 末（生產樣板都
                            // 排完後）重試折抵，仍不成才記 missing——否則覆蓋率取決於 tie-break
                            parkedByproduct.add(new Object[] { k, d });
                        } else {
                            ledger.missing.add(k, d);
                        }
                    } else {
                        int pscc = g.sccOfPattern(p.details);
                        if (g.isCyclicScc(pscc)) {
                            switch (origin) {
                                case ORIGIN_FINAL -> addPin(pscc, k, d); // 轉交 LpCycleSolver（§6.3 步驟 1）
                                case ORIGIN_PATTERN -> throw new LpFallbackException(
                                        FallbackReason.SCC_FEEDS_PATTERN, String.valueOf(k)); // [鐵則3]
                                default -> throw new LpFallbackException(
                                        FallbackReason.LOOP_NESTED, String.valueOf(k)); // [鐵則7] 環套環
                            }
                        } else {
                            schedule(p, k, d);
                        }
                    }
                }
            }
            ledger.addFlow(k, d0); // bytes 毛需求流量（只准偏大）
        }
    }

    /** 折抵池扣抵。回傳折抵後剩餘需求。 */
    long foldSurplus(AEKey k, long d, int consumerScc) {
        if (d <= 0) {
            return d;
        }
        var list = surplusPool.get(k);
        if (list == null) {
            return d;
        }
        for (var entry : list) {
            if (d <= 0) {
                break;
            }
            long amt = entry[0];
            if (amt <= 0) {
                continue;
            }
            int producerScc = (int) entry[1];
            // 守衛：加等待邊後縮點圖仍無環才可折抵；不過 → 不折抵、照常排產（超產安全）
            if (!g.canWait(consumerScc, producerScc)) {
                continue;
            }
            g.addWaitEdge(consumerScc, producerScc); // 等待邊跨全部折抵決策累積
            long take = Math.min(d, amt);
            entry[0] = amt - take;
            ledger.surplus.remove(k, take);
            d -= take;
        }
        return d;
    }

    /** 庫存扣抵 → used（[鐵則15] 毛量水位）。回傳剩餘需求。 */
    long takeAvail(AEKey k, long d) {
        if (d <= 0) {
            return d;
        }
        long a = avail.get(k);
        if (a > 0) {
            long take = Math.min(d, a);
            ledger.used.add(k, take);
            avail.remove(k, take);
            d -= take;
        }
        return d;
    }

    private void addPin(int sccId, AEKey k, long d) {
        pinDemand.computeIfAbsent(sccId, s -> new LinkedHashMap<>())
                .merge(k, d, LpMath::addX);
    }

    /** 非循環樣板排產：t = ceilDiv 一次除法（與數量無關）；輸入毛量下傳；產出入折抵池。 */
    void schedule(LpPattern p, AEKey k, long d) {
        if (p.ambiguousRedirect) {
            // 多候選重導向槽：只有真的要排 runs 才回退（§8 REDIRECT_AMBIGUOUS）
            throw new LpFallbackException(FallbackReason.REDIRECT_AMBIGUOUS, String.valueOf(k));
        }
        long outOf = p.outputOf(k);
        if (outOf <= 0) {
            // getCraftingFor 給的樣板卻不產 k（索引異常）——保守回退
            throw new LpFallbackException(FallbackReason.BAD_PATTERN, "no output of " + k);
        }
        long t = LpMath.ceilDiv(d, outOf);
        ledger.addRuns(p.details, t);
        for (int i = 0; i < p.inKey.length; i++) {
            addDemand(p.inKey[i], LpMath.mulX(p.inAmt[i], t), ORIGIN_PATTERN, -1);
        }
        // surplus 記帳：主產出超過 d 的餘數＋全部副產出（producer 標記供折抵守衛）
        int pscc = g.sccOfPattern(p.details);
        long toDeduct = d; // 對 k 的產出扣掉本筆需求，其餘進折抵池
        for (int i = 0; i < p.outKey.length; i++) {
            long produced = LpMath.mulX(p.outAmt[i], t);
            long extra;
            if (p.outKey[i].equals(k)) {
                long ded = Math.min(toDeduct, produced);
                toDeduct -= ded;
                extra = produced - ded;
            } else {
                extra = produced;
            }
            if (extra > 0) {
                addSurplus(p.outKey[i], extra, pscc);
            }
        }
    }

    /** 折抵池入帳。呼叫端保證非循環 SCC 生產者（循環 SCC 產物一律不入，鐵則 3）。 */
    private void addSurplus(AEKey k, long amt, int producerScc) {
        surplusPool.computeIfAbsent(k, kk -> new ArrayList<>()).add(new long[] { amt, producerScc });
        ledger.surplus.add(k, amt);
    }

    /** 掛起的副產供給需求重試（flow 已在掛起前入帳，此處不重計）。 */
    private void drainParkedByproduct() {
        if (parkedByproduct.isEmpty()) {
            return;
        }
        var pending = new ArrayList<>(parkedByproduct);
        parkedByproduct.clear();
        for (var e : pending) {
            var k = (AEKey) e[0];
            long rem = foldSurplus(k, (Long) e[1], g.sccOf(k));
            if (rem > 0) {
                ledger.missing.add(k, rem);
            }
        }
    }

    // ---- 啟動料回灌路由（§6.4）----

    private void drainBootstrapPending() {
        if (bootstrapPending.isEmpty()) {
            return;
        }
        var pending = new ArrayList<>(bootstrapPending);
        bootstrapPending.clear();
        for (var e : pending) {
            routeBootstrap((AEKey) e[0], (Long) e[1], (Integer) e[2]);
        }
    }

    private void routeBootstrap(AEKey k, long amt, int homeScc) {
        long d0 = amt;
        amt = foldSurplus(k, amt, g.sccOf(k));
        amt = takeAvail(k, amt);
        if (amt > 0) {
            if (snap.emitable.contains(k)) {
                ledger.emitted.add(k, amt); // 防禦：SCC 內 key 理論上不可 emitable
            } else {
                var candidates = snap.patternsByKey.get(k);
                if (candidates == null || candidates.isEmpty()) {
                    // [鐵則2] 啟動料缺口無外部解 → 不出 missing（repairPlan 會錯誤沖銷），直接回退
                    throw new LpFallbackException(FallbackReason.LOOP_NO_BOOTSTRAP, String.valueOf(k));
                }
                LpPattern chosen = null;
                boolean sawOtherCyclic = false;
                for (var cand : candidates) {
                    int cscc = g.sccOfPattern(cand);
                    if (!g.isCyclicScc(cscc)) {
                        chosen = snap.compiled.get(cand);
                        break;
                    }
                    if (cscc != homeScc) {
                        sawOtherCyclic = true;
                    }
                }
                if (chosen == null) {
                    // [鐵則7] 回灌鏈落入另一循環 SCC → LOOP_NESTED；只有本 SCC 自己 → LOOP_NO_BOOTSTRAP [鐵則2]
                    throw new LpFallbackException(
                            sawOtherCyclic ? FallbackReason.LOOP_NESTED : FallbackReason.LOOP_NO_BOOTSTRAP,
                            String.valueOf(k));
                }
                schedule(chosen, k, amt); // notRecursive 對啟動料的語意保留：由 SCC 外樣板生產
            }
        }
        ledger.addFlow(k, d0);
    }
}
