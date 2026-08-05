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
 * 修好 GTO 環境「合成樹超過一步就無法（正確）自動合成」，三個修正：
 * <ol>
 *   <li><b>算料同步化</b>（{@code @Inject beginCraftingCalculation}）：GTO 把算料丟單一背景執行緒 async，
 *       多步（遞迴）時 {@code Future} 不返回、終端 ctrl+左鍵卡死。改成呼叫端（伺服器執行緒）同步執行
 *       同一 {@code OptimizedCalculation.executeV2}。反射呼叫閉源 gtolib，找不到就退回原本 async。</li>
 *   <li><b>機器源 present-once 走 IgnoreMissing</b>（{@code @Redirect submitJob → cpuCluster.submitJob}）：
 *       實測 GTO 計畫的 {@code usedItems} 含「執行期間才回流的中間產物」（庫存快照 0 也照列），
 *       開局一次取齊（嚴格取料）必失敗 → 機器源（接口/請求器/合成卡）被 MISSING_INGREDIENT
 *       無限拒單。正解 = IgnoreMissing：取現有、缺的記 {@code missingIng} → {@code waitingFor}，
 *       回流認領。GTO 只讓「有 player」的來源走該分支 → 包一層 present-once：{@code player()} 首呼
 *       （trySubmitJob 的條件判斷）回 present 走 IgnoreMissing，其後回 empty 用 machine 身分取料。</li>
 *   <li><b>保母（只餵料）</b>（{@code onServerEndTick} 每 5 秒）：job 的 {@code waitingFor} 缺口
 *       若網路有現貨（GTO 的認領只在「插入事件」觸發，既有庫存不會被回收），直接搬進 CPU 認領。
 *       不代下巢狀合成單——實測會生出大量小任務佔滿 CPU，反而害玩家下不了單。
 *       缺口若網路無貨（算料器批量餘數幻影，見 ISSUE.md 根因二），需玩家手動下該料的單，
 *       貨一落網路保母即自動餵入解凍。</li>
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

    // [重檢12] 目標類即 CraftingService，直接 @Shadow 免反射：保母改帳後設 -1，強制下一 tick 重算
    // currentlyCrafting／isRequesting（TAIL 的修改晚於本 tick 重算、postChange 戳同 tick 值會被跳過）
    @Shadow(remap = false)
    private long lastProcessedCraftingLogicChangeTick;

    private static volatile Method gtocraftfix$executeV2;
    private static volatile boolean gtocraftfix$resolved;
    // [重檢7] log 計數器分流：info／warn／例外 各自獨立、5 分鐘窗重置——舊單一 sitterLog 累積 200 後全部
    // 靜默、例外站（≤5）被一般 log 搶額度幾乎從不觸發，半套回滾等災難完全不可診斷。
    private static final AtomicInteger gtocraftfix$logInfo = new AtomicInteger();
    private static final AtomicInteger gtocraftfix$logWarn = new AtomicInteger();
    private static final AtomicInteger gtocraftfix$logErr = new AtomicInteger();
    private static volatile long gtocraftfix$logWindow;
    // [重檢1] 補輸入輪數上限（系統屬性可調；沿用已公布的 gtodiag.lpcalc.* 前綴）
    private static final long gtocraftfix$TOPUP_ROUNDS_CAP =
            Math.max(1L, Long.getLong("gtodiag.lpcalc.topUpRoundsCap", 4096L));
    private int gtocraftfix$tickCounter;
    private Set<AEKey> gtocraftfix$noPatternLogged = new HashSet<>();
    /** [重檢14] 成品被 link 拒收的記憶改 per-cluster（cluster 弱鍵 → key → 拒收 tick）；10 分鐘內不再試。
     *  舊版全 grid 共用單一 key 表：A cluster 的死 link 會誤封 B cluster 同成品的救援。 */
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
    /** [重檢14] 陳舊等待偵測：cluster（弱鍵，重建即回收——舊 identityHashCode 字串鍵會洩漏＋撞號）→
     *  key → 首見 tick。網內無貨、無剩餘任務消費、滯留逾時才視為陳舊（epoxy 案例）。 */
    private Map<CraftingCPUCluster, HashMap<AEKey, Integer>> gtocraftfix$staleWait = new WeakHashMap<>();
    /** [重檢16] 孤兒觀測計時：cluster（弱鍵）→ 樣板 → 首見「無供應器」tick；持續 60 秒才換綁
     *  （供應器 chunk 卸載／節點暫離的暫態 unmount 不該立刻觸發換綁）。 */
    private Map<CraftingCPUCluster, HashMap<IPatternDetails, Integer>> gtocraftfix$orphanSince = new WeakHashMap<>();
    /** [重檢3] 已銷帳成品留庫量：job（弱鍵，完單即隨 GC 歸零）→ 帳已被 GTO insert 銷掉、
     *  物理留在 CPU 庫存待 storeItems 退網的成品數——selfClaimFinal 不得對這部分再燒帳。 */
    private Map<Object, long[]> gtocraftfix$claimedFinalHeld = new WeakHashMap<>();
    /** [重檢14] 成品自我認領計時：cluster（弱鍵）→ 首見滯留 tick（與陳舊等待分表，互不誤清）。 */
    private Map<CraftingCPUCluster, Integer> gtocraftfix$finalClaimTick = new WeakHashMap<>();
    private Set<String> gtocraftfix$failLogged = new HashSet<>();

    /**
     * [重檢17] Mixin 實例欄位初始化式不保證併入目標建構子（1.1.8 實測 feedRefused 為 null、
     * 保母每輪 NPE 全滅）——所有狀態表進場先過這裡懶初始化，對合併雷免疫。
     * 只在伺服器執行緒呼叫，無並發問題。
     */
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
                        LOG.info("[craftfix] 已啟用 v1.1.9：同步算料＋機器源 IgnoreMissing＋保母；lpcalc={}。",
                                com.gtocraftfix.lpcalc.LpConfig.enabled() ? "on" : "off");
                    }
                }
            }
        }

        Method m = gtocraftfix$executeV2;
        if (m == null || level == null || simRequester == null) {
            return; // 不接管 → 走原本 async
        }
        try {
            // 無樣板守衛：原版語意＝無樣板即不可合成、根本不該生任務。GTO 的 executeV2 卻會對
            // 無樣板物品硬生「從網路拿 N 顆」的退化計畫 → 無謂的算料/修補/拒單循環。
            // 機器源請求前先查索引，查無 → 直接回誠實 sim 計畫（缺 N、零任務），不進 executeV2。
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
            // 機器源優先走 lpcalc 結構化需求傳播算料器（SCC 縮點＋批量傳播）；不支援/失敗
            // 由 LpEntry 內部回退 com.gtocraftfix.calc 樹狀版（快照期當場建、求解期走 LpFallbackQueue
            // +1 tick），-Dgtodiag.lpcalc.enabled=false 一鍵停用（機器路徑完全回樹狀版）。
            // LpEntry 全包 try-catch 不外拋；外層 catch（不 setReturnValue → 退 GTO async）當最後防線。
            // 玩家維持 GTO executeV2（快，且玩家路徑在現有防線下運作正常）。
            if (machineSrc0) {
                cir.setReturnValue(com.gtocraftfix.lpcalc.LpEntry.beginMachineCalc(
                        level, grid, (ICraftingService) (Object) this, simRequester,
                        what, amount, strategy, gtocraftfix$CALC_POOL));
                return;
            }
            var inventory = grid.getStorageService().getCachedInventory().copy();
            var plan = (ICraftingPlan) m.invoke(null, grid, inventory, simRequester, what, amount, strategy);

            // 機器源降量重算：executeV2 的 CRAFT_LESS 對大量會直接回 amount=0＋sim（實測 10000 鈦錠
            // 因鎂循環記帳差 2 而整張歸 0，1000 卻可以）。正常 CRAFT_LESS 語意應回「最多可做的量」——
            // 外部重現：砍半重算直到可執行；做多少先交多少，追蹤器下輪自然補餘量。玩家不降（要看缺料畫面）。
            var actionSrc = simRequester.getActionSource();
            boolean machineSrc = actionSrc == null || actionSrc.player().isEmpty();
            if (machineSrc && plan != null && amount > 1
                    && (plan.simulation() || plan.finalOutput() == null || plan.finalOutput().amount() <= 0)) {
                long tryAmount = amount;
                for (int i = 0; i < 12 && tryAmount > 1; i++) {
                    tryAmount /= 2;
                    var inv2 = grid.getStorageService().getCachedInventory().copy();
                    var p2 = (ICraftingPlan) m.invoke(null, grid, inv2, simRequester, what, tryAmount, strategy);
                    // 拒收「退化計畫」（usedItems 吃現貨、patternTimes 空＝啥都不合成）：GTO 沒有
                    // 「把開局吸入的成品交給 link」的步驟，這種 job 會抱著現貨永凍（實測 NAND 625）。
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

    // ---- 修正 4：計畫修補（最接近根源的外部解）----
    // 病灶（ISSUE.md 根因二）：算料器把「網路裡拿不到的量」寫進 usedItems（批量餘數幻影），
    // 且不排樣板 → job 等一個沒人會做的料 → 永久凍結。
    // 修補：提交前重算帳——usedItems 超出網路實際可取的缺口，直接把該料的樣板 runs 補進
    // 「同一張計畫」（不生新任務、不佔額外 CPU）；新增 runs 的輸入遞迴同法補平：
    // 網路夠 → 記進 usedItems；不夠 → 繼續補樣板。有界迴圈，任何失敗放行原計畫。
    @Inject(method = "submitJob", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtocraftfix$repairPlan(ICraftingPlan job, ICraftingRequester requestingMachine, ICraftingCPU target,
                                        boolean prioritizePower, IActionSource src,
                                        CallbackInfoReturnable<ICraftingSubmitResult> cir) {
        gtocraftfix$ensureState(); // [重檢17]
        if (!(job instanceof CraftingPlan plan)) {
            return;
        }
        boolean blockSubmit = false;
        try {
            var storage = grid.getStorageService().getInventory();
            var used = plan.usedItems();
            var missing = plan.missingItems();
            var pt = plan.patternTimes(); // 同一個可變 map（Object2LongOpenHashMap）

            // 可用量帳本：avail=實際可取（SIMULATE），reserved=本計畫已預定
            Map<AEKey, Long> avail = new HashMap<>();
            Map<AEKey, Long> reserved = new HashMap<>();
            var deficits = new ArrayDeque<Object[]>(); // {AEKey, Long short}

            // ① missingItems：算料器標「缺」的量——有樣板就能補排（sim 計畫的病灶）。
            //    機器源的 sim 計畫會被 submitJob 守衛靜默拒單（玩家反而放行），全補完就把 sim 翻回 false。
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
            // ③ 最終產出量檢查：整條鏈 runs 取整後總產出可能 < 需求（實測 flake 差 19 →
            //    所有任務做完仍交不齊、永凍）。產出＋現貨＋放射 < 需求 → 差額列缺口補樣板。
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
                        LOG.info("[craftfix] 最終產出短缺 {} x{}（out={}）", outKey, needOut, plan.finalOutput());
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
                    // 無樣板可補＝真缺料（網路沒貨、也沒有樣板能做）→ log ＋ 聊天室提示玩家補料
                    if (hard) {
                        blockSubmit = true; // 真缺料 → 擋下提交（否則 job 必凍）
                    }
                    if (gtocraftfix$logWarnOk()) { // [重檢7] log 分流
                        LOG.warn("[craftfix] 計畫修補 無樣板可補：{} x{}（out={}）", key, shortAmt, plan.finalOutput());
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
                                                    .literal(" x" + shortAmt + "（合成 "))
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
                // [重檢11] 玩家單（executeV2 帶 gtocore$allocations）配額補登：executeCrafting 以樣板
                // definition 查配額，補進的 runs 若樣板不在該輸入 key 的配額表 → INSUFFICIENT_PRIORITY
                // 永拒（與重綁不遷配額同根因的第二個入口）。lpcalc 機器單 allocations 恆 null 天然跳過。
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
            // 可執行性模擬：紙上執行整張計畫（usedItems 當起始庫存、逐輪跑可跑的樣板）。
            // 跑不完＝有樣板被「0 庫存的輸入」卡死＝循環自舉缺口（如 H₂O₂ 蒽醌工作液：
            // 帳面淨消耗 0 → 不在 usedItems/missing → 但執行要有第一桶才轉得起來）→ 補進缺口再解。
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
        // 真缺料（無樣板可補的硬缺口）→ 擋下提交：提交了必凍。機器每 2 秒重試（聊天室/log 已去重）；
        // 玩家按確認會沒反應，但聊天室已說明缺什麼。
        if (blockSubmit) {
            cir.setReturnValue(appeng.crafting.execution.CraftingSubmitResult.INCOMPLETE_PLAN);
            return;
        }
        // 機器源退化計畫（修補後仍無任何合成任務）→ 拒單。GTO 沒有「開局吸入的現貨交給 link」
        // 的步驟，這種 job 會抱著現貨永凍（實測 NAND 625）。拒掉後接口下一輪 acquireFromNetwork
        // 會自己拉現貨，自然收斂。
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

    // ---- 修正 3：保母（每 5 秒掃孤兒 waitingFor）＋ 診斷探針（每 20 秒）----
    @Inject(method = "onServerEndTick", at = @At("TAIL"), remap = false)
    private void gtocraftfix$tick(MinecraftServer server, CallbackInfo ci) {
        gtocraftfix$ensureState(); // [重檢17]
        com.gtocraftfix.calc.CalcTicker.tick(); // 內置原版算料器的預算泵（每 tick）
        com.gtocraftfix.lpcalc.LpFallbackQueue.drainOnServerTick(); // LP 晚期回退/影子驗證的伺服器緒建構點（鐵則5/8）
        gtocraftfix$tickCounter++;
        if (gtocraftfix$tickCounter % 400 == 0) {
            for (var cluster : craftingCPUClusters) {
                try {
                    var logic = cluster.craftingLogic;
                    var out = logic.getFinalJobOutput();
                    if (out == null) {
                        continue;
                    }
                    Set<AEKey> waiting = new HashSet<>();
                    logic.getAllWaitingFor(waiting);
                    String waitStr = waiting.stream().limit(8).map(String::valueOf)
                            .reduce((a, b) -> a + "," + b).orElse("(空)");
                    String results;
                    try {
                        Object r = logic.getClass().getMethod("getCraftingResults").invoke(logic);
                        results = String.valueOf(r);
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
                    // 剩餘任務 X 光：主產物×次數＋首輸入「CPU庫存量/每輪需求」——
                    // 分辨「缺料不推」（庫存<需求）與「有料不推」（庫存≥需求＝執行器死角）
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
                                    // 印「最缺的那格」輸入——executeCrafting 任一格不足即無聲跳過，
                                    // 只看第一格會得到 15/15 的假健康
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
                                            long have1 = inv0.list.get(ps[0].what());
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
                                                    .append(worstHave).append('/').append(worstNeed).append(')');
                                        }
                                    }
                                    tb.append("; ");
                                }
                                tasksStr = tb.length() == 0 ? "(無)" : tb.toString();
                            }
                        }
                    } catch (Throwable ignored2) {
                    }
                    LOG.info("[craftfix] CPU探針 out={} waiting[{}]={} held=[{}] 剩餘任務=[{}] results={}",
                            out, waiting.size(), waitStr, held, tasksStr, results);
                    // 欄位普查（每 cluster 一次）：waiting 空＋有剩餘任務＝執行器不推but料在——
                    // 閘門必在 gtolib 私有欄位裡（最可疑：隨存檔保留的在途計數器）。全部倒出來找。
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
        if (gtocraftfix$tickCounter % 100 != 0) {
            return;
        }
        var storage = grid.getStorageService().getInventory();
        int handled = 0;
        // [重檢8] cluster 輪替起點：ReferenceOpenHashSet 迭代序輪輪相同＋handled≥8 中斷整圈，固定順序
        // 會讓後段 cluster 的餵料／補輸入／重綁永遠輪不到（前段大單每輪優先抽料）——每輪換起點。
        var clusterList = new java.util.ArrayList<>(craftingCPUClusters);
        int clusterN = clusterList.size();
        int startIdx = clusterN == 0 ? 0 : Math.floorMod(gtocraftfix$tickCounter / 100, clusterN);
        for (int cIdx = 0; cIdx < clusterN; cIdx++) {
            var cluster = clusterList.get((startIdx + cIdx) % clusterN);
            if (handled >= 8) {
                break;
            }
            boolean acted = false;
            try {
                var logic = cluster.craftingLogic;
                var finalOut = logic.getFinalJobOutput();
                if (finalOut == null) {
                    continue;
                }
                Set<AEKey> waiting = new HashSet<>();
                logic.getAllWaitingFor(waiting);
                int handledBefore = handled;
                for (var key : waiting) {
                    if (handled >= 8) {
                        break;
                    }
                    boolean isFinal = key.equals(finalOut.what());
                    if (isFinal) {
                        // 成品也要餵（成品回流網路但 CPU 沒攔到認領時，唯一救援路徑）；
                        // 拒收記憶改 per-cluster（[重檢14]），10 分鐘內不再試。
                        Integer ru = gtocraftfix$refusedGet(cluster, key);
                        if (ru != null && gtocraftfix$tickCounter - ru < 12000) {
                            continue;
                        }
                    }
                    long want = logic.getWaitingFor(key);
                    if (want <= 0) {
                        continue;
                    }
                    // 只餵料：網路有貨 → 直餵 CPU（補認領缺口）。不代下巢狀單——那會生一堆小任務佔 CPU。
                    long got = storage.extract(key, want, Actionable.MODULATE, cluster.getSrc());
                    if (got <= 0) {
                        // [重檢4] 自庫認領跳過成品：GTO insert 對成品「先按全額銷帳、後問 link」，此處認領
                        // 會與 selfClaimFinal 對同一批物品重複燒 remainingAmount——成品滯留由 selfClaimFinal
                        // 專責（該處有已銷帳留庫／冷卻防護）。
                        if (isFinal) {
                            continue;
                        }
                        // v1.1.3 自庫認領：網內無貨，但貨可能已在 CPU 自己的庫存——繞過認領 hook
                        // 進來的（液態釹案例：waiting 要 9216mB、庫存正好持有 9216mB）。
                        var inv0 = gtocraftfix$invOf(logic);
                        if (inv0 != null) {
                            long heldHere = inv0.list.get(key);
                            // [重檢13] 只認領超出「剩餘任務 capped 需求」的超額部分：topUp 塞進來的工作備料
                            // 不是繞過認領的交付品，銷了帳會讓在途真交付被拒、物流空轉（液態釹案例需求=0 → 照舊全認領）。
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
                            // [重檢3] 成品餘額不回插網路：storage.insert 會經 CraftingServiceStorage→insertIntoCpus
                            // 再進同一顆 CPU，GTO insert「先按全額銷帳後問 link」→ got<want 時殘帳被同批物品
                            // 二次銷帳、remainingAmount 二次遞減 → 提早完單／requester 短收。改留 CPU 庫存
                            //（完單 storeItems 自然退網），並記入 claimedFinalHeld 防 selfClaimFinal 之後再燒。
                            var invF = gtocraftfix$invOf(logic);
                            if (invF != null) {
                                invF.insert(key, got - accepted, Actionable.MODULATE);
                                gtocraftfix$claimedAdd(logic, got - accepted);
                            } else {
                                storage.insert(key, got - accepted, Actionable.MODULATE, cluster.getSrc());
                            }
                        } else {
                            // 非成品：GTO insert 恆全額吃下（此分支實為死碼），保留作 job 同 tick 消失時的退料安全網
                            storage.insert(key, got - accepted, Actionable.MODULATE, cluster.getSrc());
                        }
                    }
                    if (accepted <= 0 && !isFinal) {
                        continue;
                    }
                    // [重檢5] 成品 accepted==0 ≠ 拒收：GTO insert 在問 link 前已按全額銷帳（帳已推進；
                    // standalone 玩家單 link 恆回 0），不記 feedRefused——記了只會拖慢正常收斂。
                    handled++;
                    acted = true;
                    if (gtocraftfix$logInfoOk()) {
                        LOG.info("[craftfix] 保母餵料 {} x{}", key, got);
                    }
                }
                // 輸入補給：waiting 空（無在途）但仍有未完成任務＝執行中帳漂移把某任務輸入吃到不足一輪
                // → 剩餘任務每 tick 取料失敗、無聲凍結。反射讀任務清單，短缺輸入直接從網路補進 CPU 庫存。
                // v1.1.1：epoxy 案例證明「waiting 非空但無料可餵」的凍結同樣需要補輸入
                // （陳舊等待擋住視線、真正缺的是任務輸入）→ 本 cluster 這輪餵料掛零時也跑。
                if ((waiting.isEmpty() || handled == handledBefore) && handled < 8) {
                    acted |= gtocraftfix$topUpInputs(logic, storage, cluster);
                }
                // 陳舊等待解鎖：可證明無用的 waitingFor 帳目才清（無剩餘任務吃、網內無貨、滯留逾時）
                if (!waiting.isEmpty()) {
                    acted |= gtocraftfix$clearStaleWaits(logic, storage, cluster, waiting, finalOut);
                }
                // 成品自我認領：waiting 空但成品躺在 CPU 庫存不被交付帳認領（me_pattern_buffer 案例：
                // 做成品的機器自帶 ME 連接，產物繞過認領 hook 直進 CPU 庫存）→ 補帳＋重插觸發認領
                if (waiting.isEmpty()) {
                    acted |= gtocraftfix$selfClaimFinal(logic, cluster, finalOut);
                }
                // 孤兒任務重綁：樣板實例失聯（getProviders 空 → executeCrafting 空轉不留痕、永凍）
                // → 換綁到同產物＋同輸入簽名、有活供應器的現行樣板
                acted |= gtocraftfix$rebindOrphanTasks(logic, cluster);
            } catch (Throwable t) {
                if (gtocraftfix$logErrOk()) { // [重檢7] 例外計數器獨立
                    LOG.error("[craftfix] 保母例外", t);
                }
            } finally {
                if (acted) {
                    // [重檢12] 保母寫入收尾：標髒 cluster（ListCraftingInventory 只回呼 listener 不標髒——
                    // 否則崩潰／未存檔卸載時 CPU 側已收的料不落盤＝物品蒸發，網路側扣帳卻已存），
                    // 並強制下一 tick 重算 currentlyCrafting（防 isRequesting 幻影殘留）。
                    cluster.markDirty();
                    lastProcessedCraftingLogicChangeTick = -1;
                }
            }
        }
    }

    /**
     * 陳舊等待解鎖：gtolib 的 waitingFor 認領只在插入事件觸發，執行中帳漂移可留下「永遠等不到、
     * 也不再需要」的帳目（epoxy 案例：電金板早已到貨並全數加工成箔，waitingFor 卻仍掛著板）。
     * 四重證明缺一不可才清：(1) 該 key 滯留 ≥300 秒；(2) 網內無貨（有貨歸餵料處理）；
     * (3) 剩餘任務（times>0）沒有任何一個把該 key 列為可能輸入；(4) 無機器 pending 在做該 key。
     * 訂單型 job 整顆跳過；最終產物永不清。反射全軟失敗。
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
            // [重檢9] 訂單守衛：order/temp-order 的完單條件＝waitingFor 空∧tasks==1，清掉訂單依賴品的帳
            // 會讓訂單提早回報完成（子單成品之後才落網路）→ isOrder 反射直讀，整顆 CPU 跳過清帳。
            if (gtocraftfix$isOrderJob(job)) {
                return false;
            }
            var sw = gtocraftfix$staleWait.computeIfAbsent(cluster, c -> new HashMap<>());
            if (sw.size() > 256) {
                // [重檢14] 只清本 cluster 子表（舊版 >512 整張全清會把所有 cluster 年齡一起歸零、兩層武器同滅）
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
                // [重檢9] 門檻 1200→6000 tick：GT 配方常 1-10 分鐘，60 秒會清掉仍在機器內加工的真在途帳
                if (first == null || gtocraftfix$tickCounter - first < 6000) {
                    continue;
                }
                // [重檢9] 證明 (4)：pendingRequests 非空＝仍有機器在做該 key → 不清
                //（僅輔助條件：餵滿全額會清該標記、且只記主產物，不可取代其餘證明）
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
     * 孤兒任務重綁：job 的任務綁「算料當下的樣板實例」；樣板事後被重上傳／換機器／供應器
     * 重整後，新樣板與舊實例對不上 → getProviders(舊實例) 永遠空 → executeCrafting 供應器
     * 迴圈空轉、料取出又放回、不留任何紀錄（results 空、waiting 空、零進度）。GTO 無重綁機制。
     * 救援：對供應器數 0 且持續 60 秒的任務，找「全部產出逐項相等＋逐格輸入簽名相等（含替代品
     * 集合）」且有活供應器的現行樣板，先遷移 allocations 配額再換綁；目標樣板已在任務清單 →
     * 併次數。每輪最多 4 筆，反射軟失敗。
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
                    // [重檢15] 全部產出逐項 key＋amount 相等：只比主產物 key 會換到產量配比不同的樣板
                    //（times 不換算 → 總產出短缺、永湊不齊 remainingAmount）；配比不同一律不綁。
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
                                // [重檢15] 替代品清單（poss[1..]）key 集合也要相等——否則陳舊解鎖證明(3)
                                // 與執行器取料的替代品語意在換綁後漂移
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
                // [重檢2] 先遷移 allocations 配額再換綁：executeCrafting 以樣板 definition 查配額，查無即
                // INSUFFICIENT_PRIORITY＋跳過任務；舊 def 配額只會被「執行中同 def 樣板」遞減、purge 只清
                // ≤0、且隨 NBT 跨重啟 → 不遷移＝玩家單永凍。遷移失敗（反射不可用）放棄本筆換綁。
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

    // ---- [重檢7] log 分流：info（窗內 200）／warn（窗內 50）／例外（窗內 20，絕不與一般共用額度），
    //      每 5 分鐘窗重置——取代舊單一 sitterLog（200 後全靜默、例外站 ≤5 幾乎從不觸發）。
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

    /** [重檢13] 剩餘任務（times>0）對某 key 的需求總量，輪數以 [重檢1] 的 cap 截斷（兩層判準對齊）。
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
                long capped = Math.min(times, gtocraftfix$TOPUP_ROUNDS_CAP);
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

    /** [重檢9] job.isOrder 反射直讀（比 registry name 比對穩）；欄位不存在（GTO 改版）回 false 不擋。 */
    private static boolean gtocraftfix$isOrderJob(Object job) {
        try {
            if (gtocraftfix$fIsOrder == null) {
                if (gtocraftfix$fIsOrderTried) {
                    return false;
                }
                gtocraftfix$fIsOrderTried = true;
                var f = job.getClass().getDeclaredField("isOrder");
                f.setAccessible(true);
                gtocraftfix$fIsOrder = f;
            }
            return gtocraftfix$fIsOrder.getBoolean(job);
        } catch (Throwable ignored) {
            return false;
        }
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

    /** [重檢2] 把 job.allocations 裡掛在舊樣板 definition 的配額條目移除、以新樣板為 key 併回（quota 相加）。
     *  配額為空（lpcalc 機器單恆空）或欄位不存在（無配額機制的 GTO 版本）回 true；反射失敗回 false
     *  （呼叫端放棄該筆換綁——保持孤兒勝過換綁後被 INSUFFICIENT_PRIORITY 永凍）。 */
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
     * 成品自我認領：gtolib 的交付認領只掛在 CPU 的插入事件上；產出機器若自帶 ME 連接
     * （me_pattern_buffer 案例），產物會繞過認領 hook 直接進 CPU 庫存——job 拿著成品卻
     * 記不了帳，waitingFor 也是空的，餵料／陳舊解鎖／補輸入三層全部無感。
     * 救援：成品在 CPU 庫存滯留 ≥60 秒且數量不動 → 補 waitingFor 假帳、走正規 insert 觸發認領。
     * 注意 GTO insert 對成品「先按全額銷帳、後問 link」（remainingAmount 用 amount 非 link 實收）：
     * 認領量須扣掉已銷帳留庫（claimedFinalHeld）與剩餘任務需求（自催化配方），殘額送網路而非留庫，
     * 否則同批物品會被每 ~60 秒重複燒 remainingAmount → 偽完單短交。反射全軟失敗。
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
            // [重檢5] 冷卻檢查：曾被 link 全拒（standalone 玩家單恆回 0）→ 10 分鐘內不重試。
            // 舊版不查冷卻、每 ~63 秒重試，每次都對同批成品重燒 remainingAmount。
            Integer ru = gtocraftfix$refusedGet(cluster, fk);
            if (ru != null && gtocraftfix$tickCounter - ru < 12000) {
                return false;
            }
            // 數量有變動＝機器還在產出，不是滯留 → 年齡歸零（[重檢14] 計時與陳舊等待分表）
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
            if (gtocraftfix$fWaitingFor == null) {
                var fw = job.getClass().getDeclaredField("waitingFor");
                fw.setAccessible(true);
                gtocraftfix$fWaitingFor = fw;
            }
            Object wf = gtocraftfix$fWaitingFor.get(job);
            if (!(wf instanceof appeng.crafting.inv.ListCraftingInventory wli)) {
                return false;
            }
            // [重檢5] 認領上限＝持有 − 已銷帳留庫（餵料殘額，帳已被 GTO insert 銷過一次，再認領＝二次燒）
            //         − 剩餘任務對成品的 capped 需求（自催化配方保留工作料；[重檢13] 同公式）
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
                // [重檢6] 例外補償：收回假帳＋料放回 CPU 庫存再拋。舊版半途拋出會讓假帳永駐 waitingFor
                //（waiting 從此非空 → 本層永不再跑、陳舊解鎖又跳過成品 → 無人能清）＋物品蒸發。
                wli.extract(fk, got, Actionable.MODULATE);
                inv.insert(fk, got, Actionable.MODULATE);
                throw t;
            }
            if (accepted < got) {
                // [重檢5] 帳目回滾刪除：GTO insert 銷帳用 amount 非 link 實收，此刻假帳已被吃光、無帳可回滾
                //（舊 wli.extract 是對空帳的 no-op）；殘額改送網路（本 CPU waitingFor 已空 → 攔截層回 0
                // 不再入）＝standalone 單的正確交付地，且不留 CPU 庫存供下輪重燒。
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
                                            appeng.me.cluster.implementations.CraftingCPUCluster cluster) {
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
            var inv = (appeng.crafting.inv.ListCraftingInventory) gtocraftfix$fInv.get(logic);
            if (tasks == null || inv == null || tasks.isEmpty()) {
                return false;
            }
            int fed = 0;
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
                    // [重檢1] 輪數 cap：need=per×min(times,cap)。舊版 per×times 無上限（溢位還 fallback
                    // Long.MAX/4＝實質抽光全網）：x10M 常備單會把該料全量吸進單一 CPU 鎖到完單，
                    // 其他 CPU／機器全部餓死；job 存續期執行器只取不還，storeItems 只在完單後跑。
                    // cap 內的量完單時由 storeItems 退回網路，不會遺失；每 100 tick 會再續補。
                    long need;
                    try {
                        need = Math.multiplyExact(per, Math.min(times, gtocraftfix$TOPUP_ROUNDS_CAP));
                    } catch (ArithmeticException e) {
                        need = per; // [重檢1] 溢位保底一輪（初始提料本來就會拿的合法量）
                    }
                    long have = inv.list.get(ik);
                    if (have >= need) {
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
                                    ik, got, times, gtocraftfix$TOPUP_ROUNDS_CAP);
                        }
                    }
                    if (have + got < per) {
                        // 連一輪都湊不齊且網路已乾 → 執行器會無聲跳過此任務；至少留下可見證據
                        if (gtocraftfix$logWarnOk()) {
                            LOG.warn("[craftfix] 任務缺料 {}：每輪需 {}、CPU 有 {}、網路已乾（{} 任務被無聲跳過）",
                                    ik, per, have + got, pat.getPrimaryOutput().what());
                        }
                    }
                }
            }
        } catch (Throwable t) {
            if (gtocraftfix$logErrOk()) { // [重檢7]
                LOG.error("[craftfix] 補輸入例外", t);
            }
        }
        return acted;
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
