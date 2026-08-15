package com.gtocraftfix.diag;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.crafting.CraftingPlan;
import appeng.crafting.execution.CraftingCpuLogic;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.me.service.CraftingService;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * [3.7.0 純診斷] 合成單帳本（ledger）：**每 tick** 對每顆有單的 CPU 記一次
 * {剩餘輪數、CPU 庫存、在途(waitingFor)、待交付}，用一條可證的不變量抓「料到底在哪一步不見的」：
 *
 * <pre>
 *   Δ庫存(k) + Δ在途(k) + Δ已交付(k=成品) == 產出(k) − 消耗(k) + 外部塞入(k)
 *       產出(k) = Σ 本 tick 推掉的樣板輪數 × 每輪產出
 *       消耗(k) = Σ 本 tick 推掉的樣板輪數 × 每輪輸入
 * </pre>
 *
 * 推送＝庫存↓、在途↑；交付＝在途↓、庫存↑（成品則在途↓、待交付↓）。因此**任何違反都代表帳外流動**
 * （多吃、被別顆 CPU 領走、產出沒掛帳、貨被抽走…），逐 key 累加成 {@code drift}，凍結時一併報出。
 *
 * <p>另含：新單/離場快照（開局在途＝提交當下的 waitingFor，對照 {@code usedItems} 就知道差額是「沒取到」
 * 還是「沒記帳」）、任務新增/消失、每 30 秒一覽、靜止 60 秒的**凍結全景報告**（逐任務全部輸入格
 * have/need＋網存＋在途＋誰產它、饑餓鏈根源、累計漂移、判定）。
 *
 * <p>全部唯讀，不改任何狀態。行數上限 {@code -Dgtodiag.diagLines}（預設 40000），
 * {@code -Dgtodiag.ledger=false} 整組關閉。
 */
public final class CraftDiag {

    private static final Logger LOG = LogManager.getLogger("gtocraftfix");

    private static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("gtodiag.ledger", "true"));
    private static final int LINE_BUDGET = Integer.getInteger("gtodiag.diagLines", 40000);
    private static final AtomicInteger SPENT = new AtomicInteger();

    /** 靜止多久算凍結（tick，20t=1s）。 */
    private static final int STALL_TICKS = Integer.getInteger("gtodiag.stallTicks", 1200);
    /** 凍結報告重播間隔。 */
    private static final int STALL_REPEAT = Integer.getInteger("gtodiag.stallRepeat", 6000);
    /** 一覽間隔。 */
    private static final int OVERVIEW_TICKS = Integer.getInteger("gtodiag.overviewTicks", 600);

    private CraftDiag() {
    }

    private static boolean spend() {
        return SPENT.incrementAndGet() <= LINE_BUDGET;
    }

    // ---------------------------------------------------------------- 狀態

    /** 每顆 CPU 的帳本快照（key 為 CraftingCpuLogic，弱引用）。 */
    private static final class Snap {

        Object job;
        String out = "?";
        AEKey outKey;
        int firstTick;
        int lastChangeTick;
        int stallNextTick;
        int lastAnomalyTick = -9999;
        int suppressed;
        long remaining = -1;
        long pushedRounds;
        long pushedAtOverview;
        final Map<IPatternDetails, Long> rounds = new HashMap<>();
        final Map<AEKey, Long> inv = new HashMap<>();
        final Map<AEKey, Long> wait = new HashMap<>();
        /** key → 該 key 的在途從 0 變正的 tick（＝樣板推出去的時刻）。 */
        final Map<AEKey, Integer> waitSince = new HashMap<>();
        /** 本 tick 由本 mod 自己塞進 CPU 的量（並行死角解鎖），記帳時要扣掉。 */
        final Map<AEKey, Long> ext = new HashMap<>();
        /** key → 累計帳外差額（正＝憑空多出、負＝憑空消失）。 */
        final Map<AEKey, Long> drift = new HashMap<>();
    }

    private static final Map<CraftingCpuLogic, Snap> STATE = new WeakHashMap<>();
    /** 成品 key → {上次提交 tick, 累計次數}：抓請求器反覆重下單。 */
    private static final Map<String, long[]> SUBMITS = new HashMap<>();
    /** submitJob 進入前已在跑單的 CPU（identityHashCode），用來在 RETURN 找出新上機的那顆。 */
    private static final Set<Integer> BUSY_BEFORE = new HashSet<>();

    // ---------------------------------------------------------------- 反射

    private static final Map<String, Field> FIELDS = new ConcurrentHashMap<>();
    private static final Field NONE;

    static {
        Field n = null;
        try {
            n = CraftDiag.class.getDeclaredField("STATE");
        } catch (NoSuchFieldException ignored) {
        }
        NONE = n;
    }

    private static Field field(Object o, String name) {
        if (o == null) {
            return null;
        }
        Field f = FIELDS.computeIfAbsent(o.getClass().getName() + '#' + name, k -> {
            for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    var fd = c.getDeclaredField(name);
                    fd.setAccessible(true);
                    return fd;
                } catch (NoSuchFieldException ignored) {
                }
            }
            return NONE;
        });
        return f == NONE ? null : f;
    }

    private static Object job(CraftingCpuLogic logic) {
        try {
            var f = field(logic, "job");
            return f == null ? null : f.get(logic);
        } catch (Throwable t) {
            return null;
        }
    }

    private static appeng.crafting.inv.ListCraftingInventory inventory(CraftingCpuLogic logic) {
        try {
            var f = field(logic, "inventory");
            return f == null ? null : (appeng.crafting.inv.ListCraftingInventory) f.get(logic);
        } catch (Throwable t) {
            return null;
        }
    }

    private static long remaining(Object job) {
        try {
            var f = field(job, "remainingAmount");
            return f == null ? -1 : f.getLong(job);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static Boolean paused(CraftingCpuLogic logic, Object job) {
        try {
            return (Boolean) logic.getClass().getMethod("isPaused").invoke(logic);
        } catch (Throwable ignored) {
        }
        try {
            var f = field(job, "paused");
            return f == null ? null : f.getBoolean(job);
        } catch (Throwable t) {
            return null;
        }
    }

    private static ICraftingLink link(Object job) {
        try {
            var f = field(job, "link");
            if (f != null && f.get(job) instanceof ICraftingLink l) {
                return l;
            }
            for (Class<?> c = job.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (var fd : c.getDeclaredFields()) {
                    fd.setAccessible(true);
                    Object v = fd.get(job);
                    if (v instanceof ICraftingLink l2) {
                        return l2;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** job.tasks → 樣板→剩餘輪數（含 0，才能分辨「做完」與「任務被移除」）。 */
    private static Map<IPatternDetails, Long> rounds(Object job) {
        var out = new HashMap<IPatternDetails, Long>();
        try {
            var ft = field(job, "tasks");
            if (ft == null) {
                return out;
            }
            Map<?, ?> tasks = (Map<?, ?>) ft.get(job);
            if (tasks == null) {
                return out;
            }
            for (var en : tasks.entrySet()) {
                Object holder = en.getValue();
                long v;
                try {
                    var fv = holder.getClass().getField("value");
                    fv.setAccessible(true);
                    v = fv.getLong(holder);
                } catch (Throwable t) {
                    var fv = field(holder, "value");
                    v = fv == null ? 0 : fv.getLong(holder);
                }
                out.put((IPatternDetails) en.getKey(), v);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static Map<AEKey, Long> invMap(CraftingCpuLogic logic) {
        var m = new HashMap<AEKey, Long>();
        var inv = inventory(logic);
        if (inv != null) {
            for (var e : inv.list) {
                if (e.getLongValue() != 0) {
                    m.put(e.getKey(), e.getLongValue());
                }
            }
        }
        return m;
    }

    private static Map<AEKey, Long> waitMap(CraftingCpuLogic logic) {
        var m = new HashMap<AEKey, Long>();
        try {
            Set<AEKey> ks = new HashSet<>();
            logic.getAllWaitingFor(ks);
            for (var k : ks) {
                long v = logic.getWaitingFor(k);
                if (v != 0) {
                    m.put(k, v);
                }
            }
        } catch (Throwable ignored) {
        }
        return m;
    }

    private static long sum(Map<?, Long> m) {
        long s = 0;
        for (var v : m.values()) {
            s += v;
        }
        return s;
    }

    private static String id(Object o) {
        return "#" + Integer.toHexString(System.identityHashCode(o));
    }

    private static String shortKey(AEKey k) {
        String s = String.valueOf(k);
        int i = s.indexOf(':');
        return i >= 0 && i + 1 < s.length() ? s.substring(i + 1) : s;
    }

    // ---------------------------------------------------------------- 對外：本 mod 的插入

    /** 並行死角解鎖等「本 mod 主動塞料」要登記，否則會被記成帳外流入。 */
    public static void noteExternalInsert(CraftingCpuLogic logic, AEKey key, long amount) {
        if (!ENABLED) {
            return;
        }
        var s = STATE.get(logic);
        if (s != null) {
            s.ext.merge(key, amount, Long::sum);
        }
    }

    // ---------------------------------------------------------------- 每 tick

    public static void tick(int tick, CraftingService svc, IGrid grid, Collection<CraftingCPUCluster> clusters) {
        if (!ENABLED) {
            return;
        }
        for (var c : clusters) {
            try {
                one(tick, svc, grid, clusters, c.craftingLogic);
            } catch (Throwable ignored) {
            }
        }
        if (OVERVIEW_TICKS > 0 && tick % OVERVIEW_TICKS == 0) {
            try {
                overview(tick, clusters);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void one(int tick, CraftingService svc, IGrid grid,
                            Collection<CraftingCPUCluster> clusters, CraftingCpuLogic logic) {
        Object job = job(logic);
        Snap prev = STATE.get(logic);

        // ---- 單離場：正常收單 vs 沒做完就消失（取消／提前收單）
        if (job == null) {
            if (prev != null) {
                STATE.remove(logic);
                long r = sum(prev.rounds);
                long w = sum(prev.wait);
                long iv = sum(prev.inv);
                if (spend()) {
                    LOG.info("[craftfix][帳本] 單離場 out={} CPU={} 存活{}s 推送{}輪 剩餘輪{} 在途{} 庫存{} 待交付{}{}",
                            prev.out, id(logic), (tick - prev.firstTick) / 20, prev.pushedRounds,
                            r, w, iv, prev.remaining,
                            (r > 0 || w > 0 || prev.remaining > 0)
                                    ? "  ← **沒做完就離場**（取消或提前收單）" : "");
                }
                if ((r > 0 || prev.remaining > 0) && !prev.drift.isEmpty() && spend()) {
                    LOG.warn("[craftfix][帳本] 離場時累計帳外差額 out={} → {}", prev.out, driftStr(prev));
                }
            }
            return;
        }

        var curRounds = rounds(job);
        var curInv = invMap(logic);
        var curWait = waitMap(logic);
        long curRemaining = remaining(job);

        // ---- 新單上機：開局庫存＋開局在途＝「提交當下取到什麼／掛了什麼在等」
        if (prev == null || prev.job != job) {
            var s = new Snap();
            s.job = job;
            var fo = logic.getFinalJobOutput();
            s.out = String.valueOf(fo);
            s.outKey = fo == null ? null : fo.what();
            s.firstTick = tick;
            s.lastChangeTick = tick;
            s.stallNextTick = tick + STALL_TICKS;
            s.remaining = curRemaining;
            s.rounds.putAll(curRounds);
            s.inv.putAll(curInv);
            s.wait.putAll(curWait);
            for (var k : curWait.keySet()) {
                s.waitSince.put(k, tick);
            }
            STATE.put(logic, s);
            long totalRounds = sum(curRounds);
            var lk = link(job);
            if (spend()) {
                LOG.info("[craftfix][帳本] 新單上機 out={} CPU={} 任務{}種/總輪{} 開局庫存{}種({}) 開局在途{}種({}) "
                                + "待交付{} link={}",
                        s.out, id(logic), curRounds.size(), totalRounds, curInv.size(), sum(curInv),
                        curWait.size(), sum(curWait), curRemaining,
                        lk == null ? "無" : (lk.isStandalone() ? "standalone(玩家)" : "requester(機器)"));
            }
            if (!curWait.isEmpty() && spend()) {
                LOG.info("[craftfix][帳本] 開局在途明細 out={} → {}", s.out, waitDetail(curWait, grid, null));
            }
            if (!curInv.isEmpty() && spend()) {
                LOG.info("[craftfix][帳本] 開局庫存明細 out={} → {}", s.out, briefMap(curInv, 12));
            }
            return;
        }

        // ---- 任務增減（計畫被改／任務被移除）
        boolean skipAudit = false;
        for (var e : prev.rounds.entrySet()) {
            if (!curRounds.containsKey(e.getKey()) && e.getValue() > 0) {
                skipAudit = true;
                if (spend()) {
                    LOG.warn("[craftfix][帳本] 任務消失 out={} 樣板產物={} 尚餘{}輪 ← 被移除（不是做完）",
                            prev.out, e.getKey().getPrimaryOutput().what(), e.getValue());
                }
            }
        }
        for (var e : curRounds.entrySet()) {
            if (!prev.rounds.containsKey(e.getKey())) {
                skipAudit = true;
                if (spend()) {
                    LOG.warn("[craftfix][帳本] 任務新增 out={} 樣板產物={} {}輪 ← 執行中被加進來",
                            prev.out, e.getKey().getPrimaryOutput().what(), e.getValue());
                }
            }
        }

        // ---- 本 tick 推掉的輪數 → 應消耗／應產出
        var consumed = new HashMap<AEKey, Long>();
        var produced = new HashMap<AEKey, Long>();
        long dRoundsTotal = 0;
        var pushedStr = new StringBuilder();
        for (var e : prev.rounds.entrySet()) {
            Long now = curRounds.get(e.getKey());
            if (now == null) {
                continue;
            }
            long d = e.getValue() - now;
            if (d <= 0) {
                if (d < 0 && spend()) {
                    LOG.warn("[craftfix][帳本] 輪數倒增 out={} 樣板產物={} {}→{}",
                            prev.out, e.getKey().getPrimaryOutput().what(), e.getValue(), now);
                }
                continue;
            }
            dRoundsTotal += d;
            var pat = e.getKey();
            pushedStr.append(shortKey(pat.getPrimaryOutput().what())).append('×').append(d).append(' ');
            for (var in : pat.getInputs()) {
                var ps = in.getPossibleInputs();
                if (ps.length == 0) {
                    continue;
                }
                // 替代輸入：認「庫存掉最多」的那個變體（AE2 一輪只吃一種）
                var pick = ps[0];
                long best = Long.MIN_VALUE;
                for (var v : ps) {
                    long drop = prev.inv.getOrDefault(v.what(), 0L) - curInv.getOrDefault(v.what(), 0L);
                    if (drop > best) {
                        best = drop;
                        pick = v;
                    }
                }
                long per = pick.amount() * in.getMultiplier();
                if (per > 0) {
                    consumed.merge(pick.what(), per * d, Long::sum);
                }
            }
            for (var o : pat.getOutputs()) {
                if (o.amount() > 0) {
                    produced.merge(o.what(), o.amount() * d, Long::sum);
                }
            }
        }
        prev.pushedRounds += dRoundsTotal;

        // ---- 不變量：Δ庫存 + Δ在途 + Δ已交付 == 產出 − 消耗 + 外部塞入
        long delivered = (prev.remaining >= 0 && curRemaining >= 0)
                ? Math.max(0, prev.remaining - curRemaining) : 0;
        if (!skipAudit) {
            var keys = new HashSet<AEKey>();
            keys.addAll(curInv.keySet());
            keys.addAll(prev.inv.keySet());
            keys.addAll(curWait.keySet());
            keys.addAll(prev.wait.keySet());
            keys.addAll(consumed.keySet());
            keys.addAll(produced.keySet());
            var bad = new StringBuilder();
            int nbad = 0;
            for (var k : keys) {
                long dInv = curInv.getOrDefault(k, 0L) - prev.inv.getOrDefault(k, 0L);
                long dWait = curWait.getOrDefault(k, 0L) - prev.wait.getOrDefault(k, 0L);
                long ext = prev.ext.getOrDefault(k, 0L);
                long expect = produced.getOrDefault(k, 0L) - consumed.getOrDefault(k, 0L) + ext;
                long got = dInv + dWait + (k.equals(prev.outKey) ? delivered : 0);
                long diff = got - expect;
                if (diff == 0) {
                    continue;
                }
                prev.drift.merge(k, diff, Long::sum);
                if (nbad++ < 4) {
                    bad.append(shortKey(k)).append(" 庫存Δ").append(dInv).append("/在途Δ").append(dWait);
                    if (k.equals(prev.outKey) && delivered != 0) {
                        bad.append("/交付").append(delivered);
                    }
                    if (ext != 0) {
                        bad.append("/自補").append(ext);
                    }
                    bad.append(" 應為").append(expect)
                            .append("(產").append(produced.getOrDefault(k, 0L))
                            .append("-吃").append(consumed.getOrDefault(k, 0L)).append(")")
                            .append(" **差").append(diff > 0 ? "+" : "").append(diff).append("**; ");
                }
            }
            if (bad.length() > 0) {
                if (tick - prev.lastAnomalyTick >= 20) {
                    prev.lastAnomalyTick = tick;
                    if (spend()) {
                        LOG.warn("[craftfix][帳本] 對不上 out={} 本tick推[{}] → {}{}",
                                prev.out, pushedStr.length() == 0 ? "無" : pushedStr.toString().trim(), bad,
                                prev.suppressed > 0 ? "（另抑制" + prev.suppressed + "筆）" : "");
                    }
                    prev.suppressed = 0;
                } else {
                    prev.suppressed++;
                }
            }
        }

        // ---- 靜止偵測 & 快照更新
        boolean changed = dRoundsTotal > 0 || delivered > 0
                || !curInv.equals(prev.inv) || !curWait.equals(prev.wait);
        if (changed) {
            prev.lastChangeTick = tick;
            prev.stallNextTick = tick + STALL_TICKS;
        } else if (tick >= prev.stallNextTick && !curRounds.isEmpty()) {
            prev.stallNextTick = tick + STALL_REPEAT;
            try {
                stallReport(tick, svc, grid, clusters, logic, prev, job, curRounds, curInv, curWait);
            } catch (Throwable ignored) {
            }
        }
        for (var k : curWait.keySet()) {
            prev.waitSince.putIfAbsent(k, tick);
        }
        prev.waitSince.keySet().retainAll(curWait.keySet());
        prev.rounds.clear();
        prev.rounds.putAll(curRounds);
        prev.inv.clear();
        prev.inv.putAll(curInv);
        prev.wait.clear();
        prev.wait.putAll(curWait);
        prev.ext.clear();
        prev.remaining = curRemaining;
    }

    // ---------------------------------------------------------------- 一覽（每 30 秒）

    private static void overview(int tick, Collection<CraftingCPUCluster> clusters) {
        var sb = new StringBuilder();
        int n = 0;
        for (var c : clusters) {
            var s = STATE.get(c.craftingLogic);
            if (s == null) {
                continue;
            }
            n++;
            long push = s.pushedRounds - s.pushedAtOverview;
            s.pushedAtOverview = s.pushedRounds;
            sb.append(s.out).append("[剩輪").append(sum(s.rounds))
                    .append("/在途").append(sum(s.wait))
                    .append("/庫存").append(sum(s.inv))
                    .append("/待交付").append(s.remaining)
                    .append("/近").append(OVERVIEW_TICKS / 20).append("s推").append(push).append("輪")
                    .append("/靜止").append((tick - s.lastChangeTick) / 20).append("s]; ");
        }
        if (n > 0 && spend()) {
            LOG.info("[craftfix][一覽] {}顆CPU有單 → {}", n, sb);
        }
    }

    // ---------------------------------------------------------------- 凍結全景

    private static void stallReport(int tick, CraftingService svc, IGrid grid,
                                    Collection<CraftingCPUCluster> clusters, CraftingCpuLogic logic,
                                    Snap s, Object job, Map<IPatternDetails, Long> curRounds,
                                    Map<AEKey, Long> curInv, Map<AEKey, Long> curWait) {
        int secs = (tick - s.lastChangeTick) / 20;
        var net = grid.getStorageService().getCachedInventory();
        Boolean paused = paused(logic, job);
        var lk = link(job);

        // 本單「誰產它」索引（剩餘輪數 > 0 的任務）
        var producers = new HashMap<AEKey, List<IPatternDetails>>();
        for (var e : curRounds.entrySet()) {
            if (e.getValue() <= 0) {
                continue;
            }
            for (var o : e.getKey().getOutputs()) {
                producers.computeIfAbsent(o.what(), k -> new ArrayList<>()).add(e.getKey());
            }
        }

        if (spend()) {
            LOG.warn("[craftfix][凍結] out={} CPU={} 靜止{}s 任務{}種/剩輪{} 在途{}種({}) 庫存{}種({}) "
                            + "待交付{} 已推{}輪 paused={} link={}",
                    s.out, id(logic), secs, curRounds.size(), sum(curRounds),
                    curWait.size(), sum(curWait), curInv.size(), sum(curInv), s.remaining, s.pushedRounds,
                    paused, lk == null ? "無" : (lk.isStandalone() ? "standalone" : "requester")
                            + (lk.isCanceled() ? "/已取消" : "") + (lk.isDone() ? "/已完成" : ""));
        }

        // ---- 逐任務：全部輸入格（不只最缺的一格）＋供應器忙碌＋網存＋在途＋誰產它
        var readyTasks = new ArrayList<String>();
        var noProvider = new ArrayList<String>();
        var allBusy = new ArrayList<String>();
        int shown = 0;
        for (var e : curRounds.entrySet()) {
            if (e.getValue() <= 0) {
                continue;
            }
            var pat = e.getKey();
            int prov = 0;
            int busy = 0;
            String at = null;
            try {
                for (var p : svc.getProviders(pat)) {
                    prov++;
                    boolean b = false;
                    try {
                        b = p.isBusy();
                    } catch (Throwable ignored) {
                    }
                    if (b) {
                        busy++;
                    }
                    if (at == null || (b && !at.endsWith("忙"))) {
                        String pa = posOf(p);
                        if (pa != null) {
                            at = b ? pa + "忙" : pa;
                        }
                    }
                    if (prov >= 12) {
                        break;
                    }
                }
            } catch (Throwable ignored) {
            }
            long runnable = runnableRounds(pat, curInv);
            var ib = new StringBuilder();
            for (var in : pat.getInputs()) {
                var ps = in.getPossibleInputs();
                if (ps.length == 0) {
                    continue;
                }
                var pick = ps[0];
                long bestHave = -1;
                for (var v : ps) {
                    long h = curInv.getOrDefault(v.what(), 0L);
                    if (h > bestHave) {
                        bestHave = h;
                        pick = v;
                    }
                }
                long per = pick.amount() * in.getMultiplier();
                long have = curInv.getOrDefault(pick.what(), 0L);
                ib.append(shortKey(pick.what())).append(' ').append(have).append('/').append(per);
                if (have < per) {
                    ib.append("[網").append(net.get(pick.what())).append(']');
                    long w = curWait.getOrDefault(pick.what(), 0L);
                    if (w > 0) {
                        ib.append("[在途").append(w).append(']');
                    }
                    var pl = producers.get(pick.what());
                    if (pl == null) {
                        ib.append("[本單無人產]");
                    } else {
                        long rr = 0;
                        for (var p : pl) {
                            rr += curRounds.getOrDefault(p, 0L);
                        }
                        ib.append("[本單剩").append(rr).append("輪產它]");
                    }
                    if (ps.length > 1) {
                        ib.append("[替代").append(ps.length).append("種]");
                    }
                }
                ib.append("; ");
            }
            String line = shortKey(pat.getPrimaryOutput().what()) + "×" + e.getValue()
                    + " 可跑" + runnable + "輪 prov:" + prov + " 忙:" + busy + (at == null ? "" : "@" + at);
            if (runnable >= 1 && prov > 0 && busy < prov) {
                readyTasks.add(line);
            }
            if (prov == 0) {
                noProvider.add(line);
            } else if (busy >= prov) {
                allBusy.add(line);
            }
            if (shown++ < 10 && spend()) {
                LOG.warn("[craftfix][凍結]   任務 {} 輸入: {}", line, ib);
            }
        }

        // ---- 在途明細
        if (!curWait.isEmpty() && spend()) {
            LOG.warn("[craftfix][凍結]   在途: {}", waitDetail(curWait, grid, ctx(tick, s, logic, clusters, producers)));
        }
        // ---- 庫存
        if (!curInv.isEmpty() && spend()) {
            LOG.warn("[craftfix][凍結]   庫存: {}", briefMap(curInv, 12));
        }
        // ---- 累計帳外差額
        if (!s.drift.isEmpty() && spend()) {
            LOG.warn("[craftfix][凍結]   累計帳外差額（正=憑空多、負=憑空少）: {}", driftStr(s));
        }
        // ---- 饑餓鏈根源
        var roots = starveRoots(curRounds, curInv, curWait, producers, net);
        if (!roots.isEmpty() && spend()) {
            LOG.warn("[craftfix][凍結]   饑餓鏈根源: {}", String.join("; ", roots));
        }
        // ---- 判定
        String verdict;
        if (Boolean.TRUE.equals(paused)) {
            verdict = "CPU 被暫停（paused）";
        } else if (!readyTasks.isEmpty()) {
            verdict = "**料齊卻不推**（疑上游 parallel==1 死角／供應器沉默）→ " + readyTasks.get(0);
        } else if (!noProvider.isEmpty()) {
            verdict = "**樣板失聯**（prov:0，供應器清單沒有這台機器）→ " + noProvider.get(0);
        } else if (!allBusy.isEmpty() && allBusy.size() == curRounds.size()) {
            verdict = "**供應器全忙**（executeCrafting 直接 continue，不留任何結果）";
        } else if (!roots.isEmpty()) {
            verdict = "**缺料鏈斷在根**：" + roots.get(0);
        } else if (!curWait.isEmpty()) {
            verdict = "**推出去沒回來**（在途有量但貨不回；看上面『推給』座標與『等了』秒數）";
        } else {
            verdict = "未分類（在途空、料不齊、無明顯死角）";
        }
        if (spend()) {
            LOG.warn("[craftfix][凍結]   判定: {}", verdict);
        }
    }

    /** 在途明細的附加脈絡（別顆 CPU 也在等／推給哪台／等了幾秒／本單無人產）。 */
    private static Object[] ctx(int tick, Snap s, CraftingCpuLogic logic,
                                Collection<CraftingCPUCluster> clusters,
                                Map<AEKey, List<IPatternDetails>> producers) {
        return new Object[] { tick, s, logic, clusters, producers };
    }

    private static String waitDetail(Map<AEKey, Long> wait, IGrid grid, Object[] ctx) {
        var net = grid.getStorageService().getCachedInventory();
        var sb = new StringBuilder();
        int n = 0;
        for (var e : wait.entrySet()) {
            if (n++ >= 10) {
                sb.append('…');
                break;
            }
            var k = e.getKey();
            sb.append(shortKey(k)).append("(等").append(e.getValue()).append("/網").append(net.get(k));
            if (ctx != null) {
                int tick = (Integer) ctx[0];
                var s = (Snap) ctx[1];
                var logic = (CraftingCpuLogic) ctx[2];
                @SuppressWarnings("unchecked")
                var clusters = (Collection<CraftingCPUCluster>) ctx[3];
                @SuppressWarnings("unchecked")
                var producers = (Map<AEKey, List<IPatternDetails>>) ctx[4];
                Integer since = s.waitSince.get(k);
                if (since != null) {
                    sb.append("/等了").append((tick - since) / 20).append('s');
                }
                int others = 0;
                for (var c2 : clusters) {
                    if (c2.craftingLogic != logic && c2.craftingLogic.getWaitingFor(k) > 0) {
                        others++;
                    }
                }
                if (others > 0) {
                    sb.append("/另").append(others).append("顆也等");
                }
                String pend = pendingAt(logic, k);
                if (pend != null) {
                    sb.append("/推給").append(pend);
                }
                if (!producers.containsKey(k)) {
                    sb.append("/**無任務產它**");
                }
            }
            sb.append(") ");
        }
        return sb.toString();
    }

    /**
     * 饑餓鏈根源：從所有「輸入不足一輪」的任務往上游追，追到「本單無人產」或「有人產且料齊」為止。
     * 前者＝計畫本身就缺（幻影缺口／被外部吃掉），後者＝執行器該動卻沒動。
     */
    private static List<String> starveRoots(Map<IPatternDetails, Long> rounds, Map<AEKey, Long> inv,
                                            Map<AEKey, Long> wait, Map<AEKey, List<IPatternDetails>> producers,
                                            appeng.api.stacks.KeyCounter net) {
        var out = new ArrayList<String>();
        var seen = new HashSet<AEKey>();
        var queue = new ArrayDeque<AEKey>();
        for (var e : rounds.entrySet()) {
            if (e.getValue() <= 0 || runnableRounds(e.getKey(), inv) >= 1) {
                continue;
            }
            for (var k : starvedInputs(e.getKey(), inv)) {
                queue.add(k);
            }
        }
        int guard = 0;
        while (!queue.isEmpty() && guard++ < 64 && out.size() < 6) {
            var k = queue.poll();
            if (!seen.add(k)) {
                continue;
            }
            var pl = producers.get(k);
            if (pl == null || pl.isEmpty()) {
                out.add(shortKey(k) + "：本單無人產、網存" + net.get(k) + "、在途" + wait.getOrDefault(k, 0L));
                continue;
            }
            boolean anyReady = false;
            for (var p : pl) {
                if (runnableRounds(p, inv) >= 1) {
                    anyReady = true;
                } else {
                    queue.addAll(starvedInputs(p, inv));
                }
            }
            if (anyReady) {
                out.add(shortKey(k) + "：有任務產它且料齊（執行器該動卻沒動）");
            }
        }
        return out;
    }

    private static List<AEKey> starvedInputs(IPatternDetails pat, Map<AEKey, Long> inv) {
        var out = new ArrayList<AEKey>();
        for (var in : pat.getInputs()) {
            var ps = in.getPossibleInputs();
            if (ps.length == 0) {
                continue;
            }
            var pick = ps[0];
            long bestHave = -1;
            boolean ok = false;
            for (var v : ps) {
                long per = v.amount() * in.getMultiplier();
                long have = inv.getOrDefault(v.what(), 0L);
                if (per > 0 && have >= per) {
                    ok = true;
                }
                if (have > bestHave) {
                    bestHave = have;
                    pick = v;
                }
            }
            if (!ok) {
                out.add(pick.what());
            }
        }
        return out;
    }

    private static long runnableRounds(IPatternDetails pat, Map<AEKey, Long> inv) {
        long min = Long.MAX_VALUE;
        for (var in : pat.getInputs()) {
            var ps = in.getPossibleInputs();
            if (ps.length == 0) {
                continue;
            }
            long best = 0;
            for (var v : ps) {
                long per = v.amount() * in.getMultiplier();
                if (per > 0) {
                    best = Math.max(best, inv.getOrDefault(v.what(), 0L) / per);
                }
            }
            min = Math.min(min, best);
        }
        return min == Long.MAX_VALUE ? 0 : min;
    }

    private static String pendingAt(Object logic, AEKey key) {
        try {
            Object r = logic.getClass().getMethod("getPendingRequests", AEKey.class).invoke(logic, key);
            if (!(r instanceof Collection<?> col) || col.isEmpty()) {
                return null;
            }
            var sb = new StringBuilder();
            int n = 0;
            for (var o : col) {
                if (n++ >= 2) {
                    sb.append('…');
                    break;
                }
                if (o instanceof net.minecraft.core.GlobalPos gp) {
                    var p = gp.pos();
                    sb.append(p.getX()).append(',').append(p.getY()).append(',').append(p.getZ()).append(' ');
                } else {
                    sb.append(o).append(' ');
                }
            }
            return sb.toString().trim();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String posOf(Object p) {
        try {
            net.minecraft.core.BlockPos bp = null;
            if (p instanceof net.minecraft.world.level.block.entity.BlockEntity be) {
                bp = be.getBlockPos();
            } else {
                for (var mn : new String[] { "getPos", "gto$getPos", "getBlockPos" }) {
                    try {
                        Object v = p.getClass().getMethod(mn).invoke(p);
                        if (v instanceof net.minecraft.core.BlockPos b1) {
                            bp = b1;
                        } else if (v instanceof net.minecraft.core.GlobalPos g1) {
                            bp = g1.pos();
                        }
                        if (bp != null) {
                            break;
                        }
                    } catch (NoSuchMethodException ignored) {
                    }
                }
            }
            String cn = p.getClass().getSimpleName();
            return bp == null ? cn : cn + "(" + bp.getX() + "," + bp.getY() + "," + bp.getZ() + ")";
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String briefMap(Map<AEKey, Long> m, int max) {
        var sb = new StringBuilder();
        int n = 0;
        for (var e : m.entrySet()) {
            if (n++ >= max) {
                sb.append('…');
                break;
            }
            sb.append(shortKey(e.getKey())).append('x').append(e.getValue()).append("; ");
        }
        return sb.toString();
    }

    private static String driftStr(Snap s) {
        var list = new ArrayList<>(s.drift.entrySet());
        list.sort((a, b) -> Long.compare(Math.abs(b.getValue()), Math.abs(a.getValue())));
        var sb = new StringBuilder();
        int n = 0;
        for (var e : list) {
            if (e.getValue() == 0) {
                continue;
            }
            if (n++ >= 6) {
                sb.append('…');
                break;
            }
            sb.append(shortKey(e.getKey())).append(' ').append(e.getValue() > 0 ? "+" : "")
                    .append(e.getValue()).append("; ");
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- 提交前後

    /** submitJob 進入時：印來源／計畫概要，並記下「誰已經在跑單」以便在 RETURN 找出新上機的 CPU。 */
    public static void onSubmit(int tick, appeng.api.networking.crafting.ICraftingPlan plan, IActionSource src,
                                ICraftingRequester requester, Collection<CraftingCPUCluster> clusters) {
        if (!ENABLED) {
            return;
        }
        try {
            BUSY_BEFORE.clear();
            for (var c : clusters) {
                if (c.craftingLogic.getFinalJobOutput() != null) {
                    BUSY_BEFORE.add(System.identityHashCode(c.craftingLogic));
                }
            }
            if (plan == null || plan.finalOutput() == null) {
                return;
            }
            String who = src.player().map(p -> "玩家" + p.getName().getString())
                    .orElseGet(() -> "機器" + (requester == null ? "?" : requester.getClass().getSimpleName()
                            + reqPos(requester)));
            long rounds = 0;
            for (var v : plan.patternTimes().values()) {
                rounds += v == null ? 0 : v;
            }
            String outStr = String.valueOf(plan.finalOutput());
            var rec = SUBMITS.computeIfAbsent(outStr, k -> new long[] { -100000, 0 });
            long gap = (tick - rec[0]) / 20;
            rec[0] = tick;
            rec[1]++;
            if (SUBMITS.size() > 256) {
                SUBMITS.clear();
            }
            if (spend()) {
                LOG.info("[craftfix][提交] out={} 來源={} sim={} bytes={} 任務{}種/總輪{} used={}種({}) "
                                + "missing={}種({}) emitted={}種({}) 第{}次(距上次{}s)",
                        outStr, who, plan.simulation(), plan.bytes(), plan.patternTimes().size(), rounds,
                        plan.usedItems().size(), kcSum(plan.usedItems()),
                        plan.missingItems().size(), kcSum(plan.missingItems()),
                        plan.emittedItems().size(), kcSum(plan.emittedItems()),
                        rec[1], gap > 100000 ? "首次" : String.valueOf(gap));
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * submitJob 返回後：找出新上機的 CPU，把 <b>計畫的 usedItems</b> 與 <b>CPU 實際吸到的庫存＋掛上的在途</b>
     * 逐項對帳——這是分辨「取料沒取到就把差額吞掉」與「取到了但後面被吃掉」的唯一切點。
     */
    public static void onSubmitted(int tick, appeng.api.networking.crafting.ICraftingPlan plan,
                                   ICraftingSubmitResult result, IGrid grid,
                                   Collection<CraftingCPUCluster> clusters) {
        if (!ENABLED || plan == null) {
            return;
        }
        try {
            if (result != null && !result.successful()) {
                if (spend()) {
                    LOG.warn("[craftfix][開單帳本] 提交失敗 out={} err={}", plan.finalOutput(), result.errorCode());
                }
                return;
            }
            CraftingCpuLogic target = null;
            for (var c : clusters) {
                var l = c.craftingLogic;
                if (l.getFinalJobOutput() != null && !BUSY_BEFORE.contains(System.identityHashCode(l))) {
                    target = l;
                    break;
                }
            }
            if (target == null) {
                if (spend()) {
                    LOG.warn("[craftfix][開單帳本] 提交後找不到新上機的 CPU out={}（可能沿用既有 CPU）",
                            plan.finalOutput());
                }
                return;
            }
            var inv = invMap(target);
            var wait = waitMap(target);
            var sb = new StringBuilder();
            long planSum = 0;
            long lostSum = 0;
            int n = 0;
            for (var e : plan.usedItems()) {
                long want = e.getLongValue();
                if (want <= 0) {
                    continue;
                }
                planSum += want;
                long got = inv.getOrDefault(e.getKey(), 0L);
                long w = wait.getOrDefault(e.getKey(), 0L);
                long diff = want - got - w;
                if (diff != 0) {
                    lostSum += diff;
                    if (n++ < 8) {
                        sb.append(shortKey(e.getKey())).append(" 計畫用").append(want)
                                .append("/實吸").append(got).append("/在途").append(w)
                                .append("(**").append(diff > 0 ? "少了" : "多了").append(Math.abs(diff))
                                .append("**); ");
                    }
                }
            }
            if (spend()) {
                LOG.info("[craftfix][開單帳本] out={} CPU={} 計畫usedΣ={} 實吸Σ={} 在途Σ={} missingΣ={}",
                        plan.finalOutput(), id(target), planSum, sum(inv), sum(wait),
                        kcSum(plan.missingItems()));
            }
            if (sb.length() > 0 && spend()) {
                LOG.warn("[craftfix][開單帳本] 開局對帳不符 out={} 淨差{} → {}", plan.finalOutput(), lostSum, sb);
            }
        } catch (Throwable ignored) {
        }
    }

    private static long kcSum(appeng.api.stacks.KeyCounter kc) {
        long s = 0;
        for (var e : kc) {
            s += e.getLongValue();
        }
        return s;
    }

    private static String reqPos(ICraftingRequester r) {
        String s = posOf(r);
        if (s == null) {
            return "";
        }
        int i = s.indexOf('(');
        return i >= 0 ? s.substring(i) : "";
    }

    /** 計畫出生時的完整內容（提交當下留底，之後任何漂移都能回頭比對）。 */
    public static void dumpPlan(CraftingPlan plan) {
        if (!ENABLED || plan == null) {
            return;
        }
        try {
            var sb = new StringBuilder();
            int n = 0;
            for (var e : plan.patternTimes().entrySet()) {
                if (e.getValue() == null || e.getValue() <= 0) {
                    continue;
                }
                if (n++ >= 16) {
                    sb.append('…');
                    break;
                }
                sb.append(shortKey(e.getKey().getPrimaryOutput().what())).append('×').append(e.getValue())
                        .append(' ');
            }
            if (spend()) {
                LOG.info("[craftfix][計畫] out={} 任務: {}", plan.finalOutput(), sb);
            }
            var ub = new StringBuilder();
            n = 0;
            for (var e : plan.usedItems()) {
                if (n++ >= 16) {
                    ub.append('…');
                    break;
                }
                ub.append(shortKey(e.getKey())).append('x').append(e.getLongValue()).append("; ");
            }
            if (ub.length() > 0 && spend()) {
                LOG.info("[craftfix][計畫] out={} used: {}", plan.finalOutput(), ub);
            }
            if (plan.missingItems().size() > 0 && spend()) {
                var mb = new StringBuilder();
                n = 0;
                for (var e : plan.missingItems()) {
                    if (n++ >= 12) {
                        mb.append('…');
                        break;
                    }
                    mb.append(shortKey(e.getKey())).append('x').append(e.getLongValue()).append("; ");
                }
                LOG.warn("[craftfix][計畫] out={} missing: {}", plan.finalOutput(), mb);
            }
        } catch (Throwable ignored) {
        }
    }
}
