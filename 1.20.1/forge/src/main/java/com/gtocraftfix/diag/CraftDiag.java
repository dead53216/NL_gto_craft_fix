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

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
 *
 * <p>各修正的退路旗標（皆為預設值，設反即回舊行為）：{@code -Dgtodiag.linkVerdict=false}（離場不查 link，只印事實）、
 * {@code -Dgtodiag.strictAltInputs=true}（替代輸入格仍做嚴格守恆）、{@code -Dgtodiag.stateGc=false}（不主動清離場 CPU）、
 * {@code -Dgtodiag.gcIdleTicks}（帳本閒置回收門檻，預設 200 tick＝10 秒）、
 * {@code -Dgtodiag.alarmSkipPaused=false}（暫停中的 CPU 也發早期警報）、{@code -Dgtodiag.snapFinally=false}
 * （快照回寫不放 finally）、{@code -Dgtodiag.auditOnChange=false}（沒變動也照跑審計）、
 * {@code -Dgtodiag.submitCap}（提交紀錄 LRU 容量，預設 256）。
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

    /**
     * 離場判定採用開單時記下的 {@link ICraftingLink}：只認 isCanceled（取消）與 isDone（完成）兩條
     * 出口，兩條都沒走就是**異常離場**；{@code false} 則完全不查 link，只印離場事實不下判定。
     */
    private static final boolean LINK_VERDICT =
            !"false".equalsIgnoreCase(System.getProperty("gtodiag.linkVerdict", "true"));
    /** {@code true}＝對「替代輸入格」仍用舊的「庫存掉最多」歸戶做嚴格守恆（會產生假 drift／假對不上）。 */
    private static final boolean STRICT_ALT_INPUTS =
            "true".equalsIgnoreCase(System.getProperty("gtodiag.strictAltInputs", "false"));
    /** 每 tick 尾端主動清掉離場 CPU 的帳本（WeakHashMap 弱鍵被強引用鏈釘住的解法）。 */
    private static final boolean STATE_GC =
            !"false".equalsIgnoreCase(System.getProperty("gtodiag.stateGc", "true"));
    /** 帳本閒置多少伺服器 tick（20t=1s）沒被任何網路走訪就回收；預設 200 tick＝10 秒。 */
    private static final int GC_IDLE_TICKS = Integer.getInteger("gtodiag.gcIdleTicks", 200);
    /** 早期警報遇到 paused 的 CPU 直接跳過（暫停中本來就不推，不是 parallel==1 死角）。 */
    private static final boolean ALARM_SKIP_PAUSED =
            !"false".equalsIgnoreCase(System.getProperty("gtodiag.alarmSkipPaused", "true"));
    /** 快照回寫放 finally（例外時也保證 ext 被清空，不會下一 tick 二次計入）。 */
    private static final boolean SNAP_FINALLY =
            !"false".equalsIgnoreCase(System.getProperty("gtodiag.snapFinally", "true"));
    /** 這顆 CPU 本 tick 完全沒動就整段跳過不變量審計。 */
    private static final boolean AUDIT_ON_CHANGE =
            !"false".equalsIgnoreCase(System.getProperty("gtodiag.auditOnChange", "true"));
    /** 提交紀錄（SUBMITS）的 LRU 容量。 */
    private static final int SUBMIT_CAP = Integer.getInteger("gtodiag.submitCap", 256);

    /** 取樣例外的計數（只印前幾筆，避免洗版）。 */
    private static final AtomicInteger ONE_ERRORS = new AtomicInteger();

    /** 全域時鐘的快取值：取不到伺服器時停在原地不推進（寧可不回收，也不誤清活著的帳本）。 */
    private static volatile int GC_CLOCK;

    private CraftDiag() {
    }

    /**
     * 帳本回收專用的<b>全域</b>時鐘（伺服器 tick）。
     *
     * <p>不能用呼叫端傳進來的 tick：那是 mixin 的 {@code gtocraftfix$tickCounter}，掛在
     * CraftingService 上、是<b>非 static 的實例欄位</b>——多張網就有多個各自從 0 起算的計數器。
     * 拿甲網的 tick 去減乙網寫進帳本的值，會把「這一刻才剛被乙網走訪過」的活帳本算成過期清掉
     * （甲網成立得早、計數較大時必中）。
     *
     * <p>也不用牆鐘：伺服器暫停或卡頓時牆鐘照跑，會在最需要診斷的那幾秒把所有帳本清光。
     * {@code MinecraftServer#getTickCount()} 只隨真正的伺服器 tick 前進，兩邊都避開。
     */
    private static int nowTick() {
        try {
            var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server != null) {
                GC_CLOCK = server.getTickCount();
            }
        } catch (Throwable ignored) {
        }
        return GC_CLOCK;
    }

    /** 額度耗盡後先擋再遞增：不會白付計算成本，SPENT 也不會一路遞增到溢位讓額度「復活」。 */
    private static boolean spend() {
        if (SPENT.get() > LINE_BUDGET) {
            return false;
        }
        int n = SPENT.incrementAndGet();
        if (n == LINE_BUDGET + 1) {
            LOG.info("[craftfix] 診斷額度已用盡（-Dgtodiag.diagLines 可調），後續診斷行不再輸出");
            return false;
        }
        return n <= LINE_BUDGET;
    }

    // ---------------------------------------------------------------- 狀態

    /** 每顆 CPU 的帳本快照（key 為 CraftingCpuLogic，弱引用）。 */
    private static final class Snap {

        /**
         * 只用來判「還是不是同一張單」，一律弱引用：強引用會經
         * job → link → CraftingLink.cpu → CPUCluster.craftingLogic 繞回 STATE 的 key，
         * 讓 WeakHashMap 的弱鍵永遠不失效（拆 CPU／區塊卸載後整張圖滯留）。
         */
        WeakReference<Object> jobRef;
        /** 開單當下的 link（同樣弱引用）：離場時用來分辨正常完成／取消／異常離場。 */
        WeakReference<ICraftingLink> linkRef;
        /**
         * 最近一次被<b>任何</b>網路走訪到的全域伺服器 tick（見 {@link CraftDiag#nowTick()}）：回收只看這個。
         * 舊版用「建立當下的 gridId ＋ 本網現存 clusters」當清除範圍，網路分裂／合併換掉 IGrid 物件時，
         * 還在跑的單會被整個清掉 → 印出假的「新單上機」、drift/deliveredTotal 歸零，收單時再誤報一次。
         */
        int lastSeenTick;
        String out = "?";
        AEKey outKey;
        int firstTick;
        int lastChangeTick;
        int stallNextTick;
        int lastAnomalyTick = -9999;
        int suppressed;
        long remaining = -1;
        /**
         * 第一次取樣到的待交付（＝上機當下的 remainingAmount）：離場時和最後一次的 remaining 相減，
         * 就知道「這張單實際走了多少」，用來交叉檢查提前收單（−1 代表當時就讀不到，不可判定）。
         */
        long firstRemaining = -1;
        long pushedRounds;
        long pushedAtOverview;
        /** 下單總量（finalJobOutput 的 amount）與累計實際交付量：收單時一比就知道有沒有提前收單。 */
        long ordered;
        long deliveredTotal;
        long deliveredWindow;
        /** 樣板 → 「料齊到現在」的起始 tick；久齊不推＝執行器沉默（parallel==1 死角指紋）。 */
        final Map<IPatternDetails, Integer> readySince = new HashMap<>();
        final Set<IPatternDetails> silentLogged = new HashSet<>();
        final Set<AEKey> longWaitLogged = new HashSet<>();
        final Map<IPatternDetails, Long> rounds = new HashMap<>();
        final Map<AEKey, Long> inv = new HashMap<>();
        final Map<AEKey, Long> wait = new HashMap<>();
        /** key → 該 key 的在途從 0 變正的 tick（＝樣板推出去的時刻）。 */
        final Map<AEKey, Integer> waitSince = new HashMap<>();
        /** 本 tick 由本 mod 自己塞進 CPU 的量（並行死角解鎖），記帳時要扣掉。 */
        final Map<AEKey, Long> ext = new HashMap<>();
        /** key → 累計帳外差額（正＝憑空多出、負＝憑空消失）。 */
        final Map<AEKey, Long> drift = new HashMap<>();
        /**
         * 出現在「有替代輸入的輸入格」的所有變體：跨變體混扣無法歸戶，<b>只</b>豁免即時「對不上」告警。
         * drift 照常累加、報告時標「(含替代輸入,不可信)」——把它們整批從帳上抹掉，等於把「沒有 drift」
         * 變成不可驗證的空話。另：成品 key（{@link #outKey}）永遠不入列，招牌不變量不能對「被訂的東西」停擺。
         */
        final Set<AEKey> unauditable = new HashSet<>();
    }

    private static final Map<CraftingCpuLogic, Snap> STATE = new WeakHashMap<>();
    /**
     * 成品 key（<b>不含數量</b>）→ {上次提交 tick, 累計次數, 上次數量}：抓請求器反覆重下單。
     * 含數量的話每次量不同就變成新 key，「重下單」永遠顯示首次；容量以 LRU 控制（整表 clear 也會歸零）。
     */
    private static final Map<String, long[]> SUBMITS = new LinkedHashMap<String, long[]>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, long[]> eldest) {
            return size() > SUBMIT_CAP;
        }
    };
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
                long v = 0;
                try {
                    // 一律走 field() 的快取路徑：LongHolder.value 是 public，舊寫法每 entry 每 tick
                    // 都做一次 getField 查找（500 任務×30 CPU×20tps ≈ 每秒 30 萬次，全在主緒）
                    var fv = field(holder, "value");
                    if (fv != null) {
                        v = fv.getLong(holder);
                    }
                } catch (Throwable ignored) {
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
            } catch (Throwable t) {
                // 一顆出事不影響其他顆，但別再靜默：快照回寫已移到 finally，這裡只留痕跡（前 20 筆）
                if (ONE_ERRORS.incrementAndGet() <= 20 && spend()) {
                    LOG.warn("[craftfix][帳本] 取樣例外 CPU={} → {}（該顆本 tick 跳過）",
                            id(c.craftingLogic), t.toString());
                }
            }
        }
        // 主動清掉離場（拆 CPU／區塊卸載）的 CPU：Snap 的強引用鏈會讓 WeakHashMap 的弱鍵永不失效。
        // **不可**再用「建立當下的 gridId ＋ 本網現存 clusters」當範圍：網路分裂／合併會換掉 IGrid 物件，
        // 於是還在跑的單其 gridId 對上了、卻不在新網的 clusters 裡 → 帳本被整個清掉，下一 tick 印出
        // 假的「新單上機」、drift/deliveredTotal 歸零，真正收單時再誤報一次。
        // 改成純看閒置：只要還有任何一張網每 tick 走訪到這顆 CPU（見 one() 開頭刷新 lastSeenTick），
        // 就永遠不會被回收；真的沒人走訪超過 GC_IDLE_TICKS 才清，且清掉前留一行痕跡。
        if (STATE_GC && !STATE.isEmpty()) {
            try {
                int now = nowTick();
                var it = STATE.entrySet().iterator();
                while (it.hasNext()) {
                    var en = it.next();
                    var s = en.getValue();
                    int idle = now - s.lastSeenTick;
                    if (idle < 0) {
                        s.lastSeenTick = now; // 伺服器重啟／時鐘倒退：重新起算，不當成過期
                        continue;
                    }
                    if (idle <= GC_IDLE_TICKS) {
                        continue;
                    }
                    it.remove();
                    if (spend()) {
                        LOG.info("[craftfix][帳本] 帳本被回收 out={} CPU={} 已{}s 沒被任何網路走訪"
                                        + "（CPU 拆除／區塊卸載／網路停轉）推送{}輪 訂{}/交付{}",
                                s.out, id(en.getKey()), idle / 20, s.pushedRounds, s.ordered, s.deliveredTotal);
                    }
                }
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
        if (prev != null) {
            // 回收用的心跳：只要這顆 CPU 還被任何一張網走訪到就刷新，網路分裂／合併也不會誤清活帳本
            prev.lastSeenTick = nowTick();
        }

        // ---- 單離場：正常完成／取消／異常離場
        if (job == null) {
            if (prev != null) {
                STATE.remove(logic);
                jobGone(tick, logic, prev);
            }
            return;
        }

        var curRounds = rounds(job);
        var curInv = invMap(logic);
        var curWait = waitMap(logic);
        long curRemaining = remaining(job);

        // ---- 新單上機：開局庫存＋開局在途＝「提交當下取到什麼／掛了什麼在等」
        if (prev == null || prev.jobRef == null || prev.jobRef.get() != job) {
            var s = new Snap();
            s.jobRef = new WeakReference<>(job);
            s.lastSeenTick = nowTick();
            var lk = link(job);
            s.linkRef = lk == null ? null : new WeakReference<>(lk);
            var fo = logic.getFinalJobOutput();
            s.out = String.valueOf(fo);
            s.outKey = fo == null ? null : fo.what();
            s.ordered = fo == null ? 0 : fo.amount();
            // outKey 必須先設好再標不可審計：markUnauditable 會據此保住成品 key（見該方法註解）
            markUnauditable(s, curRounds);
            s.firstTick = tick;
            s.lastChangeTick = tick;
            s.stallNextTick = tick + STALL_TICKS;
            s.remaining = curRemaining;
            s.firstRemaining = curRemaining;
            s.rounds.putAll(curRounds);
            s.inv.putAll(curInv);
            s.wait.putAll(curWait);
            for (var k : curWait.keySet()) {
                s.waitSince.put(k, tick);
            }
            STATE.put(logic, s);
            long totalRounds = sum(curRounds);
            if (spend()) {
                LOG.info("[craftfix][帳本] 新單上機 out={} CPU={} 任務{}種/總輪{} 開局庫存{}種({}) 開局在途{}種({}) "
                                + "待交付{} 不可審計key{}{} link={}",
                        s.out, id(logic), curRounds.size(), totalRounds, curInv.size(), sum(curInv),
                        curWait.size(), sum(curWait), curRemaining, s.unauditable.size(), unauditableStr(s),
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

        // 以下全程包在 try/finally：中途任何例外都不能讓快照（尤其 prev.ext）殘留到下一 tick，
        // 否則自補量會被二次計入 expect，變成一筆「剛好等於自補量」的假 drift。
        try {
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
                    markUnauditable(prev, Map.of(e.getKey(), e.getValue()));
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
                    if (ps.length > 1 && !STRICT_ALT_INPUTS) {
                        // 有替代輸入的格子無法歸戶：①同 tick 上游交回同一 key 會讓「掉最多」變負而挑錯，
                        // ②gtocore 的 extractPatternInputs 會在同一格**跨變體混著扣**（A 不夠就續扣 B）。
                        // 整格所有變體列為不可審計（不記消耗、豁免即時告警），但 drift 照常累加。
                        for (var v : ps) {
                            addUnauditable(prev, v.what());
                        }
                        continue;
                    }
                    // 單一變體（或強制嚴格模式）：認「庫存掉最多」的那個變體
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

            long delivered = (prev.remaining >= 0 && curRemaining >= 0)
                    ? Math.max(0, prev.remaining - curRemaining) : 0;
            // 累計要**在審計之前**就記進去：審計區任何例外都會跳到 finally，放在後面等於這一 tick 的
            // 交付被永久吞掉（deliveredTotal 從此少算，離場對帳跟著錯）。
            prev.deliveredTotal += delivered;
            prev.deliveredWindow += delivered;

            // ---- 靜止偵測（先算 changed）：完全沒動就整段跳過審計，省下四張 map 聯集＋逐 key 差分
            boolean changed = dRoundsTotal > 0 || delivered > 0
                    || !curInv.equals(prev.inv) || !curWait.equals(prev.wait);

            // ---- 不變量：Δ庫存 + Δ在途 + Δ已交付 == 產出 − 消耗 + 外部塞入
            if (!skipAudit && (!AUDIT_ON_CHANGE || changed || !prev.ext.isEmpty())) {
                var keys = new HashSet<AEKey>();
                keys.addAll(curInv.keySet());
                keys.addAll(prev.inv.keySet());
                keys.addAll(curWait.keySet());
                keys.addAll(prev.wait.keySet());
                keys.addAll(consumed.keySet());
                keys.addAll(produced.keySet());
                StringBuilder bad = null;
                int nbad = 0;
                for (var k : keys) {
                    if (k.equals(prev.outKey) && (prev.remaining < 0 || curRemaining < 0)) {
                        continue; // 讀不到 remainingAmount 就無法把「交付出去」算進帳，成品這 key 跳過
                    }
                    long dInv = curInv.getOrDefault(k, 0L) - prev.inv.getOrDefault(k, 0L);
                    long dWait = curWait.getOrDefault(k, 0L) - prev.wait.getOrDefault(k, 0L);
                    long ext = prev.ext.getOrDefault(k, 0L);
                    long expect = produced.getOrDefault(k, 0L) - consumed.getOrDefault(k, 0L) + ext;
                    long got = dInv + dWait + (k.equals(prev.outKey) ? delivered : 0);
                    long diff = got - expect;
                    if (diff == 0) {
                        continue;
                    }
                    // 替代輸入群組也照樣累加 drift：跨變體混扣只讓數字「不可信」，不代表「沒發生」，
                    // 整批抹掉會讓「這張單沒有 drift」變成不可驗證的空話（報告時再標不可信）。
                    prev.drift.merge(k, diff, Long::sum);
                    if (prev.unauditable.contains(k)) {
                        continue; // 但即時「對不上」告警要豁免：這類 diff 每 tick 都會歪，會洗掉真訊號
                    }
                    if (nbad++ < 4) {
                        if (bad == null) {
                            bad = new StringBuilder();
                        }
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
                if (bad != null) {
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

            // ---- 交付進度回報（累計已在上面記過；「訂 N 交付 M」不能當提前收單證據，見 jobGone 說明）
            if (prev.deliveredWindow > 0 && tick % 100 == 0) {
                if (spend()) {
                    LOG.info("[craftfix][帳本] 交付 out={} 近5s+{} 累計{}/訂{} 待交付{}",
                            prev.out, prev.deliveredWindow, prev.deliveredTotal, prev.ordered, curRemaining);
                }
                prev.deliveredWindow = 0;
            }

            // ---- 早期警報（不必等 60 秒凍結）：①料齊卻 10 秒不推 ②在途 2 分鐘沒回
            if (!changed && tick % 20 == 0) {
                try {
                    earlyAlarms(tick, svc, grid, logic, prev, job, curRounds, curInv, curWait);
                } catch (Throwable ignored) {
                }
            } else if (changed) {
                prev.readySince.clear();
                prev.silentLogged.clear();
                prev.longWaitLogged.clear();
            }

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
        } finally {
            // [3.11.2／Y5] 回寫**一律**放 finally。交付量的累加（deliveredTotal）已上移到審計之前，
            // 若這裡還讓 SNAP_FINALLY=false 走「try 尾端才回寫」，審計丟例外時就會變成
            // 「交付已加、prev.remaining 沒推進」→ 下一 tick 用同一個舊基準再算一次相同的差
            // ＝整批重複計入（比舊順序的「少算一次、下一 tick 補回」更糟）。
            // SNAP_FINALLY 旗標保留只為紀錄這段推導，不再控制回寫時機。
            writeBack(tick, prev, curRounds, curInv, curWait, curRemaining);
        }
    }

    /** 快照回寫（含清空 ext）：一定要跑到，否則下一 tick 的差分會建立在過期基準上。 */
    private static void writeBack(int tick, Snap prev, Map<IPatternDetails, Long> curRounds,
                                  Map<AEKey, Long> curInv, Map<AEKey, Long> curWait, long curRemaining) {
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

    /**
     * 把「有替代輸入的輸入格」的所有變體登記為不可審計：gtocore 的 extractPatternInputs 會在同一格
     * 跨變體混著扣料，靠庫存變化回推歸戶必然出錯（假「對不上」洗版）。單一變體的輸入格不受影響。
     *
     * <p><b>排除面刻意收窄</b>（舊版一次把整張單所有樣板、含剩 0 輪不會再跑的都標掉，還可能把成品
     * key 本身排掉，等於招牌不變量對「被訂的東西」直接停擺）：
     * ①只看<b>剩餘輪數 &gt; 0</b> 的樣板——不會再跑的樣板不可能再混扣；
     * ②成品 key 由 {@link #addUnauditable} 擋下，永遠不入列；
     * ③被排除的只是「即時告警」，drift 照常累加（見審計迴圈）。
     */
    private static void markUnauditable(Snap s, Map<IPatternDetails, Long> pats) {
        if (STRICT_ALT_INPUTS) {
            return;
        }
        for (var e : pats.entrySet()) {
            Long times = e.getValue();
            if (times == null || times <= 0) {
                continue; // 剩 0 輪的樣板不會再推，沒有混扣風險，不該連累它的輸入
            }
            try {
                for (var in : e.getKey().getInputs()) {
                    var ps = in.getPossibleInputs();
                    if (ps.length <= 1) {
                        continue;
                    }
                    for (var v : ps) {
                        addUnauditable(s, v.what());
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** 唯一入列口：成品 key 一律擋下——招牌不變量最該盯的就是「被訂的東西」，不能被替代輸入牽連停擺。 */
    private static void addUnauditable(Snap s, AEKey k) {
        if (k == null || k.equals(s.outKey)) {
            return;
        }
        s.unauditable.add(k);
    }

    /** 被排除 key 的名稱（最多 8 個）：只印數量的話，「這張單沒有 drift」就變成無法驗證的空話。 */
    private static String unauditableStr(Snap s) {
        if (s.unauditable.isEmpty()) {
            return "";
        }
        var sb = new StringBuilder("[");
        int n = 0;
        for (var k : s.unauditable) {
            if (n++ >= 8) {
                sb.append('…');
                break;
            }
            if (n > 1) {
                sb.append(' ');
            }
            sb.append(shortKey(k));
        }
        return sb.append(']').toString();
    }

    /**
     * 單離場的性質判定。三個必須先記住的實證事實（反編譯 gtocore 26.7.4-alpha ＋ AE2 15.267.4）：
     *
     * <ol>
     *   <li><b>交付量永遠對不齊，不能拿來當證據。</b>AE2 的 {@code insert()} 會在 remainingAmount
     *       歸零的<b>同一次呼叫內</b>就 finishJob(true)、把 job 設成 null，而本模組每 tick 才取樣一次，
     *       永遠看不到最後一批交付 → {@code deliveredTotal} 恆小於 {@code ordered}。舊版拿
     *       {@code effDelivered = deliveredTotal + max(0, remaining)} 去補，但
     *       {@code ExecutingCraftingJob} 建構子的 remainingAmount ＝ finalOutput.amount() ＝ {@code ordered}，
     *       而每 tick 的 delivered ＝ max(0, prev.remaining − curRemaining)，望遠鏡加總後
     *       {@code deliveredTotal ≡ ordered − remaining} → {@code effDelivered ≡ ordered}，
     *       shortfall 恆 false（死碼）；反過來 remaining 讀不到（−1）時又會變成每單誤報「交付 0」。</li>
     *   <li><b>玩家單的 isDone() 恆 false。</b>{@code OptimizedCraftingCpuLogic.trySubmitJob} 以
     *       {@code generateLinkData(craftId, requester == null, false)} 建 link，玩家下單
     *       （requester==null）時 standalone=true；而 {@code craftingService.addLink(...)} 整段包在
     *       {@code if (requester != null)} 內，且 {@code CraftingService.addLink} 開頭就
     *       {@code if (link.isStandalone()) return;} → standalone link 沒有 nexus、markDone() 是 no-op。
     *       所以「!isDone()」對玩家單完全不代表「沒完成」，更不能反過來把它當成「已完成」的預設值
     *       ——舊版就是這樣把玩家單的異常離場印成「完成（無 link 可證，交付量吻合）」，訊息還跟事實
     *       相反（link 明明就在手上）。</li>
     *   <li><b>finishJob 只有 markDone 與 cancel 兩條出口。</b>兩條都沒走就代表 job 被繞過直接清掉
     *       ＝異常離場；只是 standalone 單無法再往下細分（見上一點），得靠待交付與帳外差額判讀。</li>
     * </ol>
     */
    private static void jobGone(int tick, CraftingCpuLogic logic, Snap prev) {
        long r = sum(prev.rounds);
        long w = sum(prev.wait);
        long iv = sum(prev.inv);
        ICraftingLink lk = prev.linkRef == null ? null : prev.linkRef.get();
        boolean canceled = false;
        boolean done = false;
        boolean standalone = false;
        if (LINK_VERDICT && lk != null) {
            try {
                canceled = lk.isCanceled();
                done = !canceled && lk.isDone();
                standalone = lk.isStandalone();
            } catch (Throwable ignored) {
            }
        }
        // link 還在手上、兩條出口卻都沒走 → 異常離場（三態不能塌成兩態：「讀不到 link」是另一回事）
        boolean unclosed = LINK_VERDICT && lk != null && !done && !canceled;
        // remaining 讀不到（反射失敗回 −1）＝ 什麼都不能斷言，只能標「無法判定」，不可報提前收單
        boolean pendUnknown = prev.remaining < 0;
        // 提前收單的證據＝「離場當下還有待交付」且「link 沒 markDone」；取消已另行報過，不重複點名。
        // [3.11.2／Y1,Y2] 必須排除兩種「不能斷言」的情形，否則又是恆真的死碼：
        //   ① standalone（玩家單）的 isDone() 恆 false（沒有 nexus、markDone 是 no-op），
        //      而取樣永遠看不到末批交付 → remaining 恆 >0 ⇒ 每張正常完成的玩家單都會被指名提前收單；
        //   ② lk == null（link 已被 GC）時 done/canceled 都讀不到，性質不明，同樣不能指控提前收單。
        boolean pendUnjudgeable = standalone || lk == null;
        boolean early = LINK_VERDICT && !pendUnknown && !pendUnjudgeable
                && prev.remaining > 0 && !done && !canceled;
        // [3.11.2／Y4] 交叉檢查要用**兩個獨立來源**：ordered 來自 finalJobOutput.amount()，
        // firstRemaining 來自第一次取樣的 remainingAmount。兩者不相等才是真訊號
        //（相等是常態）。舊版的「firstRemaining − remaining」與 deliveredTotal 是同一條代數恆等式，
        // 驗不到任何東西——那正是被打回的死碼寫法。
        String crossCheck = (prev.firstRemaining >= 0 && prev.firstRemaining != prev.ordered)
                ? ("⚠首次取樣待交付" + prev.firstRemaining + "≠訂單量" + prev.ordered) : "";

        String nature;
        if (canceled) {
            nature = "**取消**";
        } else if (done) {
            nature = "正常完成";
        } else if (!LINK_VERDICT) {
            nature = "離場（未查 link）";
        } else if (lk == null) {
            nature = "**性質不明：讀不到 link**";
        } else {
            nature = "**異常離場：link 未結案**";
        }
        if (spend()) {
            LOG.info("[craftfix][帳本] 單離場（{}） out={} CPU={} 存活{}s 推送{}輪 訂{}/累計交付{} "
                            + "待交付 首次{}→離場{} 剩餘輪{} 在途{} 庫存{} link={}",
                    nature, prev.out, id(logic), (tick - prev.firstTick) / 20, prev.pushedRounds,
                    prev.ordered, prev.deliveredTotal, prev.firstRemaining, prev.remaining, r, w, iv,
                    lk == null ? "無" : ((standalone ? "standalone(玩家)" : "requester(機器)")
                            + (canceled ? "/已取消" : "") + (done ? "/已完成" : "")
                            + (unclosed ? "/**未結案**" : "")));
        }
        if (canceled && spend()) {
            LOG.warn("[craftfix][帳本] **任務被取消**：out={} 訂{} 累計交付{} 離場時待交付{} 剩餘輪{}",
                    prev.out, prev.ordered, prev.deliveredTotal, prev.remaining, r);
        }
        if (unclosed && spend()) {
            LOG.warn("[craftfix][帳本] **異常離場**：out={} link={} 既沒 markDone 也沒 cancel — finishJob 只有"
                            + "這兩條出口，兩條都沒走代表 job 是被繞過直接清掉的{}",
                    prev.out, standalone ? "standalone(玩家單)" : "requester(機器單)",
                    standalone ? "；但 standalone link 沒有 nexus、markDone() 是 no-op、isDone() 恆 false，"
                            + "正常完成也會落在這一格，請配合下面的待交付與帳外差額判讀" : "");
        }
        if (pendUnknown && spend()) {
            LOG.warn("[craftfix][帳本] 待交付讀不到（remainingAmount=−1）out={} → 是否提前收單**無法判定**"
                            + "（累計交付{}／訂{} 僅供參考：末批交付本來就取樣不到）",
                    prev.out, prev.deliveredTotal, prev.ordered);
        } else if (!pendUnknown && pendUnjudgeable && prev.remaining > 0 && LINK_VERDICT && spend()) {
            // [3.11.2／Y1,Y2] 與 pendUnknown 同級的「無法判定」：不是指控，只是把事實擺出來
            LOG.warn("[craftfix][帳本] 是否提前收單**無法判定** out={} 訂{} 離場時待交付{}（{}）"
                            + " ← 待交付若只有約一輪產量，那是取樣看不到末批的正常收尾",
                    prev.out, prev.ordered, prev.remaining,
                    lk == null ? "link 已被回收，讀不到結案狀態"
                            : "standalone(玩家單)的 isDone() 恆 false，無法據以判定");
        } else if (early && spend()) {
            LOG.warn("[craftfix][帳本] **疑提前收單**：out={} 訂{} 離場時仍待交付{}（首次取樣{}、實走{}）"
                            + "且 link 未 markDone{} ← 待交付若只有約一輪產量，那是取樣看不到末批的正常收尾；"
                            + "遠大於一輪才是真的沒交完就離場",
                    prev.out, prev.ordered, prev.remaining, prev.firstRemaining, crossCheck,
                    "");
        }
        // 只要帳上有非零差額就印：本模組存在的理由就是回答「料在哪一步不見」，正常完成的單反而最需要
        // 這一行；舊版把它綁在「取消／提前收單／剩餘輪>0」之下，等於正常收單的單永遠看不到帳外差額。
        String ds = driftStr(prev);
        if (!ds.isEmpty() && spend()) {
            LOG.warn("[craftfix][帳本] 離場時累計帳外差額 out={} → {}", prev.out, ds);
        }
    }

    /**
     * 早期警報（每秒檢查一次，只在整顆 CPU 這 tick 沒動時跑）：
     * <ul>
     *   <li><b>料齊卻不推</b>：某樣板輸入夠 ≥1 輪、供應器有且不忙，卻連續 10 秒沒推——
     *       這就是上游 {@code parallel==1} 死角／供應器沉默的指紋，不必等 60 秒凍結；</li>
     *   <li><b>在途沒回</b>：某 key 掛在途 ≥2 分鐘、網路現貨 0——貨推出去就消失（含被別顆 CPU 領走）。</li>
     * </ul>
     */
    private static void earlyAlarms(int tick, CraftingService svc, IGrid grid, CraftingCpuLogic logic, Snap s,
                                    Object job, Map<IPatternDetails, Long> rounds, Map<AEKey, Long> inv,
                                    Map<AEKey, Long> wait) {
        // 暫停中的 CPU 本來就不會推（executeCrafting 開頭 `if (job == null || job.paused) return 0`），
        // 不是 parallel==1 死角；同一顆 CPU 的凍結報告也是查了 paused 才下判定，這裡不能不查。
        if (ALARM_SKIP_PAUSED && Boolean.TRUE.equals(paused(logic, job))) {
            // return 之前一定要把計時歸零：暫停期間 CPU 不動，若保留 readySince，解除暫停後第一次警報
            // 會把整段暫停時間算進「已齊 N 秒」，直接把「人為暫停」誤導成「執行器沉默／parallel==1 死角」。
            s.readySince.clear();
            s.silentLogged.clear();
            s.longWaitLogged.clear();
            return;
        }
        for (var e : rounds.entrySet()) {
            if (e.getValue() <= 0) {
                continue;
            }
            var pat = e.getKey();
            long runnable = runnableRounds(pat, inv);
            if (runnable < 1) {
                s.readySince.remove(pat);
                continue;
            }
            Integer since = s.readySince.putIfAbsent(pat, tick);
            if (since == null || tick - since < 200 || !s.silentLogged.add(pat)) {
                continue;
            }
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
            if (spend()) {
                LOG.warn("[craftfix][警報] 料齊卻不推 out={} 樣板產物={} 剩{}輪 可跑{}輪 已齊{}s "
                                + "prov:{} 忙:{}{} → {}",
                        s.out, shortKey(pat.getPrimaryOutput().what()), e.getValue(),
                        runnable, (tick - since) / 20, prov, busy,
                        at == null ? "" : "@" + at,
                        prov == 0 ? "**樣板失聯**" : (busy >= prov ? "供應器全忙（正常等機器）"
                                : "**執行器沉默：疑 parallel==1 死角**"));
            }
        }
        var net = grid.getStorageService().getCachedInventory();
        for (var e : wait.entrySet()) {
            var k = e.getKey();
            Integer since = s.waitSince.get(k);
            if (since == null || tick - since < 2400 || net.get(k) > 0 || !s.longWaitLogged.add(k)) {
                continue;
            }
            int others = 0;
            for (var c2 : STATE.keySet()) {
                if (c2 != logic && c2.getWaitingFor(k) > 0) {
                    others++;
                }
            }
            String pend = pendingAt(logic, k);
            if (spend()) {
                LOG.warn("[craftfix][警報] 在途沒回 out={} {} 等{} 已{}s 網存0{}{}",
                        s.out, shortKey(k), e.getValue(), (tick - since) / 20,
                        others > 0 ? "／另" + others + "顆也等" : "",
                        pend == null ? "" : "／推給" + pend);
            }
        }
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
                            + "待交付{} 已推{}輪 不可審計key{}{} paused={} link={}",
                    s.out, id(logic), secs, curRounds.size(), sum(curRounds),
                    curWait.size(), sum(curWait), curInv.size(), sum(curInv), s.remaining, s.pushedRounds,
                    s.unauditable.size(), unauditableStr(s),
                    paused, lk == null ? "無" : (lk.isStandalone() ? "standalone" : "requester")
                            + (lk.isCanceled() ? "/已取消" : "") + (lk.isDone() ? "/已完成" : ""));
        }

        // ---- 逐任務：全部輸入格（不只最缺的一格）＋供應器忙碌＋網存＋在途＋誰產它
        var readyTasks = new ArrayList<String>();
        var noProvider = new ArrayList<String>();
        var allBusy = new ArrayList<String>();
        int shown = 0;
        // rounds() 刻意保留 value==0 的任務（才能分辨「做完」與「被移除」），所以「全忙」不能拿
        // curRounds.size() 當分母，得另計還有剩餘輪數的任務數。
        int active = 0;
        for (var e : curRounds.entrySet()) {
            if (e.getValue() <= 0) {
                continue;
            }
            active++;
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
        } else if (!allBusy.isEmpty() && allBusy.size() == active && active > 0) {
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

    /**
     * [3.11.2／Y3] 帳外差額報告**分兩段**輸出：先印可信的（單一變體輸入，守恆結論成立），
     * 再印不可信的（含替代輸入）。
     *
     * <p>原因：有替代輸入的格子在審計時不寫 consumed，但庫存確實會掉 → 那個 key 每推一輪就累積
     * 一筆等於實吃量的 drift，一張 500 輪的鏈輕鬆累到數萬；而報告只取「絕對值前 6 大」，
     * 於是真正要抓的帳外流失（可能只有 −128）會被完全擠出名單——正常完成的單看得到差額了，
     * 卻只看得到不可信的那幾筆，等於白做。
     */
    private static String driftStr(Snap s) {
        var trusted = new ArrayList<Map.Entry<AEKey, Long>>();
        var untrusted = new ArrayList<Map.Entry<AEKey, Long>>();
        for (var e : s.drift.entrySet()) {
            if (e.getValue() == 0) {
                continue;
            }
            (s.unauditable.contains(e.getKey()) ? untrusted : trusted).add(e);
        }
        java.util.Comparator<Map.Entry<AEKey, Long>> byAbs =
                (a, b) -> Long.compare(Math.abs(b.getValue()), Math.abs(a.getValue()));
        trusted.sort(byAbs);
        untrusted.sort(byAbs);
        var sb = new StringBuilder();
        int n = 0;
        for (var e : trusted) {
            if (n++ >= 6) {
                sb.append("…（可信共").append(trusted.size()).append("筆）");
                break;
            }
            sb.append(shortKey(e.getKey())).append(' ').append(e.getValue() > 0 ? "+" : "")
                    .append(e.getValue()).append("; ");
        }
        if (!untrusted.isEmpty()) {
            if (sb.length() == 0) {
                sb.append("（可信帳目全平）");
            }
            sb.append(" ｜含替代輸入(不可信，跨變體混扣無法歸戶)").append(untrusted.size()).append("筆：");
            int m = 0;
            for (var e : untrusted) {
                if (m++ >= 2) {
                    sb.append('…');
                    break;
                }
                sb.append(shortKey(e.getKey())).append(' ').append(e.getValue() > 0 ? "+" : "")
                        .append(e.getValue()).append("; ");
            }
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
            // 計數用的 key 不能含數量（每次量不同就變新 key，「重下單」永遠顯示首次）；數量另記在 rec[2]
            String outId = String.valueOf(plan.finalOutput().what());
            long amount = plan.finalOutput().amount();
            var rec = SUBMITS.computeIfAbsent(outId, k -> new long[] { -100000, 0, 0 });
            long gap = (tick - rec[0]) / 20;
            long lastAmount = rec[2];
            rec[0] = tick;
            rec[1]++;
            rec[2] = amount;
            if (spend()) {
                LOG.info("[craftfix][提交] out={} 來源={} sim={} bytes={} 任務{}種/總輪{} used={}種({}) "
                                + "missing={}種({}) emitted={}種({}) 第{}次(距上次{}s{})",
                        outStr, who, plan.simulation(), plan.bytes(), plan.patternTimes().size(), rounds,
                        plan.usedItems().size(), kcSum(plan.usedItems()),
                        plan.missingItems().size(), kcSum(plan.missingItems()),
                        plan.emittedItems().size(), kcSum(plan.emittedItems()),
                        rec[1], rec[1] <= 1 ? "首次" : String.valueOf(gap),
                        (rec[1] > 1 && lastAmount != amount) ? "／上次量" + lastAmount : "");
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
                    // [3.16.0] 一併印 errorDetail：AE2 對 NO_SUITABLE_CPU_FOUND 會附上
                    // UnsuitableCpus[offline, busy, tooSmall, excluded]（對應 submitJob 選 CPU 的四道
                    // 過濾：isActive／!isBusy／getAvailableStorage>=bytes／canBeAutoSelectedFor）。
                    // 只印 errorCode 等於把「為什麼沒 CPU」這個答案丟掉。
                    LOG.warn("[craftfix][開單帳本] 提交失敗 out={} err={} detail={}",
                            plan.finalOutput(), result.errorCode(), result.errorDetail());
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
