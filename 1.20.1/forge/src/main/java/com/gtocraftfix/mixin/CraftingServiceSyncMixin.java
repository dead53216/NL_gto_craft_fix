package com.gtocraftfix.mixin;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.core.AEConfig;
import appeng.crafting.CraftingPlan;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.me.service.CraftingService;

import net.minecraftforge.common.util.FakePlayerFactory;


import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 修 GTO 環境「合成樹超過一步就無法正確自動合成」：
 * <ol>
 *   <li>算料同步化（beginCraftingCalculation）：GTO 單背景緒 async 算料多步時 Future 不返回 → 改伺服器執行緒同步執行。</li>
 *   <li>機器源 present-once（submitJob @Redirect）：GTO 計畫 usedItems 含執行期間才回流的中間產物，嚴格取料必失敗；
 *       IgnoreMissing 分支只對「有 player」的來源開放 → 包一層 player() 首呼回 present、其後 empty。</li>
 *   <li>保母（onServerEndTick 每秒）：GTO 認領只在插入事件觸發 → 孤兒 waitingFor 有貨直餵；不代下巢狀單（會塞爆 CPU）。</li>
 * </ol>
 */
@Mixin(value = CraftingService.class, priority = 1500, remap = false)
public abstract class CraftingServiceSyncMixin {

    private static final Logger LOG = LogManager.getLogger("gtocraftfix");

    @Shadow(remap = false)
    @Final
    private IGrid grid;

    @Shadow(remap = false)
    @Final
    private Set<CraftingCPUCluster> craftingCPUClusters;

    // [重檢12] 保母改帳後設 -1，強制下一 tick 重算 currentlyCrafting（同 tick 戳 postChange 會被跳過）
    @Shadow(remap = false)
    private long lastProcessedCraftingLogicChangeTick;

    private static volatile Method gtocraftfix$executeV2;
    private static volatile boolean gtocraftfix$resolved;
    // [重檢7] log 計數器分流：info／warn／例外各自獨立、5 分鐘窗重置（共用單一計數器時例外會被一般 log 搶光額度）
    private static final AtomicInteger gtocraftfix$logInfo = new AtomicInteger();
    private static final AtomicInteger gtocraftfix$logWarn = new AtomicInteger();
    private static final AtomicInteger gtocraftfix$logErr = new AtomicInteger();
    private static volatile long gtocraftfix$logWindow;
    // [重檢1] 補輸入輪數上限（系統屬性可調；沿用已公布的 gtodiag.lpcalc.* 前綴）
    private static final long gtocraftfix$TOPUP_ROUNDS_CAP =
            Math.max(1L, Long.getLong("gtodiag.lpcalc.topUpRoundsCap", 8192L));
    private int gtocraftfix$tickCounter;
    private Set<AEKey> gtocraftfix$noPatternLogged = new HashSet<>();
    /** [重檢14] 成品被 link 拒收的記憶，per-cluster（弱鍵 → key → 拒收 tick）；10 分鐘內不再試。 */
    private Map<CraftingCPUCluster, HashMap<AEKey, Integer>> gtocraftfix$feedRefused = new WeakHashMap<>();
    /** 內置原版算料器的背景執行緒池（daemon）。 */
    private static final java.util.concurrent.ExecutorService gtocraftfix$CALC_POOL =
            java.util.concurrent.Executors.newFixedThreadPool(2, r -> {
                var t = new Thread(r, "gtocraftfix-calc");
                t.setDaemon(true);
                return t;
            });
    private static volatile java.lang.reflect.Field gtocraftfix$fJob;
    private static volatile java.lang.reflect.Field gtocraftfix$fTasks;
    private static volatile java.lang.reflect.Field gtocraftfix$fInv;
    private static volatile java.lang.reflect.Field gtocraftfix$fHolderVal;
    private static volatile java.lang.reflect.Field gtocraftfix$fWaitingFor;
    private static volatile java.lang.reflect.Field gtocraftfix$fIsOrder;
    private static volatile boolean gtocraftfix$fIsOrderTried;
    private static volatile java.lang.reflect.Field gtocraftfix$fAlloc;
    private static volatile boolean gtocraftfix$fAllocTried;
    private static volatile boolean gtocraftfix$fAllocMissing;
    private static volatile Method gtocraftfix$mPendReq;
    private static volatile boolean gtocraftfix$mPendReqTried;
    /** [重檢14] 陳舊等待偵測：cluster（弱鍵）→ key → 首見 tick。 */
    private Map<CraftingCPUCluster, HashMap<AEKey, Integer>> gtocraftfix$staleWait = new WeakHashMap<>();
    /** [重檢16] 孤兒觀測計時：cluster（弱鍵）→ 樣板 → 首見「無供應器」tick；持續 60 秒才換綁（防暫態 unmount 誤觸發）。 */
    private Map<CraftingCPUCluster, HashMap<IPatternDetails, Integer>> gtocraftfix$orphanSince = new WeakHashMap<>();
    /** [重檢3] 已銷帳但物理留在 CPU 庫存的成品量（job 弱鍵）；selfClaimFinal 不得對這部分再燒帳。 */
    private Map<Object, long[]> gtocraftfix$claimedFinalHeld = new WeakHashMap<>();
    /** [重檢14] 成品自我認領計時：cluster（弱鍵）→ 首見滯留 tick（與陳舊等待分表，互不誤清）。 */
    private Map<CraftingCPUCluster, Integer> gtocraftfix$finalClaimTick = new WeakHashMap<>();
    private Set<String> gtocraftfix$failLogged = new HashSet<>();
    /** [v1.2.0] 完單法醫：cluster（弱鍵）→ {job 弱參照, out 描述, 交付帳剩, 任務剩輪, …}；job 消失當下印完單快照。 */
    private Map<CraftingCPUCluster, Object[]> gtocraftfix$jobTrack = new WeakHashMap<>();
    private static volatile java.lang.reflect.Field gtocraftfix$fRemaining;
    private static volatile boolean gtocraftfix$fRemainingTried;
    /** [v1.2.1] 配額死鎖計時：cluster（弱鍵）→ {job 弱參照, 首見 tick, 當時總剩輪}；[重檢18] 含 job 身分防跨 job 殘留計時。 */
    private Map<CraftingCPUCluster, Object[]> gtocraftfix$quotaStuck = new WeakHashMap<>();
    /** [重檢18] 訂單提前收單廣播冷卻：cluster（弱鍵）→ 上次廣播 tick（10 分鐘內不重發）。 */
    private Map<CraftingCPUCluster, Integer> gtocraftfix$orderNoticeTick = new WeakHashMap<>();
    /** [v1.4.0] 跑單斷料廣播：cluster（弱鍵）→ {job 弱參照, 「任務主產物|缺料」→ int[]{首見, 上次廣播}}；
     *  鍵含任務主產物，防別的任務把同 key 計時清零。 */
    private Map<CraftingCPUCluster, Object[]> gtocraftfix$starveNotice = new WeakHashMap<>();
    // [v1.8.2] 同文去重：兩顆 CPU 跑同產物會同秒連發相同訊息
    private String gtocraftfix$lastStarveTxt;
    private int gtocraftfix$lastStarveTxtTick = Integer.MIN_VALUE / 2;
    /** [v1.4.0] 玩家定向訊息節流：uuid|產物|錯誤碼 → 上次發送 ms（3 秒窗防連點；伺服器執行緒單寫）。 */
    private static Map<String, Long> gtocraftfix$playerNoticeMs = new HashMap<>();
    /** [v1.6.1] 完單短交監看：{AEKey out, 應到未到, 已到帳, 上次存量, 起始 tick}；正差分累計到貨、蓋過應到即結案。 */
    private java.util.List<Object[]> gtocraftfix$shortWatch = new java.util.ArrayList<>();
    /** [v1.6.1] 玩家單 link 標記（弱鍵）：短交監看只看玩家單——機器源有水位制自我修復，代補反而重複生產。 */
    private Map<ICraftingLink, Boolean> gtocraftfix$playerLinks = new WeakHashMap<>();

    /** [重檢17] mixin 實例欄位初始化不保證執行；狀態表一律先過這裡懶初始化。只在伺服器執行緒呼叫。 */
    private void gtocraftfix$ensureState() {
        if (gtocraftfix$noPatternLogged == null) {
            gtocraftfix$noPatternLogged = new HashSet<>();
        }
        if (gtocraftfix$feedRefused == null) {
            gtocraftfix$feedRefused = new WeakHashMap<>();
        }
        if (gtocraftfix$staleWait == null) {
            gtocraftfix$staleWait = new WeakHashMap<>();
        }
        if (gtocraftfix$orphanSince == null) {
            gtocraftfix$orphanSince = new WeakHashMap<>();
        }
        if (gtocraftfix$claimedFinalHeld == null) {
            gtocraftfix$claimedFinalHeld = new WeakHashMap<>();
        }
        if (gtocraftfix$finalClaimTick == null) {
            gtocraftfix$finalClaimTick = new WeakHashMap<>();
        }
        if (gtocraftfix$failLogged == null) {
            gtocraftfix$failLogged = new HashSet<>();
        }
        if (gtocraftfix$staleHeld == null) {
            gtocraftfix$staleHeld = new WeakHashMap<>();
        }
        if (gtocraftfix$censusDone == null) {
            gtocraftfix$censusDone = java.util.Collections.newSetFromMap(new WeakHashMap<>());
        }
        if (gtocraftfix$jobTrack == null) {
            gtocraftfix$jobTrack = new WeakHashMap<>();
        }
        if (gtocraftfix$quotaStuck == null) {
            gtocraftfix$quotaStuck = new WeakHashMap<>();
        }
        if (gtocraftfix$orderNoticeTick == null) {
            gtocraftfix$orderNoticeTick = new WeakHashMap<>();
        }
        if (gtocraftfix$starveNotice == null) {
            gtocraftfix$starveNotice = new WeakHashMap<>();
        }
        if (gtocraftfix$playerNoticeMs == null) {
            gtocraftfix$playerNoticeMs = new HashMap<>();
        }
        if (gtocraftfix$shortWatch == null) {
            gtocraftfix$shortWatch = new java.util.ArrayList<>();
        }
        if (gtocraftfix$playerLinks == null) {
            gtocraftfix$playerLinks = new WeakHashMap<>();
        }
    }

    // ---- 修正 1：算料同步化（修好終端 ctrl+左鍵多步卡死）----
    @Inject(method = "beginCraftingCalculation", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtocraftfix$syncCalc(Level level, ICraftingSimulationRequester simRequester, AEKey what, long amount,
                                      CalculationStrategy strategy, CallbackInfoReturnable<Future<ICraftingPlan>> cir) {
        gtocraftfix$ensureState(); // [重檢17]
        if (!gtocraftfix$resolved) {
            synchronized (CraftingServiceSyncMixin.class) {
                if (!gtocraftfix$resolved) {
                    gtocraftfix$executeV2 = gtocraftfix$resolve();
                    gtocraftfix$resolved = true;
                    if (gtocraftfix$executeV2 == null) {
                        LOG.warn("[craftfix] OptimizedCalculation.executeV2 找不到 → 不接管算料，退回原本 async。");
                    } else {
                        String ver;
                        try {
                            ver = net.minecraftforge.fml.ModList.get().getModContainerById("gto_craft_fix")
                                    .map(c -> c.getModInfo().getVersion().toString()).orElse("?");
                        } catch (Throwable t) {
                            ver = "?";
                        }
                        LOG.info("[craftfix] 已啟用 v{}：同步算料＋機器源 IgnoreMissing＋保母（1秒/基線防偽/paused排除）＋配額解鎖＋link判死寬限＋斷料整併點名（不自動補單）＋完單短交通知＋探針PushResult人話化；lpcalc={}。",
                                ver, com.gtocraftfix.lpcalc.LpConfig.enabled() ? "on" : "off");
                    }
                }
            }
        }

        Method m = gtocraftfix$executeV2;
        if (m == null || level == null || simRequester == null) {
            return; // 不接管 → 走原本 async
        }
        try {
            // 無樣板守衛：GTO executeV2 會對無樣板物品硬生「從網路拿 N 顆」的退化計畫；
            // 機器源查無樣板 → 直接回誠實 sim 計畫（缺 N、零任務），不進 executeV2。
            var actionSrc0 = simRequester.getActionSource();
            boolean machineSrc0 = actionSrc0 == null || actionSrc0.player().isEmpty();
            if (machineSrc0 && ((ICraftingService) (Object) this).getCraftingFor(what).isEmpty()) {
                if (gtocraftfix$noPatternLogged.add(what)) {
                    LOG.warn("[craftfix] 無樣板，擋下機器源請求：{} x{}（原版語意：不可合成）", what, amount);
                    if (gtocraftfix$noPatternLogged.size() > 128) {
                        gtocraftfix$noPatternLogged.clear();
                    }
                    // 同步在聊天室提示玩家（同 key 只提示一次）
                    if (level instanceof ServerLevel sl) {
                        sl.getServer().getPlayerList().broadcastSystemMessage(
                                net.minecraft.network.chat.Component.literal("[合成修復] 無樣板，已擋下自動合成請求：")
                                        .append(what.getDisplayName())
                                        .append(net.minecraft.network.chat.Component.literal(" x" + amount)),
                                false);
                    }
                }
                final AEKey fWhat = what;
                final long fAmount = amount;
                cir.setReturnValue(CompletableFuture.completedFuture(new ICraftingPlan() {

                    @Override
                    public appeng.api.stacks.GenericStack finalOutput() {
                        return new appeng.api.stacks.GenericStack(fWhat, fAmount);
                    }

                    @Override
                    public long bytes() {
                        return 0;
                    }

                    @Override
                    public boolean simulation() {
                        return true;
                    }

                    @Override
                    public boolean multiplePaths() {
                        return false;
                    }

                    @Override
                    public appeng.api.stacks.KeyCounter usedItems() {
                        return new appeng.api.stacks.KeyCounter();
                    }

                    @Override
                    public appeng.api.stacks.KeyCounter emittedItems() {
                        return new appeng.api.stacks.KeyCounter();
                    }

                    @Override
                    public appeng.api.stacks.KeyCounter missingItems() {
                        var kc = new appeng.api.stacks.KeyCounter();
                        kc.add(fWhat, fAmount);
                        return kc;
                    }

                    @Override
                    public java.util.Map<IPatternDetails, Long> patternTimes() {
                        return java.util.Map.of();
                    }
                }));
                return;
            }
            // 機器源走 lpcalc 算料器（失敗由 LpEntry 內部回退樹狀版；-Dgtodiag.lpcalc.enabled=false 停用）。
            // LpEntry 不外拋；外層 catch（不 setReturnValue → 退 GTO async）當最後防線。玩家維持 GTO executeV2。
            if (machineSrc0) {
                cir.setReturnValue(com.gtocraftfix.lpcalc.LpEntry.beginMachineCalc(
                        level, grid, (ICraftingService) (Object) this, simRequester,
                        what, amount, strategy, gtocraftfix$CALC_POOL));
                return;
            }
            var inventory = grid.getStorageService().getCachedInventory().copy();
            var plan = (ICraftingPlan) m.invoke(null, grid, inventory, simRequester, what, amount, strategy);

            // 機器源降量重算：executeV2 的 CRAFT_LESS 對大量會直接回 amount=0＋sim（而非「最多可做的量」）
            // → 砍半重算直到可執行，追蹤器下輪自然補餘量。玩家不降（要看缺料畫面）。
            var actionSrc = simRequester.getActionSource();
            boolean machineSrc = actionSrc == null || actionSrc.player().isEmpty();
            if (machineSrc && plan != null && amount > 1
                    && (plan.simulation() || plan.finalOutput() == null || plan.finalOutput().amount() <= 0)) {
                long tryAmount = amount;
                for (int i = 0; i < 12 && tryAmount > 1; i++) {
                    tryAmount /= 2;
                    var inv2 = grid.getStorageService().getCachedInventory().copy();
                    var p2 = (ICraftingPlan) m.invoke(null, grid, inv2, simRequester, what, tryAmount, strategy);
                    // 拒收退化計畫（吃現貨、零任務）：GTO 沒有「開局吸入的成品交給 link」的步驟，這種 job 永凍。
                    if (p2 != null && !p2.simulation() && p2.finalOutput() != null && p2.finalOutput().amount() > 0
                            && !p2.patternTimes().isEmpty()) {
                        if (gtocraftfix$logInfoOk()) { // [重檢7] log 分流
                            LOG.info("[craftfix] 機器源降量重算 {}：{} → {}", what, amount, tryAmount);
                        }
                        plan = p2;
                        break;
                    }
                }
            }
            cir.setReturnValue(CompletableFuture.completedFuture(plan));
        } catch (Throwable t) {
            LOG.error("[craftfix] 同步算料失敗，退回原本 async。", t);
        }
    }

    // ---- 修正 4：計畫修補 ----
    // 算料器會把拿不到的量寫進 usedItems 而不排樣板（批量餘數幻影）→ job 永凍。提交前重算帳：
    // 缺口把樣板 runs 補進同一張計畫、新增輸入遞迴補平。有界迴圈，任何失敗放行原計畫。
    @Inject(method = "submitJob", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtocraftfix$repairPlan(ICraftingPlan job, ICraftingRequester requestingMachine, ICraftingCPU target,
                                        boolean prioritizePower, IActionSource src,
                                        CallbackInfoReturnable<ICraftingSubmitResult> cir) {
        gtocraftfix$ensureState(); // [重檢17]
        if (!(job instanceof CraftingPlan plan)) {
            return;
        }
        boolean blockSubmit = false;
        // [v1.4.0] 硬缺口收集（玩家擋單點名用）；刻意不查 noPatternLogged 去重——玩家每次點擊都該有回饋
        var hardNoPattern = new java.util.LinkedHashMap<AEKey, Long>();
        try {
            var storage = grid.getStorageService().getInventory();
            var used = plan.usedItems();
            var missing = plan.missingItems();
            var pt = plan.patternTimes(); // 同一個可變 map（Object2LongOpenHashMap）

            // 可用量帳本：avail=實際可取（SIMULATE），reserved=本計畫已預定
            Map<AEKey, Long> avail = new HashMap<>();
            Map<AEKey, Long> reserved = new HashMap<>();
            var deficits = new ArrayDeque<Object[]>(); // {AEKey, Long short}

            // ① missingItems：有樣板就能補排；全補完會把 sim 翻回 false（機器源 sim 計畫會被守衛靜默拒單）
            for (var e : missing) {
                var key = e.getKey();
                long want = e.getLongValue();
                if (want > 0) {
                    deficits.add(new Object[] { key, want, Boolean.TRUE });
                }
            }
            // ② usedItems 超出實際可取的幻影缺口
            for (var e : used) {
                var key = e.getKey();
                long want = e.getLongValue();
                long a = avail.computeIfAbsent(key,
                        k -> storage.extract(k, Long.MAX_VALUE / 2, Actionable.SIMULATE, src));
                long take = Math.min(want, a - reserved.getOrDefault(key, 0L));
                if (take < want) {
                    deficits.add(new Object[] { key, want - take, Boolean.TRUE });
                }
                reserved.merge(key, Math.max(0, take), Long::sum);
            }
            // ③ 最終產出量檢查：整條鏈 runs 取整後總產出可能 < 需求（做完仍交不齊 → 永凍）→ 差額列缺口補樣板
            if (plan.finalOutput() != null) {
                var outKey = plan.finalOutput().what();
                long supply = used.get(outKey) + plan.emittedItems().get(outKey);
                for (var en : pt.entrySet()) {
                    Long r = en.getValue();
                    if (r == null || r <= 0) {
                        continue;
                    }
                    for (var o : en.getKey().getOutputs()) {
                        if (outKey.equals(o.what())) {
                            supply += o.amount() * r;
                        }
                    }
                }
                long needOut = plan.finalOutput().amount() - supply;
                if (needOut > 0) {
                    deficits.add(new Object[] { outKey, needOut, Boolean.TRUE });
                    if (gtocraftfix$logInfoOk()) { // [重檢7] log 分流
                        LOG.info("[craftfix] 最終產出短缺 {} x{}（out={}）",
                                outKey, gtocraftfix$fmtAmt(outKey, needOut), plan.finalOutput());
                    }
                }
            }
            if (deficits.isEmpty()) {
                return; // 帳是平的，不動
            }

            int guard = 0;
            int repaired = 0;
            StringBuilder note = new StringBuilder();
            // 外圈：解缺口 → 可執行性模擬（抓循環自舉缺口）→ 再解，最多 4 輪
            for (int round = 0; round < 4; round++) {
            while (!deficits.isEmpty() && guard++ < 96) {
                var d = deficits.poll();
                var key = (AEKey) d[0];
                long shortAmt = (Long) d[1];
                // hard=真實記帳缺口（missing/usedItems/最終產出/新增輸入）；自舉猜測（近似模擬）為 soft
                boolean hard = d.length > 2 && Boolean.TRUE.equals(d[2]);

                // 找樣板：①計畫裡主產出＝key ②計畫裡任一產出含 key（副產物，如鎂循環的鎂）③問網路
                IPatternDetails pat = null;
                long batchOut = 0;
                for (var p : pt.keySet()) {
                    if (key.equals(p.getPrimaryOutput().what())) {
                        pat = p;
                        batchOut = p.getPrimaryOutput().amount();
                        break;
                    }
                }
                if (pat == null) {
                    outer:
                    for (var p : pt.keySet()) {
                        for (var out : p.getOutputs()) {
                            if (key.equals(out.what())) {
                                pat = p;
                                batchOut = out.amount();
                                break outer;
                            }
                        }
                    }
                }
                if (pat == null) {
                    var cs = (ICraftingService) (Object) this;
                    for (var p : cs.getCraftingFor(key)) {
                        pat = p;
                        batchOut = p.getPrimaryOutput().amount();
                        break;
                    }
                }
                if (pat == null) {
                    // 無樣板可補＝真缺料 → log＋聊天室提示玩家補料
                    if (hard) {
                        blockSubmit = true; // 真缺料 → 擋下提交（否則 job 必凍）
                        hardNoPattern.merge(key, shortAmt, Long::sum); // [v1.4.0] 玩家擋單點名用
                    }
                    if (gtocraftfix$logWarnOk()) { // [重檢7] log 分流
                        LOG.warn("[craftfix] 計畫修補 無樣板可補：{} x{}（out={}）",
                                key, gtocraftfix$fmtAmt(key, shortAmt), plan.finalOutput());
                    }
                    if (gtocraftfix$noPatternLogged.add(key)) {
                        if (gtocraftfix$noPatternLogged.size() > 128) {
                            gtocraftfix$noPatternLogged.clear();
                        }
                        var server = grid.getPivot() != null && grid.getPivot().getLevel() != null
                                ? grid.getPivot().getLevel().getServer()
                                : null;
                        if (server != null) {
                            server.getPlayerList().broadcastSystemMessage(
                                    net.minecraft.network.chat.Component.literal("[合成修復] 缺料且無樣板可做：")
                                            .append(key.getDisplayName())
                                            .append(net.minecraft.network.chat.Component
                                                    .literal(" x" + gtocraftfix$fmtAmt(key, shortAmt) + "（合成 "))
                                            .append(plan.finalOutput().what().getDisplayName())
                                            .append(net.minecraft.network.chat.Component.literal(" 需要，請補料或壓樣板）")),
                                    false);
                        }
                    }
                    continue;
                }
                long batch = Math.max(1, batchOut);
                long runs = (shortAmt + batch - 1) / batch; // ceil
                pt.merge(pat, runs, Long::sum);
                // [重檢11] 玩家單配額補登：補進的 runs 若不在該輸入 key 的配額表 → INSUFFICIENT_PRIORITY
                // 永拒。lpcalc 機器單 allocations 恆 null 天然跳過。
                try {
                    var allocMap = plan.getGtocore$allocations();
                    if (allocMap != null && !allocMap.isEmpty()) {
                        for (var ain : pat.getInputs()) {
                            var apos = ain.getPossibleInputs();
                            if (apos.length == 0) {
                                continue;
                            }
                            var innerAlloc = allocMap.get(apos[0].what());
                            if (innerAlloc != null) {
                                long addQ = apos[0].amount() * ain.getMultiplier() * runs;
                                innerAlloc.merge(pat, addQ, Long::sum);
                            }
                        }
                    }
                } catch (Throwable ignoredAlloc) {
                    // 無 gtocore$allocations（非 GTO 版 AE2）＝無配額機制 → 不需補登
                }
                // 缺口來源：先沖銷 missingItems（sim 計畫的缺），剩下沖銷 usedItems（幻影引用）
                long fromMissing = Math.min(shortAmt, Math.max(0, missing.get(key)));
                if (fromMissing > 0) {
                    missing.add(key, -fromMissing);
                }
                if (shortAmt - fromMissing > 0) {
                    used.add(key, -(shortAmt - fromMissing));
                }
                repaired++;
                if (repaired <= 8) {
                    note.append(key).append(" +").append(runs).append("runs(批").append(batch).append("); ");
                }

                // 新增 runs 的輸入：網路夠 → usedItems；不夠 → 繼續補
                for (var input : pat.getInputs()) {
                    var possible = input.getPossibleInputs();
                    if (possible.length == 0) {
                        continue;
                    }
                    var prim = possible[0];
                    var inKey = prim.what();
                    long need = prim.amount() * input.getMultiplier() * runs;
                    long a = avail.computeIfAbsent(inKey,
                            k -> storage.extract(k, Long.MAX_VALUE / 2, Actionable.SIMULATE, src));
                    long free = Math.max(0, a - reserved.getOrDefault(inKey, 0L));
                    long fromNet = Math.min(need, free);
                    if (fromNet > 0) {
                        used.add(inKey, fromNet);
                        reserved.merge(inKey, fromNet, Long::sum);
                    }
                    if (need - fromNet > 0) {
                        deficits.add(new Object[] { inKey, need - fromNet, Boolean.TRUE });
                    }
                }
            }
            // 可執行性模擬：紙上跑整張計畫，跑不完＝循環自舉缺口（帳面淨消耗 0 但執行要有第一份
            // 才轉得起來，不在 usedItems/missing）→ 補進缺口再解
            var bootstrap = gtocraftfix$findBootstrapDeficits(plan);
            if (bootstrap.isEmpty()) {
                break;
            }
            for (var b : bootstrap) {
                deficits.add(b);
                if (gtocraftfix$logInfoOk()) { // [重檢7] log 分流
                    LOG.info("[craftfix] 循環自舉缺口 {} x{}（out={}）", b[0], b[1], plan.finalOutput());
                }
            }
            }
            used.removeZeros();
            missing.removeZeros();
            if (repaired > 0) {
                if (gtocraftfix$logInfoOk()) { // [重檢7] log 分流
                    LOG.info("[craftfix] 計畫修補 out={} 補{}項：{}", plan.finalOutput(), repaired, note);
                }
            }
            // sim 計畫的缺全補齊 → 翻回可執行，machine+sim 守衛不再靜默拒單（手動能、自動不能的分歧點）
            if (plan.simulation() && missing.size() == 0) {
                try {
                    var f = CraftingPlan.class.getDeclaredField("simulation");
                    f.setAccessible(true);
                    f.setBoolean(plan, false);
                    if (gtocraftfix$logInfoOk()) { // [重檢7] log 分流
                        LOG.info("[craftfix] 計畫修補 sim→可執行 out={}", plan.finalOutput());
                    }
                } catch (Throwable t) {
                    if (gtocraftfix$logWarnOk()) { // [重檢7] log 分流
                        LOG.warn("[craftfix] sim 旗標翻轉失敗（維持原行為）：{}", t.toString());
                    }
                }
            }
        } catch (Throwable t) {
            if (gtocraftfix$logErrOk()) { // [重檢7] 例外計數器獨立——不再被一般 log 搶光額度
                LOG.error("[craftfix] 計畫修補例外（放行原計畫）", t);
            }
        }
        // 真缺料 → 擋下提交（提交了必凍）。玩家回饋必須在這裡發：HEAD setReturnValue 短路後
        // RETURN 注入不保證跑到。
        if (blockSubmit) {
            gtocraftfix$notifyPlayerBlocked(plan, src, hardNoPattern);
            cir.setReturnValue(appeng.crafting.execution.CraftingSubmitResult.INCOMPLETE_PLAN);
            return;
        }
        // 機器源退化計畫（修補後仍零任務）→ 拒單；接口下一輪 acquireFromNetwork 會自己拉現貨。
        if (src.player().isEmpty() && plan.patternTimes().isEmpty()) {
            if (gtocraftfix$logWarnOk()) { // [重檢7] log 分流
                LOG.warn("[craftfix] 拒收退化計畫（無合成任務）out={}", plan.finalOutput());
            }
            cir.setReturnValue(appeng.crafting.execution.CraftingSubmitResult.INCOMPLETE_PLAN);
        }
    }

    // ---- 診斷：機器源提交失敗（原本完全無聲——追蹤器只會下一 tick 重試）----
    @Inject(method = "submitJob", at = @At("RETURN"), remap = false)
    private void gtocraftfix$diagMachineFail(ICraftingPlan job, ICraftingRequester requestingMachine,
                                             ICraftingCPU target, boolean prioritizePower, IActionSource src,
                                             CallbackInfoReturnable<ICraftingSubmitResult> cir) {
        gtocraftfix$ensureState(); // [重檢17]
        if (src.player().isPresent()) {
            var pr = cir.getReturnValue();
            if (pr != null && pr.successful() && pr.link() != null && gtocraftfix$realPlayer(src) != null) {
                gtocraftfix$playerLinks.put(pr.link(), Boolean.TRUE); // [v1.6.1] 標記玩家單（短交監看限定）
            }
            gtocraftfix$playerSubmitFeedback(job, src, cir.getReturnValue()); // [v1.4.0] 玩家單不再沉默
            return;
        }
        var r = cir.getReturnValue();
        if (r == null || r.successful()) {
            return;
        }
        // 同 key+錯誤 只記一次（機器追蹤器會每 2 秒重試，避免洗版）
        String sig = job.finalOutput().what() + "|" + r.errorCode();
        if (gtocraftfix$failLogged.add(sig)) {
            if (gtocraftfix$failLogged.size() > 128) {
                gtocraftfix$failLogged.clear();
            }
            LOG.warn("[craftfix] 機器源提交失敗 err={} sim={} missing={} out={}",
                    r.errorCode(), job.simulation(), job.missingItems().size(), job.finalOutput());
        }
    }

    // ---- [v1.4.0] 玩家下單回饋：缺料/失敗不再沉默 ----

    /** 玩家定向訊息 3 秒窗節流（同 uuid|產物|錯誤碼；手動操作每次點擊都該有回饋，窗刻意短）。 */
    private static boolean gtocraftfix$playerNoticeOk(String sig) {
        if (gtocraftfix$playerNoticeMs == null) {
            gtocraftfix$playerNoticeMs = new HashMap<>();
        }
        long now = System.currentTimeMillis();
        Long last = gtocraftfix$playerNoticeMs.get(sig);
        if (last != null && now - last < 3000L) {
            return false;
        }
        if (gtocraftfix$playerNoticeMs.size() > 256) {
            gtocraftfix$playerNoticeMs.clear();
        }
        gtocraftfix$playerNoticeMs.put(sig, now);
        return true;
    }

    /** 提交錯誤碼人話化（字串比對，免依賴 enum 常數集）。 */
    private static String gtocraftfix$errZh(Object code) {
        String c = String.valueOf(code);
        return switch (c) {
            case "INCOMPLETE_PLAN" -> "計畫缺料";
            case "NO_SUITABLE_CPU_FOUND" -> "找不到可用的合成 CPU（全忙或容量不足）";
            case "NO_CPU_FOUND" -> "網路沒有合成 CPU";
            case "CPU_BUSY" -> "指定的 CPU 忙碌中";
            case "CPU_OFFLINE" -> "指定的 CPU 離線";
            case "CPU_TOO_SMALL" -> "指定的 CPU 容量不足";
            case "MISSING_INGREDIENT" -> "取料時缺原料";
            default -> c;
        };
    }

    /** 取真人玩家（排除 FakePlayer——其他 mod 的 FakePlayer 源可能流入）；非真人回 null。 */
    private static net.minecraft.server.level.ServerPlayer gtocraftfix$realPlayer(IActionSource src) {
        var p = src == null ? null : src.player().orElse(null);
        if (p instanceof net.minecraft.server.level.ServerPlayer sp
                && !(p instanceof net.minecraftforge.common.util.FakePlayer)) {
            return sp;
        }
        return null;
    }

    /** 缺料清單 → 「顯示名 x量」前 4 項串接（超出以總項數收尾）。 */
    private static net.minecraft.network.chat.MutableComponent gtocraftfix$itemList(
            java.util.List<Map.Entry<AEKey, Long>> entries) {
        var msg = net.minecraft.network.chat.Component.empty();
        int shown = 0;
        for (var e : entries) {
            if (shown >= 4) {
                msg.append(net.minecraft.network.chat.Component.literal(" …等共 " + entries.size() + " 項"));
                break;
            }
            if (shown++ > 0) {
                msg.append(net.minecraft.network.chat.Component.literal("、"));
            }
            msg.append(e.getKey().getDisplayName())
                    .append(net.minecraft.network.chat.Component.literal(
                            " x" + gtocraftfix$fmtAmt(e.getKey(), e.getValue())));
        }
        return msg;
    }

    /** 真缺料擋單時，若是玩家手動下單 → 定向告知缺什麼（不受 noPatternLogged 全域去重影響）。 */
    private void gtocraftfix$notifyPlayerBlocked(CraftingPlan plan, IActionSource src,
                                                 Map<AEKey, Long> hardNoPattern) {
        try {
            var sp = gtocraftfix$realPlayer(src);
            if (sp == null || plan.finalOutput() == null) {
                return;
            }
            if (!gtocraftfix$playerNoticeOk(sp.getUUID() + "|" + plan.finalOutput().what() + "|INCOMPLETE_PLAN")) {
                return;
            }
            var msg = net.minecraft.network.chat.Component.literal("[合成修復] 已擋下你的訂單 ")
                    .append(plan.finalOutput().what().getDisplayName())
                    .append(net.minecraft.network.chat.Component.literal("：缺 "));
            if (hardNoPattern.isEmpty()) {
                msg.append(net.minecraft.network.chat.Component.literal("料（硬缺口，詳見伺服器紀錄）"));
            } else {
                msg.append(gtocraftfix$itemList(new java.util.ArrayList<>(hardNoPattern.entrySet())))
                        .append(net.minecraft.network.chat.Component.literal("（無樣板可做，請補料或壓樣板後再下單）"));
            }
            sp.sendSystemMessage(msg);
        } catch (Throwable ignored) {
        }
    }

    /** 玩家提交結果回饋：失敗說人話原因；成功但帶缺料（修補例外時的罕見放行路徑）也提醒。 */
    private void gtocraftfix$playerSubmitFeedback(ICraftingPlan job, IActionSource src, ICraftingSubmitResult r) {
        try {
            var sp = gtocraftfix$realPlayer(src);
            if (sp == null || r == null || job == null || job.finalOutput() == null) {
                return;
            }
            var outKey = job.finalOutput().what();
            var missing = job.missingItems();
            var entries = new java.util.ArrayList<Map.Entry<AEKey, Long>>();
            if (missing != null) {
                for (var e : missing) {
                    if (e.getLongValue() > 0) {
                        entries.add(Map.entry(e.getKey(), e.getLongValue()));
                    }
                }
            }
            if (!r.successful()) {
                if (!gtocraftfix$playerNoticeOk(sp.getUUID() + "|" + outKey + "|" + r.errorCode())) {
                    return;
                }
                var msg = net.minecraft.network.chat.Component
                        .literal("[合成修復] 下單失敗（" + gtocraftfix$errZh(r.errorCode()) + "）：")
                        .append(outKey.getDisplayName());
                if (!entries.isEmpty()) {
                    msg.append(net.minecraft.network.chat.Component.literal("，缺 "))
                            .append(gtocraftfix$itemList(entries));
                }
                sp.sendSystemMessage(msg);
            } else if (!entries.isEmpty()) {
                if (!gtocraftfix$playerNoticeOk(sp.getUUID() + "|" + outKey + "|ACCEPTED_MISSING")) {
                    return;
                }
                sp.sendSystemMessage(net.minecraft.network.chat.Component.literal("[合成修復] 已接單但缺料：")
                        .append(gtocraftfix$itemList(entries))
                        .append(net.minecraft.network.chat.Component.literal("（貨到 ME 後保母會自動餵入續作）")));
            }
        } catch (Throwable ignored) {
        }
    }

    // ---- 修正 2：機器源 present-once 走 IgnoreMissing ----
    @Redirect(method = "submitJob",
              at = @At(value = "INVOKE",
                       target = "Lappeng/me/cluster/implementations/CraftingCPUCluster;submitJob(Lappeng/api/networking/IGrid;Lappeng/api/networking/crafting/ICraftingPlan;Lappeng/api/networking/security/IActionSource;Lappeng/api/networking/crafting/ICraftingRequester;)Lappeng/api/networking/crafting/ICraftingSubmitResult;"),
              remap = false)
    private ICraftingSubmitResult gtocraftfix$machineAsPlayer(CraftingCPUCluster cluster, IGrid g, ICraftingPlan plan,
                                                              IActionSource src, ICraftingRequester requester) {
        if (src.player().isEmpty() && AEConfig.instance().isAllowMissingCraftingJobs()) {
            IActionSource wrapped = gtocraftfix$wrapPresentOnce(src, g);
            if (wrapped != null) {
                return cluster.submitJob(g, plan, wrapped, requester);
            }
        }
        return cluster.submitJob(g, plan, src, requester);
    }

    /** present-once：player() 只在第一次呼叫回 present（走 IgnoreMissing 條件），其後 empty（取料用 machine 身分）。 */
    private static IActionSource gtocraftfix$wrapPresentOnce(IActionSource orig, IGrid g) {
        try {
            var pivot = g.getPivot();
            if (pivot == null || !(pivot.getLevel() instanceof ServerLevel sl)) {
                return null;
            }
            Player fake = FakePlayerFactory.getMinecraft(sl);
            if (fake == null) {
                return null;
            }
            final Optional<Player> present = Optional.of(fake);
            final boolean[] consumed = { false };
            return new IActionSource() {

                @Override
                public Optional<Player> player() {
                    if (!consumed[0]) {
                        consumed[0] = true;
                        return present;
                    }
                    return Optional.empty();
                }

                @Override
                public Optional<IActionHost> machine() {
                    return orig.machine();
                }

                @Override
                public <T> Optional<T> context(Class<T> key) {
                    return orig.context(key);
                }
            };
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- 修正 3：保母（每秒掃孤兒 waitingFor）＋ 診斷探針（每 20 秒）----
    // [重檢18] 掛 HEAD 不掛 TAIL：GTOCore 在偶數 tick 提前 ci.cancel() 本方法，TAIL 偶數 tick 永不執行（整段半速）。
    @Inject(method = "onServerEndTick", at = @At("HEAD"), remap = false)
    private void gtocraftfix$tick(MinecraftServer server, CallbackInfo ci) {
        gtocraftfix$ensureState(); // [重檢17]
        com.gtocraftfix.calc.CalcTicker.tick(); // 內置原版算料器的預算泵（每 tick）
        com.gtocraftfix.lpcalc.LpFallbackQueue.drainOnServerTick(); // LP 回退/影子驗證只准在伺服器緒建構
        gtocraftfix$tickCounter++;
        gtocraftfix$shortWatchTick(); // [v1.6.1] 完單短交監看：累計到貨、期滿通知真損失（v1.8.0 不代補）
        if (gtocraftfix$tickCounter % 400 == 0) {
            for (var cluster : craftingCPUClusters) {
                try {
                    var logic = cluster.craftingLogic;
                    var out = logic.getFinalJobOutput();
                    if (out == null) {
                        continue;
                    }
                    boolean pausedCpu = gtocraftfix$isPausedCpu(logic); // [v1.7.1]
                    Set<AEKey> waiting = new HashSet<>();
                    logic.getAllWaitingFor(waiting);
                    String waitStr = waiting.stream().limit(8).map(String::valueOf)
                            .reduce((a, b) -> a + "," + b).orElse("(空)");
                    String results;
                    Object resultsObj = null; // [v1.2.1] 供任務段按主產物精準撈個別 PushResult（總表會被截斷）
                    try {
                        Object r = logic.getClass().getMethod("getCraftingResults").invoke(logic);
                        resultsObj = r;
                        results = gtocraftfix$zhPush(String.valueOf(r)); // [v1.7.1] PushResult 人話化
                        if (results.length() > 300) {
                            results = results.substring(0, 300) + "…";
                        }
                    } catch (Throwable t) {
                        results = "n/a";
                    }
                    // CPU 持有物（庫存＋waitingFor＋待產出）取前 10 項：卡死時直接看缺什麼
                    String held;
                    try {
                        var all = new appeng.api.stacks.KeyCounter();
                        logic.getAllItems(all);
                        StringBuilder hb = new StringBuilder();
                        int shown = 0;
                        for (var e : all) {
                            if (shown++ >= 10) {
                                hb.append("…");
                                break;
                            }
                            hb.append(e.getKey()).append('x').append(e.getLongValue()).append("; ");
                        }
                        held = hb.toString();
                    } catch (Throwable t) {
                        held = "n/a";
                    }
                    // 剩餘任務 X 光：分辨「缺料不推」（庫存<需求）與「料齊未推」（執行器死角）
                    String tasksStr = "n/a";
                    try {
                        if (gtocraftfix$fJob == null) {
                            var fj = logic.getClass().getDeclaredField("job");
                            fj.setAccessible(true);
                            gtocraftfix$fJob = fj;
                        }
                        Object job0 = gtocraftfix$fJob.get(logic);
                        if (job0 != null) {
                            if (gtocraftfix$fTasks == null) {
                                var ft = job0.getClass().getDeclaredField("tasks");
                                ft.setAccessible(true);
                                gtocraftfix$fTasks = ft;
                            }
                            Map<?, ?> ts = (Map<?, ?>) gtocraftfix$fTasks.get(job0);
                            var inv0 = gtocraftfix$invOf(logic);
                            if (ts != null) {
                                StringBuilder tb = new StringBuilder();
                                int shownT = 0;
                                for (var en : ts.entrySet()) {
                                    Object holder0 = en.getValue();
                                    if (gtocraftfix$fHolderVal == null) {
                                        var fv = holder0.getClass().getField("value");
                                        fv.setAccessible(true);
                                        gtocraftfix$fHolderVal = fv;
                                    }
                                    long times = gtocraftfix$fHolderVal.getLong(holder0);
                                    if (times <= 0) {
                                        continue;
                                    }
                                    if (shownT++ >= 8) {
                                        tb.append('…');
                                        break;
                                    }
                                    var pat0 = (IPatternDetails) en.getKey();
                                    tb.append(pat0.getPrimaryOutput().what()).append('x').append(times);
                                    // prov:0 = 樣板失聯（供應器清單找不到機器）→ executeCrafting 空轉不留痕
                                    int provN = 0;
                                    for (var p0 : ((CraftingService) (Object) this).getProviders(pat0)) {
                                        if (++provN >= 9) {
                                            break;
                                        }
                                    }
                                    tb.append(",prov:").append(provN);
                                    // [v1.2.1] 每任務最後推送結果字串（用同一 pattern 實例的主產物 key 撈）；供⚠佐證
                                    String rrStr = null;
                                    try {
                                        if (resultsObj != null) {
                                            @SuppressWarnings("rawtypes")
                                            var rr = ((com.google.common.collect.SetMultimap) resultsObj)
                                                    .get(pat0.getPrimaryOutput().what());
                                            if (rr != null && !rr.isEmpty()) {
                                                rrStr = String.valueOf(rr);
                                            }
                                        }
                                    } catch (Throwable ignored4) {
                                    }
                                    // 印「最缺的那格」輸入——executeCrafting 任一格不足即無聲跳過
                                    if (inv0 != null) {
                                        AEKey worstK = null;
                                        long worstHave = 0;
                                        long worstNeed = 0;
                                        double worstR = Double.MAX_VALUE;
                                        for (var in1 : pat0.getInputs()) {
                                            var ps = in1.getPossibleInputs();
                                            if (ps.length == 0) {
                                                continue;
                                            }
                                            long need1 = ps[0].amount() * in1.getMultiplier();
                                            if (need1 <= 0) {
                                                continue;
                                            }
                                            // [重檢18] 含全部替代品加總（只讀 ps[0] 會把持替代品誤報成缺料）
                                            long have1 = 0;
                                            for (var p1 : ps) {
                                                have1 += inv0.list.get(p1.what());
                                            }
                                            double r = (double) have1 / need1;
                                            if (r < worstR) {
                                                worstR = r;
                                                worstK = ps[0].what();
                                                worstHave = have1;
                                                worstNeed = need1;
                                            }
                                        }
                                        if (worstK != null) {
                                            tb.append("(缺口:").append(worstK).append(' ')
                                                    .append(worstHave).append('/').append(worstNeed);
                                            // [重檢18] ⚠ 需失敗碼佐證：料齊＋供應器在列＋結果集含負碼。只收
                                            // INSUFFICIENT_PRIORITY/NOWHERE_TO_PUSH/REJECTED——
                                            // PATTERN_PROVIDER_LOCKED 是阻塞流程常態碼、BREAK 是成功收尾碼。
                                            // 暫停中 CPU 不標（殘留的是暫停前快照）。
                                            if (!pausedCpu && worstHave >= worstNeed && provN > 0 && rrStr != null
                                                    && (rrStr.contains("INSUFFICIENT_PRIORITY")
                                                            || rrStr.contains("NOWHERE_TO_PUSH")
                                                            || rrStr.contains("REJECTED"))) {
                                                tb.append(" 料齊未推⚠");
                                                // [v1.7.1] 溯源：getPendingRequests 記「最近嘗試過的供應器
                                                // 座標」（含失敗嘗試，非實際送達處），列前 2 個
                                                try {
                                                    Object pend = logic.getClass()
                                                            .getMethod("getPendingRequests", AEKey.class)
                                                            .invoke(logic, pat0.getPrimaryOutput().what());
                                                    if (pend instanceof java.util.Collection<?> pc && !pc.isEmpty()) {
                                                        tb.append(" 嘗試過:");
                                                        int pn = 0;
                                                        for (Object gp : pc) {
                                                            if (pn++ >= 2) {
                                                                tb.append('…');
                                                                break;
                                                            }
                                                            tb.append(gp).append(' ');
                                                        }
                                                    }
                                                } catch (Throwable ignored5) {
                                                }
                                            }
                                            tb.append(')');
                                        }
                                    }
                                    if (rrStr != null) {
                                        tb.append("結果:").append(gtocraftfix$zhPush(rrStr)); // [v1.7.1] 人話化
                                    }
                                    tb.append("; ");
                                }
                                tasksStr = tb.length() == 0 ? "(無)" : tb.toString();
                            }
                        }
                    } catch (Throwable ignored2) {
                    }
                    // [v1.7.1] 暫停標記放 out= 之後：行首插字會破壞既有 grep 'CPU探針 out='
                    LOG.info("[craftfix] CPU探針 out={}{} waiting[{}]={} held=[{}] 剩餘任務=[{}] results={}",
                            out, pausedCpu ? "（已暫停）" : "",
                            waiting.size(), waitStr, held, tasksStr, results);
                    // 欄位普查（每 cluster 一次）：waiting 空＋有剩餘任務＝閘門在 gtolib 私有欄位裡，全部倒出來找
                    if (waiting.isEmpty() && !"n/a".equals(tasksStr) && !"(無)".equals(tasksStr)) {
                        if (gtocraftfix$censusDone.add(cluster)) { // [重檢14] cluster 弱鍵集合（防洩漏）
                            try {
                                Object job1 = gtocraftfix$fJob != null ? gtocraftfix$fJob.get(logic) : null;
                                LOG.info("[craftfix] 欄位普查 logic({}): {}",
                                        logic.getClass().getName(), gtocraftfix$census(logic));
                                if (job1 != null) {
                                    LOG.info("[craftfix] 欄位普查 job({}): {}",
                                            job1.getClass().getName(), gtocraftfix$census(job1));
                                }
                            } catch (Throwable ignored3) {
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        if (gtocraftfix$tickCounter % 20 != 0) { // 保母每秒一輪
            return;
        }
        var storage = grid.getStorageService().getInventory();
        int handled = 0;
        // [重檢8] cluster 輪替起點：固定迭代序＋handled≥16 中斷會讓後段 cluster 永遠輪不到——每輪換起點
        var clusterList = new java.util.ArrayList<>(craftingCPUClusters);
        int clusterN = clusterList.size();
        int startIdx = clusterN == 0 ? 0 : Math.floorMod(gtocraftfix$tickCounter / 20, clusterN);
        for (int cIdx = 0; cIdx < clusterN; cIdx++) {
            var cluster = clusterList.get((startIdx + cIdx) % clusterN);
            if (handled >= 16) {
                break;
            }
            boolean acted = false;
            try {
                var logic = cluster.craftingLogic;
                Object jobNow = gtocraftfix$jobOf(logic);
                gtocraftfix$trackJob(cluster, logic, jobNow, storage); // [v1.2.0] 完單法醫＋訂單交付帳＋[重檢19]成品基線
                if (gtocraftfix$isPausedCpu(logic)) {
                    // [v1.7.1] 暫停期間清空各滯留計時表——否則解除暫停後把暫停時長算入，時間門檻全部立即誤觸發
                    gtocraftfix$starveNotice.remove(cluster);
                    gtocraftfix$quotaStuck.remove(cluster);
                    gtocraftfix$staleWait.remove(cluster);
                    gtocraftfix$finalClaimTick.remove(cluster);
                    gtocraftfix$staleHeld.remove(cluster);
                    gtocraftfix$orphanSince.remove(cluster);
                    continue; // paused 時執行器不跑、零進度是預期：餵料/認領/救援全跳過（法醫照記）
                }
                boolean orderJob = jobNow != null && gtocraftfix$isOrderJob(jobNow);
                var finalOut = logic.getFinalJobOutput();
                if (finalOut == null) {
                    continue;
                }
                Set<AEKey> waiting = new HashSet<>();
                logic.getAllWaitingFor(waiting);
                int handledBefore = handled;
                for (var key : waiting) {
                    if (handled >= 16) {
                        break;
                    }
                    boolean isFinal = key.equals(finalOut.what());
                    if (isFinal) {
                        // [v1.2.0] 訂單成品不代餵：儲存裡舊單據與在途單據同 key 無法區分，
                        // 餵到舊單據＝收據充數偽完單；訂單鏈認領走正規 insert。
                        if (orderJob) {
                            continue;
                        }
                        // 成品也要餵（成品回流但 CPU 沒攔到認領時的唯一救援）；拒收記憶 10 分鐘內不再試
                        Integer ru = gtocraftfix$refusedGet(cluster, key);
                        if (ru != null && gtocraftfix$tickCounter - ru < 12000) {
                            continue;
                        }
                    }
                    long want = logic.getWaitingFor(key);
                    if (want <= 0) {
                        continue;
                    }
                    if (isFinal) {
                        // [重檢19] 成品基線防偽：只餵「開單後新增」的量（基線＝job 首見時網路存量）——
                        // 餵到既有現貨＝充當產出銷帳 → 秒完單、requester 重下、無限空轉。
                        long baseF = gtocraftfix$finalBaseline(cluster);
                        if (baseF < 0) {
                            continue; // 基線未知（trackJob 失敗）→ 保守不餵
                        }
                        long nowCount = storage.extract(key, Long.MAX_VALUE / 4,
                                Actionable.SIMULATE, cluster.getSrc());
                        long surplus = nowCount - baseF;
                        if (surplus <= 0) {
                            continue;
                        }
                        want = Math.min(want, surplus);
                    }
                    // 只餵料：網路有貨 → 直餵 CPU（補認領缺口）。不代下巢狀單——那會生一堆小任務佔 CPU。
                    long got = storage.extract(key, want, Actionable.MODULATE, cluster.getSrc());
                    if (got <= 0) {
                        // [重檢4] 自庫認領跳過成品：會與 selfClaimFinal 對同一批物品重複燒帳，成品滯留由該處專責
                        if (isFinal) {
                            continue;
                        }
                        // 自庫認領：網內無貨，但貨可能繞過認領 hook 已在 CPU 自己的庫存
                        var inv0 = gtocraftfix$invOf(logic);
                        if (inv0 != null) {
                            long heldHere = inv0.list.get(key);
                            // [重檢13] 只認領超出「剩餘任務全量需求」的超額：topUp 塞的工作備料不是交付品，
                            // 銷了帳會讓在途真交付被拒
                            long claimable = Math.min(heldHere - gtocraftfix$remainingDemand(logic, key), want);
                            if (claimable > 0) {
                                long g2 = inv0.extract(key, claimable, Actionable.MODULATE);
                                if (g2 > 0) {
                                    long acc2;
                                    try {
                                        acc2 = logic.insert(key, g2, Actionable.MODULATE);
                                    } catch (Throwable t) {
                                        // [重檢6] 例外補償：insert 半途拋出先把已取出的料放回 CPU 庫存再拋（防物品蒸發）
                                        inv0.insert(key, g2, Actionable.MODULATE);
                                        throw t;
                                    }
                                    if (acc2 < g2) {
                                        inv0.insert(key, g2 - acc2, Actionable.MODULATE);
                                    }
                                    if (acc2 > 0) {
                                        handled++;
                                        acted = true;
                                        if (gtocraftfix$logInfoOk()) {
                                            LOG.info("[craftfix] 自庫認領 {} x{}（貨在 CPU 庫存、帳未記）", key, acc2);
                                        }
                                    }
                                }
                            }
                        }
                        continue;
                    }
                    long accepted;
                    try {
                        accepted = logic.insert(key, got, Actionable.MODULATE);
                    } catch (Throwable t) {
                        // [重檢6] 例外補償：已抽出的料退回網路再拋（防「已取出未插入」的半套蒸發）
                        storage.insert(key, got, Actionable.MODULATE, cluster.getSrc());
                        throw t;
                    }
                    if (accepted < got) {
                        if (isFinal) {
                            // [重檢3] 成品餘額不回插網路（會再進同一顆 CPU 被二次銷帳 → 提早完單）；
                            // 改留 CPU 庫存（完單 storeItems 自然退網）並記入 claimedFinalHeld。
                            var invF = gtocraftfix$invOf(logic);
                            if (invF != null) {
                                invF.insert(key, got - accepted, Actionable.MODULATE);
                                gtocraftfix$claimedAdd(logic, got - accepted);
                            } else {
                                storage.insert(key, got - accepted, Actionable.MODULATE, cluster.getSrc());
                            }
                        } else {
                            // 非成品：GTO insert 恆全額吃下（實為死碼），保留作退料安全網
                            storage.insert(key, got - accepted, Actionable.MODULATE, cluster.getSrc());
                        }
                    }
                    if (accepted <= 0 && !isFinal) {
                        continue;
                    }
                    // [重檢5] 成品 accepted==0 ≠ 拒收：GTO insert 問 link 前已按全額銷帳（帳已推進），不記 feedRefused
                    handled++;
                    acted = true;
                    if (gtocraftfix$logInfoOk()) {
                        LOG.info("[craftfix] 保母餵料 {} x{}", key, got);
                    }
                }
                // 輸入補給：帳漂移可把任務輸入吃到不足一輪 → 每 tick 取料失敗、無聲凍結；
                // 短缺輸入從網路補進 CPU 庫存。waiting 空、或本輪餵料掛零時都要跑。
                if ((waiting.isEmpty() || handled == handledBefore) && handled < 16) {
                    // [v1.8.2] waiting 非空＝本單有東西在機器裡做（深鏈上游）→ 斷料點名凍結
                    acted |= gtocraftfix$topUpInputs(logic, storage, cluster, !waiting.isEmpty());
                }
                // 陳舊等待解鎖：可證明無用的 waitingFor 帳目才清（無剩餘任務吃、網內無貨、滯留逾時）
                if (!waiting.isEmpty()) {
                    acted |= gtocraftfix$clearStaleWaits(logic, storage, cluster, waiting, finalOut);
                }
                // 成品自我認領：產物繞過認領 hook 直進 CPU 庫存（機器自帶 ME 連接）→ 補帳＋重插觸發認領
                if (waiting.isEmpty()) {
                    acted |= gtocraftfix$selfClaimFinal(logic, cluster, finalOut);
                }
                // 孤兒任務重綁：樣板實例失聯（getProviders 空 → 空轉永凍）→ 換綁到同簽名現行樣板
                acted |= gtocraftfix$rebindOrphanTasks(logic, cluster);
                // [v1.2.1] 配額解鎖：GTO 配額扣到 0 會抹除該樣板定義 → 剩餘輪次永遠 INSUFFICIENT_PRIORITY；
                // 滯留 ≥30 秒且零進度 → 清空 job 配額帳退回原版行為
                acted |= gtocraftfix$unlockQuota(logic, cluster);
            } catch (Throwable t) {
                if (gtocraftfix$logErrOk()) { // [重檢7] 例外計數器獨立
                    LOG.error("[craftfix] 保母例外", t);
                }
            } finally {
                if (acted) {
                    // [重檢12] 標髒 cluster（ListCraftingInventory 不標髒，崩潰時 CPU 側已收的料不落盤＝蒸發），
                    // 並強制下一 tick 重算 currentlyCrafting
                    cluster.markDirty();
                    lastProcessedCraftingLogicChangeTick = -1;
                }
            }
        }
    }

    /**
     * 陳舊等待解鎖：認領只在插入事件觸發，帳漂移可留下「永遠等不到、也不再需要」的 waitingFor。
     * 四重證明缺一不可才清：滯留 ≥300 秒、網內無貨、剩餘任務不消費該 key、無機器 pending。
     * 訂單 job 整顆跳過；最終產物永不清。反射全軟失敗。
     */
    private boolean gtocraftfix$clearStaleWaits(appeng.crafting.execution.CraftingCpuLogic logic,
                                             appeng.api.storage.MEStorage storage,
                                             appeng.me.cluster.implementations.CraftingCPUCluster cluster,
                                             Set<AEKey> waiting, appeng.api.stacks.GenericStack finalOut) {
        boolean acted = false;
        try {
            if (gtocraftfix$fJob == null) {
                var fj = logic.getClass().getDeclaredField("job");
                fj.setAccessible(true);
                gtocraftfix$fJob = fj;
            }
            Object job = gtocraftfix$fJob.get(logic);
            if (job == null) {
                return false;
            }
            // [重檢9] 訂單守衛：清掉訂單依賴品的帳會讓訂單提早回報完成 → 整顆 CPU 跳過清帳
            if (gtocraftfix$isOrderJob(job)) {
                return false;
            }
            var sw = gtocraftfix$staleWait.computeIfAbsent(cluster, c -> new HashMap<>());
            if (sw.size() > 256) {
                // [重檢14] 只清本 cluster 子表（整張全清會把所有 cluster 年齡一起歸零）
                sw.clear();
            }
            int cleared = 0;
            for (var key : waiting) {
                if (cleared >= 2) {
                    break;
                }
                if (finalOut != null && key.equals(finalOut.what())) {
                    continue;
                }
                long want = logic.getWaitingFor(key);
                if (want <= 0) {
                    continue;
                }
                // 網內有貨 → 不是陳舊，餵料路徑會處理；年齡歸零
                if (storage.extract(key, 1, Actionable.SIMULATE, cluster.getSrc()) > 0) {
                    sw.remove(key);
                    continue;
                }
                Integer first = sw.putIfAbsent(key, gtocraftfix$tickCounter);
                // [重檢9] 門檻 6000 tick：GT 配方常數分鐘，太短會清掉仍在機器內加工的真在途帳
                if (first == null || gtocraftfix$tickCounter - first < 6000) {
                    continue;
                }
                // [重檢9] 證明 (4)：pendingRequests 非空＝仍有機器在做該 key → 不清（僅輔助，不可取代其餘證明）
                if (gtocraftfix$hasPendingRequest(logic, key)) {
                    continue;
                }
                // 證明 (3)：任何剩餘任務的任何可能輸入都不含該 key
                if (gtocraftfix$fTasks == null) {
                    var ft = job.getClass().getDeclaredField("tasks");
                    ft.setAccessible(true);
                    gtocraftfix$fTasks = ft;
                }
                Map<?, ?> tasks = (Map<?, ?>) gtocraftfix$fTasks.get(job);
                if (tasks == null) {
                    continue;
                }
                boolean consumed = false;
                for (var en : tasks.entrySet()) {
                    Object holder = en.getValue();
                    if (gtocraftfix$fHolderVal == null) {
                        var fv = holder.getClass().getField("value");
                        fv.setAccessible(true);
                        gtocraftfix$fHolderVal = fv;
                    }
                    if (gtocraftfix$fHolderVal.getLong(holder) <= 0) {
                        continue;
                    }
                    var pat = (IPatternDetails) en.getKey();
                    for (var input : pat.getInputs()) {
                        for (var poss : input.getPossibleInputs()) {
                            if (poss.what().equals(key)) {
                                consumed = true;
                                break;
                            }
                        }
                        if (consumed) {
                            break;
                        }
                    }
                    if (consumed) {
                        break;
                    }
                }
                if (consumed) {
                    continue;
                }
                if (gtocraftfix$fWaitingFor == null) {
                    var fw = job.getClass().getDeclaredField("waitingFor");
                    fw.setAccessible(true);
                    gtocraftfix$fWaitingFor = fw;
                }
                Object wf = gtocraftfix$fWaitingFor.get(job);
                if (wf instanceof appeng.crafting.inv.ListCraftingInventory li) {
                    li.extract(key, want, Actionable.MODULATE);
                } else if (wf != null) {
                    var lf = wf.getClass().getField("list");
                    lf.setAccessible(true);
                    ((appeng.api.stacks.KeyCounter) lf.get(wf)).remove(key, want);
                } else {
                    continue;
                }
                cleared++;
                acted = true;
                sw.remove(key);
                if (gtocraftfix$logInfoOk()) {
                    LOG.info("[craftfix] 解除陳舊等待 {} x{}（無剩餘任務需要、網內無貨、滯留 {} 秒）",
                            key, want, (gtocraftfix$tickCounter - first) / 20);
                }
            }
        } catch (Throwable t) {
            if (gtocraftfix$logErrOk()) { // [重檢7] 反射不可用等例外 → 記 log 後略過（下一輪再試）
                LOG.error("[craftfix] 陳舊解鎖例外", t);
            }
        }
        return acted;
    }

    /**
     * 孤兒任務重綁：任務綁「算料當下的樣板實例」，樣板重上傳／換機器後 getProviders(舊實例)
     * 永遠空 → executeCrafting 空轉不留痕。對供應器數 0 持續 60 秒的任務，換綁到「全部產出＋
     * 逐格輸入簽名（含替代品）相等」且有活供應器的現行樣板；先遷配額再換綁，已在清單則併次數。
     * 每輪最多 4 筆，反射軟失敗。
     */
    private boolean gtocraftfix$rebindOrphanTasks(appeng.crafting.execution.CraftingCpuLogic logic,
                                                  appeng.me.cluster.implementations.CraftingCPUCluster cluster) {
        boolean acted = false;
        try {
            if (gtocraftfix$fJob == null) {
                var fj = logic.getClass().getDeclaredField("job");
                fj.setAccessible(true);
                gtocraftfix$fJob = fj;
            }
            Object job = gtocraftfix$fJob.get(logic);
            if (job == null) {
                return false;
            }
            if (gtocraftfix$fTasks == null) {
                var ft = job.getClass().getDeclaredField("tasks");
                ft.setAccessible(true);
                gtocraftfix$fTasks = ft;
            }
            Map<?, ?> tasks = (Map<?, ?>) gtocraftfix$fTasks.get(job);
            if (tasks == null || tasks.isEmpty()) {
                return false;
            }
            var cs = (CraftingService) (Object) this;
            var orphanMap = gtocraftfix$orphanSince.computeIfAbsent(cluster, c -> new HashMap<>());
            java.util.List<Object[]> swaps = new java.util.ArrayList<>();
            for (var en : tasks.entrySet()) {
                if (swaps.size() >= 4) {
                    break;
                }
                Object holder = en.getValue();
                if (gtocraftfix$fHolderVal == null) {
                    var fv = holder.getClass().getField("value");
                    fv.setAccessible(true);
                    gtocraftfix$fHolderVal = fv;
                }
                if (gtocraftfix$fHolderVal.getLong(holder) <= 0) {
                    continue;
                }
                var pat = (IPatternDetails) en.getKey();
                if (cs.getProviders(pat).iterator().hasNext()) {
                    orphanMap.remove(pat); // 有活供應器 → 不是孤兒，觀測年齡歸零
                    continue;
                }
                // [重檢16] 滯留 60 秒才換綁：供應器 chunk 卸載／節點暫離的暫態 unmount 不該立刻觸發
                Integer firstOrphan = orphanMap.putIfAbsent(pat, gtocraftfix$tickCounter);
                if (firstOrphan == null || gtocraftfix$tickCounter - firstOrphan < 1200) {
                    continue;
                }
                var outK = pat.getPrimaryOutput().what();
                for (var cand : cs.getCraftingFor(outK)) {
                    if (cand.equals(pat) || !cs.getProviders(cand).iterator().hasNext()) {
                        continue;
                    }
                    // [重檢15] 全部產出逐項 key＋amount 相等：times 不換算，配比不同一律不綁
                    if (!gtocraftfix$sameOutputs(pat, cand)) {
                        continue;
                    }
                    var pi = pat.getInputs();
                    var cin = cand.getInputs();
                    if (pi.length != cin.length) {
                        continue;
                    }
                    boolean same = true;
                    for (int i = 0; i < pi.length; i++) {
                        var a = pi[i].getPossibleInputs();
                        var b = cin[i].getPossibleInputs();
                        if (a.length == 0 || b.length == 0 || !a[0].what().equals(b[0].what())
                                || a[0].amount() * pi[i].getMultiplier() != b[0].amount() * cin[i].getMultiplier()
                                // [重檢15] 替代品 key 集合也要相等——否則取料語意換綁後漂移
                                || !gtocraftfix$samePossibleKeySet(a, b)) {
                            same = false;
                            break;
                        }
                    }
                    if (same) {
                        swaps.add(new Object[] { pat, cand });
                        break;
                    }
                }
            }
            for (var sw : swaps) {
                @SuppressWarnings("unchecked")
                var tm = (Map<Object, Object>) tasks;
                var oldPat = (IPatternDetails) sw[0];
                var cand2 = (IPatternDetails) sw[1];
                // [重檢2] 先遷配額再換綁：executeCrafting 以樣板 definition 查配額，查無即
                // INSUFFICIENT_PRIORITY 跳過任務（不遷移＝玩家單永凍）；遷移失敗放棄本筆換綁。
                if (!gtocraftfix$migrateAllocations(job, oldPat, cand2)) {
                    continue;
                }
                Object holder = tm.remove(oldPat);
                if (holder == null) {
                    continue;
                }
                long times = gtocraftfix$fHolderVal.getLong(holder);
                Object existing = tm.get(cand2);
                if (existing != null) {
                    gtocraftfix$fHolderVal.setLong(existing, gtocraftfix$fHolderVal.getLong(existing) + times);
                } else {
                    tm.put(cand2, holder);
                }
                orphanMap.remove(oldPat);
                acted = true;
                if (gtocraftfix$logInfoOk()) {
                    LOG.info("[craftfix] 重綁孤兒任務 {} x{}（舊樣板失聯，已換綁現行樣板＋配額遷移）",
                            oldPat.getPrimaryOutput().what(), times);
                }
            }
        } catch (Throwable t) {
            if (gtocraftfix$logErrOk()) { // [重檢7]
                LOG.error("[craftfix] 重綁例外", t);
            }
        }
        return acted;
    }

    /** [重檢14] 成品滯留快照：cluster（弱鍵）→ 上次觀察到的 CPU 內成品數量（變動＝有進度，年齡歸零）。 */
    private Map<CraftingCPUCluster, Long> gtocraftfix$staleHeld = new WeakHashMap<>();

    /** [重檢14] 欄位普查已做過的 cluster（每場遊戲每 cluster 只倒一次）；弱集合防 cluster 重建洩漏。 */
    private Set<CraftingCPUCluster> gtocraftfix$censusDone =
            java.util.Collections.newSetFromMap(new WeakHashMap<>());

    /** 反射倒出物件全類別鏈的實例欄位（名稱=精簡值）；集合印型別(大小)，其餘 toString 截 60 字。 */
    private static String gtocraftfix$census(Object o) {
        var sb = new StringBuilder();
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (var f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Object v = f.get(o);
                    String vs;
                    if (v == null) {
                        vs = "null";
                    } else if (v instanceof Number || v instanceof Boolean) {
                        vs = String.valueOf(v);
                    } else if (v instanceof appeng.api.stacks.KeyCounter kc) {
                        vs = "KeyCounter(" + kc.size() + ")";
                    } else if (v instanceof Map<?, ?> mp) {
                        vs = v.getClass().getSimpleName() + "(" + mp.size() + ")";
                    } else if (v instanceof java.util.Collection<?> cl) {
                        vs = v.getClass().getSimpleName() + "(" + cl.size() + ")";
                    } else {
                        vs = String.valueOf(v);
                        if (vs.length() > 60) {
                            vs = vs.substring(0, 60) + "…";
                        }
                    }
                    sb.append(f.getName()).append('=').append(vs).append("; ");
                } catch (Throwable ignored) {
                }
            }
        }
        return sb.toString();
    }

    /** CPU 內部庫存（CraftingCpuLogic.inventory）反射存取；不可用回 null。 */
    private appeng.crafting.inv.ListCraftingInventory gtocraftfix$invOf(
            appeng.crafting.execution.CraftingCpuLogic logic) {
        try {
            if (gtocraftfix$fInv == null) {
                var fi = appeng.crafting.execution.CraftingCpuLogic.class.getDeclaredField("inventory");
                fi.setAccessible(true);
                gtocraftfix$fInv = fi;
            }
            return (appeng.crafting.inv.ListCraftingInventory) gtocraftfix$fInv.get(logic);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- [重檢7] log 分流：info 200／warn 50／例外 20（不共用額度），每 5 分鐘窗重置 ----
    private static boolean gtocraftfix$logOk(AtomicInteger counter, int cap) {
        long now = System.currentTimeMillis();
        if (now - gtocraftfix$logWindow > 300_000L) {
            gtocraftfix$logWindow = now;
            gtocraftfix$logInfo.set(0);
            gtocraftfix$logWarn.set(0);
            gtocraftfix$logErr.set(0);
        }
        return counter.incrementAndGet() <= cap;
    }

    private static boolean gtocraftfix$logInfoOk() {
        return gtocraftfix$logOk(gtocraftfix$logInfo, 200);
    }

    private static boolean gtocraftfix$logWarnOk() {
        return gtocraftfix$logOk(gtocraftfix$logWarn, 50);
    }

    private static boolean gtocraftfix$logErrOk() {
        return gtocraftfix$logOk(gtocraftfix$logErr, 20);
    }

    /** [重檢14] 拒收記憶 per-cluster 讀取（key → 記錄 tick；無記錄回 null）。 */
    private Integer gtocraftfix$refusedGet(CraftingCPUCluster cluster, AEKey key) {
        var m = gtocraftfix$feedRefused.get(cluster);
        return m == null ? null : m.get(key);
    }

    /** [重檢14] 拒收記憶 per-cluster 寫入（子表 >64 才清、只清本 cluster）。 */
    private void gtocraftfix$refusedPut(CraftingCPUCluster cluster, AEKey key) {
        var m = gtocraftfix$feedRefused.computeIfAbsent(cluster, c -> new HashMap<>());
        if (m.size() > 64) {
            m.clear();
        }
        m.put(key, gtocraftfix$tickCounter);
    }

    /** [重檢3] 記錄「帳已被 GTO insert 銷掉、物理留在 CPU 庫存」的成品量（job 弱鍵，完單隨 GC 歸零）。 */
    private void gtocraftfix$claimedAdd(appeng.crafting.execution.CraftingCpuLogic logic, long amount) {
        try {
            if (amount <= 0) {
                return;
            }
            if (gtocraftfix$fJob == null) {
                var fj = logic.getClass().getDeclaredField("job");
                fj.setAccessible(true);
                gtocraftfix$fJob = fj;
            }
            Object job = gtocraftfix$fJob.get(logic);
            if (job != null) {
                gtocraftfix$claimedFinalHeld.computeIfAbsent(job, j -> new long[1])[0] += amount;
            }
        } catch (Throwable ignored) {
        }
    }

    /** [重檢3] 讀取已銷帳留庫量（無記錄回 0）。 */
    private long gtocraftfix$claimedGet(Object job) {
        var a = job == null ? null : gtocraftfix$claimedFinalHeld.get(job);
        return a == null ? 0 : a[0];
    }

    /** [重檢13] 剩餘任務（times>0）對某 key 的需求總量，輪數**不截斷**——[v1.8.3] 總成滿補塞進的
     *  備料可遠超 cap，截斷會把備料誤判成可認領超額、每秒重複燒 waitingFor 帳（液態氦實錄）。
     *  失敗回 0（回到舊行為＝可全額認領）。 */
    private long gtocraftfix$remainingDemand(appeng.crafting.execution.CraftingCpuLogic logic, AEKey key) {
        try {
            if (gtocraftfix$fJob == null) {
                var fj = logic.getClass().getDeclaredField("job");
                fj.setAccessible(true);
                gtocraftfix$fJob = fj;
            }
            Object job = gtocraftfix$fJob.get(logic);
            if (job == null) {
                return 0;
            }
            if (gtocraftfix$fTasks == null) {
                var ft = job.getClass().getDeclaredField("tasks");
                ft.setAccessible(true);
                gtocraftfix$fTasks = ft;
            }
            Map<?, ?> tasks = (Map<?, ?>) gtocraftfix$fTasks.get(job);
            if (tasks == null) {
                return 0;
            }
            long demand = 0;
            for (var en : tasks.entrySet()) {
                Object holder = en.getValue();
                if (gtocraftfix$fHolderVal == null) {
                    var fv = holder.getClass().getField("value");
                    fv.setAccessible(true);
                    gtocraftfix$fHolderVal = fv;
                }
                long times = gtocraftfix$fHolderVal.getLong(holder);
                if (times <= 0) {
                    continue;
                }
                long capped = times; // [v1.8.3] 不截斷，見 javadoc
                var pat = (IPatternDetails) en.getKey();
                for (var input : pat.getInputs()) {
                    for (var poss : input.getPossibleInputs()) {
                        if (poss.what().equals(key)) {
                            try {
                                demand = Math.addExact(demand, Math.multiplyExact(
                                        Math.multiplyExact(poss.amount(), input.getMultiplier()), capped));
                            } catch (ArithmeticException e) {
                                return Long.MAX_VALUE / 4;
                            }
                            break;
                        }
                    }
                }
            }
            return demand;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /**
     * [v1.2.1] 配額死鎖解鎖：GTO 配額扣到 0 時 purgePatternEverywhere 抹除該樣板定義，之後同樣板
     * 剩餘輪次永遠 INSUFFICIENT_PRIORITY（料在手上卻不推）。結果含該碼、持續 ≥30 秒且全 job 零進度
     * → 清空配額帳（名額是優化不是正確性條件，清了＝原版行為）。反射全軟失敗。
     */
    private boolean gtocraftfix$unlockQuota(appeng.crafting.execution.CraftingCpuLogic logic,
                                            appeng.me.cluster.implementations.CraftingCPUCluster cluster) {
        try {
            Object job = gtocraftfix$jobOf(logic);
            if (job == null) {
                gtocraftfix$quotaStuck.remove(cluster);
                return false;
            }
            if (gtocraftfix$fAlloc == null && !gtocraftfix$fAllocTried) {
                gtocraftfix$fAllocTried = true;
                try {
                    var f = job.getClass().getDeclaredField("allocations");
                    f.setAccessible(true);
                    gtocraftfix$fAlloc = f;
                } catch (NoSuchFieldException e) {
                    gtocraftfix$fAllocMissing = true;
                }
            }
            if (gtocraftfix$fAlloc == null) {
                return false;
            }
            Object allocObj = gtocraftfix$fAlloc.get(job);
            if (!(allocObj instanceof Map<?, ?> am) || am.isEmpty()) {
                gtocraftfix$quotaStuck.remove(cluster);
                return false;
            }
            Object resultsObj;
            try {
                resultsObj = logic.getClass().getMethod("getCraftingResults").invoke(logic);
            } catch (Throwable t) {
                return false;
            }
            if (!(resultsObj instanceof com.google.common.collect.SetMultimap)) {
                return false;
            }
            @SuppressWarnings("rawtypes")
            com.google.common.collect.SetMultimap rm = (com.google.common.collect.SetMultimap) resultsObj;
            if (gtocraftfix$fTasks == null) {
                var ft = job.getClass().getDeclaredField("tasks");
                ft.setAccessible(true);
                gtocraftfix$fTasks = ft;
            }
            Map<?, ?> ts = (Map<?, ?>) gtocraftfix$fTasks.get(job);
            if (ts == null || ts.isEmpty()) {
                gtocraftfix$quotaStuck.remove(cluster);
                return false;
            }
            long totalRounds = 0;
            AEKey stuckOut = null;
            for (var en : ts.entrySet()) {
                Object holder = en.getValue();
                if (gtocraftfix$fHolderVal == null) {
                    var fv = holder.getClass().getField("value");
                    fv.setAccessible(true);
                    gtocraftfix$fHolderVal = fv;
                }
                long v = gtocraftfix$fHolderVal.getLong(holder);
                if (v <= 0) {
                    continue;
                }
                totalRounds += v;
                var pat = (IPatternDetails) en.getKey();
                var outK = pat.getPrimaryOutput().what();
                // 用字串比對避免 compile 期依賴 gtolib 的 PushResult enum
                if (stuckOut == null && String.valueOf(rm.get(outK)).contains("INSUFFICIENT_PRIORITY")) {
                    stuckOut = outK;
                }
            }
            if (stuckOut == null) {
                gtocraftfix$quotaStuck.remove(cluster);
                return false;
            }
            Object[] st = gtocraftfix$quotaStuck.get(cluster);
            Object stJob = st == null ? null : ((java.lang.ref.WeakReference<?>) st[0]).get();
            if (st == null || stJob != job || (Long) st[2] != totalRounds) {
                // [重檢18] 首見／換單／有進度 → 重新計時；掛號當下先拔該 key 的 INSUFFICIENT_PRIORITY
                // 殘留——活的配額鎖每 tick 會被 GTO 重寫回來，陳舊殘留拔了就不回來。
                gtocraftfix$removeResult(rm, stuckOut, "INSUFFICIENT_PRIORITY");
                gtocraftfix$quotaStuck.put(cluster, new Object[] {
                        new java.lang.ref.WeakReference<>(job), (long) gtocraftfix$tickCounter, totalRounds });
                return false;
            }
            if (gtocraftfix$tickCounter - (Long) st[1] < 600) {
                return false;
            }
            int nKeys = am.size();
            ((Map<?, ?>) am).clear();
            gtocraftfix$quotaStuck.remove(cluster);
            LOG.warn("[craftfix] 配額解鎖 {}：INSUFFICIENT_PRIORITY 滯留 {} 秒、零進度 → 清空優先名額帳（{} key，退回原版無名額行為）",
                    stuckOut, (gtocraftfix$tickCounter - (Long) st[1]) / 20, nKeys);
            return true;
        } catch (Throwable t) {
            if (gtocraftfix$logErrOk()) {
                LOG.error("[craftfix] 配額解鎖例外", t);
            }
            return false;
        }
    }

    /** [v1.3.5] 缺料量人話化：液體 mB → B（去尾零）、物品 → 個。 */
    private static String gtocraftfix$fmtAmt(AEKey key, long amount) {
        try {
            if (key instanceof appeng.api.stacks.AEFluidKey) {
                return new java.math.BigDecimal(amount).movePointLeft(3)
                        .stripTrailingZeros().toPlainString() + " B";
            }
        } catch (Throwable ignored) {
        }
        return amount + " 個";
    }

    /** [重檢19] 讀本 cluster 現任 job 的成品基線（trackJob slot9）；未知回 -1。 */
    private long gtocraftfix$finalBaseline(appeng.me.cluster.implementations.CraftingCPUCluster cluster) {
        try {
            Object[] t = gtocraftfix$jobTrack.get(cluster);
            if (t != null && t.length > 9 && t[9] instanceof Long b) {
                return b;
            }
        } catch (Throwable ignored) {
        }
        return -1L;
    }

    /** [重檢18] 從 craftingResults（raw SetMultimap 活視圖）移除含指定字樣的結果項；全軟失敗。 */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static void gtocraftfix$removeResult(com.google.common.collect.SetMultimap rm, Object key, String marker) {
        try {
            var set = rm.get(key);
            Object hit = null;
            for (Object o : set) {
                if (String.valueOf(o).contains(marker)) {
                    hit = o;
                    break;
                }
            }
            if (hit != null) {
                rm.remove(key, hit);
            }
        } catch (Throwable ignored) {
        }
    }

    private static volatile Method gtocraftfix$mIsPaused;
    private static volatile boolean gtocraftfix$mIsPausedTried;

    /** [v1.7.1] CPU 是否被玩家暫停（paused 時執行器不跑、零進度是預期）；
     *  NoSuchMethod 永久 fail-open（WARN 一次），其他例外允許下次重試。 */
    private static boolean gtocraftfix$isPausedCpu(appeng.crafting.execution.CraftingCpuLogic logic) {
        try {
            var m = gtocraftfix$mIsPaused;
            if (m == null) {
                if (gtocraftfix$mIsPausedTried) {
                    return false;
                }
                try {
                    m = logic.getClass().getMethod("isPaused");
                } catch (NoSuchMethodException e) {
                    gtocraftfix$mIsPausedTried = true;
                    LOG.warn("[craftfix] isPaused API 不存在（{}）→ 暫停偵測停用（fail-open）",
                            logic.getClass().getName());
                    return false;
                }
                gtocraftfix$mIsPaused = m;
            }
            return Boolean.TRUE.equals(m.invoke(logic));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** [v1.7.1] PushResult 人話化（正碼＝成功、負碼＝失敗；BREAK 是「推送完成」成功收尾碼）。
     *  括號保留原碼供既有 grep；\b 邊界防子串誤傷與二次替換（底線是 word char）。 */
    private static String gtocraftfix$zhPush(String s) {
        if (s == null) {
            return null;
        }
        return s.replaceAll("\\bBREAK_TASK_LOOP\\b", "本tick推滿(BREAK_TASK_LOOP)")
                .replaceAll("\\bBREAK\\b", "已派完(BREAK)")
                .replaceAll("\\bSUCCESS\\b", "推送中(SUCCESS)")
                .replaceAll("\\bNOWHERE_TO_PUSH\\b", "機器滿載(NOWHERE_TO_PUSH)")
                .replaceAll("\\bPATTERN_PROVIDER_LOCKED\\b", "供應器鎖定(PATTERN_PROVIDER_LOCKED)")
                .replaceAll("\\bPATTERN_DOES_NOT_EXIST\\b", "樣板不存在(PATTERN_DOES_NOT_EXIST)")
                .replaceAll("\\bGRID_NODE_MISSING\\b", "節點離線(GRID_NODE_MISSING)")
                .replaceAll("\\bREJECTED\\b", "讀不到相鄰機器(REJECTED)")
                .replaceAll("\\bINSUFFICIENT_PRIORITY\\b", "配額拒推(INSUFFICIENT_PRIORITY)");
    }

    /** [v1.2.0] logic.job 反射直讀（懶初始化共用 fJob）；全軟失敗回 null。 */
    private static Object gtocraftfix$jobOf(appeng.crafting.execution.CraftingCpuLogic logic) {
        try {
            if (gtocraftfix$fJob == null) {
                var fj = logic.getClass().getDeclaredField("job");
                fj.setAccessible(true);
                gtocraftfix$fJob = fj;
            }
            return gtocraftfix$fJob.get(logic);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** [v1.2.0] job.remainingAmount（成品交付帳）反射直讀；讀不到回 -1。 */
    private static long gtocraftfix$remainingOf(Object job) {
        try {
            if (gtocraftfix$fRemaining == null) {
                if (gtocraftfix$fRemainingTried) {
                    return -1L;
                }
                gtocraftfix$fRemainingTried = true;
                var f = job.getClass().getDeclaredField("remainingAmount");
                f.setAccessible(true);
                gtocraftfix$fRemaining = f;
            }
            return gtocraftfix$fRemaining.getLong(job);
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    /** [v1.2.0] 剩餘任務總輪數（Σ tasks 值）；讀不到回 -1。 */
    private static long gtocraftfix$totalTaskRounds(Object job) {
        try {
            if (gtocraftfix$fTasks == null) {
                var ft = job.getClass().getDeclaredField("tasks");
                ft.setAccessible(true);
                gtocraftfix$fTasks = ft;
            }
            Map<?, ?> ts = (Map<?, ?>) gtocraftfix$fTasks.get(job);
            if (ts == null) {
                return -1L;
            }
            long sum = 0;
            for (var en : ts.entrySet()) {
                Object holder = en.getValue();
                if (gtocraftfix$fHolderVal == null) {
                    var fv = holder.getClass().getField("value");
                    fv.setAccessible(true);
                    gtocraftfix$fHolderVal = fv;
                }
                long v = gtocraftfix$fHolderVal.getLong(holder);
                if (v > 0) {
                    sum += v;
                }
            }
            return sum;
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    /**
     * [v1.2.0] 完單法醫：每輪記錄 cluster 現任 job 的（交付帳剩、任務剩輪）；job 消失/更換當下
     * 欠帳 >0 印「完單快照」，訂單交付帳變動也印。純記錄，不動任何帳。
     */
    private void gtocraftfix$trackJob(appeng.me.cluster.implementations.CraftingCPUCluster cluster,
                                      appeng.crafting.execution.CraftingCpuLogic logic, Object jobNow,
                                      appeng.api.storage.MEStorage storage) {
        try {
            Object[] prev = gtocraftfix$jobTrack.get(cluster);
            Object prevJob = prev == null ? null : ((java.lang.ref.WeakReference<?>) prev[0]).get();
            // [重檢18] prevJob 弱參照被 GC 清掉 ⟺ job 確實換過（單比 jobNow != prevJob 在 null==null 時誤判沒換單）
            if (prev != null && (jobNow != prevJob || prevJob == null)) {
                long lr = (Long) prev[2];
                long rounds = (Long) prev[3];
                // [重檢18] 死亡時刻現讀 link 狀態：取消→job 死亡同 tick 完成，slot4 快照對 cancel 死法恆 false；
                // link 物件受強持有，死後仍可靠讀。
                boolean liveCanceled = prev.length > 4 && Boolean.TRUE.equals(prev[4]);
                Object lref = prev.length > 5 && prev[5] instanceof java.lang.ref.WeakReference<?> w5
                        ? w5.get() : null;
                if (lref instanceof appeng.api.networking.crafting.ICraftingLink lcl) {
                    liveCanceled = lcl.isCanceled();
                }
                if ((lr > 0 || rounds > 0) && gtocraftfix$logInfoOk()) {
                    LOG.info("[craftfix] 完單快照 out={}：job 消失/更換當下交付帳剩 {}、任務剩 {} 輪（{}）",
                            prev[1], lr, rounds,
                            liveCanceled ? "link 已取消→撤單/玩家取消" : "link 未取消→執行器自行完單");
                }
                // [v1.3.1] 訂單提前收單提示：GTO 在單據全數推入機器後即收單，成品稍後才落 ME
                // ——廣播防玩家重複下單。[重檢18] 取消死法不播（在途未推部分不會補做）；10 分鐘冷卻。
                long pinf = prev.length > 6 && prev[6] instanceof Long l6 ? l6 : 0L;
                boolean pOrder = prev.length > 8 && Boolean.TRUE.equals(prev[8]);
                if (pOrder && lr > 0 && pinf > 0 && !liveCanceled) {
                    Integer lastNotice = gtocraftfix$orderNoticeTick.get(cluster);
                    if (lastNotice == null || gtocraftfix$tickCounter - lastNotice >= 12000) {
                        gtocraftfix$orderNoticeTick.put(cluster, gtocraftfix$tickCounter);
                        var server = grid.getPivot() != null && grid.getPivot().getLevel() != null
                                ? grid.getPivot().getLevel().getServer()
                                : null;
                        if (server != null) {
                            var name = prev.length > 7 && prev[7] instanceof AEKey k7
                                    ? k7.getDisplayName()
                                    : net.minecraft.network.chat.Component.literal(String.valueOf(prev[1]));
                            server.getPlayerList().broadcastSystemMessage(
                                    net.minecraft.network.chat.Component.literal("[合成修復] 訂單提前收單：")
                                            .append(name)
                                            .append(net.minecraft.network.chat.Component.literal(
                                                    " 約 " + Math.min(lr, pinf)
                                                            + " 份在途，機器做完會直接進 ME 儲存（勿重複下單）")),
                                    false);
                        }
                    }
                }
                // [v1.6.1] 非訂單完單短交「監看後補」：GTO 預測性收單攔不到（finishJob 在 gtolib
                // 閉源子類），且帳差多半稍後自行落庫、立即補差額＝重複生產。改登記監看：5 分鐘內
                // 累計到貨，期滿未到的餘額才是真損失。撤單死法不看；同 key 監看中跳過（寧漏勿重）。
                if (!pOrder && !liveCanceled && lr > 0 && prev.length > 7 && prev[7] instanceof AEKey outK
                        && lref instanceof appeng.api.networking.crafting.ICraftingLink plink
                        && Boolean.TRUE.equals(gtocraftfix$playerLinks.get(plink))) {
                    // 只看玩家單；先扣完單瞬間網路現貨（storeItems 已退庫的帳差在這裡歸零）——寧漏勿重
                    long s0 = Math.max(0, grid.getStorageService().getCachedInventory().get(outK));
                    long need = lr - s0;
                    boolean dup = false;
                    for (var w : gtocraftfix$shortWatch) {
                        if (outK.equals(w[0])) {
                            dup = true;
                            break;
                        }
                    }
                    if (need > 0 && !dup && gtocraftfix$shortWatch.size() < 8) {
                        gtocraftfix$shortWatch.add(new Object[] { outK, need, 0L, s0, gtocraftfix$tickCounter });
                        if (gtocraftfix$logInfoOk()) {
                            LOG.info("[craftfix] 完單短交監看 out={}：帳差 {}、現貨已抵 {}、應到 {}（在途取樣 {}）——5 分鐘內到帳即結案",
                                    prev[1], lr, s0, need, pinf);
                        }
                    }
                }
            }
            if (jobNow == null) {
                if (prev != null) {
                    gtocraftfix$jobTrack.remove(cluster);
                }
                return;
            }
            long remaining = gtocraftfix$remainingOf(jobNow);
            long rounds = gtocraftfix$totalTaskRounds(jobNow);
            var fo = logic.getFinalJobOutput();
            String outDesc = fo == null ? "?" : String.valueOf(fo.what());
            long inflight = fo == null ? 0 : logic.getWaitingFor(fo.what());
            if (prev != null && prevJob == jobNow) {
                long lr = (Long) prev[2];
                if (remaining != lr && gtocraftfix$isOrderJob(jobNow) && gtocraftfix$logInfoOk()) {
                    LOG.info("[craftfix] 訂單交付帳 {}：remaining {}→{}（在途 {}、任務剩 {} 輪）",
                            outDesc, lr, remaining, inflight, rounds);
                }
            }
            // [v1.2.1] link 取消偵測：requester 撤單當下警告一次——GTO 下一 tick 會棄殺整張 job
            boolean linkDead = gtocraftfix$linkCanceled(jobNow);
            if (linkDead && !(prev != null && prevJob == jobNow && prev.length > 4
                    && Boolean.TRUE.equals(prev[4]))) {
                LOG.warn("[craftfix] link 已取消 out={}：requester 撤單/卸載——GTO 將棄殺整張 job（交付帳剩 {}、任務剩 {} 輪）",
                        outDesc, remaining, rounds);
            }
            Object linkObj = gtocraftfix$linkObjOf(jobNow); // 法醫用
            // [重檢19] slot9 成品基線：job 首見時網路已有的成品量；同 job 沿用首見值，不隨庫存波動
            long baseline;
            if (prev != null && prevJob == jobNow && prev.length > 9 && prev[9] instanceof Long b9) {
                baseline = b9;
            } else {
                baseline = fo == null ? -1L
                        : storage.extract(fo.what(), Long.MAX_VALUE / 4,
                                Actionable.SIMULATE, cluster.getSrc());
            }
            gtocraftfix$jobTrack.put(cluster, new Object[] {
                    new java.lang.ref.WeakReference<>(jobNow), outDesc, remaining, rounds, linkDead,
                    linkObj == null ? null : new java.lang.ref.WeakReference<>(linkObj),
                    inflight, fo == null ? null : fo.what(),
                    gtocraftfix$isOrderJob(jobNow), // [v1.3.1] slot8：死後判訂單用
                    baseline }); // [重檢19] slot9
        } catch (Throwable ignored) {
        }
    }

    // [v1.3.0] deliverStranded 已移除：requester 為存量水位制，成品落 ME 儲存即正確歸宿
    //（且舊實作在 link.insert 拋例外時未回補已抽出的貨）。

    /** [v1.2.2] job.link 物件反射直讀；讀不到回 null。 */
    private static Object gtocraftfix$linkObjOf(Object job) {
        try {
            if (gtocraftfix$fLink == null) {
                if (gtocraftfix$fLinkTried) {
                    return null;
                }
                gtocraftfix$fLinkTried = true;
                var f = job.getClass().getDeclaredField("link");
                f.setAccessible(true);
                gtocraftfix$fLink = f;
            }
            return gtocraftfix$fLink.get(job);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static volatile java.lang.reflect.Field gtocraftfix$fLink;
    private static volatile boolean gtocraftfix$fLinkTried;

    /** [v1.2.1] job.link.isCanceled() 反射直讀；讀不到回 false。 */
    private static boolean gtocraftfix$linkCanceled(Object job) {
        try {
            if (gtocraftfix$fLink == null) {
                if (gtocraftfix$fLinkTried) {
                    return false;
                }
                gtocraftfix$fLinkTried = true;
                var f = job.getClass().getDeclaredField("link");
                f.setAccessible(true);
                gtocraftfix$fLink = f;
            }
            Object l = gtocraftfix$fLink.get(job);
            return l instanceof appeng.api.networking.crafting.ICraftingLink cl && cl.isCanceled();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** [重檢9] job.isOrder 反射直讀；[重檢18] 反射失效走 registry id 後援（訂單守衛不可被一次反射失敗無聲全滅）。 */
    private static boolean gtocraftfix$isOrderJob(Object job) {
        try {
            if (gtocraftfix$fIsOrder == null) {
                if (gtocraftfix$fIsOrderTried) {
                    return gtocraftfix$isOrderByOutput(job);
                }
                gtocraftfix$fIsOrderTried = true;
                var f = job.getClass().getDeclaredField("isOrder");
                f.setAccessible(true);
                gtocraftfix$fIsOrder = f;
            }
            return gtocraftfix$fIsOrder.getBoolean(job);
        } catch (Throwable ignored) {
            return gtocraftfix$isOrderByOutput(job);
        }
    }

    /** [重檢18] isOrder 後援：finalOutput 的 registry id 比對（gtocore:order／temporary_order）。 */
    private static boolean gtocraftfix$isOrderByOutput(Object job) {
        try {
            var f = job.getClass().getDeclaredField("finalOutput");
            f.setAccessible(true);
            Object fo = f.get(job);
            if (fo instanceof appeng.api.stacks.GenericStack gs
                    && gs.what() instanceof appeng.api.stacks.AEItemKey ik) {
                var id = ik.getId().toString();
                return id.equals("gtocore:order") || id.equals("gtocore:temporary_order");
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** [重檢9] OCCL.getPendingRequests(key) 非空＝仍有機器在做；不可用（非 GTO 執行器）回 false。 */
    private static boolean gtocraftfix$hasPendingRequest(appeng.crafting.execution.CraftingCpuLogic logic, AEKey key) {
        try {
            if (gtocraftfix$mPendReq == null) {
                if (gtocraftfix$mPendReqTried) {
                    return false;
                }
                gtocraftfix$mPendReqTried = true;
                gtocraftfix$mPendReq = logic.getClass().getMethod("getPendingRequests", AEKey.class);
            }
            Object r = gtocraftfix$mPendReq.invoke(logic, key);
            return r instanceof java.util.Collection<?> c && !c.isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** [重檢15] 兩樣板全部產出逐項 key＋amount 相等（含主產物量；配比不同一律不換綁）。 */
    private static boolean gtocraftfix$sameOutputs(IPatternDetails a, IPatternDetails b) {
        var ao = a.getOutputs();
        var bo = b.getOutputs();
        if (ao.length != bo.length) {
            return false;
        }
        for (int i = 0; i < ao.length; i++) {
            if (!ao[i].what().equals(bo[i].what()) || ao[i].amount() != bo[i].amount()) {
                return false;
            }
        }
        return true;
    }

    /** [重檢15] 兩輸入槽的 possibleInputs key 集合相等（替代品授權一致才可換綁）。 */
    private static boolean gtocraftfix$samePossibleKeySet(appeng.api.stacks.GenericStack[] a,
                                                          appeng.api.stacks.GenericStack[] b) {
        var sa = new HashSet<AEKey>();
        for (var g : a) {
            sa.add(g.what());
        }
        var sb = new HashSet<AEKey>();
        for (var g : b) {
            sb.add(g.what());
        }
        return sa.equals(sb);
    }

    /** [重檢2] 把 job.allocations 裡掛在舊樣板 definition 的配額條目移到新樣板（quota 相加）。
     *  配額為空或欄位不存在回 true；反射失敗回 false（呼叫端放棄換綁——保持孤兒勝過換綁後永凍）。 */
    private static boolean gtocraftfix$migrateAllocations(Object job, IPatternDetails oldPat, IPatternDetails cand) {
        try {
            if (gtocraftfix$fAlloc == null && !gtocraftfix$fAllocTried) {
                gtocraftfix$fAllocTried = true;
                try {
                    var f = job.getClass().getDeclaredField("allocations");
                    f.setAccessible(true);
                    gtocraftfix$fAlloc = f;
                } catch (NoSuchFieldException nsf) {
                    gtocraftfix$fAllocMissing = true; // 欄位不存在＝此版無配額機制 → 換綁安全
                }
            }
            if (gtocraftfix$fAlloc == null) {
                return gtocraftfix$fAllocMissing;
            }
            Object allocObj = gtocraftfix$fAlloc.get(job);
            if (allocObj == null) {
                return true;
            }
            if (!(allocObj instanceof Map<?, ?> alloc)) {
                return false; // 型別不明 → 不敢動
            }
            if (alloc.isEmpty()) {
                return true;
            }
            var oldDef = oldPat.getDefinition();
            for (var e : alloc.entrySet()) {
                if (!(e.getValue() instanceof Map<?, ?>)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                var inner = (Map<Object, Long>) e.getValue();
                java.util.List<Object> oldKeys = null;
                long quota = 0;
                for (var ie : inner.entrySet()) {
                    if (ie.getKey() instanceof IPatternDetails ip && oldDef.equals(ip.getDefinition())) {
                        if (oldKeys == null) {
                            oldKeys = new java.util.ArrayList<>(2);
                        }
                        oldKeys.add(ie.getKey());
                        quota += ie.getValue();
                    }
                }
                if (oldKeys == null) {
                    continue;
                }
                for (var k : oldKeys) {
                    inner.remove(k);
                }
                inner.merge(cand, quota, Long::sum);
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 成品自我認領：產出機器自帶 ME 連接時產物繞過認領 hook 直進 CPU 庫存——記不了帳、waitingFor 空、
     * 其餘救援全無感。滯留 ≥60 秒且數量不動 → 補 waitingFor 假帳、走正規 insert 觸發認領。
     * GTO insert 對成品「先按全額銷帳、後問 link」：認領量須扣已銷帳留庫與剩餘任務需求，
     * 殘額送網路而非留庫（防同批物品重複燒帳）。反射全軟失敗。
     */
    private boolean gtocraftfix$selfClaimFinal(appeng.crafting.execution.CraftingCpuLogic logic,
                                            appeng.me.cluster.implementations.CraftingCPUCluster cluster,
                                            appeng.api.stacks.GenericStack finalOut) {
        try {
            if (finalOut == null) {
                return false;
            }
            if (gtocraftfix$fInv == null) {
                var fi = appeng.crafting.execution.CraftingCpuLogic.class.getDeclaredField("inventory");
                fi.setAccessible(true);
                gtocraftfix$fInv = fi;
            }
            var inv = (appeng.crafting.inv.ListCraftingInventory) gtocraftfix$fInv.get(logic);
            if (inv == null) {
                return false;
            }
            var fk = finalOut.what();
            long heldFin = inv.list.get(fk);
            if (heldFin <= 0) {
                gtocraftfix$finalClaimTick.remove(cluster);
                gtocraftfix$staleHeld.remove(cluster);
                return false;
            }
            // [重檢5] 曾被 link 全拒（standalone 玩家單恆回 0）→ 10 分鐘內不重試（每次重試都重燒帳）
            Integer ru = gtocraftfix$refusedGet(cluster, fk);
            if (ru != null && gtocraftfix$tickCounter - ru < 12000) {
                return false;
            }
            // 數量有變動＝機器還在產出 → 年齡歸零（[重檢14] 計時與陳舊等待分表）
            Long prev = gtocraftfix$staleHeld.put(cluster, heldFin);
            if (prev == null || prev != heldFin) {
                gtocraftfix$finalClaimTick.put(cluster, gtocraftfix$tickCounter);
                return false;
            }
            Integer first = gtocraftfix$finalClaimTick.get(cluster);
            if (first == null) {
                gtocraftfix$finalClaimTick.put(cluster, gtocraftfix$tickCounter);
                return false;
            }
            if (gtocraftfix$tickCounter - first < 1200) {
                return false;
            }
            // 補帳：認領路徑要求 waitingFor 有這筆，先塞回去
            if (gtocraftfix$fJob == null) {
                var fj = logic.getClass().getDeclaredField("job");
                fj.setAccessible(true);
                gtocraftfix$fJob = fj;
            }
            Object job = gtocraftfix$fJob.get(logic);
            if (job == null) {
                return false;
            }
            if (gtocraftfix$isOrderJob(job) && gtocraftfix$totalTaskRounds(job) != 0) {
                // [v1.2.0] 有剩餘任務的訂單 job 不認領（庫存單據可能是前單殘留，認領＝收據充數偽完單）；
                // [v1.2.2] 零任務放行——此時單據＝本單交付品，認領是唯一歸還路徑。
                return false;
            }
            if (gtocraftfix$fWaitingFor == null) {
                var fw = job.getClass().getDeclaredField("waitingFor");
                fw.setAccessible(true);
                gtocraftfix$fWaitingFor = fw;
            }
            Object wf = gtocraftfix$fWaitingFor.get(job);
            if (!(wf instanceof appeng.crafting.inv.ListCraftingInventory wli)) {
                return false;
            }
            // [重檢5] 認領上限＝持有 − 已銷帳留庫 − 剩餘任務對成品的全量需求（自催化配方保留工作料）
            long claimable = Math.min(heldFin - gtocraftfix$claimedGet(job)
                    - gtocraftfix$remainingDemand(logic, fk), heldFin);
            if (claimable <= 0) {
                return false;
            }
            long got = inv.extract(fk, claimable, Actionable.MODULATE);
            if (got <= 0) {
                return false;
            }
            wli.insert(fk, got, Actionable.MODULATE);
            long accepted;
            try {
                accepted = logic.insert(fk, got, Actionable.MODULATE);
            } catch (Throwable t) {
                // [重檢6] 例外補償：收回假帳＋料放回 CPU 庫存再拋（假帳永駐 waitingFor 會讓所有救援層失效）
                wli.extract(fk, got, Actionable.MODULATE);
                inv.insert(fk, got, Actionable.MODULATE);
                throw t;
            }
            if (accepted < got) {
                // [重檢5] 此刻假帳已被 GTO insert 全額吃光、無帳可回滾；殘額送網路
                //（standalone 單的正確交付地），不留 CPU 庫存供下輪重燒。
                grid.getStorageService().getInventory()
                        .insert(fk, got - accepted, Actionable.MODULATE, cluster.getSrc());
            }
            gtocraftfix$finalClaimTick.put(cluster, gtocraftfix$tickCounter);
            gtocraftfix$staleHeld.remove(cluster);
            if (accepted <= 0) {
                gtocraftfix$refusedPut(cluster, fk); // [重檢5] link 全拒 → 記 10 分鐘冷卻節流
            }
            if (gtocraftfix$logInfoOk()) {
                LOG.info("[craftfix] 成品自我認領 {} x{}（waiting 空、成品滯留 CPU {} 秒；link 實收 {}）",
                        fk, got, (gtocraftfix$tickCounter - first) / 20, accepted);
            }
            return true;
        } catch (Throwable t) {
            if (gtocraftfix$logErrOk()) { // [重檢7]
                LOG.error("[craftfix] 成品自我認領例外", t);
            }
            return false;
        }
    }

    /** 輸入補給：讀 GTO job 的剩餘任務，對「需求 > CPU 庫存」的主輸入從網路補進（輪數有 cap）。反射全軟失敗。 */
    private boolean gtocraftfix$topUpInputs(appeng.crafting.execution.CraftingCpuLogic logic,
                                            appeng.api.storage.MEStorage storage,
                                            appeng.me.cluster.implementations.CraftingCPUCluster cluster,
                                            boolean jobBusy) {
        boolean acted = false;
        try {
            IActionSource src = cluster.getSrc();
            if (gtocraftfix$fJob == null) {
                var fj = logic.getClass().getDeclaredField("job");
                fj.setAccessible(true);
                gtocraftfix$fJob = fj;
                var fi = appeng.crafting.execution.CraftingCpuLogic.class.getDeclaredField("inventory");
                fi.setAccessible(true);
                gtocraftfix$fInv = fi;
            }
            Object job = gtocraftfix$fJob.get(logic);
            if (job == null) {
                return false;
            }
            if (gtocraftfix$fTasks == null) {
                var ft = job.getClass().getDeclaredField("tasks");
                ft.setAccessible(true);
                gtocraftfix$fTasks = ft;
            }
            Map<?, ?> tasks = (Map<?, ?>) gtocraftfix$fTasks.get(job);
            // [v1.2.2] fInv 走自帶初始化的 invOf（「fJob==null 才順便初始化 fInv」的耦合會被 jobOf 先跑而跳過 → NPE）
            var inv = gtocraftfix$invOf(logic);
            if (tasks == null || inv == null || tasks.isEmpty()) {
                return false;
            }
            int fed = 0;
            var starveDue = new java.util.ArrayList<Object[]>(); // [v1.8.0] 斷料條目收集（整併廣播用）
            for (var en : tasks.entrySet()) {
                if (fed >= 16) {
                    break;
                }
                Object holder = en.getValue();
                if (gtocraftfix$fHolderVal == null) {
                    var fv = holder.getClass().getField("value");
                    fv.setAccessible(true);
                    gtocraftfix$fHolderVal = fv;
                }
                long times = gtocraftfix$fHolderVal.getLong(holder);
                if (times <= 0) {
                    continue;
                }
                var pat = (IPatternDetails) en.getKey();
                // [v1.3.6] 樣板總成（PatternBuffer 系列，無限槽）供應器放寬 cap 讓執行器快推；
                // [v1.8.3] 放寬有界（cap×8）：無界會把該料全網存量抽進單一 CPU（液態氦 6.5 億 mB
                // 實錄），其他單全部餓死，且超出 remainingDemand 判準會被自庫認領誤燒帳。
                long roundsCap = gtocraftfix$TOPUP_ROUNDS_CAP;
                try {
                    for (var prov : ((CraftingService) (Object) this).getProviders(pat)) {
                        if (prov != null && prov.getClass().getName().contains("PatternBuffer")) {
                            roundsCap = Math.min(times, gtocraftfix$TOPUP_ROUNDS_CAP * 8);
                            break;
                        }
                    }
                } catch (Throwable ignored) {
                }
                // [v1.7.1] 完單時點節流已撤：非訂單 job 唯一收單點＝insert 實際交付打穿 remainingAmount，
                // 節流改不了收單時點、只會卡住大並行產線的餵料窗口。
                for (var input : pat.getInputs()) {
                    var poss = input.getPossibleInputs();
                    if (poss.length == 0) {
                        continue;
                    }
                    var ik = poss[0].what();
                    long per;
                    try {
                        per = Math.multiplyExact(poss[0].amount(), input.getMultiplier());
                    } catch (ArithmeticException e) {
                        continue; // [重檢1] per 溢位（理論值）→ 跳過此格
                    }
                    if (per <= 0) {
                        continue;
                    }
                    // [重檢1] 輪數 cap：無上限會把該料全量吸進單一 CPU 鎖到完單、其他 CPU 餓死；
                    // cap 內的量完單時 storeItems 退回網路，每秒會再續補。
                    long need;
                    try {
                        need = Math.multiplyExact(per, Math.min(times, roundsCap));
                    } catch (ArithmeticException e) {
                        need = per; // [重檢1] 溢位保底一輪（初始提料本來就會拿的合法量）
                    }
                    long have = inv.list.get(ik);
                    if (have >= need) {
                        gtocraftfix$starveClear(cluster, pat.getPrimaryOutput().what(), ik); // [v1.4.0] 料齊歸零計時
                        continue;
                    }
                    long got = storage.extract(ik, need - have, Actionable.MODULATE, src);
                    if (got > 0) {
                        try {
                            inv.insert(ik, got, Actionable.MODULATE);
                        } catch (Throwable t) {
                            // [重檢6] 例外補償：插不進 CPU 庫存就退回網路再拋（防半套蒸發）
                            storage.insert(ik, got, Actionable.MODULATE, src);
                            throw t;
                        }
                        acted = true;
                        fed++;
                        if (gtocraftfix$logInfoOk()) {
                            LOG.info("[craftfix] 保母補輸入 {} x{}（剩餘 {} 輪、cap {}）",
                                    ik, got, times, roundsCap == times ? "總成滿補" : roundsCap);
                        }
                    }
                    if (have + got < per) {
                        // 連一輪都湊不齊且網路已乾 → 執行器會無聲跳過此任務；留下可見證據
                        long flying = gtocraftfix$inFlightAmt(job, ik); // [v1.8.1] 在途生產＝慢，非卡
                        if (gtocraftfix$logWarnOk()) {
                            LOG.warn("[craftfix] 任務缺料 {}：每輪需 {}、CPU 有 {}、在途 {}、網路已乾（{} 任務被無聲跳過{}）",
                                    ik, gtocraftfix$fmtAmt(ik, per), gtocraftfix$fmtAmt(ik, have + got),
                                    gtocraftfix$fmtAmt(ik, flying), pat.getPrimaryOutput().what(),
                                    jobBusy ? "；本單在製中" : "");
                        }
                        // [v1.8.0] 斷料持續 60 秒 → 收集、迴圈後整併成單則聊天訊息（不自動下單）
                        var due = gtocraftfix$starveDue(cluster, job, pat.getPrimaryOutput().what(),
                                ik, per, have + got, flying, jobBusy);
                        if (due != null) {
                            starveDue.add(due);
                        }
                    } else {
                        gtocraftfix$starveClear(cluster, pat.getPrimaryOutput().what(), ik); // [v1.4.0]
                    }
                }
            }
            gtocraftfix$starveBroadcast(logic, starveDue); // [v1.8.0] 一 cluster 一則整併訊息
        } catch (Throwable t) {
            if (gtocraftfix$logErrOk()) { // [重檢7]
                LOG.error("[craftfix] 補輸入例外", t);
            }
        }
        return acted;
    }

    /** [v1.8.0] 跑單斷料計時：同 job 同（任務主產物|缺料）連續 60 秒湊不齊、網路已乾且**整單完全
     *  靜止**、過 10 分鐘冷卻 → 回報條目（由 starveBroadcast 整併）；未達門檻回 null。
     *  換 job／料補齊即重計。凍結條件（[v1.8.1] 此料全網有在途；[v1.8.2] 本單 waitingFor 非空＝
     *  深鏈上游在製，缺料只是還沒輪到）任一成立即重計不點名——只點名「整單無任何機器在做」的真卡。
     *  自動救援下單已移除：補單量不可控，會塞爆 CPU/產線。 */
    private Object[] gtocraftfix$starveDue(appeng.me.cluster.implementations.CraftingCPUCluster cluster,
                                           Object job, AEKey patOut, AEKey ik, long per, long have,
                                           long flying, boolean jobBusy) {
        try {
            Object[] st = gtocraftfix$starveNotice.get(cluster);
            Object stJob = st == null ? null : ((java.lang.ref.WeakReference<?>) st[0]).get();
            if (st == null || stJob != job) { // [重檢18] 換單即重計，防跨 job 殘留計時
                st = new Object[] { new java.lang.ref.WeakReference<>(job), new HashMap<String, int[]>() };
                gtocraftfix$starveNotice.put(cluster, st);
            }
            @SuppressWarnings("unchecked")
            var m = (HashMap<String, int[]>) st[1];
            if (m.size() > 64) {
                m.clear();
            }
            var rec = m.computeIfAbsent(patOut + "|" + ik,
                    k -> new int[] { gtocraftfix$tickCounter, Integer.MIN_VALUE / 2 });
            if (flying > 0 || jobBusy) {
                rec[0] = gtocraftfix$tickCounter; // 凍結：60 秒門檻只累計「整單靜止」時間
                return null;
            }
            int stuck = gtocraftfix$tickCounter - rec[0];
            if (stuck < 1200 || gtocraftfix$tickCounter - rec[1] < 12000) {
                return null; // 60 秒凍結門檻＋10 分鐘廣播冷卻
            }
            rec[1] = gtocraftfix$tickCounter;
            return new Object[] { ik, per, have, stuck };
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** [v1.8.1] 全網在途生產量：GTO 版 getRequestedAmount＝各 CPU waitingFor 合計（涵蓋玩家另開的
     *  補料單）；API 例外時退看本單 waitingFor。>0＝有機器正在做這個料。 */
    private long gtocraftfix$inFlightAmt(Object job, AEKey ik) {
        try {
            return ((CraftingService) (Object) this).getRequestedAmount(ik);
        } catch (Throwable ignored) {
        }
        try {
            if (gtocraftfix$fWaitingFor == null) {
                var fw = job.getClass().getDeclaredField("waitingFor");
                fw.setAccessible(true);
                gtocraftfix$fWaitingFor = fw;
            }
            Object wf = gtocraftfix$fWaitingFor.get(job);
            if (wf instanceof appeng.crafting.inv.ListCraftingInventory li) {
                return li.list.get(ik);
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /** [v1.8.0] 斷料整併廣播：一 cluster 一則，前 3 項含量、其餘計數（各料細節見任務缺料 WARN）。
     *  聊天節流獨立於 logWarnOk 額度：log 額度耗盡不得吞聊天提示。 */
    private void gtocraftfix$starveBroadcast(appeng.crafting.execution.CraftingCpuLogic logic,
                                             java.util.List<Object[]> due) {
        try {
            if (due.isEmpty()) {
                return;
            }
            var server = grid.getPivot() != null && grid.getPivot().getLevel() != null
                    ? grid.getPivot().getLevel().getServer()
                    : null;
            if (server == null) {
                return;
            }
            var fo = logic.getFinalJobOutput();
            var head = fo != null ? fo.what().getDisplayName()
                    : net.minecraft.network.chat.Component.literal("(未知產物)");
            int stuck = (Integer) due.get(0)[3];
            var msg = net.minecraft.network.chat.Component.literal("[合成修復] 跑單斷料：合成 ")
                    .append(head)
                    .append(net.minecraft.network.chat.Component.literal(
                            " 的單已卡 " + (stuck / 20) + " 秒（期間無任何機器在製）——缺 "));
            int shown = 0;
            for (var d : due) {
                if (shown >= 3) {
                    msg.append(net.minecraft.network.chat.Component.literal(
                            " …等共 " + due.size() + " 項（其餘見伺服器紀錄）"));
                    break;
                }
                if (shown++ > 0) {
                    msg.append(net.minecraft.network.chat.Component.literal("、"));
                }
                var k = (AEKey) d[0];
                msg.append(k.getDisplayName())
                        .append(net.minecraft.network.chat.Component.literal(
                                "（每輪 " + gtocraftfix$fmtAmt(k, (Long) d[1])
                                        + "、CPU " + gtocraftfix$fmtAmt(k, (Long) d[2]) + "）"));
            }
            msg.append(net.minecraft.network.chat.Component.literal("；網路無貨，補進 ME 後會自動續作"));
            String txt = msg.getString();
            if (txt.equals(gtocraftfix$lastStarveTxt)
                    && gtocraftfix$tickCounter - gtocraftfix$lastStarveTxtTick < 1200) {
                return; // [v1.8.2] 60 秒內同文只發一次
            }
            gtocraftfix$lastStarveTxt = txt;
            gtocraftfix$lastStarveTxtTick = gtocraftfix$tickCounter;
            server.getPlayerList().broadcastSystemMessage(msg, false);
        } catch (Throwable ignored) {
        }
    }

    /** [v1.6.1] 完單短交監看泵（每 tick）：只對「玩家單、扣現貨後仍應到未到」的量做 5 分鐘到貨
     *  監看（cachedInventory 正差分累計）；期滿餘額才視為真損失 → 只聊天通知、不代補。 */
    private void gtocraftfix$shortWatchTick() {
        try {
            if (gtocraftfix$shortWatch.isEmpty()) {
                return;
            }
            var cached = grid.getStorageService().getCachedInventory();
            var it = gtocraftfix$shortWatch.iterator();
            while (it.hasNext()) {
                var w = it.next();
                var out = (AEKey) w[0];
                long need = (Long) w[1];
                long accum = (Long) w[2];
                long last = (Long) w[3];
                long now = cached.get(out);
                if (now > last) {
                    accum += now - last;
                }
                w[2] = accum;
                w[3] = now;
                if (accum >= need) {
                    it.remove();
                    if (gtocraftfix$logInfoOk()) {
                        LOG.info("[craftfix] 完單短交監看結案 {}：應到 {} 已全數到帳", out, need);
                    }
                    continue;
                }
                if (gtocraftfix$tickCounter - (Integer) w[4] < 6000) {
                    continue; // 監看窗 5 分鐘
                }
                it.remove();
                long loss = need - accum;
                // [v1.8.0] 只通知不代補：真損失罕見，交玩家決定
                var server = grid.getPivot() != null && grid.getPivot().getLevel() != null
                        ? grid.getPivot().getLevel().getServer()
                        : null;
                if (server != null) {
                    server.getPlayerList().broadcastSystemMessage(
                            net.minecraft.network.chat.Component.literal("[合成修復] 完單短交確認：")
                                    .append(out.getDisplayName())
                                    .append(net.minecraft.network.chat.Component.literal(
                                            " 提前收單後 5 分鐘僅到帳 " + gtocraftfix$fmtAmt(out, accum)
                                                    + "／應到 " + gtocraftfix$fmtAmt(out, need)
                                                    + "，實際少 " + gtocraftfix$fmtAmt(out, loss)
                                                    + "——如有需要請自行補單")),
                            false);
                }
                if (gtocraftfix$logWarnOk()) { // [重檢7]
                    LOG.warn("[craftfix] 完單短交確認 {}：應到 {}、到帳 {}、真損失 {}（不代補）",
                            out, need, accum, loss);
                }
            }
        } catch (Throwable t) {
            if (gtocraftfix$logErrOk()) { // [重檢7]
                LOG.error("[craftfix] 完單短交監看例外", t);
            }
        }
    }

    /** [v1.4.0] 料補齊 → 歸零該（任務主產物|缺料）的斷料計時。 */
    private void gtocraftfix$starveClear(appeng.me.cluster.implementations.CraftingCPUCluster cluster,
                                         AEKey patOut, AEKey ik) {
        try {
            Object[] st = gtocraftfix$starveNotice.get(cluster);
            if (st != null) {
                ((Map<?, ?>) st[1]).remove(patOut + "|" + ik);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 可執行性模擬：以 usedItems 為起始庫存、逐輪執行可滿足輸入的樣板（主替代品近似）。
     * 回傳「卡死樣板中、庫存為 0 的輸入」各一輪 run 的量＝循環自舉缺口；可全部跑完則回空。
     */
    private static java.util.List<Object[]> gtocraftfix$findBootstrapDeficits(CraftingPlan plan) {
        var inv = new HashMap<AEKey, Long>();
        for (var e : plan.usedItems()) {
            inv.merge(e.getKey(), e.getLongValue(), Long::sum);
        }
        var remaining = new HashMap<IPatternDetails, Long>();
        plan.patternTimes().forEach((p, r) -> {
            if (r != null && r > 0) {
                remaining.put(p, r);
            }
        });
        boolean progress = true;
        while (progress && !remaining.isEmpty()) {
            progress = false;
            for (var it = remaining.entrySet().iterator(); it.hasNext();) {
                var en = it.next();
                var p = en.getKey();
                long runs = en.getValue();
                long can = runs;
                for (var input : p.getInputs()) {
                    var poss = input.getPossibleInputs();
                    if (poss.length == 0) {
                        continue;
                    }
                    long per = poss[0].amount() * input.getMultiplier();
                    if (per <= 0) {
                        continue;
                    }
                    can = Math.min(can, inv.getOrDefault(poss[0].what(), 0L) / per);
                    if (can == 0) {
                        break;
                    }
                }
                if (can > 0) {
                    for (var input : p.getInputs()) {
                        var poss = input.getPossibleInputs();
                        if (poss.length == 0) {
                            continue;
                        }
                        long per = poss[0].amount() * input.getMultiplier();
                        inv.merge(poss[0].what(), -per * can, Long::sum);
                    }
                    for (var out : p.getOutputs()) {
                        inv.merge(out.what(), out.amount() * can, Long::sum);
                    }
                    if (can >= runs) {
                        it.remove();
                    } else {
                        en.setValue(runs - can);
                    }
                    progress = true;
                }
            }
        }
        var res = new java.util.ArrayList<Object[]>();
        if (remaining.isEmpty()) {
            return res;
        }
        var added = new HashSet<AEKey>();
        for (var en : remaining.entrySet()) {
            for (var input : en.getKey().getInputs()) {
                var poss = input.getPossibleInputs();
                if (poss.length == 0) {
                    continue;
                }
                var k = poss[0].what();
                long per = poss[0].amount() * input.getMultiplier();
                if (per > 0 && inv.getOrDefault(k, 0L) <= 0 && added.add(k)) {
                    res.add(new Object[] { k, per }); // 補一輪 run 的量當自舉
                }
            }
        }
        return res;
    }

    private static Method gtocraftfix$resolve() {
        try {
            Class<?> oc = Class.forName("com.gtolib.api.ae2.crafting.OptimizedCalculation");
            for (Method m : oc.getDeclaredMethods()) {
                if (m.getName().equals("executeV2") && m.getParameterCount() == 6) {
                    m.setAccessible(true);
                    return m;
                }
            }
        } catch (Throwable ignored) {
            // 略 → 回 null
        }
        return null;
    }
}
