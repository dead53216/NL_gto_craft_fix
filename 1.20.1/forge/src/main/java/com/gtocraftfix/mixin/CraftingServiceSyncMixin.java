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

    /**
     * [slim 分支] 精簡版：**只保留四項會改行為的修正**——①算料同步化（終端 ctrl+左鍵）
     * ②機器源 present-once IgnoreMissing（請求器/接口/合成卡）③並行死角解鎖
     * ④機器源降量重算（3.1.0 加回；lpcalc 停用後它是 CRAFT_LESS 的唯一處理者）。
     * 其餘（無樣板守衛、lpcalc 接管、計畫修補＋真缺料擋單＋拒收退化計畫、保母餵料/補輸入）
     * 全部停用但**程式碼保留、log 照印**，方便對照觀察 GTO 原生行為。
     * 改 false 即恢復完整版行為（各處以 {@code gtocraftfix$SLIM} 判斷）。
     */
    private static final boolean gtocraftfix$SLIM = true;

    @Shadow(remap = false)
    @Final
    private IGrid grid;

    @Shadow(remap = false)
    @Final
    private Set<CraftingCPUCluster> craftingCPUClusters;

    private static volatile Method gtocraftfix$executeV2;
    private static volatile boolean gtocraftfix$resolved;
    private static final AtomicInteger gtocraftfix$sitterLog = new AtomicInteger();
    private int gtocraftfix$tickCounter;
    private final Set<AEKey> gtocraftfix$noPatternLogged = new HashSet<>();
    /** 成品餵料被 link 拒收（回 0）的 key → 拒收時 tick；10 分鐘內不再試（防 x0 空轉）。 */
    private final Map<AEKey, Integer> gtocraftfix$feedRefused = new HashMap<>();
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
    private final Set<String> gtocraftfix$failLogged = new HashSet<>();
    /** [2.0.1 純診斷] 欄位普查已做過的 cluster（每場遊戲每 cluster 只倒一次，避免洗版）。 */
    private final Set<String> gtocraftfix$censusDone = new HashSet<>();

    /** [2.0.1 純診斷] 反射倒出物件全類別鏈的實例欄位（名稱=精簡值）；集合印型別(大小)，其餘截 60 字。 */
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

    /** [3.2.1 純診斷] 該 key 最近被推送到哪些供應器（GTO 的 {@code getPendingRequests(AEKey)} 公開 API，
     *  反射呼叫）：印前 2 個座標。語意＝「樣板送去過這裡」，貨沒回來時的第一現場。讀不到回 null。 */
    private static String gtocraftfix$pendingAt(Object logic, AEKey key) {
        try {
            Object r = logic.getClass().getMethod("getPendingRequests", AEKey.class).invoke(logic, key);
            if (!(r instanceof java.util.Collection<?> col) || col.isEmpty()) {
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

    /** [2.3.1 純診斷] 供應器位置「類型(x,y,z)」；BlockEntity 直取，GTO 機器類走反射
     *  （getPos/gto$getPos/getBlockPos），都讀不到回 null。 */
    private static String gtocraftfix$provAt(Object p) {
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
            if (bp == null) {
                return cn;
            }
            return cn + "(" + bp.getX() + "," + bp.getY() + "," + bp.getZ() + ")";
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** [2.0.1 純診斷] CPU 內部庫存（CraftingCpuLogic.inventory）反射存取；不可用回 null。 */
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

    // ---- 修正 1：算料同步化（修好終端 ctrl+左鍵多步卡死）----
    @Inject(method = "beginCraftingCalculation", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtocraftfix$syncCalc(Level level, ICraftingSimulationRequester simRequester, AEKey what, long amount,
                                      CalculationStrategy strategy, CallbackInfoReturnable<Future<ICraftingPlan>> cir) {
        if (!gtocraftfix$resolved) {
            synchronized (CraftingServiceSyncMixin.class) {
                if (!gtocraftfix$resolved) {
                    gtocraftfix$executeV2 = gtocraftfix$resolve();
                    gtocraftfix$resolved = true;
                    if (gtocraftfix$executeV2 == null) {
                        LOG.warn("[craftfix] OptimizedCalculation.executeV2 找不到 → 不接管算料，退回原本 async。");
                    } else {
                        if (gtocraftfix$SLIM) {
                            LOG.info("[craftfix] 已啟用 slim：只保留 同步算料(ctrl+左鍵)＋機器源 IgnoreMissing(請求器)"
                                    + "＋並行死角解鎖＋降量重算；守衛/lpcalc/計畫修補/保母 皆停用（僅印 log）。");
                        } else {
                            LOG.info("[craftfix] 已啟用完整版：同步算料＋機器源 IgnoreMissing＋保母；lpcalc={}。",
                                    com.gtocraftfix.lpcalc.LpConfig.enabled() ? "on" : "off");
                        }
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
                    if (!gtocraftfix$SLIM && level instanceof ServerLevel sl) {
                        sl.getServer().getPlayerList().broadcastSystemMessage(
                                net.minecraft.network.chat.Component.literal("[合成修復] 無樣板，已擋下自動合成請求：")
                                        .append(what.getDisplayName())
                                        .append(net.minecraft.network.chat.Component.literal(" x" + amount)),
                                false);
                    }
                }
                final AEKey fWhat = what;
                final long fAmount = amount;
                if (gtocraftfix$SLIM) {
                    return; // [slim] 不擋單，交回 GTO 原生流程（上方 WARN 仍留紀錄）
                }
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
            if (machineSrc0 && !gtocraftfix$SLIM) { // [slim] 停用 lpcalc 接管：機器源與玩家一樣走下方同步 executeV2
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
            // [slim 3.1.0] 降量重算啟用：lpcalc 停用後，本段是機器源 CRAFT_LESS 的唯一處理者
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
                        int c = gtocraftfix$sitterLog.incrementAndGet();
                        if (c <= 200) {
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
        if (!(job instanceof CraftingPlan plan)) {
            return;
        }
        if (gtocraftfix$SLIM) {
            // [slim] 計畫修補＋真缺料擋單＋拒收退化計畫全部停用，計畫原封不動交給 GTO；
            // 只留一行紀錄（sim 計畫與缺料在此可見，方便對照原生行為）
            int c0 = gtocraftfix$sitterLog.incrementAndGet();
            if (c0 <= 200 && (plan.simulation() || !plan.missingItems().isEmpty())) {
                LOG.info("[craftfix] 放行未修補計畫 out={} sim={} missing={} 任務數={}",
                        plan.finalOutput(), plan.simulation(), plan.missingItems().size(),
                        plan.patternTimes().size());
            }
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
                    int c = gtocraftfix$sitterLog.incrementAndGet();
                    if (c <= 200) {
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
                    int c = gtocraftfix$sitterLog.incrementAndGet();
                    if (c <= 200) {
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
                int c = gtocraftfix$sitterLog.incrementAndGet();
                if (c <= 200) {
                    LOG.info("[craftfix] 循環自舉缺口 {} x{}（out={}）", b[0], b[1], plan.finalOutput());
                }
            }
            }
            used.removeZeros();
            missing.removeZeros();
            if (repaired > 0) {
                int c = gtocraftfix$sitterLog.incrementAndGet();
                if (c <= 200) {
                    LOG.info("[craftfix] 計畫修補 out={} 補{}項：{}", plan.finalOutput(), repaired, note);
                }
            }
            // sim 計畫的缺全補齊 → 翻回可執行，machine+sim 守衛不再靜默拒單（手動能、自動不能的分歧點）
            if (plan.simulation() && missing.size() == 0) {
                try {
                    var f = CraftingPlan.class.getDeclaredField("simulation");
                    f.setAccessible(true);
                    f.setBoolean(plan, false);
                    int c = gtocraftfix$sitterLog.incrementAndGet();
                    if (c <= 200) {
                        LOG.info("[craftfix] 計畫修補 sim→可執行 out={}", plan.finalOutput());
                    }
                } catch (Throwable t) {
                    int c = gtocraftfix$sitterLog.incrementAndGet();
                    if (c <= 5) {
                        LOG.warn("[craftfix] sim 旗標翻轉失敗（維持原行為）：{}", t.toString());
                    }
                }
            }
        } catch (Throwable t) {
            int c = gtocraftfix$sitterLog.incrementAndGet();
            if (c <= 5) {
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
            int c = gtocraftfix$sitterLog.incrementAndGet();
            if (c <= 200) {
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

    // ---- [3.2.0 純診斷] 認領歸屬追蹤：AE2 的 insertIntoCpus 對 craftingCPUClusters（HashSet，順序任意）
    // 逐顆呼叫 logic.insert，**不認這批貨是誰訂的**——只要哪顆 CPU 的 waitingFor 有這個 key 就先給誰。
    // 多單共用中間料時「A 訂的貨被 B 領走」在此發生：HEAD 記候選、RETURN 比對誰實際吃到。
    // 只在候選 ≥2（可能誤認領）時印，避免每筆入庫都洗版。 ----
    private java.util.List<Object[]> gtocraftfix$claimSnap;
    private static final AtomicInteger gtocraftfix$claimLog = new AtomicInteger();

    @Inject(method = "insertIntoCpus", at = @At("HEAD"), remap = false)
    private void gtocraftfix$claimHead(AEKey what, long amount, Actionable type,
                                       CallbackInfoReturnable<Long> cir) {
        gtocraftfix$claimSnap = null;
        if (type != Actionable.MODULATE || gtocraftfix$claimLog.get() > 300) {
            return;
        }
        try {
            java.util.List<Object[]> snap = null;
            for (var c : craftingCPUClusters) {
                long w = c.craftingLogic.getWaitingFor(what);
                if (w > 0) {
                    if (snap == null) {
                        snap = new java.util.ArrayList<>(4);
                    }
                    snap.add(new Object[] { c, w, c.craftingLogic.getFinalJobOutput() });
                }
            }
            if (snap != null && snap.size() >= 2) { // 單一候選＝正常交付，不記
                gtocraftfix$claimSnap = snap;
            }
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "insertIntoCpus", at = @At("RETURN"), remap = false)
    private void gtocraftfix$claimTail(AEKey what, long amount, Actionable type,
                                       CallbackInfoReturnable<Long> cir) {
        var snap = gtocraftfix$claimSnap;
        gtocraftfix$claimSnap = null;
        if (snap == null) {
            return;
        }
        try {
            var sb = new StringBuilder();
            for (var s : snap) {
                var c = (CraftingCPUCluster) s[0];
                long before = (Long) s[1];
                long after = c.craftingLogic.getWaitingFor(what);
                var fo = (appeng.api.stacks.GenericStack) s[2];
                sb.append(fo == null ? "?" : fo.what()).append("(等").append(before);
                if (after < before) {
                    sb.append("→吃下").append(before - after);
                }
                sb.append(") ");
            }
            gtocraftfix$claimLog.incrementAndGet();
            LOG.info("[craftfix][認領] {} x{} 交付：{}顆 CPU 同時在等 → {}",
                    what, amount, snap.size(), sb);
        } catch (Throwable ignored) {
        }
    }

    // ---- 修正 3：保母（[2.4.0] 每 tick 掃孤兒 waitingFor）＋ 診斷探針（每 20 秒）----
    // [2.4.0] 掛 HEAD 不掛 TAIL：GTOCore 在偶數 tick 提前 ci.cancel() 本方法，掛 TAIL 整段實跑半速
    // （原「5 秒」實為 10 秒、探針 20 秒實為 40 秒——log 探針間隔 40 秒實證）。
    @Inject(method = "onServerEndTick", at = @At("HEAD"), remap = false)
    private void gtocraftfix$tick(MinecraftServer server, CallbackInfo ci) {
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
                    // [3.2.0] 每個在途 key 印「等N/網M/別的CPU也等K」：分辨「貨沒回網路」「貨被別人領走」
                    String waitStr;
                    if (waiting.isEmpty()) {
                        waitStr = "(空)";
                    } else {
                        var cached = grid.getStorageService().getCachedInventory();
                        var wb = new StringBuilder();
                        int wn = 0;
                        for (var wk : waiting) {
                            if (wn++ >= 8) {
                                wb.append('…');
                                break;
                            }
                            long others = 0;
                            for (var c2 : craftingCPUClusters) {
                                if (c2 != cluster && c2.craftingLogic.getWaitingFor(wk) > 0) {
                                    others++;
                                }
                            }
                            wb.append(wk).append("(等").append(logic.getWaitingFor(wk))
                                    .append("/網").append(cached.get(wk));
                            if (others > 0) {
                                wb.append("/另").append(others).append("顆也等");
                            }
                            // [3.2.1] 樣板推去哪台機器：貨沒回來時直接指出現場（GTO 公開 API，反射呼叫）
                            String pend = gtocraftfix$pendingAt(logic, wk);
                            if (pend != null) {
                                wb.append("/推給").append(pend);
                            }
                            wb.append(") ");
                        }
                        waitStr = wb.toString();
                    }
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
                    // [2.0.1 純診斷] 剩餘任務 X 光（自 1.1.4/1.1.6/1.1.7 診斷段移植，零行為變動）：
                    // 主產物×次數＋供應器數＋最缺輸入格「CPU庫存/每輪需求」——
                    // 分辨「計畫沒排任務」「缺料不推」「有料不推」「樣板失聯(prov:0)」
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
                                    // prov:0 = 樣板失聯（供應器清單找不到機器）→ executeCrafting 空轉不留痕；
                                    // [2.3.0] 忙碌數：全忙時 executeCrafting 同樣不推、不留任何結果（與死角同貌）
                                    int provN = 0;
                                    int busyN = 0;
                                    String provAt = null;
                                    for (var p0 : ((CraftingService) (Object) this).getProviders(pat0)) {
                                        provN++;
                                        boolean b0 = false;
                                        try {
                                            b0 = p0.isBusy();
                                        } catch (Throwable ignored4) {
                                        }
                                        if (b0) {
                                            busyN++;
                                        }
                                        // [2.3.1] 忙碌機器座標：卡單時直接走過去看那台（優先印忙碌者）
                                        if (provAt == null || (b0 && !provAt.endsWith("忙"))) {
                                            String pa = gtocraftfix$provAt(p0);
                                            if (pa != null) {
                                                provAt = b0 ? pa + "忙" : pa;
                                            }
                                        }
                                        if (provN >= 9) {
                                            break;
                                        }
                                    }
                                    tb.append(",prov:").append(provN).append(",忙:").append(busyN);
                                    if (provAt != null) {
                                        tb.append('@').append(provAt);
                                    }
                                    // 印「最缺的那格」輸入——executeCrafting 任一格不足即無聲跳過，
                                    // 只看第一格會得到假健康
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
                    // [2.0.1 純診斷] 欄位普查（自 1.1.5 移植）：waiting 空＋有剩餘任務＝執行器不推但料在，
                    // 閘門多半在 gtolib 私有欄位裡；每 cluster 只倒一次
                    if (waiting.isEmpty() && !"n/a".equals(tasksStr) && !"(無)".equals(tasksStr)) {
                        String cid0 = Integer.toHexString(System.identityHashCode(cluster));
                        if (gtocraftfix$censusDone.add(cid0)) {
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
        // [2.4.0] 保母改每 tick（原 %100 在半頻下實為 10 秒一輪，兩單搶料時只能一點一點給）
        var storage = grid.getStorageService().getInventory();
        int handled = 0;
        for (var cluster : craftingCPUClusters) {
            if (handled >= 8) {
                break;
            }
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
                    if (gtocraftfix$SLIM) {
                        break; // [slim] 保母餵料停用（孤兒 waitingFor 交回 GTO 自己處理）
                    }
                    if (handled >= 8) {
                        break;
                    }
                    boolean isFinal = key.equals(finalOut.what());
                    if (isFinal) {
                        // 成品也要餵（成品回流網路但 CPU 沒攔到認領時，唯一救援路徑）；
                        // 但 link 被拒（訂單型死 link 回 0）會 x0 空轉 → 拒收記憶 10 分鐘。
                        Integer ru = gtocraftfix$feedRefused.get(key);
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
                    if (got > 0) {
                        long accepted = logic.insert(key, got, Actionable.MODULATE);
                        if (accepted < got) {
                            storage.insert(key, got - accepted, Actionable.MODULATE, cluster.getSrc());
                        }
                        if (accepted <= 0) {
                            if (isFinal) {
                                gtocraftfix$feedRefused.put(key, gtocraftfix$tickCounter);
                                if (gtocraftfix$feedRefused.size() > 128) {
                                    gtocraftfix$feedRefused.clear();
                                }
                            }
                            continue;
                        }
                        handled++;
                        int c = gtocraftfix$sitterLog.incrementAndGet();
                        if (c <= 200) {
                            LOG.info("[craftfix] 保母餵料 {} x{}", key, accepted);
                        }
                    }
                }
                // 輸入補給：剩餘任務輸入不足一輪＝每 tick 取料失敗、無聲凍結 → 從網路補進 CPU 庫存。
                // [2.4.0] 閘門放寬：原本只在 waiting 空時跑，但「有在途＋另一任務缺料」（兩單搶料實錄：
                // 在途 qbit 晶圓擋住整個補給）同樣要補；本輪沒餵到料時一律跑。
                if (!gtocraftfix$SLIM // [slim] 補輸入停用
                        && (waiting.isEmpty() || handled == handledBefore) && handled < 8) {
                    gtocraftfix$topUpInputs(logic, storage, cluster.getSrc());
                }
                // [2.1.0] 並行死角解鎖：上游 executeCrafting 對 parallel==1 永久無聲跳過（見方法 javadoc）
                gtocraftfix$unjamParallelOne(logic, storage, cluster.getSrc());
            } catch (Throwable t) {
                int c = gtocraftfix$sitterLog.incrementAndGet();
                if (c <= 5) {
                    LOG.error("[craftfix] 保母例外", t);
                }
            }
        }
    }

    /** [2.4.0] 補輸入輪數上限：預設無限（＝一次補滿全部剩餘輪，兩單搶料時靠「先到先贏」序列化
     *  打破 50/50 分食）。舊版全額曾把單料全網存量吸進單一 CPU 餓死其他單——真出事就用
     *  `-Dgtodiag.topupRounds=N` 收斂（N=1 即回 1.1.0 的每次一輪）。 */
    private static final long gtocraftfix$TOPUP_ROUNDS = gtocraftfix$topupRoundsProp();

    private static long gtocraftfix$topupRoundsProp() {
        try {
            long v = Long.parseLong(System.getProperty("gtodiag.topupRounds", "0"));
            return v > 0 ? v : Long.MAX_VALUE;
        } catch (Throwable ignored) {
            return Long.MAX_VALUE;
        }
    }

    /** 輸入補給：讀 GTO job 的剩餘任務，對「CPU 庫存 &lt; 需求」的主輸入從網路補足。反射全軟失敗。
     *  [2.4.0] 補到「剩餘輪數×每輪需求」（受 gtodiag.topupRounds 上限），原為固定一輪。 */
    private void gtocraftfix$topUpInputs(appeng.crafting.execution.CraftingCpuLogic logic,
                                         appeng.api.storage.MEStorage storage, IActionSource src) {
        try {
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
                return;
            }
            if (gtocraftfix$fTasks == null) {
                var ft = job.getClass().getDeclaredField("tasks");
                ft.setAccessible(true);
                gtocraftfix$fTasks = ft;
            }
            Map<?, ?> tasks = (Map<?, ?>) gtocraftfix$fTasks.get(job);
            var inv = (appeng.crafting.inv.ListCraftingInventory) gtocraftfix$fInv.get(logic);
            if (tasks == null || inv == null || tasks.isEmpty()) {
                return;
            }
            int fed = 0;
            for (var en : tasks.entrySet()) {
                if (fed >= 6) {
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
                long rounds = Math.min(times, gtocraftfix$TOPUP_ROUNDS); // [2.4.0] 全額（可用系統屬性收斂）
                var pat = (IPatternDetails) en.getKey();
                for (var input : pat.getInputs()) {
                    var poss = input.getPossibleInputs();
                    if (poss.length == 0) {
                        continue;
                    }
                    var ik = poss[0].what();
                    long per = poss[0].amount() * input.getMultiplier();
                    if (per <= 0) {
                        continue;
                    }
                    long need;
                    try {
                        need = Math.multiplyExact(per, rounds);
                    } catch (ArithmeticException e) {
                        need = per; // 溢位保底一輪
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
                            storage.insert(ik, got, Actionable.MODULATE, src); // 插不進退回網路，防蒸發
                            throw t;
                        }
                        fed++;
                        int c = gtocraftfix$sitterLog.incrementAndGet();
                        if (c <= 200) {
                            LOG.info("[craftfix] 保母補輸入 {} x{}（剩餘 {} 輪、目標 {} 輪份）",
                                    ik, got, times, rounds == Long.MAX_VALUE ? times : rounds);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // 反射不可用（模組未開放等）→ 靜默略過
        }
    }

    /** [2.1.0] gtolib 並行樣板介面反射解析（一次）；解析失敗保守視為並行（多補 1 輪料無害）。 */
    private static volatile Class<?> gtocraftfix$parallelIface;
    private static volatile boolean gtocraftfix$parallelIfaceTried;

    private static boolean gtocraftfix$isParallelPattern(Object pat) {
        if (!gtocraftfix$parallelIfaceTried) {
            try {
                gtocraftfix$parallelIface = Class.forName("com.gtolib.api.ae2.pattern.IParallelPatternDetails");
            } catch (Throwable ignored) {
            }
            gtocraftfix$parallelIfaceTried = true;
        }
        var c = gtocraftfix$parallelIface;
        return c == null || c.isInstance(pat);
    }

    /** [2.1.0] 並行死角解鎖：上游 OptimizedCraftingCpuLogic.executeCrafting 的並行分支漏了
     *  parallel==1 的取料路徑——「並行樣板＋剩餘輪數>1＋庫存恰夠 1 輪」被每 tick 無聲跳過、永久卡死
     *  （中子反射板 x2 實錄；催化劑返還配方按淨需求備料必然踩中，已回報上游）。
     *  解法：命中指紋（min⌊庫存/每輪⌋==1 且 times>1）時把各輸入從網路補到 2 輪份，讓 GTO 自己的
     *  parallel>1 分支正常取料推送——只補料，不代推送、不碰帳目。網路無貨則無操作（下輪再試）。 */
    private void gtocraftfix$unjamParallelOne(appeng.crafting.execution.CraftingCpuLogic logic,
                                              appeng.api.storage.MEStorage storage, IActionSource src) {
        try {
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
                return;
            }
            if (gtocraftfix$fTasks == null) {
                var ft = job.getClass().getDeclaredField("tasks");
                ft.setAccessible(true);
                gtocraftfix$fTasks = ft;
            }
            Map<?, ?> tasks = (Map<?, ?>) gtocraftfix$fTasks.get(job);
            var inv = (appeng.crafting.inv.ListCraftingInventory) gtocraftfix$fInv.get(logic);
            if (tasks == null || inv == null || tasks.isEmpty()) {
                return;
            }
            for (var en : tasks.entrySet()) {
                Object holder = en.getValue();
                if (gtocraftfix$fHolderVal == null) {
                    var fv = holder.getClass().getField("value");
                    fv.setAccessible(true);
                    gtocraftfix$fHolderVal = fv;
                }
                long times = gtocraftfix$fHolderVal.getLong(holder);
                if (times <= 1) {
                    continue; // 剩 1 輪走一般取料分支，無死角
                }
                var pat = (IPatternDetails) en.getKey();
                if (!gtocraftfix$isParallelPattern(pat)) {
                    continue; // 非並行樣板走 else 分支，無死角
                }
                // 死角指紋：可並行數==1（照抄 getMaxParallel 的算法：替代品加總、除以 multiplier 取整）
                long mp = Long.MAX_VALUE;
                for (var input : pat.getInputs()) {
                    var poss = input.getPossibleInputs();
                    if (poss.length == 0) {
                        continue;
                    }
                    long units = 0;
                    var seen = new HashSet<AEKey>();
                    for (var ps : poss) {
                        if (ps.amount() > 0 && seen.add(ps.what())) {
                            units += inv.list.get(ps.what()) / ps.amount();
                        }
                    }
                    if (input.getMultiplier() > 0) {
                        mp = Math.min(mp, units / input.getMultiplier());
                    }
                    if (mp == 0) {
                        break;
                    }
                }
                if (mp != 1) {
                    continue; // 0=真缺料（輸入補給負責）；>=2 取料正常，皆非死角
                }
                for (var input : pat.getInputs()) {
                    var poss = input.getPossibleInputs();
                    if (poss.length == 0) {
                        continue;
                    }
                    var ik = poss[0].what();
                    long per = poss[0].amount() * input.getMultiplier();
                    if (per <= 0) {
                        continue;
                    }
                    long have = inv.list.get(ik);
                    long target = 2 * per;
                    if (have >= target) {
                        continue;
                    }
                    long got = storage.extract(ik, target - have, Actionable.MODULATE, src);
                    if (got > 0) {
                        try {
                            inv.insert(ik, got, Actionable.MODULATE);
                        } catch (Throwable t) {
                            // 例外補償：插不進就退回網路再拋，防半套蒸發
                            storage.insert(ik, got, Actionable.MODULATE, src);
                            throw t;
                        }
                        int c = gtocraftfix$sitterLog.incrementAndGet();
                        if (c <= 200) {
                            LOG.info("[craftfix] 並行死角解鎖 {}：補 {} x{}（湊滿 2 輪，繞過上游 parallel==1 取料漏洞）",
                                    pat.getPrimaryOutput().what(), ik, got);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // 反射不可用 → 靜默略過（純救援層，失效不影響原行為）
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
