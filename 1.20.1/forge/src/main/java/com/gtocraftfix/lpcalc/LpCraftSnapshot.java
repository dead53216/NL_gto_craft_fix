package com.gtocraftfix.lpcalc;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.world.level.Level;

import appeng.api.config.Actionable;
import appeng.api.config.FuzzyMode;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.core.AEConfig;

/**
 * 不可變快照（§5）。整個 lpcalc 唯一允許讀 grid/level 的地方；只可在伺服器執行緒建。
 * capture 內任何例外不外拋：回傳的 fallbackReason() 非 null（呼叫端當場改建樹狀版，鐵則 5）。
 */
public final class LpCraftSnapshot {

    /** [鐵則1] IParallelPatternDetails 反射只解析一次；解析失敗 → 全樣板視為並行（保守） */
    private static final Class<?> PARALLEL_IFACE;
    private static final boolean PARALLEL_UNKNOWN;

    static {
        Class<?> c = null;
        try {
            c = Class.forName("com.gtolib.api.ae2.pattern.IParallelPatternDetails");
        } catch (Throwable ignored) {
            // 找不到（版本改名等）→ PARALLEL_UNKNOWN = true
        }
        PARALLEL_IFACE = c;
        PARALLEL_UNKNOWN = c == null;
    }

    private final FallbackReason fallbackReason;
    private final AEKey what;
    private final long amount;
    private final CalculationStrategy strategy;

    // ---- 套件內可見欄位（背景緒唯讀）----
    /** 求解用工作拷貝的母本（傳播時再 copy）；頂層 what 精確 key 已歸 0（ignore 語意，§5.1） */
    final KeyCounter avail;
    /** 原始快照凍結值（含 ignore 歸零），audit(b) 的上界 [鐵則15] */
    final KeyCounter availOriginal;
    /** getCraftingFor 原始實例、原始順序；含空清單項（鐵則 2 檢查用） */
    final LinkedHashMap<AEKey, List<IPatternDetails>> patternsByKey;
    final LinkedHashMap<IPatternDetails, LpPattern> compiled;
    /** canEmitFor 快照（emitable 不展開） */
    final Set<AEKey> emitable;
    /** [鐵則10] 候選樣板 ≥2 的 key */
    final Set<AEKey> multiCandidate;
    /** [鐵則10] 快照庫存 findFuzzy 有主 key 以外變體存量的 key */
    final Set<AEKey> fuzzyStocked;
    /** [鐵則10] 任一樣板任一槽 possibleInputs.length > 1 */
    final boolean anyMultiInput;
    /** 閉包 key 數（bytes 的 nodeCount 近似用，§6.9） */
    final int closureKeyCount;

    private LpCraftSnapshot(FallbackReason reason, AEKey what, long amount, CalculationStrategy strategy,
                            KeyCounter avail, KeyCounter availOriginal,
                            LinkedHashMap<AEKey, List<IPatternDetails>> patternsByKey,
                            LinkedHashMap<IPatternDetails, LpPattern> compiled,
                            Set<AEKey> emitable, Set<AEKey> multiCandidate, Set<AEKey> fuzzyStocked,
                            boolean anyMultiInput, int closureKeyCount) {
        this.fallbackReason = reason;
        this.what = what;
        this.amount = amount;
        this.strategy = strategy;
        this.avail = avail;
        this.availOriginal = availOriginal;
        this.patternsByKey = patternsByKey;
        this.compiled = compiled;
        this.emitable = emitable;
        this.multiCandidate = multiCandidate;
        this.fuzzyStocked = fuzzyStocked;
        this.anyMultiInput = anyMultiInput;
        this.closureKeyCount = closureKeyCount;
    }

    /** null = 可進背景求解 */
    public FallbackReason fallbackReason() {
        return fallbackReason;
    }

    public AEKey what() {
        return what;
    }

    public long amount() {
        return amount;
    }

    public CalculationStrategy strategy() {
        return strategy;
    }

    private static LpCraftSnapshot failed(FallbackReason r, AEKey what, long amount, CalculationStrategy strategy) {
        return new LpCraftSnapshot(r, what, amount, strategy, null, null, null, null,
                null, null, null, false, 0);
    }

    /** 只可在伺服器執行緒呼叫。任何內部例外不外拋：回傳的 snapshot.fallbackReason() 非 null。 */
    public static LpCraftSnapshot capture(Level level, IGrid grid, ICraftingService svc,
            ICraftingSimulationRequester simRequester, AEKey what, long amount,
            CalculationStrategy strategy) {
        long t0 = System.nanoTime(); // [鐵則14]
        try {
            return captureInner(level, grid, svc, simRequester, what, amount, strategy, t0);
        } catch (LpFallbackException e) {
            return failed(e.reason(), what, amount, strategy);
        } catch (Throwable t) {
            return failed(FallbackReason.SNAPSHOT_ERROR, what, amount, strategy);
        }
    }

    private static LpCraftSnapshot captureInner(Level level, IGrid grid, ICraftingService svc,
            ICraftingSimulationRequester simRequester, AEKey what, long amount,
            CalculationStrategy strategy, long t0) {
        // ---- §5.1 庫存快照（逐條複刻 NetworkCraftingSimulationState 語意）----
        // avail 與 availOriginal 同迴圈建（省一次全量走訪）；[鐵則14] 預算檢查涵蓋本迴圈
        //（否則 10 萬項＋craftingSimulatedExtraction 的網路在任何檢查點之前就吃掉 10-100ms，
        // 此時樹狀版尚未建、及早回退無雙份成本）
        var avail = new KeyCounter();
        var availOriginal = new KeyCounter();
        var src = simRequester.getActionSource();
        if (src != null) { // getActionSource()==null → 庫存視為全空
            var storage = grid.getStorageService();
            boolean simExtract = AEConfig.instance().isCraftingSimulatedExtraction();
            int scanned = 0;
            for (var stack : storage.getCachedInventory()) {
                if ((++scanned & 255) == 0 && System.nanoTime() - t0 > LpConfig.snapshotBudgetNanos()) {
                    throw new LpFallbackException(FallbackReason.CLOSURE_CAP, "inv=" + scanned);
                }
                long networkAmount = simExtract
                        ? storage.getInventory().extract(stack.getKey(), stack.getLongValue(),
                                Actionable.SIMULATE, src)
                        : stack.getLongValue();
                if (networkAmount > 0) {
                    avail.add(stack.getKey(), networkAmount);
                    availOriginal.add(stack.getKey(), networkAmount);
                }
            }
        }
        // ignore 語意：頂層請求的精確 key 歸 0（「要 N 個 X」＝真的做 N 個新的；模糊變體不歸零）
        avail.set(what, 0);
        availOriginal.set(what, 0);

        // ---- §5.2 需求閉包 BFS ----
        var patternsByKey = new LinkedHashMap<AEKey, List<IPatternDetails>>();
        var compiled = new LinkedHashMap<IPatternDetails, LpPattern>();
        var emitable = new LinkedHashSet<AEKey>();
        var multiCandidate = new LinkedHashSet<AEKey>();
        var fuzzyStocked = new LinkedHashSet<AEKey>();
        boolean anyMultiInput = false;

        var visited = new LinkedHashSet<AEKey>();
        var queue = new ArrayDeque<AEKey>();
        queue.add(what);
        int pops = 0;
        while (!queue.isEmpty()) {
            AEKey k = queue.poll();
            if (!visited.add(k)) {
                continue;
            }
            if ((++pops & 63) == 0) { // [鐵則14] 每 64 個 pop 檢查一次預算與規模
                if (System.nanoTime() - t0 > LpConfig.snapshotBudgetNanos()
                        || visited.size() > LpConfig.maxKeys()
                        || compiled.size() > LpConfig.maxPatterns()) {
                    throw new LpFallbackException(FallbackReason.CLOSURE_CAP, "keys=" + visited.size());
                }
            }
            if (svc.canEmitFor(k)) {
                emitable.add(k); // emitable 不展開（Node:196-199 語意）
                continue;
            }
            var pats = new ArrayList<IPatternDetails>(svc.getCraftingFor(k)); // 原始實例、原始順序
            patternsByKey.put(k, pats);
            if (pats.size() >= 2) {
                multiCandidate.add(k);
            }
            // [鐵則10] 模糊變體存量偵測（只看主 key 以外、量 > 0）
            for (var fz : avail.findFuzzy(k, FuzzyMode.IGNORE_ALL)) {
                if (fz.getLongValue() > 0 && !fz.getKey().equals(k)) {
                    fuzzyStocked.add(k);
                    break;
                }
            }
            for (var details : pats) { // 全部候選都編譯（鐵則 10 判定要完整名單；傳播只用首位）
                var lp = compiled.get(details);
                if (lp == null) {
                    lp = compile(details, svc, level);
                    compiled.put(details, lp);
                    // [鐵則14] 時間檢查同時掛在編譯數上：<64 key 的閉包永遠到不了 pop 取樣點，
                    // 但每 key 數百張候選的 compile（getFuzzyCraftable 逐候選比對）一樣燒伺服器緒
                    if (compiled.size() > LpConfig.maxPatterns()
                            || ((compiled.size() & 31) == 0
                                    && System.nanoTime() - t0 > LpConfig.snapshotBudgetNanos())) {
                        throw new LpFallbackException(FallbackReason.CLOSURE_CAP,
                                "patterns=" + compiled.size());
                    }
                }
                if (lp.multiInput) {
                    anyMultiInput = true;
                }
                for (var in : lp.inKey) {
                    if (!visited.contains(in)) {
                        queue.add(in);
                    }
                }
            }
        }

        if (visited.size() > LpConfig.maxKeys()) {
            throw new LpFallbackException(FallbackReason.CLOSURE_CAP, "keys=" + visited.size());
        }

        return new LpCraftSnapshot(null, what, amount, strategy, avail, availOriginal,
                patternsByKey, compiled, emitable, multiCandidate, fuzzyStocked,
                anyMultiInput, visited.size());
    }

    /** §5.3 樣板編譯與輸入槽唯一重導向（findCraftedStack 等價）。 */
    private static LpPattern compile(IPatternDetails details, ICraftingService svc, Level level) {
        // [鐵則6] hashCode 暖機：防背景緒首次組 map 觸發樣板惰性 NBT 初始化的執行緒競爭
        details.hashCode();

        var inputs = details.getInputs();
        var inKeys = new ArrayList<AEKey>(inputs.length);
        var inAmts = new ArrayList<Long>(inputs.length);
        boolean multiInput = false;
        boolean ambiguousRedirect = false;

        for (var input : inputs) {
            var possible = input.getPossibleInputs();
            if (possible.length == 0) {
                continue; // 空槽（與 mixin repairPlan/topUpInputs 同語意：略過）
            }
            if (possible.length > 1) {
                multiInput = true;
            }
            var primary = possible[0];
            long per = LpMath.mulX(primary.amount(), input.getMultiplier());
            if (per <= 0) {
                throw new LpFallbackException(FallbackReason.BAD_PATTERN, "inAmt<=0");
            }
            // 容器物品：輸出依實際消耗模板而變，不可線性化（Proc:95-97 limitQty 條件）
            if (input.getRemainingKey(primary.what()) != null) {
                throw new LpFallbackException(FallbackReason.CONTAINER_ITEM, String.valueOf(primary.what()));
            }
            AEKey resolved = primary.what();
            if (svc.getCraftingFor(resolved).isEmpty()) {
                // 重導向（Node:78-106 findCraftedStack 等價）：數量語意相同的替代品中找可合成模糊變體
                long acceptableAmount = primary.amount();
                var candidates = new LinkedHashSet<AEKey>();
                for (var possibleInput : possible) {
                    if (possibleInput.amount() != acceptableAmount) {
                        continue;
                    }
                    var fuzzy = svc.getFuzzyCraftable(possibleInput.what(),
                            cand -> input.isValid(cand, level));
                    if (fuzzy != null) {
                        candidates.add(fuzzy);
                    }
                }
                if (candidates.size() == 1) {
                    resolved = candidates.iterator().next(); // 恰一候選 → 路由與重放都用代入後 key
                } else if (candidates.size() > 1) {
                    ambiguousRedirect = true; // 求解期該樣板 runs>0 才回退（REDIRECT_AMBIGUOUS）
                }
            }
            inKeys.add(resolved);
            inAmts.add(per);
        }

        var outs = details.getOutputs();
        var primaryOut = details.getPrimaryOutput();
        if (primaryOut == null || primaryOut.amount() <= 0 || outs.length == 0) {
            throw new LpFallbackException(FallbackReason.BAD_PATTERN, "primaryOut<=0");
        }
        var outKeys = new AEKey[outs.length];
        var outAmts = new long[outs.length];
        for (int i = 0; i < outs.length; i++) {
            outKeys[i] = outs[i].what();
            outAmts[i] = outs[i].amount();
            if (outAmts[i] <= 0) {
                throw new LpFallbackException(FallbackReason.BAD_PATTERN, "outAmt<=0");
            }
        }

        boolean parallelOrUnknown = PARALLEL_UNKNOWN || PARALLEL_IFACE.isInstance(details); // [鐵則1]

        var inKeyArr = inKeys.toArray(new AEKey[0]);
        var inAmtArr = new long[inAmts.size()];
        for (int i = 0; i < inAmtArr.length; i++) {
            inAmtArr[i] = inAmts.get(i);
        }
        return new LpPattern(details, inKeyArr, inAmtArr, outKeys, outAmts,
                primaryOut.what(), primaryOut.amount(), parallelOrUnknown, multiInput, ambiguousRedirect);
    }

    static KeyCounter copyOf(KeyCounter src) {
        var out = new KeyCounter();
        for (var e : src) {
            if (e.getLongValue() != 0) {
                out.add(e.getKey(), e.getLongValue());
            }
        }
        return out;
    }
}
