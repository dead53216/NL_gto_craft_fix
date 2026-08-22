package com.gtocraftfix.mixin;

import com.gtocraftfix.support.CraftingHotfixSupport;
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
 *   <li><b>保母（只餵料）</b>（{@code onServerEndTick}）：job 的 {@code waitingFor} 缺口若網路有現貨，
 *       只在「非 final、全單無樣板在途、反射狀態已知」時搬進 CPU；幻影模式每秒掃一次，明確關掉
 *       phantom-only 時才每 tick 掃。它不代下巢狀合成單；網路無貨就維持等待，不另生任務。</li>
 * </ol>
 */
@Mixin(value = CraftingService.class, priority = 1500, remap = false)
public abstract class CraftingServiceSyncMixin {

    private static final Logger LOG = LogManager.getLogger("gtocraftfix");

    /**
     * [slim 分支] 精簡版：保留 ①算料同步化（終端 ctrl+左鍵）②機器源 present-once IgnoreMissing
     * （請求器/接口/合成卡）③並行死角解鎖 ④機器源降量重算（3.1.0）⑤**計畫修補（3.3.0）**——
     * 根因二實證後加回：算料器把網路沒有、也沒排生產的量寫進 usedItems，IgnoreMissing 讓它變成
     * 永遠等不到的 waitingFor；修補把缺口補成真正的樣板輪次，是這條鏈唯一的根治點。
     * 仍停用：lpcalc 接管、真缺料擋單、保母補輸入。機器源「無樣板／退化計畫」會明確拒收，避免
     * used-only 計畫上機永凍；保母只餵可證明無在途的非成品幻影 key。
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
    /** 餵料回補異常另設 ERROR 額度，避免壞掉的 storage 每 tick 洗滿 log。 */
    private static final AtomicInteger gtocraftfix$feedErrorLog = new AtomicInteger();
    /** [3.13.6] 保母略過原因的 log 額度（每場 100 行）。 */
    private static final AtomicInteger gtocraftfix$feedSkipLog = new AtomicInteger();
    private int gtocraftfix$tickCounter;

    /**
     * [3.13.4] 缺料通知的重播間隔（秒）。`0`／負數＝退回 3.13.3 的「每個 key 只通知一次」。
     * 請求器每 10 秒重試一次，所以這裡不能設太小，否則同一個缺料會把聊天室洗掉。
     */
    private static final int gtocraftfix$NOTIFY_REPEAT_SEC =
            Integer.getInteger("gtodiag.notifyRepeatSec", 300);

    /**
     * 同一個「無樣板可做」的 key 是否該再通知一次（log 與聊天室共用同一個節流）。
     * 表大小有硬上限；超過就先掃掉過期項，仍過大才整表清掉。
     */
    private boolean gtocraftfix$shouldNotifyNoPattern(AEKey key) {
        int now = gtocraftfix$tickCounter;
        Integer last = gtocraftfix$noPatternNotified.get(key);
        if (last != null) {
            if (gtocraftfix$NOTIFY_REPEAT_SEC <= 0) {
                return false; // 明確要求「只通知一次」
            }
            if (now - last < gtocraftfix$NOTIFY_REPEAT_SEC * 20) {
                return false;
            }
        }
        gtocraftfix$noPatternNotified.put(key, now);
        if (gtocraftfix$noPatternNotified.size() > 128) {
            int keepAfter = gtocraftfix$NOTIFY_REPEAT_SEC <= 0 ? 0 : gtocraftfix$NOTIFY_REPEAT_SEC * 20;
            gtocraftfix$noPatternNotified.entrySet().removeIf(e -> now - e.getValue() >= keepAfter);
            if (gtocraftfix$noPatternNotified.size() > 128) {
                gtocraftfix$noPatternNotified.clear();
            }
            gtocraftfix$noPatternNotified.put(key, now);
        }
        return true;
    }
    /**
     * [3.13.4] 「這個 key 沒樣板可做」的通知節流：key → 上次通知的 tick。
     * <p>3.13.3 以前是一次性 {@code Set}，同一個 key 一輩子只通知一次。實測（2026-08-21）
     * 稀土金屬粉的請求器連續 378 次提交失敗，玩家從頭到尾只在開服後第 2 秒收到過一行字，
     * 之後完全靜音——「缺料不會通知」就是這個。改成每 {@code gtodiag.notifyRepeatSec} 秒可再通知一次。
     */
    private final Map<AEKey, Integer> gtocraftfix$noPatternNotified = new HashMap<>();
    /** storage 暫時拒收時由本 mod 保管、下 tick 優先回補的餵料餘額。 */
    private final Map<AEKey, Long> gtocraftfix$feedRefunds = new HashMap<>();
    /**
     * NetworkStorage／CPU waiting 帳一旦無法以 before/after 證明，整張 grid 的自動搬料永久隔離到重啟；
     * 不可再重播「可能已部分成功」的數量。map 只供 ERROR 說明人工介入範圍，絕不自動套用。
     */
    private boolean gtocraftfix$transferQuarantined;
    private final Map<AEKey, Long> gtocraftfix$manualTransferAttention = new HashMap<>();
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
    // [3.14.0] CraftingLinkNexus 的私有欄位（canceled/done/req/cpu/tickOfDeath）
    private static volatile java.lang.reflect.Field[] gtocraftfix$fNexus;
    private static volatile java.lang.reflect.Field gtocraftfix$fCraftingLinks;
    private static volatile boolean gtocraftfix$nexusResolveFailed;
    /** 同一個 craftId 的 link 異常只在狀態改變時再印一次。 */
    private final Map<java.util.UUID, String> gtocraftfix$linkReported = new HashMap<>();
    private static final AtomicInteger gtocraftfix$linkLog = new AtomicInteger();
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

    /** [3.5.0] 收支診斷專用額度（與 sitterLog 分開，避免被其他訊息燒光而靜音）；[3.7.0] 上限拉到 5000。 */
    private static final AtomicInteger gtocraftfix$balLog = new AtomicInteger();
    private static final int gtocraftfix$BAL_MAX = 5000;

    /**
     * [3.11.2／X2] 3.11.x 新增訊息的獨立額度。M5 才把配平觀測移出 balLog，同一輪的 M1／M2 又各自
     * 新增一個 balLog 消費者——機器源每 2 秒重試一次的話，5000 次額度約 2.8 小時就會被燒光，
     * 3.8.0 時代的四種診斷（開單即缺／最終產出短缺／循環自舉缺口／計畫修補）全部跟著靜音。
     */
    private static final AtomicInteger gtocraftfix$repairNoteLog = new AtomicInteger();
    private static final int gtocraftfix$NOTE_LOG_MAX = 2000;

    /**
     * [3.11.2／M5] 配平觀測（{@code repairBalanceLog} 預設 on）專用額度。
     * <p>原本與「開單即缺／最終產出短缺／循環自舉缺口／計畫修補」共用 {@link #gtocraftfix$balLog}
     * 的終身 5000 次額度——配平觀測是**每次提交必印一行**的新增訊息，會把 3.8.0 時代就有的診斷
     * 提前燒光而靜音（等於用新診斷換掉舊診斷）。獨立額度後兩邊互不影響。
     */
    private static final AtomicInteger gtocraftfix$balanceLog = new AtomicInteger();
    private static final int gtocraftfix$BALANCE_LOG_MAX = 2000;

    /** [3.11.2／M5] B2「修補後最終產出仍不足」驗證專用額度（理由同上，也不與 balLog 共用）。 */
    private static final AtomicInteger gtocraftfix$finalCheckLog = new AtomicInteger();
    private static final int gtocraftfix$FINAL_CHECK_LOG_MAX = 2000;

    /** [3.11.2／M4] 缺口來源標記讀不到（SRC_UNKNOWN）的 WARN 額度：只印前 5 次，避免逐項洗版。 */
    private static final AtomicInteger gtocraftfix$srcUnknownLog = new AtomicInteger();

    // ==================== [3.11.0] 修補旗標 ====================
    // 背景：3.9.0～3.10.2 四個版本裡有兩次實測退步（3.9.0 讓 LUV 電路交付從 466/500 掉到 8/500；
    // 3.10.0 把 157 任務／217 萬輪的正常 UHV 計畫每 10 秒擋一次）。原因是多個行為綁在一起上線，
    // 出事時無法定位。**3.11.0 把 3.9–3.10 新增的行為全部改成「程式碼保留、預設關閉」**，
    // 三個上限退回 3.8.0 值（實測最好的一版），每項都能單獨開關以便在遊戲內 A/B。
    // 刻意不做「一鍵全開」的總開關——那正是釀成退步的做法。

    /**
     * [3.8.0/3.13.2] 修補迴圈可處理的缺口數上限，預設 20000。
     * （3.8.0 實測：LUV 電路修補補 3650 項即完成、交付 466/500；現在保留更大的安全餘裕。）
     * 更早的 96 是「幻影缺口時代」的值，已證實會讓大電路交出半套計畫而必凍。
     * 上限本身必須留（遞迴補料遇循環配方可以無限長，而且跑在伺服器主緒），**耗盡時整組還原**。
     */
    private static final int gtocraftfix$REPAIR_GUARD = Integer.getInteger("gtodiag.repairGuard", 20000);

    /**
     * [3.10.1/3.13.2] 修補的**共用時間**預算（毫秒），預設 200（約 4 個 tick）；0＝停用。
     * 毫秒轉奈秒使用 checked／saturating arithmetic，極大值不會溢位成負數；主迴圈與 bootstrap
     * 共用同一個起始時間，不會在昂貴子步驟重新取得一份預算。
     */
    private static final long gtocraftfix$REPAIR_BUDGET_MS = Long.getLong("gtodiag.repairBudgetMs", 200L);
    private static final long gtocraftfix$REPAIR_BUDGET_NS =
            CraftingHotfixSupport.budgetNanos(gtocraftfix$REPAIR_BUDGET_MS);

    /**
     * [3.8.0/3.11.0] 修補可新增的「總輪數」上限，**預設退回 3.8.0 的 200 萬**。
     * ⚠ 3.10.0 把它放到 2000 萬時的理由「計畫過大 GTO 自己會回 NO_SUITABLE_CPU_FOUND」是**錯的**：
     * CPU 挑選與 trySubmitJob 都是拿 {@code plan.bytes()} 比 {@code cluster.getAvailableStorage()}，
     * 而 bytes 是 CraftingPlan 的 final 欄位、修補全程沒動過 → 那道保護從未觸發，
     * 補了 200 萬輪的計畫照樣會落在一顆小 CPU 上。這個上限是目前唯一的規模護欄。
     */
    private static final long gtocraftfix$REPAIR_RUN_CAP = Long.getLong("gtodiag.repairRunCap", 2_000_000L);

    /**
     * [3.13.0] 修補上限的**比例項**：實際上限 = min(runHardCap, max(runCap, 原計畫總輪數 × runFactor))。
     * <p>病灶（3.12.0 實錄）：{@code universal_circuit_uhv x100} 的原計畫 1,119,456 輪，修補要新增
     * 2,011,267 輪 → 只超過固定上限 200 萬的 **0.56%** 就整組還原、照原樣送出 → CPU 等 17 種
     * 「網存 0 且無任務產它」的料，靜止 600 秒以上（＝必凍）。
     * <p>固定常數的問題是它與計畫規模無關：小計畫的 200 萬形同無限，大計畫的 200 萬卻在正常修補量
     * 就撞牆。改成「跟著原計畫規模縮放」＝同一個相對嚴格度適用所有尺寸；真正的**發散**護欄是
     * {@code repairBudgetMs}（時間預算）與 {@code runHardCap}（絕對天花板），不是這條。
     * <p>係數設 0 ＝停用比例項、退回純固定上限（3.8.0～3.12.0 行為）。
     */
    private static final long gtocraftfix$REPAIR_RUN_FACTOR = Long.getLong("gtodiag.repairRunFactor", 4L);

    /** [3.13.0] 比例上限的絕對天花板：再大的計畫也不會讓修補超過這個新增輪數。 */
    private static final long gtocraftfix$REPAIR_RUN_HARD_CAP =
            Long.getLong("gtodiag.repairRunHardCap", 50_000_000L);

    /** [3.9.0/3.11.0] 缺口優先吃網路現貨（否則一律排樣板）。預設 **off**＝3.8.0 行為。 */
    private static final boolean gtocraftfix$REPAIR_NET_SPOT = Boolean.getBoolean("gtodiag.repairNetSpot");

    /** [3.9.1/3.11.0] 內部配平缺口用網路現貨補齊（第五維）。預設 **off**＝3.8.0 行為。 */
    private static final boolean gtocraftfix$REPAIR_BALANCE = Boolean.getBoolean("gtodiag.repairBalance");

    /** [3.10.2/3.11.0] 配平補齊在「修補中止」時也照做。預設 **off**＝維持全有全無。 */
    private static final boolean gtocraftfix$REPAIR_BALANCE_ON_ABORT =
            Boolean.getBoolean("gtodiag.repairBalanceOnAbort");

    /** [3.11.0] 即使不補齊，也照算一次內部配平缺口並印 log（純唯讀觀測，預設 on）。 */
    private static final boolean gtocraftfix$REPAIR_BALANCE_LOG =
            !"false".equalsIgnoreCase(System.getProperty("gtodiag.repairBalanceLog", "true"));

    /**
     * [3.10.0/3.13.2] 修補中止後擋下機器源提交：{@code off}（預設）／{@code on}／{@code force}。
     * {@code on} 在 slim 分支不生效；要在 slim 上擋一般中止單必須明寫 {@code force}。
     * 唯一安全例外是機器源的零任務退化計畫：不受此旗標與 slim 影響，一律拒收。
     */
    private static final String gtocraftfix$REPAIR_BLOCK =
            System.getProperty("gtodiag.repairBlockOnAbort", "off").trim().toLowerCase(java.util.Locale.ROOT);

    /** [3.10.0/3.11.0] 中止擋單時在聊天室廣播缺料（會對全伺服器玩家送出）。預設 off。 */
    private static final boolean gtocraftfix$REPAIR_ABORT_BROADCAST =
            Boolean.getBoolean("gtodiag.repairAbortBroadcast");

    /**
     * [3.10.1/3.11.0] 必凍探針：對「計畫無人產」的 usedItems 逐項做 SIMULATE 取料，判斷還原後的計畫
     * 是否必然凍結。擋單開啟時一定會跑；此旗標讓你在不擋單的情況下也留下判定 log。預設 off。
     */
    private static final boolean gtocraftfix$REPAIR_FREEZE_PROBE =
            Boolean.getBoolean("gtodiag.repairFreezeProbe");

    /**
     * [3.11.1／B1] 缺口沖銷方向：{@code on}（預設，完整來源判定）／{@code clamp}（不分來源，但沖銷量
     * 一律夾在 0 以上）／{@code off}（3.8.0 原樣，用於 A/B）。
     * <p>病灶：只有「usedItems 幻影」的缺口才該把 usedItems 降下來（那筆 usedItems 是網路給不出的謊報）；
     * 其他來源（新增 runs 的輸入／最終產出短缺／循環自舉）從來沒把量加進 usedItems，照樣沖銷就會把
     * usedItems **寫成負值**——KeyCounter.add 不擋負數、removeZeros 只刪 0、NetworkStorage.extract
     * 對負量回 0，而 GTO 的 tryExtractInitialItemsIgnoreMissing 對負量連 waitingFor 都不掛
     * → 該量在執行期無聲蒸發（計畫看起來補好了，實際少了一批料）。
     */
    private static final String gtocraftfix$REPAIR_DEFICIT_SRC =
            System.getProperty("gtodiag.repairDeficitSrc", "on").trim().toLowerCase(java.util.Locale.ROOT);

    /** [3.11.1／B1] 缺口來源標記（缺口元組第 4 欄）：來自 {@code missingItems}，可沖銷 missingItems。 */
    private static final String gtocraftfix$SRC_MISSING = "missing";
    /** [3.11.1／B1] 來源＝usedItems 幻影（網路給不出的謊報），**唯一**可沖銷 usedItems 的來源。 */
    private static final String gtocraftfix$SRC_USED = "used";
    /** [3.11.1／B1] 來源＝最終產出短缺（B2：沖銷 usedItems 會讓「加輪次」被自己抵銷成原地踏步）。 */
    private static final String gtocraftfix$SRC_FINAL = "final";
    /** [3.11.1／B1] 來源＝新增 runs 的輸入需求（從未加進 usedItems，不可沖銷）。 */
    private static final String gtocraftfix$SRC_INPUT = "input";
    /** [3.11.1／B1] 來源＝循環自舉（可執行性模擬猜的量，從未加進 usedItems，不可沖銷）。 */
    private static final String gtocraftfix$SRC_BOOTSTRAP = "bootstrap";
    /**
     * [3.11.2／M4] 來源不明的哨兵：缺口元組沒帶第 4 欄（日後新增入列點漏標）時用它。
     * <p>語意＝**什麼都不沖銷**（missingItems／usedItems 都不動）。原本預設值是 {@link #gtocraftfix$SRC_USED}，
     * 等於「漏標一個入列點就靜默退回 B1 的病灶」——把沒加進 usedItems 的量從 usedItems 扣掉 → 寫成負值 →
     * NetworkStorage.extract 對負量回 0、GTO 的 tryExtractInitialItemsIgnoreMissing 對負量連 waitingFor
     * 都不掛 → 該批料在執行期無聲蒸發。改成「不沖銷」最多是多留一筆 usedItems（保守、可執行），
     * 遇到時另印 WARN 讓人去補標，不會靜默壞掉。
     */
    private static final String gtocraftfix$SRC_UNKNOWN = "unknown";

    /**
     * [3.11.1／B6，3.11.2／M1 改預設 off] 外圈 4 輪跑滿仍有未解缺口時視為「修補沒做完」＝中止（整組還原）。
     * <p>⚠ **預設 false**，理由（實證推導，勿再改回 true）：外圈 {@code while} 只有兩種出口
     * ——(a) 缺口清空，(b) guard／runCap／時間預算耗盡，而 (b) 一定會設 {@code abortReason} 並 break。
     * 因此正常路徑中「{@code abortReason == null} 且佇列非空」只會發生在第 4 輪末尾：
     * {@code findBootstrapDeficits} 又回報 soft 自舉缺口。防禦上仍逐筆驗證：只要混入任何 hard 缺口
     * 就無條件中止；此旗標只控制「確定全為 soft」時是否採嚴格中止。
     * 這種計畫被整組還原成**未修補的原計畫** → 幻影 usedItems 沒補 → IgnoreMissing 掛出永遠等不到的
     * waitingFor → CPU 必凍，比不修更糟。而且 {@code findBootstrapDeficits} 只用 {@code poss[0]} 主變體
     * 判斷，靠替代輸入滿足的樣板會被誤判成卡死、每輪回報同一筆 → 這是**確定性重現**，不是偶發。
     * <p>開成 true 只適合 A/B 觀測；預設路徑改成「印一行 WARN，不設 abortReason、不還原」。
     */
    private static final boolean gtocraftfix$REPAIR_STRICT_ROUNDS =
            Boolean.getBoolean("gtodiag.repairStrictRounds");

    /**
     * [3.11.1／B8] 修補新增輪次後，依比例把 {@code plan.bytes()} 調高（反射寫 final 欄位，作法同
     * simulation 翻轉）。**預設 off**：findSuitableCraftingCPU 與 trySubmitJob 都是拿 bytes 比
     * {@code cluster.getAvailableStorage()}，調高後原本擠得上小 CPU 的計畫會改吃 CPU_TOO_SMALL
     * ——這是行為變化，要自己開才生效。
     * <p>[3.11.2／M6a] 更正舊註解的事實錯誤：{@code javap} 證實 {@code appeng.crafting.CraftingPlan}
     * 是 {@code public final class}（**不是 record**）、欄位是 {@code private final long bytes}，
     * 而 JDK 21 對「非 record 的 final 實例欄位」在 {@code setAccessible(true)} 後**可寫**
     * （只有 record 元件與靜態 final 才會擋）。所以這裡不存在「反射寫不進去所以沒事」的僥倖：
     * 旗標一開，bytes 就一定會被改，CPU_TOO_SMALL 的行為變化**必然發生**。
     */
    private static final boolean gtocraftfix$REPAIR_UPDATE_BYTES =
            Boolean.getBoolean("gtodiag.repairUpdateBytes");

    /**
     * [3.11.1／B9] 循環自舉模擬（可執行性模擬）的 pass 數上限，預設 20 萬。
     * 回饋型配方會退化成每 pass 只前進 1 輪，而計畫合法可有數百萬輪、又跑在伺服器主緒
     * → 單次提交可卡住數秒。超過上限即視為「無法判定」，回空清單（不補自舉缺口）。
     */
    private static final int gtocraftfix$BOOTSTRAP_MAX_PASS =
            Integer.getInteger("gtodiag.bootstrapMaxPass", 200_000);

    /**
     * [3.11.1／B9，3.11.2／M7] 自舉模擬超上限的 WARN 去重鍵（成品 key 字串）。
     * <p>原本是「全域只印一次」——第一個踩到的成品把額度用光，之後任何成品都靜音，而超上限＝
     * 「本次跳過自舉補齊」是會影響計畫正確性的事實，必須看得到是**哪些**成品受影響。
     * 改成每個成品 key 印一次；上限 128 筆即 clear（同 noPatternNotified 的作法，避免無限長大）。
     * 方法是 static，故容器用 ConcurrentHashMap 的 key set（提交雖在伺服器主緒，但不假設）。
     */
    private static final Set<String> gtocraftfix$bootstrapCapLogged =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    // ---------------------------------------------------------------- [3.13.2] 機器源降量重算保險

    /**
     * [3.13.5 改回預設開啟] 機器源 CRAFT_LESS 的砍半重算。lpcalc 在 slim 停用，所以這段是機器來源
     * 大數量請求的**唯一**處理者；關掉＝那類請求什麼都做不出來。
     * <p>⚠ 3.13.2 曾以「純空轉」為由改成預設關閉，**那個判斷是錯的**：它的依據是
     * {@code [帳本] 單離場} 的「推送N輪／累計交付N」都是 0——而這兩個欄位正是 README
     * 「兩個不可用的診斷欄位」點名不可拿來證明「沒交付」的東西（每 tick 差分，單在同一 tick 收單就必印 0）。
     * 改用 3.12.0／B10 為此加的 link 三態來看，稀土金屬粉在 976／1953／3906／…／125000 這串
     * 砍半量上共有 1507 筆 {@code 單離場（正常完成）}，是真的在出貨。
     */
    private static final boolean gtocraftfix$MACHINE_DOWNSCALE =
            !"false".equalsIgnoreCase(System.getProperty("gtodiag.machineDownscale", "true"));

    /**
     * 一次降量工作共用的總預算；`0` 代表不設時間上限（＝3.13.1 以前的行為，也是預設）。
     * 砍半迴圈最多 12 趟 {@code executeV2}，設了預算就可能在還沒找到可行量時提早收手。
     */
    private static final long gtocraftfix$DOWNSCALE_BUDGET_MS =
            Long.getLong("gtodiag.machineDownscaleBudgetMs", 0L);
    private static final long gtocraftfix$DOWNSCALE_BUDGET_NS =
            CraftingHotfixSupport.budgetNanos(gtocraftfix$DOWNSCALE_BUDGET_MS);

    /**
     * 同一 grid（本 mixin 實例）／requester／key 的重試冷卻；`0`＝不冷卻（預設，＝3.13.1 以前的行為）。
     * 冷卻對「成功的降量」一樣會生效，而降量成功的單通常幾秒就做完，設 600 秒等於把產能砍到 1/100；
     * 只有在確認某個 key 真的在空轉時才拿它來收斂。
     */
    private static final long gtocraftfix$DOWNSCALE_COOLDOWN_SEC =
            Long.getLong("gtodiag.machineDownscaleCooldownSec", 0L);
    private static final long gtocraftfix$DOWNSCALE_COOLDOWN_NS =
            CraftingHotfixSupport.cooldownNanos(gtocraftfix$DOWNSCALE_COOLDOWN_SEC);

    private static final class DownscaleKey {
        private final ICraftingSimulationRequester requester;
        private final AEKey key;
        private final int hash;

        private DownscaleKey(ICraftingSimulationRequester requester, AEKey key) {
            this.requester = requester;
            this.key = key;
            this.hash = 31 * System.identityHashCode(requester) + key.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof DownscaleKey other
                    && requester == other.requester
                    && key.equals(other.key);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    private final Map<DownscaleKey, Long> gtocraftfix$downscaleLastAttempt = new HashMap<>();

    /** [3.10.0] 修補中止已通知過的成品（聊天室去重）。 */
    private final Set<String> gtocraftfix$abortNotified = new HashSet<>();

    // ---------------------------------------------------------------- [3.13.0] 執行期救援

    /**
     * [3.13.0] 保母餵料（把網路現貨直接補進 CPU 的 waitingFor 缺口）。slim 分支自 2.x 起停用，
     * 3.13.0 起**預設重新開啟**——但改成只餵「幻影 key」（見 {@link #gtocraftfix$FEED_PHANTOM_ONLY}）。
     * <p>理由（3.12.0 實錄）：{@code gtocore:order} 卡在 {@code supercritical_steam 等78.5億／網2450億}
     * ——CPU 等一個網路裡明明堆滿的東西，而沒有任何任務會產它（＝那筆 waitingFor 是執行期長出來的
     * 幻影，永遠不會有機器送貨來銷帳）。這種缺口除了從網路直接餵，沒有別的解。
     */
    private static final boolean gtocraftfix$FEED =
            !"false".equalsIgnoreCase(System.getProperty("gtodiag.sitterFeed", "true"));

    /**
     * [3.13.0/3.13.2] 餵料只針對**幻影 key**：沒有剩餘任務會產它，且整張 job 的
     * {@code pendingRequests} 已確定為空；final key 另有無條件禁餵守衛。
     * <p>舊版（2.x）是無差別餵：任何 waitingFor 都從網路抓。那會把「機器 2 秒後就會送回來」的正常
     * 在途料也搶先餵掉，機器回貨時 {@code insert} 已無額度可銷 → 貨彈回網路，帳雖不會少但整網空轉，
     * 且會跟其他單搶料。加上這道指紋後，餵料只會發生在**證明不會有人送貨**的 key 上。
     */
    private static final boolean gtocraftfix$FEED_PHANTOM_ONLY =
            !"false".equalsIgnoreCase(System.getProperty("gtodiag.sitterFeedPhantomOnly", "true"));

    /**
     * [3.13.0] 保母補輸入（把剩餘任務的輸入從網路補進 CPU 庫存）：**維持 slim 的停用狀態**。
     * <p>這條跟餵料不同，它沒有 waitingFor 當額度上限，實測曾「把單一料的全網存量吸進一顆 CPU、
     * 餓死其他單」（見 {@code gtodiag.topupRounds} 的註解）。三個實錄卡單都不需要它，故不預設開啟。
     */
    private static final boolean gtocraftfix$SITTER_TOPUP = Boolean.getBoolean("gtodiag.sitterTopUp");

    /**
     * [3.13.0，3.13.1 起**預設關閉**] 卡死救援：對「證明等不到貨」且長時間零進度的 CPU 取消整張單。
     * <p>要開請自己設 {@code -Dgtodiag.stallCancel=true}；關閉時 {@link #gtocraftfix$stallWatch()}
     * 連呼叫都不會發生（tick 鉤子先判旗標），零成本、零行為。
     * <p>⚠ **為什麼 3.13.1 就把它關掉**——2026-08-19 實測，當時網路上兩種真實的卡單形狀它一種都碰不到：
     * <ul>
     *   <li>密銀／索륨：每 4 秒開一張新單、每張只活 2 秒就離場，{@code stallCancelSec=300} 的
     *       零進度計時器**永遠累積不到**（單活得比門檻短兩個數量級）。</li>
     *   <li>並行控制倉：零進度 21 分鐘、無任務產它、網存 0，三道閘門全過，卻卡在第四道——
     *       {@code getPendingRequests} 回報樣板還押在供應器上（那筆推送其實早就丟了）→ 一票否決。</li>
     * </ul>
     * 也就是說它目前**只有誤殺風險、沒有實際效益**。而且真正的病灶是「請求器滿足判定看網路現貨
     * ＋ 降量重算硬開單」構成的迴圈（見 README「3.13.1」段），取消單並不治它。
     * <p>程式碼保留不刪：這是本 mod 對新行為的一貫作法（見 3.11.0「程式碼保留但預設關閉」），
     * 保留才能在修好 pendingAt 誤判後直接 A/B，而不是重寫一次。
     */
    private static final boolean gtocraftfix$STALL_CANCEL =
            Boolean.getBoolean("gtodiag.stallCancel");

    /** [3.13.0] 判定卡死所需的「零進度」秒數（實錄卡單靜止 600 秒以上仍在增加）。 */
    private static final int gtocraftfix$STALL_SEC = Integer.getInteger("gtodiag.stallCancelSec", 300);

    /** [3.13.0] 同一顆 CPU 兩次救援的最短間隔（秒）：防「取消→重下→再卡→再取消」高頻空轉。 */
    private static final int gtocraftfix$STALL_COOLDOWN_SEC =
            Integer.getInteger("gtodiag.stallCancelCooldownSec", 600);

    /** [3.13.0] 救援時在聊天室廣播（玩家單被取消時不廣播就等於無聲吞單）。 */
    private static final boolean gtocraftfix$STALL_BROADCAST =
            !"false".equalsIgnoreCase(System.getProperty("gtodiag.stallCancelBroadcast", "true"));

    /** [3.13.0] 每顆 CPU 的進度追蹤：{進度指紋, 指紋最後變動的 tick, 上次救援的 tick}。 */
    private final Map<appeng.crafting.execution.CraftingCpuLogic, long[]> gtocraftfix$stallState =
            new java.util.WeakHashMap<>();

    /** [3.13.0] 救援次數（純計數，用來在 log 裡看有沒有進入取消迴圈）。 */
    private static final AtomicInteger gtocraftfix$stallCancels = new AtomicInteger();

    /**
     * [3.13.0] 救援廣播去重：成品 key → 上次廣播的 tick。
     * <p>冷卻是綁在 CPU 上的，但重下的單常常落到**另一顆** CPU（那顆沒有冷卻）→ 對一個真的做不出來的
     * 配方，取消會每 5 分鐘循環一次。log 該留（那是事實），但聊天室不該每 5 分鐘洗一次同一句話。
     */
    private final Map<String, Integer> gtocraftfix$stallNotified = new HashMap<>();

    private static volatile java.lang.reflect.Field gtocraftfix$fRemaining;
    private static volatile java.lang.reflect.Field gtocraftfix$fPaused;
    private static volatile java.lang.reflect.Field gtocraftfix$fPendingRequests;
    private static volatile Method gtocraftfix$mPending;
    private static volatile Method gtocraftfix$mPaused;

    /** [3.11.0] 現行旗標一覽（啟動時印一次，方便對照 log 與設定）。 */
    private static String gtocraftfix$flagLine() {
        return "guard=" + gtocraftfix$REPAIR_GUARD
                + " runCap=" + gtocraftfix$REPAIR_RUN_CAP
                + " budgetMs=" + gtocraftfix$REPAIR_BUDGET_MS
                + " netSpot=" + gtocraftfix$REPAIR_NET_SPOT
                + " balance=" + gtocraftfix$REPAIR_BALANCE
                + "(onAbort=" + gtocraftfix$REPAIR_BALANCE_ON_ABORT
                + ",log=" + gtocraftfix$REPAIR_BALANCE_LOG + ")"
                + " block=" + gtocraftfix$REPAIR_BLOCK
                + " freezeProbe=" + gtocraftfix$REPAIR_FREEZE_PROBE
                + " deficitSrc=" + gtocraftfix$REPAIR_DEFICIT_SRC // [3.11.1]
                + " strictRounds=" + gtocraftfix$REPAIR_STRICT_ROUNDS
                + " updateBytes=" + gtocraftfix$REPAIR_UPDATE_BYTES
                + " bootstrapMaxPass=" + gtocraftfix$BOOTSTRAP_MAX_PASS
                // [3.13.0]
                + " runFactor=" + gtocraftfix$REPAIR_RUN_FACTOR
                + " runHardCap=" + gtocraftfix$REPAIR_RUN_HARD_CAP
                + " machineDownscale=" + gtocraftfix$MACHINE_DOWNSCALE
                + "(" + gtocraftfix$DOWNSCALE_BUDGET_MS + "ms,cd="
                + gtocraftfix$DOWNSCALE_COOLDOWN_SEC + "s)"
                + " feed=" + gtocraftfix$FEED + "(phantomOnly=" + gtocraftfix$FEED_PHANTOM_ONLY + ")"
                + " topUp=" + gtocraftfix$SITTER_TOPUP
                + " stallCancel=" + gtocraftfix$STALL_CANCEL
                + "(" + gtocraftfix$STALL_SEC + "s,cd=" + gtocraftfix$STALL_COOLDOWN_SEC
                + "s,broadcast=" + gtocraftfix$STALL_BROADCAST + ")";
    }

    /** 本 mixin 實例即一張 grid；再以 requester identity＋key 分流，避免另一張網路被一起冷卻。 */
    private boolean gtocraftfix$startDownscale(ICraftingSimulationRequester requester, AEKey key, long now) {
        var throttleKey = new DownscaleKey(requester, key);
        Long last = gtocraftfix$downscaleLastAttempt.get(throttleKey);
        if (last != null && !CraftingHotfixSupport.budgetExpired(last,
                gtocraftfix$DOWNSCALE_COOLDOWN_NS, now)) {
            return false;
        }
        gtocraftfix$downscaleLastAttempt.put(throttleKey, now);
        if (gtocraftfix$downscaleLastAttempt.size() > 512) {
            gtocraftfix$downscaleLastAttempt.entrySet().removeIf(e ->
                    CraftingHotfixSupport.budgetExpired(e.getValue(),
                            gtocraftfix$DOWNSCALE_COOLDOWN_NS, now));
            // 冷卻設得極長時仍給容器硬上限；本次 key 已寫入，清空後補回它。
            if (gtocraftfix$downscaleLastAttempt.size() > 512) {
                gtocraftfix$downscaleLastAttempt.clear();
                gtocraftfix$downscaleLastAttempt.put(throttleKey, now);
            }
        }
        return true;
    }

    private static boolean gtocraftfix$isMachineSource(IActionSource src) {
        if (src == null) {
            return true;
        }
        try {
            var player = src.player();
            return player == null || player.isEmpty();
        } catch (Throwable ignored) {
            return true; // 來源身分未知：提交／搬料安全守衛一律按機器側處理
        }
    }

    /** patternTimes 非空但全為 null／0／負值仍等於零可執行任務；任何 accessor 例外都 fail-closed。 */
    private static boolean gtocraftfix$hasPositiveTask(ICraftingPlan job) {
        try {
            return job != null && job.patternTimes() != null
                    && CraftingHotfixSupport.hasPositiveTask(job.patternTimes().values());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 收到的提交形狀只要是機器源零任務（含匿名誠實 sim）就拒收；尾端另留 defense-in-depth。 */
    private static boolean gtocraftfix$isDegenerateMachinePlan(ICraftingPlan job, IActionSource src) {
        if (!gtocraftfix$isMachineSource(src)) {
            return false;
        }
        return !gtocraftfix$hasPositiveTask(job);
    }

    private record FinalDeliveryCheck(boolean known, long supply, long demand) {
        private boolean insufficient() {
            return known && supply < demand;
        }
    }

    /**
     * 對「此刻實際要提交的 plan」重算最終交付量；呼叫點必須在修補／配平／rollback 全部完成後。
     * used(final) 刻意不列入。任何欄位／樣板讀取例外都回 UNKNOWN，不能據此誤擋。
     */
    private static FinalDeliveryCheck gtocraftfix$finalDeliveryCheck(CraftingPlan plan) {
        try {
            if (plan == null || plan.finalOutput() == null || plan.finalOutput().what() == null
                    || plan.patternTimes() == null || plan.emittedItems() == null) {
                return new FinalDeliveryCheck(false, 0, 0);
            }
            var finalKey = plan.finalOutput().what();
            long patternProduced = 0;
            for (var entry : plan.patternTimes().entrySet()) {
                Long runs = entry.getValue();
                if (runs == null || runs <= 0) {
                    continue;
                }
                for (var output : entry.getKey().getOutputs()) {
                    if (finalKey.equals(output.what())) {
                        patternProduced = CraftingHotfixSupport.saturatingAdd(patternProduced,
                                CraftingHotfixSupport.saturatingMultiply(output.amount(), runs));
                    }
                }
            }
            long supply = CraftingHotfixSupport.finalDeliverable(
                    plan.emittedItems().get(finalKey), patternProduced);
            return new FinalDeliveryCheck(true, supply, plan.finalOutput().amount());
        } catch (Throwable ignored) {
            return new FinalDeliveryCheck(false, 0, 0);
        }
    }

    /** 回傳 true 表示已把機器提交設成 INCOMPLETE_PLAN；玩家只保留診斷、不改原行為。 */
    private static boolean gtocraftfix$rejectProvenFinalShortfall(CraftingPlan plan, IActionSource src,
            CallbackInfoReturnable<ICraftingSubmitResult> cir) {
        var check = gtocraftfix$finalDeliveryCheck(plan);
        if (!check.known()) {
            if (gtocraftfix$finalCheckLog.incrementAndGet() <= 5) {
                // UNKNOWN 可能正是 finalOutput() accessor 拋例外；log 不可再次呼叫同一 accessor。
                LOG.warn("[craftfix] 最終產出驗證失敗（UNKNOWN，不據此擋單）planType={}",
                        plan == null ? "null" : plan.getClass().getName());
            }
            return false;
        }
        if (!check.insufficient()) {
            return false;
        }
        boolean machine = gtocraftfix$isMachineSource(src);
        if (gtocraftfix$finalCheckLog.incrementAndGet() <= gtocraftfix$FINAL_CHECK_LOG_MAX) {
            LOG.warn("[craftfix] 最終交付量可證明不足：供給{}/需求{}{}",
                    check.supply(), check.demand(),
                    machine ? "（機器源 → 拒收）" : "（玩家維持原行為）");
        }
        if (machine) {
            cir.setReturnValue(appeng.crafting.execution.CraftingSubmitResult.INCOMPLETE_PLAN);
            return true;
        }
        return false;
    }

    private static void gtocraftfix$addCounterSaturated(appeng.api.stacks.KeyCounter counter,
                                                        AEKey key, long delta) {
        long current = counter.get(key);
        long target = CraftingHotfixSupport.saturatingAdd(current, delta);
        long applied = CraftingHotfixSupport.saturatingSubtract(target, current);
        if (applied != 0) {
            counter.add(key, applied);
        }
    }

    /**
     * [3.14.0] 掃 AE2 的 link 登記簿，找出「請求器那半還掛著、但已經沒有 CPU 在跑」的孤兒 link。
     * <p>這是「請求器完全不呼叫 submitJob」的唯一可見成因：merequester 手上只要還有一條未結案的
     * link，就認為上一批還在路上，`目標 − (網路現貨 + 在途)` 永遠不成立 → 不再請求；而那半 link
     * 會寫進方塊 NBT，**重開世界也還在**（實測 2026-08-22 稀土金屬粉那顆請求器跨兩次重開都沒醒）。
     * <p>純唯讀：只印，不動任何 link。同一個 craftId 只在狀態字串改變時再印一次。
     */
    private void gtocraftfix$linkAudit() {
        if (gtocraftfix$nexusResolveFailed) {
            return;
        }
        // 刻意用反射而不是 @Shadow：@Shadow 對不上是 apply 期的硬失敗＝世界載不進去
        // （3.13.2 的 CraftingHotfixSupport 才剛用另一種方式示範過）。純診斷不值得冒那個險。
        var craftingLinks = gtocraftfix$craftingLinks();
        if (craftingLinks == null || craftingLinks.isEmpty()) {
            return;
        }
        var fields = gtocraftfix$nexusFields();
        if (fields == null) {
            return;
        }
        java.util.Set<java.util.UUID> alive = new HashSet<>();
        for (var e : craftingLinks.entrySet()) {
            var nexus = e.getValue();
            if (nexus == null) {
                continue;
            }
            alive.add(e.getKey());
            try {
                boolean canceled = fields[0].getBoolean(nexus);
                boolean done = fields[1].getBoolean(nexus);
                Object req = fields[2].get(nexus);
                Object cpu = fields[3].get(nexus);
                int tickOfDeath = fields[4].getInt(nexus);
                // 只點名真正會卡住請求器的形狀：請求器那半還在、CPU 那半沒了、又沒 done／canceled
                boolean orphan = req != null && cpu == null && !done && !canceled;
                String state = "req=" + (req != null) + " cpu=" + (cpu != null)
                        + " done=" + done + " canceled=" + canceled + " tickOfDeath=" + tickOfDeath;
                if (!orphan) {
                    gtocraftfix$linkReported.remove(e.getKey());
                    continue;
                }
                if (state.equals(gtocraftfix$linkReported.get(e.getKey()))) {
                    continue; // 狀態沒變，不重複洗版
                }
                gtocraftfix$linkReported.put(e.getKey(), state);
                if (gtocraftfix$linkReported.size() > 256) {
                    gtocraftfix$linkReported.clear();
                }
                if (gtocraftfix$linkLog.incrementAndGet() <= 200) {
                    LOG.warn("[craftfix][link] **孤兒 link**：craftId={} {} 請求器={}"
                            + " → 這條沒結案，該請求器不會再下單（重開世界也不會好，link 存在方塊 NBT）",
                            e.getKey(), state, gtocraftfix$requesterOf(req));
                }
            } catch (Throwable t) {
                gtocraftfix$nexusResolveFailed = true;
                LOG.warn("[craftfix][link] nexus 欄位讀取失敗，link 稽核停用：{}", t.toString());
                return;
            }
        }
        gtocraftfix$linkReported.keySet().retainAll(alive);
    }

    /** AE2 CraftingService 的 link 登記簿（private final Map<UUID, CraftingLinkNexus>）。 */
    @SuppressWarnings("unchecked")
    private Map<java.util.UUID, appeng.crafting.CraftingLinkNexus> gtocraftfix$craftingLinks() {
        try {
            var field = gtocraftfix$fCraftingLinks;
            if (field == null) {
                field = appeng.me.service.CraftingService.class.getDeclaredField("craftingLinks");
                field.setAccessible(true);
                gtocraftfix$fCraftingLinks = field;
            }
            Object value = field.get(this);
            return value instanceof Map<?, ?> map
                    ? (Map<java.util.UUID, appeng.crafting.CraftingLinkNexus>) map
                    : null;
        } catch (Throwable t) {
            gtocraftfix$nexusResolveFailed = true;
            LOG.warn("[craftfix][link] 讀不到 CraftingService.craftingLinks，link 稽核停用：{}", t.toString());
            return null;
        }
    }

    /** CraftingLinkNexus 的私有欄位；解析不到就永久停用稽核（純診斷，不值得每 tick 重試）。 */
    private static java.lang.reflect.Field[] gtocraftfix$nexusFields() {
        var cached = gtocraftfix$fNexus;
        if (cached != null) {
            return cached;
        }
        try {
            Class<?> c = appeng.crafting.CraftingLinkNexus.class;
            var f = new java.lang.reflect.Field[5];
            String[] names = { "canceled", "done", "req", "cpu", "tickOfDeath" };
            for (int i = 0; i < names.length; i++) {
                f[i] = c.getDeclaredField(names[i]);
                f[i].setAccessible(true);
            }
            gtocraftfix$fNexus = f;
            return f;
        } catch (Throwable t) {
            gtocraftfix$nexusResolveFailed = true;
            LOG.warn("[craftfix][link] CraftingLinkNexus 欄位對不上，link 稽核停用：{}", t.toString());
            return null;
        }
    }

    /** 從 CraftingLink 取請求器，能拿到座標就印座標（方便直接到現場）。 */
    private static String gtocraftfix$requesterOf(Object link) {
        try {
            var m = appeng.crafting.CraftingLink.class.getDeclaredMethod("getRequester");
            m.setAccessible(true);
            Object requester = m.invoke(link);
            if (requester == null) {
                return "(null)";
            }
            if (requester instanceof net.minecraft.world.level.block.entity.BlockEntity be) {
                return requester.getClass().getSimpleName() + be.getBlockPos();
            }
            return requester.getClass().getSimpleName();
        } catch (Throwable ignored) {
            return "(讀不到)";
        }
    }

    /** [3.8.0] 把 KeyCounter 倒回快照值（以差額回沖，KeyCounter.add 吃負數）。 */
    private static void gtocraftfix$restoreCounter(appeng.api.stacks.KeyCounter kc, Map<AEKey, Long> snap) {
        var keys = new HashSet<AEKey>(snap.keySet());
        for (var e : kc) {
            keys.add(e.getKey());
        }
        for (var k : keys) {
            long delta = CraftingHotfixSupport.saturatingSubtract(snap.getOrDefault(k, 0L), kc.get(k));
            if (delta != 0) {
                gtocraftfix$addCounterSaturated(kc, k, delta);
            }
        }
        kc.removeZeros();
    }

    /**
     * [3.11.1／B3] 把計畫的三本帳（patternTimes／usedItems／missingItems）整組倒回修補前的快照。
     * 「正常中止路徑」與「catch(Throwable) 路徑」共用同一份還原，維持「全有全無」核心不變式
     * ——半套計畫（輪次加了、輸入沒補）＝必凍，比不修更糟。快照為 null（還沒留底就出事）時不動。
     */
    private static void gtocraftfix$restorePlan(Map<IPatternDetails, Long> pt,
                                                 appeng.api.stacks.KeyCounter used,
                                                 appeng.api.stacks.KeyCounter missing,
                                                 Map<IPatternDetails, Long> snapPt,
                                                 Map<AEKey, Long> snapUsed,
                                                 Map<AEKey, Long> snapMissing) {
        if (pt != null && snapPt != null) {
            pt.keySet().removeIf(k -> !snapPt.containsKey(k));
            for (var e : snapPt.entrySet()) {
                pt.put(e.getKey(), e.getValue());
            }
        }
        if (used != null && snapUsed != null) {
            gtocraftfix$restoreCounter(used, snapUsed);
        }
        if (missing != null && snapMissing != null) {
            gtocraftfix$restoreCounter(missing, snapMissing);
        }
    }

    // [3.13.2] 這裡曾經有一段「修補後清掉 GTO 過時 allocations」的反射（getGtocore$allocations／
    // setGtocore$allocations）。實際反編譯 gtocore-0.5.6-beta 證實**沒有這組 accessor**：
    // allocations 是 com.gtocore.api.ae2.crafting.ExecutingCraftingJob 的欄位，在**執行期**由 job
    // 自己建立，CraftingPlan 上根本沒有可清的東西。那段程式碼因此恆為 no-op（getter 永遠找不到），
    // 屬於未經驗證的臆測 API，已整段移除；別再依「GTO 可能有」的假設重新加回來。

    /** [3.6.0] 逐輪記帳快照：cluster → {job, 樣板→剩餘輪數, key→CPU庫存, key→在途}。 */
    private final Map<CraftingCPUCluster, Object[]> gtocraftfix$pushSnap = new java.util.WeakHashMap<>();

    /**
     * [3.6.0 純診斷] 推送記帳：抓「某樣板剩餘輪數下降」的瞬間（＝執行器推了 Δ 輪），比對
     * <ul>
     *   <li><b>實際抽走的輸入</b> vs <b>計畫每輪值 × Δ</b> —— 多吃＝並行取料換算與計畫不一致；</li>
     *   <li><b>在途(waitingFor)增加量</b> vs <b>樣板每輪產出 × Δ</b> —— 記少了＝產出帳沒掛上。</li>
     * </ul>
     * 只在「本次取樣恰有一個樣板變動」時報告（多樣板同時推無法歸屬）。純讀取，不改任何狀態。
     */
    private void gtocraftfix$pushAudit(appeng.crafting.execution.CraftingCpuLogic logic,
                                       CraftingCPUCluster cluster) {
        try {
            Object job = gtocraftfix$fJob == null ? null : gtocraftfix$fJob.get(logic);
            if (job == null || gtocraftfix$fTasks == null) {
                gtocraftfix$pushSnap.remove(cluster);
                return;
            }
            Map<?, ?> tasks = (Map<?, ?>) gtocraftfix$fTasks.get(job);
            var inv = gtocraftfix$invOf(logic);
            if (tasks == null || inv == null) {
                return;
            }
            var curTimes = new HashMap<IPatternDetails, Long>();
            var keys = new HashSet<AEKey>();
            for (var en : tasks.entrySet()) {
                long t = gtocraftfix$fHolderVal.getLong(en.getValue());
                var pat = (IPatternDetails) en.getKey();
                curTimes.put(pat, t);
                for (var in : pat.getInputs()) {
                    for (var v : in.getPossibleInputs()) {
                        keys.add(v.what());
                    }
                }
                for (var o : pat.getOutputs()) {
                    keys.add(o.what());
                }
            }
            var curInv = new HashMap<AEKey, Long>();
            var curWait = new HashMap<AEKey, Long>();
            for (var k : keys) {
                curInv.put(k, inv.list.get(k));
                curWait.put(k, logic.getWaitingFor(k));
            }
            Object[] prev = gtocraftfix$pushSnap.get(cluster);
            gtocraftfix$pushSnap.put(cluster, new Object[] { job, curTimes, curInv, curWait });
            if (prev == null || prev[0] != job) {
                return; // 換單重新起算
            }
            @SuppressWarnings("unchecked")
            var pTimes = (HashMap<IPatternDetails, Long>) prev[1];
            @SuppressWarnings("unchecked")
            var pInv = (HashMap<AEKey, Long>) prev[2];
            @SuppressWarnings("unchecked")
            var pWait = (HashMap<AEKey, Long>) prev[3];
            IPatternDetails moved = null;
            long delta = 0;
            for (var e : pTimes.entrySet()) {
                long now = curTimes.getOrDefault(e.getKey(), 0L);
                long d = CraftingHotfixSupport.saturatingSubtract(e.getValue(), now);
                if (d > 0) {
                    if (moved != null) {
                        return; // 同一取樣有多個樣板推送 → 無法歸屬，跳過
                    }
                    moved = e.getKey();
                    delta = d;
                }
            }
            if (moved == null) {
                return;
            }
            var sb = new StringBuilder();
            for (var in : moved.getInputs()) {
                var ps = in.getPossibleInputs();
                if (ps.length == 0) {
                    continue;
                }
                // 實際抽走：取「庫存下降最多」的變體（並行版可能挑了別的替代品）
                var used = ps[0];
                long usedDrop = Long.MIN_VALUE;
                for (var v : ps) {
                    long drop = CraftingHotfixSupport.saturatingSubtract(
                            pInv.getOrDefault(v.what(), 0L), curInv.getOrDefault(v.what(), 0L));
                    if (drop > usedDrop) {
                        usedDrop = drop;
                        used = v;
                    }
                }
                long expect = CraftingHotfixSupport.saturatingMultiply(
                        CraftingHotfixSupport.saturatingMultiply(used.amount(), in.getMultiplier()), delta);
                if (usedDrop != expect) {
                    sb.append(used.what()).append(" 預期吃").append(expect)
                            .append("/實吃").append(usedDrop).append("; ");
                }
            }
            for (var o : moved.getOutputs()) {
                long expect = CraftingHotfixSupport.saturatingMultiply(o.amount(), delta);
                long got = CraftingHotfixSupport.saturatingSubtract(
                        curWait.getOrDefault(o.what(), 0L), pWait.getOrDefault(o.what(), 0L));
                if (got != expect) {
                    sb.append(o.what()).append(" 預期在途+").append(expect)
                            .append("/實際+").append(got).append("; ");
                }
            }
            if (sb.length() > 0 && gtocraftfix$balLog.incrementAndGet() <= gtocraftfix$BAL_MAX) {
                LOG.warn("[craftfix] 推送記帳不符 {} Δ輪={} → {}",
                        moved.getPrimaryOutput().what(), delta, sb);
            }
        } catch (Throwable ignored) {
        }
    }

    /** [3.5.0 純診斷] **出生收支**：提交當下用計畫自己的數字驗證
     *  「Σ(每輪輸入×runs) ≤ usedItems ＋ emittedItems ＋ Σ(每輪產出×runs)」。
     *  算料器是拿虛擬庫存模擬跑過一遍才產出計畫，理論上必然成立；若這裡就負，代表**算料階段**
     *  已經不平（餘數向下取整之類），與執行期無關。替代輸入取「供給最多的變體」以免誤報。 */
    private void gtocraftfix$planBirthBalance(CraftingPlan plan) {
        try {
            var pt = plan.patternTimes();
            if (pt.isEmpty()) {
                return;
            }
            var supply = new HashMap<AEKey, Long>();
            for (var e : plan.usedItems()) {
                supply.merge(e.getKey(), e.getLongValue(), CraftingHotfixSupport::saturatingAdd);
            }
            for (var e : plan.emittedItems()) {
                supply.merge(e.getKey(), e.getLongValue(), CraftingHotfixSupport::saturatingAdd);
            }
            for (var pe : pt.entrySet()) {
                long runs = pe.getValue();
                if (runs <= 0) {
                    continue;
                }
                for (var o : pe.getKey().getOutputs()) {
                    if (o.amount() > 0) {
                        supply.merge(o.what(), CraftingHotfixSupport.saturatingMultiply(o.amount(), runs),
                                CraftingHotfixSupport::saturatingAdd);
                    }
                }
            }
            var demand = new HashMap<AEKey, Long>();
            for (var pe : pt.entrySet()) {
                long runs = pe.getValue();
                if (runs <= 0) {
                    continue;
                }
                for (var in : pe.getKey().getInputs()) {
                    var ps = in.getPossibleInputs();
                    if (ps.length == 0) {
                        continue;
                    }
                    // 替代輸入：算在「計畫實際供給最多」的那個變體上（避免只看 ps[0] 的誤報）
                    var best = ps[0];
                    long bestSup = -1;
                    for (var v : ps) {
                        long s = supply.getOrDefault(v.what(), 0L);
                        if (s > bestSup) {
                            bestSup = s;
                            best = v;
                        }
                    }
                    long per = CraftingHotfixSupport.saturatingMultiply(best.amount(), in.getMultiplier());
                    if (per > 0) {
                        demand.merge(best.what(), CraftingHotfixSupport.saturatingMultiply(per, runs),
                                CraftingHotfixSupport::saturatingAdd);
                    }
                }
            }
            var sb = new StringBuilder();
            int n = 0;
            for (var e : demand.entrySet()) {
                long have = supply.getOrDefault(e.getKey(), 0L);
                long shortAmt = CraftingHotfixSupport.positiveDeficit(e.getValue(), have);
                if (shortAmt > 0 && n++ < 6) {
                    sb.append(e.getKey()).append(" 需").append(e.getValue())
                            .append("/計畫供").append(have).append("(缺").append(shortAmt).append("); ");
                }
            }
            if (gtocraftfix$balLog.incrementAndGet() > gtocraftfix$BAL_MAX) {
                return;
            }
            if (sb.length() > 0) {
                LOG.warn("[craftfix] 出生收支：**算料階段就不平** out={} 任務{}種 → {}",
                        plan.finalOutput(), pt.size(), sb);
            } else {
                LOG.info("[craftfix] 出生收支：平衡 out={} 任務{}種（之後若不平＝執行期漂移）",
                        plan.finalOutput(), pt.size());
            }
        } catch (Throwable ignored) {
        }
    }

    /** [3.4.0 純診斷] 計畫內部收支平衡檢查（可嚴格證明「這張單做不完」的不變量）：
     *  <p>對每個 key：總消耗 = Σ(剩餘任務每輪輸入 × 剩餘輪數)；總可得 = Σ(剩餘任務每輪產出 × 剩餘輪數)
     *  ＋ CPU 庫存 ＋ 在途(waitingFor)。若 總可得 &lt; 總消耗，**無論執行順序如何都不可能完成**——
     *  代表計畫本身少排了輪次（批量餘數向下取整的內部版本，usedItems 對網路的比對抓不到）。
     *  只在有負差時印，額度與其他診斷共用。 */
    private void gtocraftfix$balanceReport(appeng.crafting.execution.CraftingCpuLogic logic,
                                           appeng.api.stacks.GenericStack out) {
        try {
            Object job = gtocraftfix$fJob == null ? null : gtocraftfix$fJob.get(logic);
            if (job == null || gtocraftfix$fTasks == null) {
                return;
            }
            Map<?, ?> tasks = (Map<?, ?>) gtocraftfix$fTasks.get(job);
            var inv = gtocraftfix$invOf(logic);
            if (tasks == null || tasks.isEmpty() || inv == null) {
                return;
            }
            var demand = new HashMap<AEKey, Long>();
            var supply = new HashMap<AEKey, Long>();
            // 先算供給（排定產出），替代輸入才有依據擇優
            for (var en : tasks.entrySet()) {
                long times = gtocraftfix$fHolderVal.getLong(en.getValue());
                if (times <= 0) {
                    continue;
                }
                for (var o : ((IPatternDetails) en.getKey()).getOutputs()) {
                    if (o.amount() > 0) {
                        supply.merge(o.what(), CraftingHotfixSupport.saturatingMultiply(o.amount(), times),
                                CraftingHotfixSupport::saturatingAdd);
                    }
                }
            }
            for (var en : tasks.entrySet()) {
                long times = gtocraftfix$fHolderVal.getLong(en.getValue());
                if (times <= 0) {
                    continue;
                }
                for (var in : ((IPatternDetails) en.getKey()).getInputs()) {
                    var ps = in.getPossibleInputs();
                    if (ps.length == 0) {
                        continue;
                    }
                    // [3.5.0] 替代輸入：算在「庫存＋在途＋排定產出最多」的變體上，消除誤報
                    var best = ps[0];
                    long bestAvail = -1;
                    for (var v : ps) {
                        long a = inv.list.get(v.what()) + Math.max(0, logic.getWaitingFor(v.what()))
                                + supply.getOrDefault(v.what(), 0L);
                        if (a > bestAvail) {
                            bestAvail = a;
                            best = v;
                        }
                    }
                    long per = CraftingHotfixSupport.saturatingMultiply(best.amount(), in.getMultiplier());
                    if (per > 0) {
                        demand.merge(best.what(), CraftingHotfixSupport.saturatingMultiply(per, times),
                                CraftingHotfixSupport::saturatingAdd);
                    }
                }
            }
            var sb = new StringBuilder();
            int n = 0;
            for (var e : demand.entrySet()) {
                var k = e.getKey();
                long have = inv.list.get(k) + Math.max(0, logic.getWaitingFor(k))
                        + supply.getOrDefault(k, 0L);
                long shortAmt = CraftingHotfixSupport.positiveDeficit(e.getValue(), have);
                if (shortAmt > 0 && n++ < 6) {
                    sb.append(k).append(" 需").append(e.getValue()).append("/可得").append(have)
                            .append("(缺").append(shortAmt).append(supply.containsKey(k) ? "" : "、無人產")
                            .append("); ");
                }
            }
            if (sb.length() > 0 && gtocraftfix$balLog.incrementAndGet() <= gtocraftfix$BAL_MAX) {
                LOG.warn("[craftfix] 計畫收支缺口（排定產出＋庫存＋在途 < 總消耗，此單不可能完成）out={} → {}",
                        out, sb);
            }
        } catch (Throwable ignored) {
        }
    }

    /** [3.2.2 純診斷] 本 job 所有剩餘任務（times&gt;0）的產出 key 集合；讀不到回 null（不標記）。 */
    private java.util.Set<AEKey> gtocraftfix$taskOutputs(appeng.crafting.execution.CraftingCpuLogic logic) {
        try {
            if (gtocraftfix$fJob == null) {
                var fj = logic.getClass().getDeclaredField("job");
                fj.setAccessible(true);
                gtocraftfix$fJob = fj;
            }
            Object job = gtocraftfix$fJob.get(logic);
            if (job == null) {
                return null;
            }
            if (gtocraftfix$fTasks == null) {
                var ft = job.getClass().getDeclaredField("tasks");
                ft.setAccessible(true);
                gtocraftfix$fTasks = ft;
            }
            Map<?, ?> tasks = (Map<?, ?>) gtocraftfix$fTasks.get(job);
            if (tasks == null) {
                return null;
            }
            var out = new HashSet<AEKey>();
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
                for (var o : ((IPatternDetails) en.getKey()).getOutputs()) {
                    out.add(o.what());
                }
            }
            return out;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private record PendingResult(CraftingHotfixSupport.PendingKnowledge state, String locations) {
    }

    private static final PendingResult gtocraftfix$PENDING_UNKNOWN =
            new PendingResult(CraftingHotfixSupport.PendingKnowledge.UNKNOWN, null);
    private static final PendingResult gtocraftfix$PENDING_NONE =
            new PendingResult(CraftingHotfixSupport.PendingKnowledge.NONE, null);

    /** [3.2.1/3.13.2] 該 key 最近被推送到哪些供應器。NONE 與反射失效的 UNKNOWN 必須分開：
     *  餵料／取消只有在**確定 NONE**時才可搬料或動刀，UNKNOWN 一律 fail-closed。 */
    private static PendingResult gtocraftfix$pendingAt(Object logic, AEKey key) {
        try {
            // [3.13.0] Method 快取：3.13.0 起這條從「每 400 tick 診斷用」變成餵料的判斷條件之一，
            // 未快取的 getMethod（會複製整個 Method 陣列）會變成每秒數千次的主緒成本。
            var m = gtocraftfix$mPending;
            if (m == null) {
                m = logic.getClass().getMethod("getPendingRequests", AEKey.class);
                gtocraftfix$mPending = m;
            }
            Object r = m.invoke(logic, key);
            if (!(r instanceof java.util.Collection<?> col)) {
                return gtocraftfix$PENDING_UNKNOWN;
            }
            if (col.isEmpty()) {
                return gtocraftfix$PENDING_NONE;
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
            return new PendingResult(CraftingHotfixSupport.PendingKnowledge.PRESENT, sb.toString().trim());
        } catch (Throwable ignored) {
            return gtocraftfix$PENDING_UNKNOWN;
        }
    }

    /**
     * GTO 26.7.4 的 pendingRequests 用樣板主產物當 key。回傳 keySet 的快照供**逐 key** 判定；
     * 欄位或型別讀不到回 {@code null}（呼叫端一律 fail-closed 不餵）。
     * <p>⚠ [3.13.6] 這裡曾經是 {@code pendingAnywhere}：只要 multimap 非空就**整單所有 key 都不餵**。
     * 理由是「pendingRequests 只索引主產物，副產物查不到，可能誤餵」。實測代價遠大於收益——
     * 2026-08-22 全天 5,459 張稀土單，每張都有一筆 {@code rare_earth_oxide_dust} 押在供應器上，
     * 於是同一張單裡「網路有 714 億、無任務產它」的 {@code salt_water} 幻影缺口一次都沒被餵，
     * 單活 8 秒就離場、**累計交付 0**、AE 庫存 0。當天 510 筆該餵的缺口實際只餵了 0 筆
     * （3.13.1 同類場景是 690 筆餵 134 筆）。
     * <p>改回逐 key 之後殘留的誤餵風險是有界的：餵入量以 {@code getWaitingFor(key)} 為上限，
     * final key 一律不餵，多餵的副產物只會留在 CPU 庫存、離場時回網路，不會動到 link 帳。
     */
    private static java.util.Set<AEKey> gtocraftfix$pendingKeys(Object logic) {
        try {
            var field = gtocraftfix$fPendingRequests;
            if (field == null) {
                Class<?> type = logic.getClass();
                while (type != null) {
                    try {
                        field = type.getDeclaredField("pendingRequests");
                        field.setAccessible(true);
                        gtocraftfix$fPendingRequests = field;
                        break;
                    } catch (NoSuchFieldException ignored) {
                        type = type.getSuperclass();
                    }
                }
            }
            if (field == null) {
                return null;
            }
            Object requests = field.get(logic);
            if (!(requests instanceof com.google.common.collect.Multimap<?, ?> pending)) {
                return null;
            }
            var keys = new HashSet<AEKey>();
            for (Object k : pending.keySet()) {
                if (k instanceof AEKey aeKey) {
                    keys.add(aeKey);
                } else if (k != null) {
                    return null; // key 型別不是 AEKey＝語意讀不懂，不能當成「沒有在途」
                }
            }
            return keys;
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

    /** GTO job 的 waitingFor 實體；餵料前必須可讀，logic.insert 例外時才有辦法恢復被先扣掉的帳。 */
    private appeng.crafting.inv.ListCraftingInventory gtocraftfix$waitingInvOf(
            appeng.crafting.execution.CraftingCpuLogic logic) {
        try {
            Object job = gtocraftfix$jobOf(logic);
            if (job == null) {
                return null;
            }
            if (gtocraftfix$fWaitingFor == null) {
                var field = job.getClass().getDeclaredField("waitingFor");
                field.setAccessible(true);
                gtocraftfix$fWaitingFor = field;
            }
            return (appeng.crafting.inv.ListCraftingInventory) gtocraftfix$fWaitingFor.get(job);
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ---------------------------------------------------------------- [3.13.0] 卡死救援

    /** GTO job 物件（{@code OptimizedCraftingCpuLogic.job}）；沒單或反射失效回 null。 */
    private Object gtocraftfix$jobOf(appeng.crafting.execution.CraftingCpuLogic logic) {
        try {
            if (gtocraftfix$fJob == null) {
                var fj = logic.getClass().getDeclaredField("job");
                fj.setAccessible(true);
                gtocraftfix$fJob = fj;
            }
            return gtocraftfix$fJob.get(logic);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 待交付量（{@code ExecutingCraftingJob.remainingAmount}）；讀不到回 −1。 */
    private long gtocraftfix$remainingAmount(Object job) {
        try {
            if (job == null) {
                return -1;
            }
            if (gtocraftfix$fRemaining == null) {
                var fr = job.getClass().getDeclaredField("remainingAmount");
                fr.setAccessible(true);
                gtocraftfix$fRemaining = fr;
            }
            return gtocraftfix$fRemaining.getLong(job);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 剩餘任務輪數總和；讀不到回 −1。 */
    private long gtocraftfix$remainingRounds(Object job) {
        try {
            if (job == null) {
                return -1;
            }
            if (gtocraftfix$fTasks == null) {
                var ft = job.getClass().getDeclaredField("tasks");
                ft.setAccessible(true);
                gtocraftfix$fTasks = ft;
            }
            Map<?, ?> tasks = (Map<?, ?>) gtocraftfix$fTasks.get(job);
            if (tasks == null) {
                return -1;
            }
            long sum = 0;
            for (var v : tasks.values()) {
                if (gtocraftfix$fHolderVal == null) {
                    var fv = v.getClass().getField("value");
                    fv.setAccessible(true);
                    gtocraftfix$fHolderVal = fv;
                }
                long r = gtocraftfix$fHolderVal.getLong(v);
                if (r > 0) {
                    sum = CraftingHotfixSupport.saturatingAdd(sum, r);
                }
            }
            return sum;
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * GTO 的暫停單（玩家在 CPU 介面按暫停）：刻意停著，不是卡死，一律不救。
     * <p>兩條路都要試——{@code isPaused()} 方法若不存在而只回 false，暫停中的單會被當成
     * 「零進度」一路數到 300 秒然後被取消，這是本救援唯一有實際傷害的誤判。
     * 故方法讀不到就退回讀 job 的 {@code paused} 欄位（作法同 {@code CraftDiag.paused}）。
     */
    private boolean gtocraftfix$isPaused(appeng.crafting.execution.CraftingCpuLogic logic) {
        try {
            var m = gtocraftfix$mPaused;
            if (m == null) {
                m = logic.getClass().getMethod("isPaused");
                gtocraftfix$mPaused = m;
            }
            Object value = m.invoke(logic);
            if (value instanceof Boolean paused) {
                return paused;
            }
        } catch (Throwable ignored) {
        }
        try {
            Object job = gtocraftfix$jobOf(logic);
            if (job == null) {
                return true; // 無法證明正在執行：所有會搬料／取消的救援一律 fail-closed
            }
            if (gtocraftfix$fPaused == null) {
                var fp = job.getClass().getDeclaredField("paused");
                fp.setAccessible(true);
                gtocraftfix$fPaused = fp;
            }
            return gtocraftfix$fPaused.getBoolean(job);
        } catch (Throwable ignored) {
            return true;
        }
    }

    /**
     * 進度指紋：任何一項變了就算「這一秒有進度」。涵蓋推樣板（剩餘輪數↓、庫存↓）、
     * 機器回貨（在途↓、庫存↑）、交付（待交付↓）三種前進方式。
     */
    private long gtocraftfix$progressSig(appeng.crafting.execution.CraftingCpuLogic logic) {
        long h = 17;
        try {
            Set<AEKey> waiting = new HashSet<>();
            logic.getAllWaitingFor(waiting);
            long ws = 0;
            for (var k : waiting) {
                ws = CraftingHotfixSupport.saturatingAdd(ws, logic.getWaitingFor(k));
            }
            h = h * 31 + ws;
            h = h * 31 + waiting.size();
            Object job = gtocraftfix$jobOf(logic);
            h = h * 31 + gtocraftfix$remainingAmount(job);
            h = h * 31 + gtocraftfix$remainingRounds(job);
            var inv = gtocraftfix$invOf(logic);
            if (inv != null) {
                long s = 0;
                for (var e : inv.list) {
                    s = CraftingHotfixSupport.saturatingAdd(s, e.getLongValue());
                }
                h = h * 31 + s;
            }
        } catch (Throwable ignored) {
        }
        return h;
    }

    /**
     * 找出一筆「證明等不到」的 waitingFor：沒有剩餘任務會產它、沒有樣板押在供應器上、網路也一滴都抽不到。
     * <p>三個條件缺一不可——少任何一個都可能是「還在路上」而誤殺一張正常的單。讀不到 job 時回 null
     * （寧可不救也不誤判）。
     */
    private AEKey gtocraftfix$frozenKey(appeng.crafting.execution.CraftingCpuLogic logic,
                                        appeng.api.storage.MEStorage storage, IActionSource src) {
        Set<AEKey> waiting = new HashSet<>();
        logic.getAllWaitingFor(waiting);
        if (waiting.isEmpty()) {
            return null;
        }
        // GTO pendingRequests 只以 primary output 為 key；只查目前 waiting key 會漏掉同一張樣板
        // 已在途的副產物。取消整張單是破壞性動作，這裡**維持全單 fail-closed**：只要仍有任一
        // pending（或反射無法判定）就不救。（3.13.6 把餵料側改成逐 key，這裡刻意不跟進——
        // 餵錯一筆料的代價是留在 CPU 庫存、離場回網路；取消錯一張單的代價是玩家半成品全退。）
        var pendingKeys = gtocraftfix$pendingKeys(logic);
        if (pendingKeys == null || !pendingKeys.isEmpty()) {
            return null;
        }
        var producible = gtocraftfix$taskOutputs(logic);
        if (producible == null) {
            return null;
        }
        for (var k : waiting) {
            if (logic.getWaitingFor(k) <= 0 || producible.contains(k)) {
                continue;
            }
            if (gtocraftfix$pendingAt(logic, k).state() != CraftingHotfixSupport.PendingKnowledge.NONE) {
                continue; // 樣板已押在供應器上＝貨在路上
            }
            if (storage.extract(k, 1, Actionable.SIMULATE, src) > 0) {
                continue; // 網路還抽得到 → 交給保母餵料，不必動刀
            }
            return k;
        }
        return null;
    }

    /**
     * [3.13.0] 每秒一次：長時間零進度 ＋ 有一筆證明等不到的在途料 → 取消整張單。
     * <p>取消會把 CPU 手上的半成品全退回網路，機器請求器 10 秒後自己重下（新計畫拿當下存量重算，
     * 剛退回的中間產物都算得到）。玩家單不會自動重下，故一律廣播一行訊息。
     */
    private void gtocraftfix$stallWatch() {
        appeng.api.storage.MEStorage storage;
        try {
            storage = grid.getStorageService().getInventory();
        } catch (Throwable t) {
            return;
        }
        for (var cluster : craftingCPUClusters) {
            try {
                var logic = cluster.craftingLogic;
                var st = gtocraftfix$stallState.get(logic);
                if (st == null) {
                    st = new long[] { Long.MIN_VALUE, gtocraftfix$tickCounter, Long.MIN_VALUE / 4 };
                    gtocraftfix$stallState.put(logic, st);
                }
                var out = logic.getFinalJobOutput();
                // 沒單／暫停中 → 重置計時器（暫停是刻意的，不算卡死）
                if (out == null || gtocraftfix$isPaused(logic)) {
                    st[0] = Long.MIN_VALUE;
                    st[1] = gtocraftfix$tickCounter;
                    continue;
                }
                long sig = gtocraftfix$progressSig(logic);
                if (sig != st[0]) {
                    st[0] = sig;
                    st[1] = gtocraftfix$tickCounter;
                    continue;
                }
                long stalledTicks = CraftingHotfixSupport.saturatingSubtract(
                        gtocraftfix$tickCounter, st[1]);
                if (stalledTicks < CraftingHotfixSupport.saturatingMultiply(gtocraftfix$STALL_SEC, 20L)) {
                    continue;
                }
                if (CraftingHotfixSupport.saturatingSubtract(gtocraftfix$tickCounter, st[2])
                        < CraftingHotfixSupport.saturatingMultiply(gtocraftfix$STALL_COOLDOWN_SEC, 20L)) {
                    continue; // 冷卻中：同一顆 CPU 不連續開刀
                }
                var frozen = gtocraftfix$frozenKey(logic, storage, cluster.getSrc());
                if (frozen == null) {
                    continue;
                }
                long want = logic.getWaitingFor(frozen);
                st[2] = gtocraftfix$tickCounter;
                st[1] = gtocraftfix$tickCounter;
                st[0] = Long.MIN_VALUE;
                int n = gtocraftfix$stallCancels.incrementAndGet();
                LOG.warn("[craftfix] 卡死救援：取消整張單 out={} —— 靜止 {}s、等 {} x{}"
                        + "（無任務產它／無樣板在途／網路抽不到）；半成品退回網路，"
                        + "機器單會自動重下、玩家單請手動重下。累計第 {} 次",
                        out, stalledTicks / 20, frozen, want, n);
                logic.cancel();
                // 廣播去重：同一成品 10 分鐘內只說一次（重下的單常落到別顆沒冷卻的 CPU）
                boolean sayIt = false;
                if (gtocraftfix$STALL_BROADCAST) {
                    String ok = String.valueOf(out.what());
                    Integer last = gtocraftfix$stallNotified.get(ok);
                    if (last == null || gtocraftfix$tickCounter - last >= 12000) {
                        sayIt = true;
                        gtocraftfix$stallNotified.put(ok, gtocraftfix$tickCounter);
                        if (gtocraftfix$stallNotified.size() > 128) {
                            gtocraftfix$stallNotified.clear();
                        }
                    }
                }
                if (sayIt) {
                    try {
                        var server = grid.getPivot() != null && grid.getPivot().getLevel() != null
                                ? grid.getPivot().getLevel().getServer()
                                : null;
                        if (server != null) {
                            server.getPlayerList().broadcastSystemMessage(
                                    net.minecraft.network.chat.Component.literal("[合成修復] 卡死救援：已取消 ")
                                            .append(out.what().getDisplayName())
                                            .append(net.minecraft.network.chat.Component.literal(
                                                    " 的合成（等不到 "))
                                            .append(frozen.getDisplayName())
                                            .append(net.minecraft.network.chat.Component.literal(
                                                    " x" + want + "，已靜止 " + (stalledTicks / 20)
                                                            + " 秒）；料已退回網路，請重新下單")),
                                    false);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable t) {
                int c = gtocraftfix$sitterLog.incrementAndGet();
                if (c <= 5) {
                    LOG.error("[craftfix] 卡死救援例外", t);
                }
            }
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
                            LOG.info("[craftfix] 已啟用 slim：同步算料(ctrl+左鍵)＋機器源 IgnoreMissing(請求器)"
                                    + "＋並行死角解鎖＋計畫修補＋機器退化計畫拒收；"
                                    + "lpcalc 停用；降量重算={}、幻影中間料餵料={}。",
                                    gtocraftfix$MACHINE_DOWNSCALE, gtocraftfix$FEED);
                            LOG.info("[craftfix] 修補旗標 {}", gtocraftfix$flagLine()); // [3.11.0]
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
            boolean machineSrc0 = gtocraftfix$isMachineSource(actionSrc0);
            if (machineSrc0 && ((ICraftingService) (Object) this).getCraftingFor(what).isEmpty()) {
                if (gtocraftfix$shouldNotifyNoPattern(what)) {
                    LOG.warn("[craftfix] 無樣板，擋下機器源請求：{} x{}（原版語意：不可合成）", what, amount);
                    // [3.13.4] 聊天室提示不再吃 !SLIM：3.13.2 起這道守衛在 slim 是**真的會擋**的
                    // （回零任務誠實 sim → 提交被拒），擋了卻不出聲＝玩家只看得到「機器不動」。
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
            if (machineSrc0 && !gtocraftfix$SLIM) { // [slim] 停用 lpcalc 接管：機器源與玩家一樣走下方同步 executeV2
                cir.setReturnValue(com.gtocraftfix.lpcalc.LpEntry.beginMachineCalc(
                        level, grid, (ICraftingService) (Object) this, simRequester,
                        what, amount, strategy, gtocraftfix$CALC_POOL));
                return;
            }
            var inventory = grid.getStorageService().getCachedInventory().copy();
            var plan = (ICraftingPlan) m.invoke(null, grid, inventory, simRequester, what, amount, strategy);

            // [3.13.2] 機器源降量重算預設關閉：它會把誠實的 CRAFT_LESS 硬開成短命單，與請求器
            // 「網存 0 就重下整批」組成吸料空轉。A/B 明確開啟時，一次工作共用 deadline，且同一
            // CraftingService(grid)／requester identity／key 有冷卻；全失敗只在冷卻週期首筆留一行 WARN。
            var actionSrc = simRequester.getActionSource();
            boolean machineSrc = gtocraftfix$isMachineSource(actionSrc);
            long downscaleStartedAt = System.nanoTime();
            if (gtocraftfix$MACHINE_DOWNSCALE && machineSrc && plan != null && amount > 1
                    && (plan.simulation() || plan.finalOutput() == null || plan.finalOutput().amount() <= 0)
                    && gtocraftfix$startDownscale(simRequester, what, downscaleStartedAt)) {
                long tryAmount = amount;
                long startedAt = downscaleStartedAt;
                int attempts = 0;
                boolean succeeded = false;
                boolean timedOut = false;
                Throwable retryFailure = null;
                for (int i = 0; i < 12 && tryAmount > 1; i++) {
                    if (CraftingHotfixSupport.budgetExpired(startedAt,
                            gtocraftfix$DOWNSCALE_BUDGET_NS, System.nanoTime())) {
                        timedOut = true;
                        break;
                    }
                    tryAmount /= 2;
                    ICraftingPlan p2;
                    try {
                        var inv2 = grid.getStorageService().getCachedInventory().copy();
                        p2 = (ICraftingPlan) m.invoke(null, grid, inv2, simRequester, what, tryAmount, strategy);
                        attempts++;
                    } catch (Throwable retryError) {
                        retryFailure = retryError;
                        break;
                    }
                    if (CraftingHotfixSupport.budgetExpired(startedAt,
                            gtocraftfix$DOWNSCALE_BUDGET_NS, System.nanoTime())) {
                        timedOut = true;
                        break;
                    }
                    // 拒收「退化計畫」（usedItems 吃現貨、patternTimes 空＝啥都不合成）：GTO 沒有
                    // 「把開局吸入的成品交給 link」的步驟，這種 job 會抱著現貨永凍（實測 NAND 625）。
                    if (p2 != null && !p2.simulation() && p2.finalOutput() != null && p2.finalOutput().amount() > 0
                            && gtocraftfix$hasPositiveTask(p2)) {
                        int c = gtocraftfix$sitterLog.incrementAndGet();
                        if (c <= 200) {
                            LOG.info("[craftfix] 機器源降量重算 {}：{} → {}", what, amount, tryAmount);
                        }
                        plan = p2;
                        succeeded = true;
                        break;
                    }
                }
                if (!succeeded) {
                    LOG.warn("[craftfix] 機器源降量重算全失敗 {} x{}（試 {} 次{}{}）→ 保留原 CRAFT_LESS；"
                                    + "同 grid/requester/key {} 秒內不再重算",
                            what, amount, attempts,
                            timedOut ? "、共用時間預算 " + gtocraftfix$DOWNSCALE_BUDGET_MS + "ms 已耗盡" : "",
                            retryFailure == null ? "" : "、例外=" + retryFailure,
                            gtocraftfix$DOWNSCALE_COOLDOWN_SEC);
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
    // 網路夠 → 記進 usedItems；不夠 → 繼續補樣板。有界迴圈，中途失敗整組還原；是否拒收由守衛決定。
    @Inject(method = "submitJob", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtocraftfix$repairPlan(ICraftingPlan job, ICraftingRequester requestingMachine, ICraftingCPU target,
                                        boolean prioritizePower, IActionSource src,
                                        CallbackInfoReturnable<ICraftingSubmitResult> cir) {
        // [3.13.2] 看的是「提交時收到的形狀」：機器源零 tasks 不先嘗試修成另一張單，一律誠實拒收。
        // 玩家來源不套此守衛；方法尾仍再驗一次，防修補／還原意外產生退化形狀。
        if (gtocraftfix$isDegenerateMachinePlan(job, src)) {
            cir.setReturnValue(appeng.crafting.execution.CraftingSubmitResult.INCOMPLETE_PLAN);
            if (gtocraftfix$sitterLog.incrementAndGet() <= 200) {
                LOG.warn("[craftfix] 退化／不可判定計畫（機器源、無合成任務快照）→ 拒收");
            }
            return;
        }
        if (!(job instanceof CraftingPlan plan)) {
            // [3.7.0] 非 GTO CraftingPlan 仍保留提交診斷；安全守衛必須先跑，避免診斷例外繞過拒收。
            com.gtocraftfix.diag.CraftDiag.onSubmit(gtocraftfix$tickCounter, job, src, requestingMachine,
                    craftingCPUClusters);
            return;
        }
        // [3.7.0] 提交入口記錄（來源／計畫概要／重下單次數），並記下「誰已經在跑單」供 RETURN 對帳
        com.gtocraftfix.diag.CraftDiag.onSubmit(gtocraftfix$tickCounter, job, src, requestingMachine,
                craftingCPUClusters);
        com.gtocraftfix.diag.CraftDiag.dumpPlan(plan); // [3.7.0] 計畫出生留底（任務／used／missing）
        if (gtocraftfix$SLIM) {
            // [slim 3.3.0] 計畫修補**已啟用**（下方照跑）；一般真缺料擋單仍停用，但機器源零任務
            // 退化計畫是安全例外，任何分支都拒收。
            // [3.2.3] 修補前先把「幻影缺口」點出來：usedItems 要的量網路實際取不到、且計畫沒排任何
            // 樣板產它 → 不修就必然變成永遠等不到的 waitingFor（凍結源自計畫本身，與機器/認領無關）。
            try {
                // [3.7.0] 改用 balLog 額度（原本共用 sitterLog，幾分鐘就被燒光而靜音）
                if (gtocraftfix$balLog.incrementAndGet() <= gtocraftfix$BAL_MAX) {
                    var st = grid.getStorageService().getInventory();
                    var made = new HashSet<AEKey>();
                    for (var pe : plan.patternTimes().entrySet()) {
                        if (pe.getValue() > 0) {
                            for (var o : pe.getKey().getOutputs()) {
                                made.add(o.what());
                            }
                        }
                    }
                    var sb = new StringBuilder();
                    int n = 0;
                    for (var e : plan.usedItems()) {
                        long want = e.getLongValue();
                        if (want <= 0 || made.contains(e.getKey())) {
                            continue; // 計畫有排生產＝不是幻影
                        }
                        long can = st.extract(e.getKey(), want, Actionable.SIMULATE, src);
                        if (can < want && n++ < 6) {
                            sb.append(e.getKey()).append(" 要").append(want)
                                    .append("/網").append(can).append("; ");
                        }
                    }
                    if (sb.length() > 0) {
                        LOG.warn("[craftfix] 開單即缺（計畫未排生產，將由計畫修補補上）out={} → {}",
                                plan.finalOutput(), sb);
                    }
                }
                // [3.5.0] 出生收支：用計畫自己的數字驗證「消耗 ≤ usedItems＋排定產出」。
                // 出生就負＝算料器的鍋；出生平、跑到一半才負＝執行期漂移（並行取料／認領歸屬）。
                gtocraftfix$planBirthBalance(plan);
            } catch (Throwable ignored) {
            }
        }
        boolean blockSubmit = false;
        // [3.11.1／B3] 計畫三本帳的參照與修補前快照**宣告在 try 之外**：原本宣告在 try 內部，
        // catch(Throwable) 看不到 → 例外時直接送出半套計畫（輪次加了、輸入沒補）＝必凍。
        Map<IPatternDetails, Long> pt = null;
        appeng.api.stacks.KeyCounter used = null;
        appeng.api.stacks.KeyCounter missing = null;
        Map<IPatternDetails, Long> snapPt = null;
        Map<AEKey, Long> snapUsed = null;
        Map<AEKey, Long> snapMissing = null;
        // [3.11.1／B5，3.11.2／M2 收斂] 中止路徑改成旗標 fall-through 到方法尾端的統一出口，
        // 但**只在擋單旗標為 on/force 時才 fall-through**：預設 off 必須維持 3.8.0 的「中止即 return」，
        // 否則 SLIM=false 的建置會在中止後多走尾端的 blockSubmit／退化計畫兩道拒單 → 機器每 2 秒重試
        // 被擋（3.10.0 那次退步的形狀），而且完全不受 repairBlockOnAbort 控制。詳見中止區塊內註解。
        boolean aborted = false;
        // [3.11.2／M6b] resultSet 只是**防禦性**旗標：mixin 0.8.7 對 cancellable=true 的 @Inject，
        // CallbackInfoReturnable.setReturnValue 是冪等的（重複呼叫不會拋 CancellationException——
        // 那是 cancellable=false 時呼叫 cancel/setReturnValue 才有的事）。保留它是為了語意清楚：
        // 一次提交只由一個判斷決定回傳值，後面的判斷不覆寫前面的。
        boolean resultSet = false;
        // [3.11.2／M3] 修補是否已「成功走完」：catch(Throwable) 只在**尚未成功**時才整組還原。
        // 病灶：try 內從「修補成功」到方法結束之間還有數段不在自家 try 裡的程式碼（B2 最終產出驗證、
        // 配平段、removeZeros、log、bytes／sim 反射），其中任何一句拋例外，原本的無條件還原都會把
        // **已經修好的計畫**倒回幻影計畫（＝必凍），比不修更糟。
        boolean repairDone = false;
        boolean snapReady = false; // [3.11.2／X5] 三份快照都填完才可以拿去還原
        try {
            long repairStartedAt = System.nanoTime();
            var storage = grid.getStorageService().getInventory();
            used = plan.usedItems();
            missing = plan.missingItems();
            pt = plan.patternTimes(); // 同一個可變 map（Object2LongOpenHashMap）

            // 可用量帳本：avail=實際可取（SIMULATE），reserved=本計畫已預定
            Map<AEKey, Long> avail = new HashMap<>();
            Map<AEKey, Long> reserved = new HashMap<>();
            // [3.11.1／B1] 缺口元組＝{AEKey, Long 短缺量, Boolean hard, String 來源}；
            // 第 4 欄的來源決定「這筆缺口可以沖銷哪一本帳」（只有 SRC_USED 能降 usedItems）。
            var deficits = new ArrayDeque<Object[]>();
            var finalKey = plan.finalOutput() == null ? null : plan.finalOutput().what();

            // ① missingItems：算料器標「缺」的量——有樣板就能補排（sim 計畫的病灶）。
            //    機器源的 sim 計畫會被 submitJob 守衛靜默拒單（玩家反而放行），全補完就把 sim 翻回 false。
            for (var e : missing) {
                var key = e.getKey();
                long want = e.getLongValue();
                if (want > 0) {
                    deficits.add(new Object[] { key, want, Boolean.TRUE, gtocraftfix$SRC_MISSING });
                }
            }
            // ② usedItems 超出實際可取的幻影缺口（**唯一**該把 usedItems 降下來的來源：那筆量是謊報）
            for (var e : used) {
                var key = e.getKey();
                long want = e.getLongValue();
                long a = avail.computeIfAbsent(key,
                        k -> storage.extract(k, Long.MAX_VALUE / 2, Actionable.SIMULATE, src));
                long take = Math.min(want, Math.max(0,
                        CraftingHotfixSupport.saturatingSubtract(a, reserved.getOrDefault(key, 0L))));
                if (take < want) {
                    deficits.add(new Object[] { key,
                            CraftingHotfixSupport.positiveDeficit(want, take),
                            Boolean.TRUE, gtocraftfix$SRC_USED });
                }
                reserved.merge(key, Math.max(0, take), CraftingHotfixSupport::saturatingAdd);
            }
            // ③ 最終產出量檢查：整條鏈 runs 取整後總產出可能 < 需求（實測 flake 差 19 →
            //    所有任務做完仍交不齊、永凍）。樣板產出＋放射 < 需求 → 差額列缺口補樣板；
            //    used(final) 現貨不算，因為執行器不會把開局吸入 CPU 的 final 交給 link。
            if (plan.finalOutput() != null) {
                var outKey = finalKey;
                long patternProduced = 0;
                for (var en : pt.entrySet()) {
                    Long r = en.getValue();
                    if (r == null || r <= 0) {
                        continue;
                    }
                    for (var o : en.getKey().getOutputs()) {
                        if (outKey.equals(o.what())) {
                            patternProduced = CraftingHotfixSupport.saturatingAdd(patternProduced,
                                    CraftingHotfixSupport.saturatingMultiply(o.amount(), r));
                        }
                    }
                }
                // GTO 不會把開局吸入 CPU 的 used(final) 交給 link；最終可交付量只能算 emitted＋樣板產出。
                long supply = CraftingHotfixSupport.finalDeliverable(
                        plan.emittedItems().get(outKey), patternProduced);
                long needOut = CraftingHotfixSupport.positiveDeficit(plan.finalOutput().amount(), supply);
                if (needOut > 0) {
                    // [3.11.1／B2] 標 SRC_FINAL：這筆缺口從沒加進 usedItems，沖銷它等於「加了輪次
                    // 又從 usedItems 扣掉等量」＝供給原地踏步（自我抵銷），下方沖銷段一律不碰。
                    deficits.add(new Object[] { outKey, needOut, Boolean.TRUE, gtocraftfix$SRC_FINAL });
                    if (gtocraftfix$balLog.incrementAndGet() <= gtocraftfix$BAL_MAX) {
                        LOG.info("[craftfix] 最終產出短缺 {} x{}（out={}）", outKey, needOut, plan.finalOutput());
                    }
                }
            }
            int guard = 0;
            int repaired = 0;
            long addedRuns = 0;
            String abortReason = null;
            StringBuilder note = new StringBuilder();
            // [3.8.0] 修補前留底：修不完整就整組還原——半套計畫（輪次加了、輸入沒補）＝必凍，比不修更糟
            // [3.11.1／B3] 三個快照的**宣告**已上移到 try 之外，這裡只負責填值（catch 才看得到）
            // [3.11.2／X5] 三份快照要「全部填完」才算可用：填 snapUsed 的迴圈中途拋例外時，
            // 用半份快照回沖會把不在快照裡的 usedItems 全部歸零（delta = 0 - 現值），等於清掉取料清單。
            snapReady = false;
            snapPt = new HashMap<>(pt);
            var snapUsed0 = new HashMap<AEKey, Long>();
            for (var e : used) {
                snapUsed0.put(e.getKey(), e.getLongValue());
            }
            var snapMissing0 = new HashMap<AEKey, Long>();
            for (var e : missing) {
                snapMissing0.put(e.getKey(), e.getLongValue());
            }
            snapUsed = snapUsed0;
            snapMissing = snapMissing0;
            snapReady = true;
            // [3.13.0] 本次修補的新增輪數上限：跟著原計畫規模縮放（見 REPAIR_RUN_FACTOR 的 javadoc）。
            // 固定 200 萬對 112 萬輪的計畫來說，正常修補量就會撞牆 → 整組還原 → 必凍。
            long planRounds = 0;
            for (var v : snapPt.values()) {
                if (v != null && v > 0) {
                    planRounds = CraftingHotfixSupport.saturatingAdd(planRounds, v);
                }
            }
            long runCap = gtocraftfix$REPAIR_RUN_CAP;
            if (gtocraftfix$REPAIR_RUN_FACTOR > 0 && planRounds > 0) {
                long scaled;
                try {
                    scaled = Math.multiplyExact(planRounds, gtocraftfix$REPAIR_RUN_FACTOR);
                } catch (ArithmeticException overflow) {
                    scaled = Long.MAX_VALUE;
                }
                runCap = Math.max(runCap, scaled);
            }
            if (gtocraftfix$REPAIR_RUN_HARD_CAP > 0) {
                runCap = Math.min(runCap, gtocraftfix$REPAIR_RUN_HARD_CAP);
            }
            // 外圈：解缺口 → 可執行性模擬（抓循環自舉缺口）→ 再解，最多 4 輪
            for (int round = 0; round < 4; round++) {
            while (!deficits.isEmpty() && guard++ < gtocraftfix$REPAIR_GUARD) {
                if ((guard & 63) == 0 && CraftingHotfixSupport.budgetExpired(
                        repairStartedAt, gtocraftfix$REPAIR_BUDGET_NS, System.nanoTime())) {
                    abortReason = "超過時間預算 " + (gtocraftfix$REPAIR_BUDGET_NS / 1_000_000)
                            + "ms（已處理 " + guard + " 項、仍剩 " + deficits.size() + " 項）";
                    break;
                }
                var d = deficits.poll();
                var key = (AEKey) d[0];
                long shortAmt = (Long) d[1];
                // hard=真實記帳缺口（missing/usedItems/最終產出/新增輸入）；自舉猜測（近似模擬）為 soft
                boolean hard = d.length > 2 && Boolean.TRUE.equals(d[2]);
                // [3.11.1／B1] 來源（第 4 欄）：決定下方沖銷哪一本帳。
                // [3.11.2／M4] 讀不到來源的預設值從 SRC_USED 改成 SRC_UNKNOWN（＝什麼都不沖銷）：
                // 舊預設等於「日後任何入列點漏標，就靜默退回 B1 的病灶」——把沒進過 usedItems 的量
                // 從 usedItems 扣成負值 → 執行期無聲蒸發。不沖銷最壞只是多留一筆 usedItems（保守），
                // 並另印 WARN 指出是哪個 key，讓漏標當場曝光而不是變成隱性錯帳。
                String srcTag = gtocraftfix$SRC_UNKNOWN;
                if (d.length > 3 && d[3] instanceof String) {
                    srcTag = (String) d[3];
                }
                if (gtocraftfix$SRC_UNKNOWN.equals(srcTag)
                        && gtocraftfix$srcUnknownLog.incrementAndGet() <= 5) {
                    LOG.warn("[craftfix] 缺口來源未標記（視為 UNKNOWN → 不沖銷任何一本帳）：{} x{} out={}"
                            + "（請補上缺口入列點的第 4 欄來源）", key, shortAmt, plan.finalOutput());
                }

                // [3.9.0，3.11.0 起預設關閉 `-Dgtodiag.repairNetSpot=true` 開啟]
                // 先拿網路現貨：缺的量網路有就直接記進 usedItems（開局一次取進 CPU），比排樣板便宜也即時。
                // 實錄：LUV 電路做到剩 34 個時餓死在 lubricant 132／copper_block 4／platinum_single_wire 6，
                // 而網路各有 115209／6／13——就差這一步。
                // 成品本身例外：GTO 沒有「把開局吸入的成品交給 link」的步驟，吸進去只會抱著現貨永凍。
                if (gtocraftfix$REPAIR_NET_SPOT && shortAmt > 0 && !key.equals(finalKey)) {
                    long a0 = avail.computeIfAbsent(key,
                            k -> storage.extract(k, Long.MAX_VALUE / 2, Actionable.SIMULATE, src));
                    long free0 = Math.max(0, CraftingHotfixSupport.saturatingSubtract(
                            a0, reserved.getOrDefault(key, 0L)));
                    long fromNet0 = Math.min(shortAmt, free0);
                    if (fromNet0 > 0) {
                        gtocraftfix$addCounterSaturated(used, key, fromNet0);
                        reserved.merge(key, fromNet0, CraftingHotfixSupport::saturatingAdd);
                        long fm0 = Math.min(fromNet0, Math.max(0, missing.get(key)));
                        if (fm0 > 0) {
                            gtocraftfix$addCounterSaturated(missing, key, -fm0);
                        }
                        shortAmt = CraftingHotfixSupport.positiveDeficit(shortAmt, fromNet0);
                        repaired++;
                        if (repaired <= 8) {
                            note.append(key).append(" +").append(fromNet0).append("(網路現貨); ");
                        }
                        if (shortAmt <= 0) {
                            continue;
                        }
                    }
                }

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
                    if (gtocraftfix$balLog.incrementAndGet() <= gtocraftfix$BAL_MAX) {
                        LOG.warn("[craftfix] 計畫修補 無樣板可補：{} x{}（out={}）", key, shortAmt, plan.finalOutput());
                    }
                    if (gtocraftfix$shouldNotifyNoPattern(key)) {
                        var server = grid.getPivot() != null && grid.getPivot().getLevel() != null
                                ? grid.getPivot().getLevel().getServer()
                                : null;
                        // [3.11.1／B3] finalOutput() 可能為 null（同方法他處都判過，只有這裡沒判），
                        // 下面直接 .what().getDisplayName() 是實際可達的 NPE → 補判斷。
                        if (server != null && plan.finalOutput() != null) {
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
                long runs = CraftingHotfixSupport.ceilDivPositive(shortAmt, batch);
                addedRuns = CraftingHotfixSupport.saturatingAdd(addedRuns, runs);
                if (addedRuns > runCap) {
                    abortReason = "新增輪數 " + addedRuns + " 超過上限 " + runCap
                            + "（原計畫 " + planRounds + " 輪 × 係數 " + gtocraftfix$REPAIR_RUN_FACTOR
                            + "，底線 " + gtocraftfix$REPAIR_RUN_CAP
                            + "、天花板 " + gtocraftfix$REPAIR_RUN_HARD_CAP + "）";
                    break;
                }
                pt.merge(pat, runs, CraftingHotfixSupport::saturatingAdd);
                // [3.11.1／B1+B2] 缺口沖銷：**只有 SRC_USED（usedItems 幻影）才可以降 usedItems**——
                // 那筆 usedItems 是網路給不出的謊報，降下來才對得上帳；其他來源（SRC_FINAL 最終產出短缺、
                // SRC_INPUT 新增 runs 的輸入、SRC_BOOTSTRAP 循環自舉）從來沒把量加進 usedItems，
                // 照樣沖銷就會把 usedItems 寫成負值 → 執行期無聲蒸發（且 SRC_FINAL 還會自我抵銷：
                // 加了輪次又扣掉等量供給，等於原地踏步）。沖銷量一律 clamp 在 0 以上。
                // 旗標 -Dgtodiag.repairDeficitSrc：on（預設）／clamp（不分來源只防負值）／off（3.8.0 原樣）。
                boolean srcOn = "on".equals(gtocraftfix$REPAIR_DEFICIT_SRC);
                boolean srcOff = "off".equals(gtocraftfix$REPAIR_DEFICIT_SRC);
                // [3.11.2／M4] UNKNOWN＝來源不明 → 兩本帳都不動（連 clamp/off 的 A/B 模式也不例外：
                // 那兩個模式是「不分來源」的實驗值，前提是缺口確實有來源；來源根本讀不到時，
                // 唯一安全的選擇是不沖銷。現行四個入列點都有標來源，這條路只會在日後漏標時走到）。
                boolean srcUnknown = gtocraftfix$SRC_UNKNOWN.equals(srcTag);
                long rest = shortAmt;
                if (!srcUnknown && (!srcOn || gtocraftfix$SRC_MISSING.equals(srcTag))) {
                    long fromMissing = Math.min(rest, Math.max(0, missing.get(key)));
                    if (fromMissing > 0) {
                        gtocraftfix$addCounterSaturated(missing, key, -fromMissing);
                        rest = CraftingHotfixSupport.positiveDeficit(rest, fromMissing);
                    }
                }
                if (rest > 0 && !srcUnknown && (!srcOn || gtocraftfix$SRC_USED.equals(srcTag))) {
                    long fromUsed = srcOff ? rest : Math.min(rest, Math.max(0, used.get(key)));
                    if (fromUsed > 0) {
                        gtocraftfix$addCounterSaturated(used, key, -fromUsed);
                    }
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
                    long perInput = CraftingHotfixSupport.saturatingMultiply(
                            prim.amount(), input.getMultiplier());
                    long need = CraftingHotfixSupport.saturatingMultiply(perInput, runs);
                    long a = avail.computeIfAbsent(inKey,
                            k -> storage.extract(k, Long.MAX_VALUE / 2, Actionable.SIMULATE, src));
                    long free = Math.max(0, CraftingHotfixSupport.saturatingSubtract(
                            a, reserved.getOrDefault(inKey, 0L)));
                    long fromNet = Math.min(need, free);
                    if (fromNet > 0) {
                        gtocraftfix$addCounterSaturated(used, inKey, fromNet);
                        reserved.merge(inKey, fromNet, CraftingHotfixSupport::saturatingAdd);
                    }
                    long remainingNeed = CraftingHotfixSupport.positiveDeficit(need, fromNet);
                    if (remainingNeed > 0) {
                        // [3.11.1／B1] SRC_INPUT：新增 runs 的輸入需求，從未加進 usedItems → 不可沖銷
                        deficits.add(new Object[] { inKey, remainingNeed, Boolean.TRUE,
                                gtocraftfix$SRC_INPUT });
                    }
                }
            }
            // [3.8.0] while 只有兩種出口：缺口解完，或處理額度耗盡。後者代表計畫被改到一半 → 放棄。
            if (abortReason == null && !deficits.isEmpty()) {
                abortReason = "缺口未解完（已處理 " + guard + " 項、上限 " + gtocraftfix$REPAIR_GUARD
                        + "，仍剩 " + deficits.size() + " 項）";
            }
            if (abortReason != null) {
                break;
            }
            // 可執行性模擬：紙上執行整張計畫（usedItems 當起始庫存、逐輪跑可跑的樣板）。
            // 跑不完＝有樣板被「0 庫存的輸入」卡死＝循環自舉缺口（如 H₂O₂ 蒽醌工作液：
            // 帳面淨消耗 0 → 不在 usedItems/missing → 但執行要有第一桶才轉得起來）→ 補進缺口再解。
            var bootstrap = gtocraftfix$findBootstrapDeficits(
                    plan, repairStartedAt, gtocraftfix$REPAIR_BUDGET_NS);
            if (bootstrap == null) {
                abortReason = "循環自舉模擬超過共用時間預算 "
                        + (gtocraftfix$REPAIR_BUDGET_NS / 1_000_000) + "ms";
                break;
            }
            for (var b : bootstrap) {
                deficits.add(b);
                if (gtocraftfix$balLog.incrementAndGet() <= gtocraftfix$BAL_MAX) {
                    LOG.info("[craftfix] 循環自舉缺口 {} x{}（out={}）", b[0], b[1], plan.finalOutput());
                }
            }
            if (deficits.isEmpty()) {
                break;
            }
            }
            // [3.11.1／B6，3.11.2／M1 改成預設只記錄] 外圈 4 輪跑滿仍有缺口。
            // 推導（見 REPAIR_STRICT_ROUNDS 的 javadoc）：內圈 while 只有「缺口清空」與「guard／runCap／
            // 時間預算耗盡（已設 abortReason 並 break）」兩種出口，所以走到這裡且 abortReason==null
            // ⇒ 正常情況是第 4 輪末尾 findBootstrapDeficits 又回報 soft 自舉猜測；防禦上仍驗證每筆，
            // 任何 hard 殘留都必須中止。全 soft 時若整組還原成未修補的原計畫＝幻影 usedItems 沒補 →
            // IgnoreMissing 掛出永遠等不到的 waitingFor → 必凍；而 findBootstrapDeficits 只看
            // poss[0] 主變體，靠替代輸入滿足的樣板會被誤判、每輪回報同一筆 → 確定性重現。
            // 故「確定全 soft」時預設只印 WARN；只要含 hard 就無條件中止，strict 旗標僅控制全 soft。
            if (abortReason == null && !deficits.isEmpty()) {
                boolean allSoft = true;
                for (var d : deficits) {
                    if (d.length > 2 && Boolean.TRUE.equals(d[2])) {
                        allSoft = false;
                        break;
                    }
                }
                if (!allSoft || gtocraftfix$REPAIR_STRICT_ROUNDS) {
                    abortReason = "外圈 4 輪仍有未解缺口（剩 " + deficits.size() + " 項"
                            + (allSoft ? "、全為 soft" : "、含 hard") + "）";
                } else if (gtocraftfix$repairNoteLog.incrementAndGet() <= gtocraftfix$NOTE_LOG_MAX) {
                    LOG.warn("[craftfix] 外圈 4 輪後仍有殘留缺口：剩 {} 項（{}）→ 照送已修好的計畫"
                            + "（預設不還原：還原成原計畫才是必凍）out={}",
                            deficits.size(),
                            "全為 soft＝近似模擬的自舉猜測",
                            plan.finalOutput());
                }
            }
            // repairDone 要等可選配平原子完成；配平若半途例外，仍須整組倒回快照，
            // 不能留下「輪次已改＋只補一半 usedItems」的計畫。最終交付守衛刻意不在
            // 這裡觀測：必須等本段可能的配平例外／rollback 結束，再直接檢查最後實際要提交的 plan。
            // [3.9.1] 第五維：內部配平**只做網路現貨補齊**，一趟做完、不排樣板、不遞迴。
            // 3.9.0 曾把配平缺口丟回缺口佇列（＝補樣板輪次），結果遞迴發散：實測 LUV 電路的缺口
            // 6 輪後從 glowstone 147456 膨脹到 3833856，計畫被灌大且仍不平，交付量從 466/500 掉到 8/500。
            // 補樣板會製造新輸入需求 → 新缺口 → 再補，這條路只能由算料器做；修補這層只該把
            // 「網路現貨拿得到的小缺口」補上（實錄：lubricant 132／copper_block 4／
            // electronic_grade_silicon 6912，網路各有 115209／6／564480）。
            // [3.8.0] 修補不完整 → 整組還原成修補前的計畫（寧可被拒單重試，也不交半套計畫）。
            // [3.10.2] 還原必須在配平補齊**之前**做，否則補進 usedItems 的現貨會被還原一起洗掉。
            if (abortReason != null) {
                // [3.11.1／B3] 還原抽成共用私有方法，與 catch(Throwable) 路徑走同一份程式碼
                gtocraftfix$restorePlan(pt, used, missing, snapPt, snapUsed, snapMissing);
            }
            // ---- 第五維：內部配平（[3.9.1] 補齊、[3.10.2] 中止也做、[3.11.0] 全部改成旗標）----
            // apply=真的把缺口用網路現貨補進 usedItems（`-Dgtodiag.repairBalance=true`）；
            //       中止時是否照做由 `-Dgtodiag.repairBalanceOnAbort` 決定（預設否＝維持全有全無）。
            // 只有 log 時純唯讀，不碰 used/reserved——計畫位元與 3.8.0 完全相同，只多一次掃描。
            // ⚠ 這個配平模型是啟發式的（替代輸入以供給扣除法歸戶、缺口記在主變體上），高估就會把
            //   沒人會消耗的料吸進 CPU 抱到收單，所以預設關閉、先看 log 再決定要不要開。
            boolean balApply = gtocraftfix$REPAIR_BALANCE
                    && (abortReason == null || gtocraftfix$REPAIR_BALANCE_ON_ABORT);
            if (balApply || gtocraftfix$REPAIR_BALANCE_LOG) {
                var bal = gtocraftfix$internalBalanceDeficits(plan);
                if (bal == null) {
                    if (gtocraftfix$balanceLog.incrementAndGet() <= gtocraftfix$BALANCE_LOG_MAX) {
                        LOG.warn("[craftfix] 內部配平 out={} 無法判定（掃描例外）；未採用任何部分結果{}",
                                plan.finalOutput(), balApply ? "，整組計畫將還原" : "（只觀測）");
                    }
                    if (balApply) {
                        // repairDone 尚未設成 true；交給共用 catch 還原 patternTimes／used／missing／allocations。
                        throw new IllegalStateException("內部配平掃描失敗");
                    }
                } else {
                    if (balApply) {
                        // 先確認完整掃描成功，才重建 reserved／開始碰 used；失敗不可能套用部分列表。
                        reserved.clear();
                        for (var e : used) {
                            if (e.getLongValue() > 0) {
                                reserved.merge(e.getKey(), e.getLongValue(),
                                        CraftingHotfixSupport::saturatingAdd);
                            }
                        }
                    }
                    long filled = 0;
                    var miss = new StringBuilder();
                    int mn = 0;
                    for (var b : bal) {
                        var bk = (AEKey) b[0];
                        long need = (Long) b[1];
                        if (bk.equals(finalKey)) {
                            continue; // 成品不吸現貨（GTO 沒有把開局現貨交給 link 的步驟）
                        }
                        long take1 = 0;
                        if (balApply) {
                            long a1 = avail.computeIfAbsent(bk,
                                    k -> storage.extract(k, Long.MAX_VALUE / 2, Actionable.SIMULATE, src));
                            long free1 = Math.max(0, CraftingHotfixSupport.saturatingSubtract(
                                    a1, reserved.getOrDefault(bk, 0L)));
                            take1 = Math.min(need, free1);
                            if (take1 > 0) {
                                gtocraftfix$addCounterSaturated(used, bk, take1);
                                reserved.merge(bk, take1, CraftingHotfixSupport::saturatingAdd);
                                filled = CraftingHotfixSupport.saturatingAdd(filled, take1);
                                repaired++;
                            }
                        }
                        long stillNeed = CraftingHotfixSupport.positiveDeficit(need, take1);
                        if (stillNeed > 0 && mn++ < 5) {
                            miss.append(bk).append(" x").append(stillNeed).append("; ");
                        }
                    }
                    // [3.11.2／M5] 配平觀測是每次提交都可能印的新訊息，額度改走獨立的 balanceLog：
                    // 原本共用 balLog 會把「開單即缺／最終產出短缺／循環自舉缺口／計畫修補」這些
                    // 3.8.0 時代就有的診斷提前燒到靜音。
                    if ((filled > 0 || mn > 0)
                            && gtocraftfix$balanceLog.incrementAndGet() <= gtocraftfix$BALANCE_LOG_MAX) {
                        LOG.info("[craftfix] 內部配平 out={} {}{}", plan.finalOutput(),
                                balApply ? "網路補齊" + filled + "單位" : "（只觀測，未補齊）",
                                mn == 0 ? "（帳已平）" : "；仍缺：" + miss);
                    }
                }
            }
            if (abortReason == null) {
                repairDone = true;
            }
            if (abortReason != null) {
                var left = new StringBuilder();
                int ln = 0;
                for (var d : deficits) {
                    if (ln++ >= 5) {
                        left.append('…');
                        break;
                    }
                    left.append(d[0]).append(" x").append(d[1]).append("; ");
                }
                // [3.10.1] 擋不擋單，看的是**還原後的計畫會不會必凍**，不是「修補有沒有做完」。
                // 必凍的充要條件（實測唯一會鎖死 CPU 的形狀）：usedItems 要的量網路給不出來，
                // 而且計畫裡沒有任何任務會產它 → IgnoreMissing 把差額變成永遠等不到的 waitingFor。
                // UHV 電路的退化計畫（1 個任務、used=wetware_processor_mainframe x100、網路只有 64）
                // 正是這個形狀；而「157 個任務、217 萬輪的正常大計畫只是修補沒跑完」不是——那種照送。
                // 3.10.0 一律擋，把後者也擋掉了（玩家手動能做、機器一直被擋）。
                // [3.11.0] 擋單改成旗標：off（預設，＝3.8.0 的「還原後照原樣送出」）／on／force。
                // on 在 slim 分支自動不生效——另外兩個擋單點（下方 blockSubmit、退化計畫）都有 SLIM 例外，
                // 只有這裡沒有，等於 3.10.0 起 slim 版違反了自己「只修計畫、不擋單」的約束。
                boolean machineSrc2 = gtocraftfix$isMachineSource(src);
                boolean doBlock = "force".equals(gtocraftfix$REPAIR_BLOCK)
                        || ("on".equals(gtocraftfix$REPAIR_BLOCK) && !gtocraftfix$SLIM);
                boolean willFreeze = false;
                var fatal = new StringBuilder();
                if (doBlock || gtocraftfix$REPAIR_FREEZE_PROBE) {
                    var made2 = new HashSet<AEKey>();
                    for (var pe : pt.entrySet()) {
                        if (pe.getValue() != null && pe.getValue() > 0) {
                            for (var o : pe.getKey().getOutputs()) {
                                made2.add(o.what());
                            }
                        }
                    }
                    int fn = 0;
                    for (var e : used) {
                        long want = e.getLongValue();
                        if (want <= 0 || made2.contains(e.getKey())) {
                            continue; // 計畫有排生產＝不會變成孤兒 waitingFor
                        }
                        long can = storage.extract(e.getKey(), want, Actionable.SIMULATE, src);
                        if (can < want) {
                            willFreeze = true;
                            if (fn++ < 5) {
                                fatal.append(e.getKey()).append(" 要").append(want)
                                        .append("/網").append(can).append("; ");
                            }
                        }
                    }
                }
                LOG.warn("[craftfix] **計畫修補放棄**（{}）→ 已還原成原計畫，{}。未解缺口：{} out={}",
                        abortReason,
                        !machineSrc2 ? "玩家路徑照原樣送出"
                                : (doBlock && willFreeze)
                                        ? "且還原後的計畫必凍（" + fatal + "）→ 擋下這次提交（機器會重試）"
                                        : willFreeze ? "⚠ 還原後的計畫必凍（" + fatal + "）但擋單已關閉 → 照原樣送出"
                                                : "照原樣送出",
                        left, plan.finalOutput());
                if (doBlock && machineSrc2 && willFreeze) {
                    // [3.11.0] 廣播另開旗標：這是對**全伺服器所有玩家**送訊息，多人環境會洗頻
                    // [3.11.1] finalOutput 可能為 null → 去重鍵與廣播都先判斷（擋單本身不受影響）
                    if (gtocraftfix$REPAIR_ABORT_BROADCAST && plan.finalOutput() != null
                            && gtocraftfix$abortNotified.add("abort|" + plan.finalOutput().what())) {
                        if (gtocraftfix$abortNotified.size() > 128) {
                            gtocraftfix$abortNotified.clear();
                        }
                        var server2 = grid.getPivot() != null && grid.getPivot().getLevel() != null
                                ? grid.getPivot().getLevel().getServer()
                                : null;
                        if (server2 != null) {
                            server2.getPlayerList().broadcastSystemMessage(
                                    net.minecraft.network.chat.Component.literal("[合成修復] 缺料補不齊，已擋下自動合成：")
                                            .append(plan.finalOutput().what().getDisplayName())
                                            .append(net.minecraft.network.chat.Component.literal(
                                                    " x" + plan.finalOutput().amount() + "（缺 " + fatal + "）")),
                                    false);
                        }
                    }
                    cir.setReturnValue(appeng.crafting.execution.CraftingSubmitResult.INCOMPLETE_PLAN);
                    resultSet = true;
                }
                // [3.11.1／B5 → 3.11.2／M2] fall-through **只在擋單旗標開著時**才做。
                // 實證：3.8.0 在這裡是直接 return，方法尾端的 blockSubmit（真缺料守衛）與退化計畫拒單
                // 在「修補中止」時**永遠碰不到**。B5 改成無條件 fall-through 後，SLIM=false 的建置
                // 會在中止後多走那兩道拒單 → 機器每 2 秒重試都被擋，正是 3.10.0 那次退步的形狀，
                // 而且完全不受 repairBlockOnAbort 控制（等於偷偷把擋單預設打開）。
                // 規則：off（預設）＝行為等同 3.8.0，直接 return；on/force＝才 fall-through 到統一出口
                //（SLIM 例外語意由尾端自行維持：slim 只修計畫、不擋單）。
                boolean blockCfg = "on".equals(gtocraftfix$REPAIR_BLOCK)
                        || "force".equals(gtocraftfix$REPAIR_BLOCK);
                // [3.11.2／X4] 再加一道 machineSrc2：force ＋ 玩家源時，上面的 WARN 印「玩家路徑照原樣送出」
                // 卻 fall-through 到尾端拒單（SLIM=false 建置），log 與實際行為相反。玩家源一律 return。
                boolean degenerateMachinePlan = gtocraftfix$isDegenerateMachinePlan(plan, src);
                if ((!blockCfg && !degenerateMachinePlan) || !machineSrc2) {
                    // 不吞掉「真缺料」這件事：本次若曾判定過硬缺口無樣板可補，明說守衛被略過的理由。
                    if (blockSubmit
                            && gtocraftfix$repairNoteLog.incrementAndGet() <= gtocraftfix$NOTE_LOG_MAX) {
                        LOG.info("[craftfix] 真缺料守衛因修補中止而略過（3.8.0 行為；"
                                + "要在中止後擋單請設 -Dgtodiag.repairBlockOnAbort=on/force）out={}",
                                plan.finalOutput());
                    }
                    // 中止路徑預設會在這裡提前離開；先針對已還原後的實際 plan 重算 final，避免
                    // used(final) 不可交付的必凍形狀繞過方法尾守衛。
                    gtocraftfix$rejectProvenFinalShortfall(plan, src, cir);
                    return;
                }
                aborted = true;
            }
            if (!aborted) {
                used.removeZeros();
                missing.removeZeros();
                if (repaired > 0 && gtocraftfix$balLog.incrementAndGet() <= gtocraftfix$BAL_MAX) {
                    LOG.info("[craftfix] 計畫修補 out={} 補{}項/新增{}輪：{}",
                            plan.finalOutput(), repaired, addedRuns, note);
                }
                // [3.13.0] 修補量超過原計畫規模＝計算器漏掉的比排出來的還多，或修補在發散。
                // 兩者都不該靜默通過：3.9.0 的遞迴發散就是先出現這個形狀（glowstone 147456→3833856）。
                if (planRounds > 0 && addedRuns > planRounds
                        && gtocraftfix$repairNoteLog.incrementAndGet() <= gtocraftfix$NOTE_LOG_MAX) {
                    LOG.warn("[craftfix] 修補量超過原計畫規模：新增 {} 輪 > 原計畫 {} 輪（上限 {}）"
                            + " → 計畫已照補送出，但請留意這張單的執行時間與 CPU 佔用 out={}",
                            addedRuns, planRounds, runCap, plan.finalOutput());
                }
                // [3.11.1／B8] 修補新增輪次後同步調高 plan.bytes()（預設關閉）。findSuitableCraftingCPU
                // 與 trySubmitJob 都是拿 bytes 比 cluster.getAvailableStorage()，而 bytes 是 CraftingPlan
                // 的 private final 欄位、修補全程沒動 → 「計畫過大 GTO 會自己拒絕」這道保護從未觸發。
                // 反射寫法比照下方 simulation 翻轉。
                // [3.11.2／M6a] 事實更正：javap 證實 CraftingPlan 是 `public final class`（**不是 record**）、
                // 欄位是 `private final long bytes`，JDK 21 對非 record 的 final 實例欄位在 setAccessible 後
                // **可寫**。所以開啟這個旗標時 bytes 一定寫得進去，CPU_TOO_SMALL 的行為變化是必然發生
                // ——不能靠「反射大概會失敗」當安全網；下方 catch 只涵蓋真正的意外（安全管理員等）。
                if (gtocraftfix$REPAIR_UPDATE_BYTES && addedRuns > 0) {
                    try {
                        long baseRuns = 0;
                        for (var v : snapPt.values()) {
                            if (v != null && v > 0) {
                                baseRuns = CraftingHotfixSupport.saturatingAdd(baseRuns, v);
                            }
                        }
                        long nowRuns = 0;
                        for (var v : pt.values()) {
                            if (v != null && v > 0) {
                                nowRuns = CraftingHotfixSupport.saturatingAdd(nowRuns, v);
                            }
                        }
                        long oldBytes = plan.bytes();
                        if (baseRuns > 0 && nowRuns > baseRuns && oldBytes > 0) {
                            double ratio = (double) nowRuns / (double) baseRuns;
                            double scaled = Math.ceil(oldBytes * ratio);
                            long newBytes = scaled >= (double) Long.MAX_VALUE
                                    ? Long.MAX_VALUE
                                    : Math.max(oldBytes, (long) scaled);
                            var fb = CraftingPlan.class.getDeclaredField("bytes");
                            fb.setAccessible(true);
                            fb.setLong(plan, newBytes);
                            int c = gtocraftfix$sitterLog.incrementAndGet();
                            if (c <= 200) {
                                LOG.info("[craftfix] 計畫修補 bytes {}→{}（輪數 {}→{}）out={}",
                                        oldBytes, newBytes, baseRuns, nowRuns, plan.finalOutput());
                            }
                        }
                    } catch (Throwable t) {
                        int c = gtocraftfix$sitterLog.incrementAndGet();
                        if (c <= 5) {
                            LOG.warn("[craftfix] bytes 調整失敗（維持原值）：{}", t.toString());
                        }
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
                // [3.13.2] repairDone 已在 allocations 清理與可選配平都成功後設好；後續只有
                // removeZeros／診斷與各自 fail-soft 的 bytes、simulation 反射，不再碰修補的原子狀態。
            }
        } catch (Throwable t) {
            // [3.11.1／B3] 例外也要整組還原：原本只記 log 就放行，等於把「輪次加了、輸入沒補」的
            // 半套計畫送出去（必凍），違反「全有全無」核心不變式。與正常中止路徑共用同一份還原。
            // [3.11.2／M3] 但**只在修補尚未成功收尾時**還原：try 內「修補成功」之後仍有數段不在
            // 自家 try 裡的程式碼（B2 最終產出驗證、配平段、removeZeros、log、bytes／sim 反射），
            // 無條件還原會把已經修好的計畫倒回幻影計畫。repairDone=true 之後只記 log。
            if (!repairDone && snapReady) {
                try {
                    gtocraftfix$restorePlan(pt, used, missing, snapPt, snapUsed, snapMissing);
                    if (snapPt != null) {
                        LOG.warn("[craftfix] 計畫修補例外 → **已整組還原成原計畫**（避免送出半套計畫）");
                    }
                } catch (Throwable t2) {
                    LOG.error("[craftfix] 計畫修補例外後還原失敗（計畫可能已被改到一半）", t2);
                }
            }
            int c = gtocraftfix$sitterLog.incrementAndGet();
            if (c <= 5) {
                LOG.error("[craftfix] 計畫修補例外（{}）",
                        repairDone ? "修補已完成，保留修好的計畫、不還原" : "放行原計畫", t);
            }
        }
        // 所有修補、配平、例外還原都結束後才看當下 plan；不沿用任何修補前／修補中的快照判定。
        if (gtocraftfix$rejectProvenFinalShortfall(plan, src, cir)) {
            return;
        }
        // [3.13.2] 機器源退化計畫永遠拒收（slim 也一樣）。GTO 沒有把開局吸入的 final 現貨交給
        // link 的步驟；used-only／零任務計畫一旦上機只會抱貨永凍。玩家來源維持原行為。
        if (gtocraftfix$isDegenerateMachinePlan(plan, src)) {
            int c = gtocraftfix$sitterLog.incrementAndGet();
            if (c <= 200) {
                LOG.warn("[craftfix] 退化計畫（機器源、無合成任務）out={} → 拒收", plan.finalOutput());
            }
            if (!resultSet) {
                cir.setReturnValue(appeng.crafting.execution.CraftingSubmitResult.INCOMPLETE_PLAN);
                resultSet = true;
            }
            return;
        }
        // 真缺料（無樣板可補的硬缺口）→ 擋下提交：提交了必凍。機器每 2 秒重試（聊天室/log 已去重）；
        // 玩家按確認會沒反應，但聊天室已說明缺什麼。
        // [3.11.1／B5 → 3.11.2／M2] 中止路徑**只有在 repairBlockOnAbort=on/force 時**才 fall-through
        // 到這裡（預設 off 已在中止區塊直接 return＝3.8.0 行為），所以擋單前先看 resultSet。
        // [3.11.2／M6b] 事實更正：mixin 0.8.7 對 cancellable=true 的 @Inject，setReturnValue 是**冪等**的
        // （不會拋 CancellationException——那是 cancellable=false 時呼叫才有的事），resultSet 純屬防禦性
        // 寫法／語意宣告：一次提交的回傳值只由最先判定的那個守衛決定。SLIM 例外語意維持不變。
        if (blockSubmit) {
            if (gtocraftfix$SLIM) {
                return; // [slim 3.3.0] 只修計畫不擋單：真缺料照樣提交（log/聊天已點名）
            }
            if (!resultSet) {
                cir.setReturnValue(appeng.crafting.execution.CraftingSubmitResult.INCOMPLETE_PLAN);
                resultSet = true;
            }
            return;
        }
    }

    // ---- [3.7.0 純診斷] 開單對帳：提交返回後找出新上機的 CPU，把「計畫 usedItems」與
    // 「CPU 實際吸到的庫存＋掛上的在途」逐項比對——差額若不落在任一邊，就是取料階段直接吞掉；
    // 落在在途則是正常 IgnoreMissing（之後要靠認領回來）。這是分辨兩種凍結成因的唯一切點。----
    @Inject(method = "submitJob", at = @At("RETURN"), remap = false)
    private void gtocraftfix$diagSubmitted(ICraftingPlan job, ICraftingRequester requestingMachine,
                                           ICraftingCPU target, boolean prioritizePower, IActionSource src,
                                           CallbackInfoReturnable<ICraftingSubmitResult> cir) {
        com.gtocraftfix.diag.CraftDiag.onSubmitted(gtocraftfix$tickCounter, job, cir.getReturnValue(),
                grid, craftingCPUClusters);
    }

    // ---- 診斷：機器源提交失敗（原本完全無聲——追蹤器只會下一 tick 重試）----
    @Inject(method = "submitJob", at = @At("RETURN"), remap = false)
    private void gtocraftfix$diagMachineFail(ICraftingPlan job, ICraftingRequester requestingMachine,
                                             ICraftingCPU target, boolean prioritizePower, IActionSource src,
                                             CallbackInfoReturnable<ICraftingSubmitResult> cir) {
        if (!gtocraftfix$isMachineSource(src)) {
            return;
        }
        var r = cir.getReturnValue();
        if (r == null || r.successful()) {
            return;
        }
        // 同 key+錯誤 只記一次（機器追蹤器會每 2 秒重試，避免洗版）
        var finalOutput = job == null ? null : job.finalOutput();
        String sig = (finalOutput == null ? "?" : String.valueOf(finalOutput.what())) + "|" + r.errorCode();
        if (gtocraftfix$failLogged.add(sig)) {
            if (gtocraftfix$failLogged.size() > 128) {
                gtocraftfix$failLogged.clear();
            }
            LOG.warn("[craftfix] 機器源提交失敗 err={} sim={} missing={} out={}",
                    r.errorCode(), job != null && job.simulation(),
                    job == null || job.missingItems() == null ? -1 : job.missingItems().size(), finalOutput);
        }
    }

    // ---- 修正 2：機器源 present-once 走 IgnoreMissing ----
    @Redirect(method = "submitJob",
              at = @At(value = "INVOKE",
                       target = "Lappeng/me/cluster/implementations/CraftingCPUCluster;submitJob(Lappeng/api/networking/IGrid;Lappeng/api/networking/crafting/ICraftingPlan;Lappeng/api/networking/security/IActionSource;Lappeng/api/networking/crafting/ICraftingRequester;)Lappeng/api/networking/crafting/ICraftingSubmitResult;"),
              remap = false)
    private ICraftingSubmitResult gtocraftfix$machineAsPlayer(CraftingCPUCluster cluster, IGrid g, ICraftingPlan plan,
                                                              IActionSource src, ICraftingRequester requester) {
        if (src != null && gtocraftfix$isMachineSource(src)
                && AEConfig.instance().isAllowMissingCraftingJobs()) {
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

    private static void gtocraftfix$feedError(String message, Throwable error) {
        int n = gtocraftfix$feedErrorLog.incrementAndGet();
        if (n <= 20) {
            if (error == null) {
                LOG.error("[craftfix] {}", message);
            } else {
                LOG.error("[craftfix] {}", message, error);
            }
        } else if (n == 21) {
            LOG.error("[craftfix] 餵料回補 ERROR 已達 20 行，後續同類錯誤停止輸出（回補佇列仍會重試）");
        }
    }

    private record NetworkSnapshot(Long amount, Throwable error) {
    }

    /** 以同一 IActionSource 量出目前可抽總量；SIMULATE 自身失敗只代表證據未知，不會改 storage。 */
    private static NetworkSnapshot gtocraftfix$networkSnapshot(appeng.api.storage.MEStorage storage,
            AEKey key, IActionSource src) {
        try {
            long amount = storage.extract(key, Long.MAX_VALUE, Actionable.SIMULATE, src);
            if (amount < 0) {
                return new NetworkSnapshot(null,
                        new IllegalStateException("SIMULATE extract 回傳負數 " + amount));
            }
            return new NetworkSnapshot(amount, null);
        } catch (Throwable error) {
            return new NetworkSnapshot(null, error);
        }
    }

    /**
     * 任一 storage mutation／CPU waiting 帳無法證明時永久封鎖本 CraftingService 的自動搬料。
     * attempted 只是人工盤點的上界，絕不放回自動回補佇列；首次訊息不受一般 ERROR 額度影響。
     */
    private void gtocraftfix$quarantineTransfers(AEKey key, long attempted, String reason, Throwable error) {
        long attention = Math.max(0, attempted);
        if (key != null && attention > 0) {
            gtocraftfix$manualTransferAttention.merge(
                    key, attention, CraftingHotfixSupport::saturatingAdd);
        }
        boolean first = !gtocraftfix$transferQuarantined;
        gtocraftfix$transferQuarantined = true;
        String message = "自動搬料帳目無法證明（" + reason + "）：" + key + " 嘗試量上界 " + attention
                + "。本 grid 的 waiting feed／top-up／parallel rescue／refund replay 已全部停止；"
                + "不會猜 0 或自動重播，請人工核對 storage、CPU inventory、waitingFor。"
                + "此人工介入帳只存在目前 CraftingService 實例生命週期。";
        if (first) {
            if (error == null) {
                LOG.error("[craftfix] **人工介入必要** {}", message);
            } else {
                LOG.error("[craftfix] **人工介入必要** {}", message, error);
            }
        } else {
            gtocraftfix$feedError(message, error);
        }
    }

    private boolean gtocraftfix$automaticTransfersAllowed() {
        return !gtocraftfix$transferQuarantined;
    }

    /**
     * NetworkStorage MODULATE extract 的唯一入口。
     * <p>[3.13.2] 正常 return 以 <b>AE2 契約的回傳值</b>為準（{@code MEStorage.extract} 回傳「實際抽走多少」）。
     * 不再拿 before/after 的 SIMULATE 差額去否決它：那等於替 AE2 的核心契約再發明一套裁決，而且每次搬料
     * 都要多付一趟全網 SIMULATE。before 快照只留給「mutation 拋例外」這條真正無法從回傳值得知搬走多少
     * 的路徑；回傳值落在 {@code [0, requested]} 之外才算契約破裂而 quarantine。
     */
    private CraftingHotfixSupport.TransferDelta gtocraftfix$measuredNetworkExtract(
            appeng.api.storage.MEStorage storage, AEKey key, long requested, IActionSource src, String reason) {
        if (requested <= 0) {
            return new CraftingHotfixSupport.TransferDelta(true, 0);
        }
        if (!gtocraftfix$automaticTransfersAllowed()) {
            return new CraftingHotfixSupport.TransferDelta(false, 0);
        }
        var before = gtocraftfix$networkSnapshot(storage, key, src);
        if (before.amount() == null) {
            gtocraftfix$feedError("MODULATE extract 前無法取得 SIMULATE 快照，未搬料："
                    + key + " x" + requested + "（" + reason + "）", before.error());
            return new CraftingHotfixSupport.TransferDelta(true, 0);
        }

        long reported;
        try {
            reported = storage.extract(key, requested, Actionable.MODULATE, src);
        } catch (Throwable mutationError) {
            // 沒有回傳值可用，只能靠 before/after 推；推不出來就整組隔離，不猜 0（猜 0 會遺失已抽走的貨）。
            var after = gtocraftfix$networkSnapshot(storage, key, src);
            var delta = CraftingHotfixSupport.extractedDelta(requested, before.amount(), after.amount());
            if (!delta.known()) {
                gtocraftfix$quarantineTransfers(key, requested,
                        "NetworkStorage extract 例外且差額 UNKNOWN；before=" + before.amount()
                                + " after=" + after.amount() + " reason=" + reason,
                        after.error() != null ? after.error() : mutationError);
                return delta;
            }
            gtocraftfix$feedError("NetworkStorage extract 例外但差額可證：" + key + " 實抽 "
                    + delta.amount() + "/" + requested + "（" + reason + "）", mutationError);
            return delta;
        }
        if (reported < 0 || reported > requested) {
            gtocraftfix$quarantineTransfers(key, requested,
                    "NetworkStorage extract 回傳量違反契約；reported=" + reported
                            + " requested=" + requested + " reason=" + reason,
                    null);
            return new CraftingHotfixSupport.TransferDelta(false, 0);
        }
        return new CraftingHotfixSupport.TransferDelta(true, reported);
    }

    /**
     * MODULATE insert 的唯一入口；以 AE2 契約的回傳值為準。
     * <p>[3.13.2] <b>insert 這一側不能用 before/after 的可用量差額對帳</b>：AE2 的
     * {@code CraftingServiceStorage} 以 {@code Integer.MAX_VALUE} 最高優先權掛在網路上，插進來的貨
     * 只要有 CPU 的 {@code waitingFor} 在等就會被當場認領，<b>不會出現在可查詢庫存裡</b>（本 mod 的
     * 「認領」診斷整節講的就是這件事）。此時 after==before → 差額 0 ≠ 回傳量 → 舊寫法會把完全正常的
     * 回補判成帳目衝突並永久隔離整張 grid；而回補的觸發時機恰好就是「有 CPU 在等這個 key」，等於保證
     * 誤觸。改為只驗回傳值是否落在 {@code [0, requested]}。
     */
    private CraftingHotfixSupport.TransferDelta gtocraftfix$measuredNetworkInsert(
            appeng.api.storage.MEStorage storage, AEKey key, long requested, IActionSource src, String reason) {
        if (requested <= 0) {
            return new CraftingHotfixSupport.TransferDelta(true, 0);
        }
        if (!gtocraftfix$automaticTransfersAllowed()) {
            return new CraftingHotfixSupport.TransferDelta(false, 0);
        }
        long reported;
        try {
            reported = storage.insert(key, requested, Actionable.MODULATE, src);
        } catch (Throwable mutationError) {
            // 接受量無法從外部觀測（見上方認領說明），例外＝真的不知道回進去多少 → 隔離。
            gtocraftfix$quarantineTransfers(key, requested,
                    "NetworkStorage insert 例外，實際接受量無法證明；reason=" + reason,
                    mutationError);
            return new CraftingHotfixSupport.TransferDelta(false, 0);
        }
        if (reported < 0 || reported > requested) {
            gtocraftfix$quarantineTransfers(key, requested,
                    "NetworkStorage insert 回傳量違反契約；reported=" + reported
                            + " requested=" + requested + " reason=" + reason,
                    null);
            return new CraftingHotfixSupport.TransferDelta(false, 0);
        }
        return new CraftingHotfixSupport.TransferDelta(true, reported);
    }

    private void gtocraftfix$queueFeedRefund(AEKey key, long amount) {
        if (amount > 0 && gtocraftfix$automaticTransfersAllowed()) {
            gtocraftfix$feedRefunds.merge(key, amount, CraftingHotfixSupport::saturatingAdd);
        } else if (amount > 0) {
            gtocraftfix$manualTransferAttention.merge(
                    key, amount, CraftingHotfixSupport::saturatingAdd);
            gtocraftfix$feedError("自動搬料已隔離，已知待回補量不再自動重播："
                    + key + " x" + amount + "（需人工介入）", null);
        }
    }

    private static Long gtocraftfix$cpuAmount(
            appeng.crafting.inv.ListCraftingInventory inventory, AEKey key) {
        if (inventory == null) {
            return null;
        }
        try {
            return inventory.list.get(key);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * CPU insert 可能「先改庫存、後拋例外」；不能盲目把 extracted 全額回網路而複製已入 CPU 的部分。
     * 同一伺服器緒上以 CPU 庫存 before/after 作實體持有證據，只回補可證明未入庫的差額。若反射
     * 讀不到或差額方向／範圍不可能，不能猜 0 後全額回補；改進人工隔離並停止所有後續自動搬料。
     */
    private void gtocraftfix$restituteCpuInsertFailure(appeng.api.storage.MEStorage storage,
            appeng.crafting.inv.ListCraftingInventory inventory, AEKey key, Long before,
            long extracted, IActionSource src, String reason) {
        Long after = gtocraftfix$cpuAmount(inventory, key);
        var retainedProof = CraftingHotfixSupport.insertedDelta(extracted, before, after);
        if (!retainedProof.known()) {
            gtocraftfix$quarantineTransfers(key, extracted,
                    "CPU inventory insert 後差額 UNKNOWN；before=" + before + " after=" + after
                            + " reason=" + reason,
                    null);
            return;
        }
        long retained = retainedProof.amount();
        long refund = CraftingHotfixSupport.positiveDeficit(extracted, retained);
        gtocraftfix$returnExtracted(storage, key, refund, src,
                reason + "；CPU 已入 " + retained);
        if (retained > 0) {
            gtocraftfix$feedError("CPU insert 例外前已有部分入庫：" + key + " CPU 留 " + retained
                    + "、回補 " + refund + "/" + extracted + "（" + reason + "）", null);
        }
    }

    /**
     * GTO logic.insert 的順序是 waitingFor.extract → 計時帳 → CPU inventory.insert；中途拋例外時，
     * 只看 CPU 差額會把「waiting 已扣、CPU 未留」的貨退回網路，卻留下不再等待的壞帳。這裡同時量
     * 兩本帳：CPU 已留的視為接受，其餘先補回 waitingFor，再把實體差額退回 storage。
     */
    private void gtocraftfix$restituteLogicInsertFailure(appeng.api.storage.MEStorage storage,
            appeng.crafting.inv.ListCraftingInventory cpuInventory,
            appeng.crafting.inv.ListCraftingInventory waitingInventory,
            AEKey key, long cpuBefore, long waitingBefore, long extracted,
            IActionSource src, String reason) {
        Long cpuAfter = gtocraftfix$cpuAmount(cpuInventory, key);
        Long waitingAfter = gtocraftfix$cpuAmount(waitingInventory, key);
        var retainedProof = CraftingHotfixSupport.insertedDelta(extracted, cpuBefore, cpuAfter);
        var waitingProof = CraftingHotfixSupport.extractedDelta(extracted, waitingBefore, waitingAfter);
        if (!retainedProof.known() || !waitingProof.known()) {
            gtocraftfix$quarantineTransfers(key, extracted,
                    "logic.insert 後 CPU/waitingFor 差額 UNKNOWN；cpu=" + cpuBefore + "→" + cpuAfter
                            + " waiting=" + waitingBefore + "→" + waitingAfter,
                    null);
            return;
        }

        var compensation = CraftingHotfixSupport.logicInsertCompensation(
                extracted, cpuBefore, cpuAfter, waitingBefore, waitingAfter);
        long retained = compensation.retained();
        long consumedWaiting = compensation.consumedWaiting();
        long restoreWaiting = compensation.restoreWaiting();
        long restoredWaiting = 0;
        Throwable restoreError = null;
        boolean restoreProofUnknown = false;
        if (restoreWaiting > 0) {
            long restoreBase = waitingAfter;
            try {
                // 直接回沖 KeyCounter，刻意不走 ListCraftingInventory listener；原例外可能正是 listener
                // 在「KeyCounter 已改」後拋出，再呼 listener 只會重演例外並使交易無法復原。
                gtocraftfix$addCounterSaturated(waitingInventory.list, key, restoreWaiting);
            } catch (Throwable t) {
                restoreError = t;
            }
            Long afterRestore = gtocraftfix$cpuAmount(waitingInventory, key);
            var restoreProof = CraftingHotfixSupport.insertedDelta(
                    restoreWaiting, restoreBase, afterRestore);
            if (restoreProof.known()) {
                restoredWaiting = restoreProof.amount();
            } else {
                restoreProofUnknown = true;
            }
        }

        long unresolvedWaiting = CraftingHotfixSupport.positiveDeficit(restoreWaiting, restoredWaiting);
        if (unresolvedWaiting > 0) {
            gtocraftfix$feedError("logic.insert 例外後 waitingFor 回滾不完整：" + key
                    + " 尚差 " + unresolvedWaiting + "/" + restoreWaiting
                    + "（已保守把未入 CPU 的實體退回 storage）", restoreError);
        }
        long refund = compensation.physicalRefund();
        gtocraftfix$returnExtracted(storage, key, refund, src,
                reason + "；CPU 實留 " + retained + "、waitingFor 補回 " + restoredWaiting);
        if (retained > consumedWaiting) {
            gtocraftfix$feedError("logic.insert 例外後 CPU 留存大於 waitingFor 扣帳：" + key
                    + " CPU 留 " + retained + "、waiting 扣 " + consumedWaiting
                    + "；實體僅回補 " + refund + "，避免複製", restoreError);
        }
        if (retained != consumedWaiting || restoreWaiting > 0) {
            gtocraftfix$feedError("logic.insert 例外交易已對帳：" + key + " 抽 " + extracted
                    + "、CPU 實留 " + retained + "、waiting 扣 " + consumedWaiting
                    + "、waitingFor 補回 " + restoredWaiting
                    + "、storage 回 " + refund, restoreError);
        }
        if (restoreProofUnknown || unresolvedWaiting > 0 || retained > consumedWaiting) {
            gtocraftfix$quarantineTransfers(key, extracted,
                    "logic.insert 帳目回滾未完整證明；CPU 留=" + retained
                            + " waiting扣=" + consumedWaiting + " waiting補=" + restoredWaiting
                            + "/" + restoreWaiting,
                    restoreError);
        }
    }

    /** 把尚未被 CPU 接受的抽取量完整退回；storage 暫時拒收時保留帳並於後續 tick 優先重試。 */
    private boolean gtocraftfix$returnExtracted(appeng.api.storage.MEStorage storage, AEKey key, long amount,
                                                 IActionSource src, String reason) {
        if (amount <= 0) {
            return true;
        }
        if (!gtocraftfix$automaticTransfersAllowed()) {
            gtocraftfix$manualTransferAttention.merge(
                    key, amount, CraftingHotfixSupport::saturatingAdd);
            gtocraftfix$feedError("隔離後不再嘗試已知回補量：" + key + " x" + amount
                    + "（" + reason + "；需人工介入）", null);
            return false;
        }
        var insertion = gtocraftfix$measuredNetworkInsert(storage, key, amount, src, reason);
        if (!insertion.known()) {
            // attempted 已由 quarantine 記進人工介入帳；絕不可把全額再排入自動 replay。
            return false;
        }
        long restored = insertion.amount();
        long remaining = CraftingHotfixSupport.positiveDeficit(amount, restored);
        if (remaining > 0) {
            gtocraftfix$queueFeedRefund(key, remaining);
            gtocraftfix$feedError("餵料未能完整回存：" + key + " 已回 " + restored + "/" + amount
                    + "、暫存待重試 " + remaining + "（" + reason + "）", null);
            return false;
        }
        return true;
    }

    /** 回補債未清以前不再抽新的 sitter 餵料，避免 storage 故障時持續擴大暫存量。 */
    private boolean gtocraftfix$flushFeedRefunds(appeng.api.storage.MEStorage storage, IActionSource src) {
        if (!gtocraftfix$automaticTransfersAllowed()) {
            return false;
        }
        if (gtocraftfix$feedRefunds.isEmpty()) {
            return true;
        }
        for (var it = gtocraftfix$feedRefunds.entrySet().iterator(); it.hasNext();) {
            var entry = it.next();
            long owed = entry.getValue();
            if (owed <= 0) {
                it.remove();
                continue;
            }
            var insertion = gtocraftfix$measuredNetworkInsert(
                    storage, entry.getKey(), owed, src, "回補佇列重試");
            if (!insertion.known()) {
                // 此筆實際已回多少未知，移出自動佇列；quarantine map 只留人工盤點上界。
                it.remove();
                break;
            }
            long remaining = CraftingHotfixSupport.positiveDeficit(owed, insertion.amount());
            if (remaining == 0) {
                it.remove();
            } else {
                entry.setValue(remaining);
            }
        }
        return gtocraftfix$automaticTransfersAllowed() && gtocraftfix$feedRefunds.isEmpty();
    }

    // ---- 修正 3：保母（phantom-only 每秒；明確關閉時每 tick；final 永不餵）＋診斷探針（每 20 秒）----
    // [2.4.0] 掛 HEAD 不掛 TAIL：GTOCore 在偶數 tick 提前 ci.cancel() 本方法，掛 TAIL 整段實跑半速
    // （原「5 秒」實為 10 秒、探針 20 秒實為 40 秒——log 探針間隔 40 秒實證）。
    @Inject(method = "onServerEndTick", at = @At("HEAD"), remap = false)
    private void gtocraftfix$tick(MinecraftServer server, CallbackInfo ci) {
        com.gtocraftfix.calc.CalcTicker.tick(); // 內置原版算料器的預算泵（每 tick）
        com.gtocraftfix.lpcalc.LpFallbackQueue.drainOnServerTick(); // LP 晚期回退/影子驗證的伺服器緒建構點（鐵則5/8）
        gtocraftfix$tickCounter++;
        // [3.15.0] 登記這張網路，讓 /craftfix why 有東西可查（WeakHashMap，網路消失自動掉）
        com.gtocraftfix.GridRegistry.seen((ICraftingService) (Object) this, grid);
        // [3.14.0] link 稽核：每 10 秒掃一次孤兒 link（請求器不再下單的唯一可見成因）。
        if (gtocraftfix$tickCounter % 200 == 0) {
            try {
                gtocraftfix$linkAudit();
            } catch (Throwable ignored) {
                // 純診斷，永遠不能影響合成
            }
        }
        // [3.7.0] 帳本：每 tick 逐 CPU 對「Δ庫存＋Δ在途＋Δ交付 == 產出−消耗＋自補」這條不變量，
        // 違反即帳外流動（多吃／被領走／產出沒掛帳）；另含新單/離場快照、任務增減、一覽、凍結全景。
        // 取代 3.6.0 的每 2 tick 取樣式 pushAudit（取樣會把任務移除誤算成巨量 Δ）。
        com.gtocraftfix.diag.CraftDiag.tick(gtocraftfix$tickCounter, (CraftingService) (Object) this,
                grid, craftingCPUClusters);
        // [3.13.0] 卡死救援：每秒取樣一次進度指紋，長時間零進度且證明等不到貨 → 取消整張單
        if (gtocraftfix$STALL_CANCEL && gtocraftfix$tickCounter % 20 == 0) {
            gtocraftfix$stallWatch();
        }
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
                        // [3.2.2] 本單剩餘任務會產出的 key 集合：在途料若不在其中＝沒有任何機器會做它
                        var producible = gtocraftfix$taskOutputs(logic);
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
                            var pend = gtocraftfix$pendingAt(logic, wk);
                            if (pend.state() == CraftingHotfixSupport.PendingKnowledge.PRESENT) {
                                wb.append("/推給").append(pend.locations());
                            } else if (pend.state() == CraftingHotfixSupport.PendingKnowledge.UNKNOWN) {
                                wb.append("/樣板在途未知");
                            }
                            // [3.2.2] 幻影缺口指紋：沒有剩餘任務產它＝開單當下就缺、沒人會做（等到天荒地老）
                            if (producible != null && !producible.contains(wk)) {
                                wb.append("/無任務產它");
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
                                            long need1 = CraftingHotfixSupport.saturatingMultiply(
                                                    ps[0].amount(), in1.getMultiplier());
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
                    gtocraftfix$balanceReport(logic, out); // [3.4.0] 計畫內部收支平衡檢查
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
        // [2.4.0/3.13.2] 鉤子每 tick 進入；phantom-only 判斷較昂貴，feedTick 另降到 1Hz。
        var storage = grid.getStorageService().getInventory();
        int handled = 0;
        boolean feedRefundsClear = gtocraftfix$feedRefunds.isEmpty();
        for (var cluster : craftingCPUClusters) {
            if (handled >= 8) {
                break;
            }
            try {
                var logic = cluster.craftingLogic;
                if (!feedRefundsClear) {
                    feedRefundsClear = gtocraftfix$flushFeedRefunds(storage, cluster.getSrc());
                }
                var finalOut = logic.getFinalJobOutput();
                if (finalOut == null) {
                    continue;
                }
                Set<AEKey> waiting = new HashSet<>();
                logic.getAllWaitingFor(waiting);
                int handledBefore = handled;
                // [3.13.0/3.13.2] 幻影模式判斷要走完整 tasks 圖並反射 pendingRequests，成本較高；
                // 「補一筆永遠不會來的料」不必每 tick 做 → 降到 1Hz。
                // 無差別模式維持每 tick（那是 2.x 兩單搶料時的原始節奏）。
                boolean feedTick = gtocraftfix$FEED && feedRefundsClear
                        && (!gtocraftfix$FEED_PHANTOM_ONLY || gtocraftfix$tickCounter % 20 == 0);
                // [3.13.6] 逐 key 判定「這個 key 是否已有樣板押在供應器上」。
                // pendingKeys==null＝反射讀不到 → 本單全部 fail-closed 不餵（見該方法 javadoc）。
                java.util.Set<AEKey> pendingKeys = feedTick ? gtocraftfix$pendingKeys(logic) : null;
                // 本單剩餘任務會產出的 key；讀不到 job（反射失效）回 null → 幻影模式下一律不餵（保守）。
                // 惰性求值：真的有 key 要判時才算。
                java.util.Set<AEKey> producible = null;
                boolean producibleReady = false;
                for (var key : waiting) {
                    if (!feedTick || !feedRefundsClear) {
                        break; // 餵料停用（-Dgtodiag.sitterFeed=false）或本 tick 不是幻影掃描週期
                    }
                    if (handled >= 8) {
                        break;
                    }
                    boolean isFinal = key.equals(finalOut.what());
                    // [3.13.2] final 絕不 sitter feed：GTO insert 會用完整 got 扣 remainingAmount，
                    // link 即使只收部分／0 也可能提前 finish，差額回網路無法回滾帳目。
                    if (isFinal) {
                        continue;
                    }
                    var pendingSnapshot = pendingKeys == null
                            ? CraftingHotfixSupport.PendingKnowledge.UNKNOWN
                            : pendingKeys.contains(key)
                                    ? CraftingHotfixSupport.PendingKnowledge.PRESENT
                                    : CraftingHotfixSupport.PendingKnowledge.NONE;
                    if (gtocraftfix$FEED_PHANTOM_ONLY) {
                        if (!producibleReady) {
                            producible = gtocraftfix$taskOutputs(logic);
                            producibleReady = true;
                        }
                        if (!CraftingHotfixSupport.shouldFeedWaiting(isFinal,
                                producible != null,
                                producible != null && producible.contains(key),
                                pendingSnapshot)) {
                            // [3.13.6] 略過原因留一行（節流）：保母整天靜音卻查不出被哪道閘門擋，
                            // 正是 3.13.2→3.13.5 那次「餵料 0 筆」拖了一整天才被發現的原因。
                            if (gtocraftfix$feedSkipLog.incrementAndGet() <= 100) {
                                LOG.info("[craftfix] 保母略過 {}：{}", key,
                                        pendingSnapshot == CraftingHotfixSupport.PendingKnowledge.UNKNOWN
                                                ? "pendingRequests 反射讀不到（fail-closed）"
                                                : pendingSnapshot
                                                        == CraftingHotfixSupport.PendingKnowledge.PRESENT
                                                ? "已有樣板押在供應器上"
                                                : producible == null
                                                        ? "讀不到剩餘任務產出表（fail-closed）"
                                                        : "本單剩餘任務會產它");
                            }
                            continue;
                        }
                    } else if (pendingSnapshot != CraftingHotfixSupport.PendingKnowledge.NONE) {
                        continue; // 無差別模式仍不能在「已在途／反射未知」時搶先餵
                    }
                    long want = logic.getWaitingFor(key);
                    if (want <= 0) {
                        continue;
                    }
                    var cpuInventory = gtocraftfix$invOf(logic);
                    Long cpuBefore = gtocraftfix$cpuAmount(cpuInventory, key);
                    var waitingInventory = gtocraftfix$waitingInvOf(logic);
                    Long waitingBefore = gtocraftfix$cpuAmount(waitingInventory, key);
                    if (cpuBefore == null || waitingBefore == null || waitingBefore != want) {
                        gtocraftfix$feedError("保母無法取得一致的 CPU/waitingFor 入庫前快照，"
                                + "為避免例外後無法完整回滾而跳過："
                                + key + " x" + want, null);
                        continue;
                    }
                    // 只餵料：網路有貨 → 直餵 CPU（補認領缺口）。不代下巢狀單——那會生一堆小任務佔 CPU。
                    long got = storage.extract(key, want, Actionable.MODULATE, cluster.getSrc());
                    if (got > 0) {
                        long accepted;
                        try {
                            accepted = logic.insert(key, got, Actionable.MODULATE);
                            if (accepted < 0 || accepted > got) {
                                throw new IllegalStateException("logic.insert 回傳非法數量 " + accepted
                                        + "（抽出 " + got + "）");
                            }
                        } catch (Throwable insertError) {
                            gtocraftfix$restituteLogicInsertFailure(storage, cpuInventory, waitingInventory,
                                    key, cpuBefore, waitingBefore, got, cluster.getSrc(), "logic.insert 例外");
                            gtocraftfix$feedError("保母 logic.insert 例外，已回滾 waitingFor 並依 CPU 差額回補："
                                    + key + " x" + got, insertError);
                            feedRefundsClear = gtocraftfix$feedRefunds.isEmpty();
                            continue;
                        }
                        if (accepted < got) {
                            gtocraftfix$returnExtracted(storage, key, got - accepted, cluster.getSrc(),
                                    "CPU 僅接受 " + accepted + "/" + got);
                            feedRefundsClear = gtocraftfix$feedRefunds.isEmpty();
                        }
                        if (accepted <= 0) {
                            continue;
                        }
                        handled++;
                        int c = gtocraftfix$sitterLog.incrementAndGet();
                        if (c <= 200) {
                            LOG.info("[craftfix] 保母餵料 {} x{}（{}；out={}）", key, accepted,
                                    gtocraftfix$FEED_PHANTOM_ONLY ? "幻影缺口：無任務產它、無樣板在途" : "無差別餵",
                                    finalOut);
                        }
                    }
                }
                // 輸入補給：剩餘任務輸入不足一輪＝每 tick 取料失敗、無聲凍結 → 從網路補進 CPU 庫存。
                // [2.4.0] 閘門放寬：原本只在 waiting 空時跑，但「有在途＋另一任務缺料」（兩單搶料實錄：
                // 在途 qbit 晶圓擋住整個補給）同樣要補；本輪沒餵到料時一律跑。
                // [3.13.0] slim 仍預設停用（沒有 waitingFor 當額度、會吸乾單料）；要開用 -Dgtodiag.sitterTopUp=true
                if (feedRefundsClear && (!gtocraftfix$SLIM || gtocraftfix$SITTER_TOPUP)
                        && (waiting.isEmpty() || handled == handledBefore) && handled < 8) {
                    gtocraftfix$topUpInputs(logic, storage, cluster.getSrc());
                    feedRefundsClear = gtocraftfix$feedRefunds.isEmpty();
                }
                // [2.1.0] 並行死角解鎖：上游 executeCrafting 對 parallel==1 永久無聲跳過（見方法 javadoc）
                if (feedRefundsClear) {
                    gtocraftfix$unjamParallelOne(logic, storage, cluster.getSrc());
                    feedRefundsClear = gtocraftfix$feedRefunds.isEmpty();
                }
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
            if (gtocraftfix$isPaused(logic)) {
                return;
            }
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
                    long per = CraftingHotfixSupport.saturatingMultiply(
                            poss[0].amount(), input.getMultiplier());
                    if (per <= 0) {
                        continue;
                    }
                    long need = CraftingHotfixSupport.saturatingMultiply(per, rounds);
                    long have = inv.list.get(ik);
                    if (have >= need) {
                        continue;
                    }
                    long got = storage.extract(ik,
                            CraftingHotfixSupport.positiveDeficit(need, have), Actionable.MODULATE, src);
                    if (got > 0) {
                        try {
                            inv.insert(ik, got, Actionable.MODULATE);
                        } catch (Throwable t) {
                            gtocraftfix$restituteCpuInsertFailure(storage, inv, ik, have,
                                    got, src, "保母補輸入插入 CPU 例外");
                            throw t;
                        }
                        com.gtocraftfix.diag.CraftDiag.noteExternalInsert(logic, ik, got); // [3.7.0] 帳本登記
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

    /** [2.1.0/3.13.2] gtolib 並行樣板介面反射解析（一次）；解析失敗一律視為未知、不搬料。 */
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
        return c != null && c.isInstance(pat);
    }

    /** [2.1.0] 並行死角解鎖：上游 OptimizedCraftingCpuLogic.executeCrafting 的並行分支漏了
     *  parallel==1 的取料路徑——「並行樣板＋剩餘輪數>1＋庫存恰夠 1 輪」被每 tick 無聲跳過、永久卡死
     *  （中子反射板 x2 實錄；催化劑返還配方按淨需求備料必然踩中，已回報上游）。
     *  解法：命中指紋（min⌊庫存/每輪⌋==1 且 times>1）時把各輸入從網路補到 2 輪份，讓 GTO 自己的
     *  parallel>1 分支正常取料推送——只補料，不代推送、不碰帳目。網路無貨則無操作（下輪再試）。 */
    private void gtocraftfix$unjamParallelOne(appeng.crafting.execution.CraftingCpuLogic logic,
                                              appeng.api.storage.MEStorage storage, IActionSource src) {
        try {
            if (gtocraftfix$isPaused(logic)) {
                return; // 暫停或暫停狀態讀不到：救援層不得搬料
            }
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
                            units = CraftingHotfixSupport.saturatingAdd(units,
                                    Math.max(0, inv.list.get(ps.what())) / ps.amount());
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
                    long per = CraftingHotfixSupport.saturatingMultiply(
                            poss[0].amount(), input.getMultiplier());
                    if (per <= 0) {
                        continue;
                    }
                    long have = inv.list.get(ik);
                    long target = CraftingHotfixSupport.saturatingMultiply(2, per);
                    if (have >= target) {
                        continue;
                    }
                    long need = CraftingHotfixSupport.positiveDeficit(target, have);
                    long got = storage.extract(ik, need, Actionable.MODULATE, src);
                    if (got > 0) {
                        try {
                            inv.insert(ik, got, Actionable.MODULATE);
                        } catch (Throwable t) {
                            // 例外補償以庫存差額為準，避免「先入庫、後拋例外」時全額回補造成複製。
                            gtocraftfix$restituteCpuInsertFailure(storage, inv, ik, have,
                                    got, src, "並行死角解鎖插入 CPU 例外");
                            throw t;
                        }
                        // [3.7.0] 登記「本 mod 自己塞的料」，帳本才不會把它算成帳外流入
                        com.gtocraftfix.diag.CraftDiag.noteExternalInsert(logic, ik, got);
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
     * [3.9.0] 內部配平缺口（修補第五維）：對整張計畫每個 key 算
     * {@code demand = Σ(每輪輸入 × runs)}、{@code supply = usedItems ＋ emittedItems ＋ Σ(每輪產出 × runs)}，
     * 負差即缺口。前四維只檢查「計畫對網路的引用」與「可執行性」，**沒有人檢查修補後的計畫自己配不配得平**
     * ——實錄：LUV 通用電路修補完仍差 lubricant 132／copper_block 4／platinum_single_wire 6／naquadah_boule 2，
     * 做到剩最後 34 個成品時全鏈餓死，而網路各有 115209／6／13／3。
     * 替代輸入取「計畫供給最多」的變體（與 {@code planBirthBalance} 同一模型），避免誤報。
     * 回傳 {@code null} 表示掃描失敗；絕不把 catch 前累積的部分列表交給套用端。
     */
    private static java.util.List<Object[]> gtocraftfix$internalBalanceDeficits(CraftingPlan plan) {
        var out = new java.util.ArrayList<Object[]>();
        try {
            var pt = plan.patternTimes();
            if (pt.isEmpty()) {
                return out;
            }
            var supply = new HashMap<AEKey, Long>();
            for (var e : plan.usedItems()) {
                supply.merge(e.getKey(), e.getLongValue(), CraftingHotfixSupport::saturatingAdd);
            }
            for (var e : plan.emittedItems()) {
                supply.merge(e.getKey(), e.getLongValue(), CraftingHotfixSupport::saturatingAdd);
            }
            for (var pe : pt.entrySet()) {
                long runs = pe.getValue() == null ? 0 : pe.getValue();
                if (runs <= 0) {
                    continue;
                }
                for (var o : pe.getKey().getOutputs()) {
                    if (o.amount() > 0) {
                        supply.merge(o.what(), CraftingHotfixSupport.saturatingMultiply(o.amount(), runs),
                                CraftingHotfixSupport::saturatingAdd);
                    }
                }
            }
            // [3.9.1] 供給扣除法：逐槽把「要跑幾輪」分配到吃得下的變體，供給用掉就扣掉，全部變體都不夠
            // 才算缺口（記在主變體上）。3.9.0 用「供給最多的變體」歸戶會自我增強——補了那個變體
            // 反而讓它繼續吸走全部需求，缺口愈補愈大（glowstone 147456 → 3833856 實錄）。
            var left = new HashMap<>(supply);
            var deficit = new HashMap<AEKey, Long>();
            for (var pe : pt.entrySet()) {
                long runs = pe.getValue() == null ? 0 : pe.getValue();
                if (runs <= 0) {
                    continue;
                }
                for (var in : pe.getKey().getInputs()) {
                    var ps = in.getPossibleInputs();
                    if (ps.length == 0) {
                        continue;
                    }
                    long roundsLeft = runs;
                    for (var v : ps) {
                        long per = CraftingHotfixSupport.saturatingMultiply(
                                v.amount(), in.getMultiplier());
                        if (per <= 0) {
                            continue;
                        }
                        long can = Math.max(0, left.getOrDefault(v.what(), 0L)) / per;
                        long take = Math.min(roundsLeft, can);
                        if (take > 0) {
                            long consumed = CraftingHotfixSupport.saturatingMultiply(take, per);
                            left.put(v.what(), Math.max(0, CraftingHotfixSupport.saturatingSubtract(
                                    left.getOrDefault(v.what(), 0L), consumed)));
                            roundsLeft = CraftingHotfixSupport.positiveDeficit(roundsLeft, take);
                        }
                        if (roundsLeft <= 0) {
                            break;
                        }
                    }
                    if (roundsLeft > 0) {
                        long per0 = CraftingHotfixSupport.saturatingMultiply(
                                ps[0].amount(), in.getMultiplier());
                        if (per0 > 0) {
                            deficit.merge(ps[0].what(),
                                    CraftingHotfixSupport.saturatingMultiply(per0, roundsLeft),
                                    CraftingHotfixSupport::saturatingAdd);
                        }
                    }
                }
            }
            for (var e : deficit.entrySet()) {
                if (e.getValue() > 0) {
                    out.add(new Object[] { e.getKey(), e.getValue(), Boolean.TRUE });
                }
            }
        } catch (Throwable ignored) {
            return null;
        }
        return out;
    }

    /**
     * 依現行「主替代品」近似，先把同一 key 的多個輸入槽聚合成每輪需求。null 表示乘法／聚合溢位，
     * 呼叫端必須把整次 bootstrap 視為 UNKNOWN；飽和 MAX 不是可拿來證明可執行的精確需求。
     */
    private static Map<AEKey, Long> gtocraftfix$primaryInputRequirements(IPatternDetails pattern) {
        var requirements = new HashMap<AEKey, Long>();
        for (var input : pattern.getInputs()) {
            var possible = input.getPossibleInputs();
            if (possible.length == 0) {
                continue;
            }
            long amount = possible[0].amount();
            long multiplier = input.getMultiplier();
            if (amount <= 0 || multiplier <= 0) {
                continue;
            }
            Long per = CraftingHotfixSupport.checkedNonNegativeMultiply(amount, multiplier);
            if (per == null) {
                return null;
            }
            var key = possible[0].what();
            Long total = CraftingHotfixSupport.checkedNonNegativeAdd(
                    requirements.getOrDefault(key, 0L), per);
            if (total == null) {
                return null;
            }
            requirements.put(key, total);
        }
        return requirements;
    }

    /**
     * 可執行性模擬：以 usedItems 為起始庫存、逐輪執行可滿足輸入的樣板（主替代品近似）。
     * 回傳「卡死樣板中、庫存為 0 的輸入」各一輪 run 的量＝循環自舉缺口；可全部跑完則回空。
     * <p>[3.13.2] 除 pass 上限外也接收整次修補共用的 startedAt/budget；逾時回 null，呼叫端中止並
     * 整組還原，不能在進入這個昂貴步驟時偷偷重設一份新預算。
     */
    private static java.util.List<Object[]> gtocraftfix$findBootstrapDeficits(
            CraftingPlan plan, long startedAt, long budgetNanos) {
        if (CraftingHotfixSupport.budgetExpired(startedAt, budgetNanos, System.nanoTime())) {
            return null;
        }
        var inv = new HashMap<AEKey, Long>();
        for (var e : plan.usedItems()) {
            Long total = CraftingHotfixSupport.checkedNonNegativeAdd(
                    inv.getOrDefault(e.getKey(), 0L), e.getLongValue());
            if (total == null) {
                return null;
            }
            inv.put(e.getKey(), total);
        }
        var remaining = new HashMap<IPatternDetails, Long>();
        plan.patternTimes().forEach((p, r) -> {
            if (r != null && r > 0) {
                remaining.put(p, r);
            }
        });
        boolean progress = true;
        int pass = 0; // [3.11.1／B9]
        while (progress && !remaining.isEmpty()) {
            if (CraftingHotfixSupport.budgetExpired(startedAt, budgetNanos, System.nanoTime())) {
                return null;
            }
            if (++pass > gtocraftfix$BOOTSTRAP_MAX_PASS) {
                // [3.11.2／M7] 回空清單＝把「無法判定」當成「沒有缺口」，這是刻意的：改成中止會讓
                // 整組還原（＝退回幻影計畫、必凍）更糟。但既然是會影響正確性的靜默跳過，log 就不能
                // 「全域只印一次」——第一個踩到的成品會把之後所有成品都消音。改成**每個成品 key
                // 印一次**（上限 128 筆即 clear，同 noPatternNotified 的作法），並在訊息裡明說跳過。
                String capKey = String.valueOf(plan.finalOutput() == null ? "?" : plan.finalOutput().what());
                if (gtocraftfix$bootstrapCapLogged.add(capKey)) {
                    if (gtocraftfix$bootstrapCapLogged.size() > 128) {
                        gtocraftfix$bootstrapCapLogged.clear();
                    }
                    LOG.warn("[craftfix] 循環自舉模擬超過 {} 趟上限（剩 {} 種樣板未跑完）→ 視為無法判定、"
                            + "**本次跳過自舉補齊**（計畫照原樣送出，若真有自舉缺口仍可能卡住）"
                            + " out={}（-Dgtodiag.bootstrapMaxPass 可調）",
                            gtocraftfix$BOOTSTRAP_MAX_PASS, remaining.size(), plan.finalOutput());
                }
                return new java.util.ArrayList<>();
            }
            progress = false;
            int scanned = 0;
            for (var it = remaining.entrySet().iterator(); it.hasNext();) {
                if ((++scanned & 63) == 0
                        && CraftingHotfixSupport.budgetExpired(startedAt, budgetNanos, System.nanoTime())) {
                    return null;
                }
                var en = it.next();
                var p = en.getKey();
                long runs = en.getValue();
                long can = runs;
                var requirements = gtocraftfix$primaryInputRequirements(p);
                if (requirements == null) {
                    return null;
                }
                for (var requirement : requirements.entrySet()) {
                    can = Math.min(can, Math.max(0, inv.getOrDefault(requirement.getKey(), 0L))
                            / requirement.getValue());
                    if (can == 0) {
                        break;
                    }
                }
                if (can > 0) {
                    for (var requirement : requirements.entrySet()) {
                        Long consumed = CraftingHotfixSupport.checkedNonNegativeMultiply(
                                requirement.getValue(), can);
                        if (consumed == null) {
                            return null;
                        }
                        long have = inv.getOrDefault(requirement.getKey(), 0L);
                        if (consumed > have) {
                            return null;
                        }
                        inv.put(requirement.getKey(), have - consumed);
                    }
                    for (var out : p.getOutputs()) {
                        if (out.amount() < 0) {
                            return null;
                        }
                        Long produced = CraftingHotfixSupport.checkedNonNegativeMultiply(out.amount(), can);
                        if (produced == null) {
                            return null;
                        }
                        Long total = CraftingHotfixSupport.checkedNonNegativeAdd(
                                inv.getOrDefault(out.what(), 0L), produced);
                        if (total == null) {
                            return null;
                        }
                        inv.put(out.what(), total);
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
        var bootstrapNeed = new HashMap<AEKey, Long>();
        int finalScan = 0;
        for (var en : remaining.entrySet()) {
            if ((++finalScan & 63) == 0
                    && CraftingHotfixSupport.budgetExpired(startedAt, budgetNanos, System.nanoTime())) {
                return null;
            }
            var requirements = gtocraftfix$primaryInputRequirements(en.getKey());
            if (requirements == null) {
                return null;
            }
            for (var requirement : requirements.entrySet()) {
                long missingOneRun = CraftingHotfixSupport.positiveDeficit(
                        requirement.getValue(), inv.getOrDefault(requirement.getKey(), 0L));
                if (missingOneRun > 0) {
                    // 同一 key 卡住多個樣板時取「至少讓其中一輪可跑」所需的最大缺口，避免 map 迭代
                    // 順序讓較小需求先佔位，卻仍無法啟動較大的一輪。
                    bootstrapNeed.merge(requirement.getKey(), missingOneRun, Math::max);
                }
            }
        }
        for (var need : bootstrapNeed.entrySet()) {
            // 補一輪 run 的量當自舉；[3.11.1／B1] 第 3 欄 soft（近似模擬猜的量，語意同舊格式的
            // 「長度 2＝非 hard」）、第 4 欄 SRC_BOOTSTRAP（從未加進 usedItems → 不可沖銷）
            res.add(new Object[] { need.getKey(), need.getValue(), Boolean.FALSE, gtocraftfix$SRC_BOOTSTRAP });
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
