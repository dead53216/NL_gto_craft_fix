# DESIGN-lpcalc.md — 結構化需求傳播算料器（package com.gtocraftfix.lpcalc）未啟用草案

狀態：**完整版設計草案，尚未啟用、尚未驗收**。目前 `slim` 以
`CraftingServiceSyncMixin.gtocraftfix$SLIM=true` 在外層硬停用 lpcalc；即使
`-Dgtodiag.lpcalc.enabled=true` 也不會進入本路徑。本文件描述重新啟用前要達成的目標，
不是目前 JAR 的行為承諾；與 [README.md](README.md) 的 slim 現況衝突時，以 README 與程式硬閘門為準。

文內「鐵則 N」指驗收標準第 N 條（見 §2）。所有項目必須有自動測試或遊戲內證據後才能
由「待驗收」改成「通過」，不能只因原始碼已存在便視為完成。

- 環境：MC 1.20.1 Forge、Java 21、AE2 API（AEKey/KeyCounter/GenericStack/IPatternDetails/Object2LongMap）。
- 程式註解繁體中文、UTF-8 無 BOM，只在「程式本身看不出的約束」處下註解。
- 執行層是 gtolib 閉源 `OptimizedCraftingCpuLogic`，完全不可改；一切設計以
  「執行可行」壓倒「材料最優」。

路徑縮寫（舊版 `Mixin:行號` 等數字只保留為歷史定位，不是目前程式的可靠錨點）：
- Mixin = `1.20.1/forge/src/main/java/com/gtocraftfix/mixin/CraftingServiceSyncMixin.java`
- Node/Proc/Calc/Sim/Helper/NetSim = `1.20.1/forge/src/main/java/com/gtocraftfix/calc/` 下的
  CraftingTreeNode / CraftingTreeProcess / CraftingCalculation / CraftingSimulationState /
  CraftingCpuHelper / NetworkCraftingSimulationState
- Cpu = GTOCore repo `OptimizedCraftingCpuLogic.java`、Job = `ExecutingCraftingJob.java`

---

## 1. 目標與非目標

**目標**
1. 未來重新啟用後，讓機器來源的算料請求優先改走 lpcalc：成本只與樣板圖大小成正比，
   與請求數量無關（10000 鈦錠 ≈ 10 鈦錠）。
2. 修復循環配方（Kroll 鈦、NaOH、蒽醌 H2O2 等）：樹狀版被 `notRecursive`（Node:135-153）
   剪光路徑而報缺料，lpcalc 以 SCC 正規求解。
3. 任何不支援 / 不確定的情形回退 `com.gtocraftfix.calc` 樹狀版。

**非目標**
- 不追求材料最優（超產一律接受；短產 / 死鎖零容忍）。
- 不支援：容器物品、多樣板分攤、模糊模板載重合成、多輸入替代品載重——全部回退。
- 不改任何 `com.gtocraftfix.calc` 檔案、不改 Mixin 的不可動件（§10.3）。

---

## 2. 十五條鐵則（驗收標準）與章節對照

| # | 鐵則摘要 | 落地章節 |
|---|---|---|
| 1 | `IParallelPatternDetails` 反射失敗 → 全樣板視為並行（w≥2）；audit 對不確定樣板套 parallel==1 死角規則 | §5.4、§6.4、§6.8 |
| 2 | missing 不變量：每個 missing key 必須 getCraftingFor 空且非計畫內任何樣板主/副產出；違反 → LOOP_NO_BOOTSTRAP 回退 | §6.7 |
| 3 | 禁止 SCC 淨輸出對同計畫其他樣板需求抵帳；SCC 產出只交付 finalOutput 或超產回網 | §6.2、§6.3 |
| 4 | LpAuditor 至少雙順序波次重放（宣告序＋對抗序）；起始庫存只灌 usedItems | §6.8 |
| 5 | 不預建/雙重快照；快照期回退當場建樹狀版；求解期回退走 LpFallbackQueue（+1 tick）；禁止背景緒建 CraftingCalculation / 讀 grid | §3、§9 |
| 6 | 快照期在伺服器執行緒對每張 IPatternDetails 呼叫一次 hashCode() 暖機 | §5.5 |
| 7 | 多趟傳播（≤4）帳本語意：後趟只處理增量、surplus 不重複計入、最終全量 audit；啟動料回灌鏈落入另一 SCC → 回退 | §6.2、§6.4 |
| 8 | shadowVerifyOnMissing 系統屬性（預設開）：LP missing 非空 → 影子跑樹狀版，成功則採用並記分歧 | §9.3 |
| 9 | CRAFT_LESS 每個候選 R 過精確整數傳播＋完整 LpAuditor 才可回傳；R_max==0 的 sim 計畫受鐵則 2 約束 | §7 |
| 10 | missing 溯源 v1 粗化：missing 非空且閉包內任一 key ≥2 候選樣板或模糊變體存量 → 一律回退 | §6.7 |
| 11 | LpStats：FallbackReason 逐項計數＋fastPathHit 率＋節流 log（[craftfix] 風格） | §11 |
| 12 | 完整版內層保留 `-Dgtodiag.lpcalc.enabled=false` kill switch；目前 slim 另有不可由屬性繞過的外層硬停用 | §9.1、§10 |
| 13 | 不可動件清單；patternTimes key 用 getCraftingFor() 原始實例、容器用 new Object2LongOpenHashMap | §6.9、§10.3 |
| 14 | 快照期 nanoTime 硬預算（~1ms）與規模上限，超限 → CLOSURE_CAP 回退 | §5.6 |
| 15 | usedItems 語意 =「相對網路起始庫存的最大下探水位」（峰值需求）；LP 產出保守可偏大、不可偏小 | §6.6 |

---

## 3. 總體資料流與執行緒模型

```
伺服器執行緒（beginCraftingCalculation HEAD，Mixin machineSrc0 分支）
  ├─ kill switch 關 → new calc.CraftingCalculation → CALC_POOL.submit(calc::run)（＝現狀）
  ├─ LpCraftSnapshot.capture(...)          ← 唯一 grid/level 讀取點；nanoTime 預算 ~1ms
  │    ├─ 快照期偵測到不支援（fallbackReason != null）
  │    │    → 當場 new calc.CraftingCalculation → CALC_POOL.submit(calc::run)（單份快照，鐵則 5）
  │    └─ 成功 → future = new CompletableFuture<>()
  │              CALC_POOL.submit(new LpCalcTask(snap, req, future)) → 回傳 future
  ↓
背景執行緒（CALC_POOL；不註冊 CalcTicker；零 grid/level 存取）
  ├─ LpSolver：圖 → SCC → 傳播 → 高斯 → 整數化 → audit
  ├─ 成功 → future.complete(plan)；LpStats.hit()
  ├─ 誠實 sim（missing 非空、全部不變量過）且 shadowVerifyOnMissing 開
  │    → LpFallbackQueue.enqueueShadow(req, lpSimPlan, future)
  └─ 任何回退觸發 → LpFallbackQueue.enqueue(req, reason, future)
  ↓
伺服器執行緒（onServerEndTick，在既有 CalcTicker.tick() 後排空 LP 回退佇列）
  └─ LpFallbackQueue.drainOnServerTick()
       → 在伺服器執行緒 new calc.CraftingCalculation（ctor 讀 grid 安全）
       → CALC_POOL.submit(() -> future.complete(calc.run()))    （+1 tick 可接受）
```

執行緒鐵律：
- `LpCraftSnapshot.capture`、`LpFallbackQueue.drainOnServerTick`、樹狀版建構：**只在伺服器執行緒**。
- `LpSolver` 全程只讀 snapshot 內資料；`getCraftingFor / canEmitFor / getFuzzyCraftable /
  IInput.isValid(…, level)` 只允許出現在 capture 內（Sim:73-91、Node:96 證據：isValid 吃 Level）。
- LP 路徑不註冊 CalcTicker（快照後零 grid 存取，不需要握手）；回退路徑照舊註冊，
  由 Mixin 既有的 `CalcTicker.tick()` 發預算——重新接線時必須保留這項語意。

---

## 4. 類別清單與 public API

全部放 `1.20.1/forge/src/main/java/com/gtocraftfix/lpcalc/`。相依：AE2 API、fastutil、
`com.gtocraftfix.calc`（僅回退建構）、`appeng.crafting.CraftingPlan`。

### 4.1 LpConfig（系統屬性集中讀取，final class，全 static）
```java
public final class LpConfig {
    public static boolean enabled();                // gtodiag.lpcalc.enabled，預設 true（鐵則 12）
    public static boolean shadowVerifyOnMissing();  // gtodiag.lpcalc.shadowVerifyOnMissing，預設 true（鐵則 8）
    public static long snapshotBudgetNanos();       // gtodiag.lpcalc.snapshotBudgetNanos，預設 1_000_000（鐵則 14）
    public static long solveBudgetNanos();          // gtodiag.lpcalc.solveBudgetNanos，預設 100_000_000
    public static int maxKeys();                    // gtodiag.lpcalc.maxKeys，預設 4096
    public static int maxPatterns();                // gtodiag.lpcalc.maxPatterns，預設 16384
    public static int maxPasses();                  // 固定 4（鐵則 7）
    public static int maxWaves();                   // 固定 64（audit 波次上限）
    public static int maxBootstrapDoublings();      // 固定 3
}
```
屬性在類初始化時讀一次快取（Boolean.parseBoolean(System.getProperty(...))）。

### 4.2 FallbackReason（enum）＋ LpFallbackException
```java
public enum FallbackReason { /* 完整清單見 §8 */ }

public final class LpFallbackException extends RuntimeException {
    public LpFallbackException(FallbackReason reason, String detail);
    public FallbackReason reason();
}
```

### 4.3 Rational（有理數，不可變）
```java
final class Rational {
    static final Rational ZERO, ONE;
    static Rational of(long num, long den);      // gcd 約分；den>0 正規化
    Rational add(Rational o); Rational sub(Rational o);
    Rational mul(Rational o); Rational div(Rational o);
    boolean isZero(); boolean isNegative();
    long ceilToLong();                           // 離開有理數域的唯一出口
}
```
long 運算全用 `Math.addExact/multiplyExact`；溢位升 BigInteger；BigInteger 任一
分子/分母超過 128 bit → `LpFallbackException(OVERFLOW)`。有理數只允許存在於
兩處：SCC 高斯消去（§6.3）與 CRAFT_LESS 速率（§7），離開前必整數化。

### 4.4 LpCraftSnapshot（不可變快照；只在伺服器執行緒建）
```java
public final class LpCraftSnapshot {
    /** 只可在伺服器執行緒呼叫。任何內部例外不外拋：回傳的 snapshot.fallbackReason() 非 null。 */
    public static LpCraftSnapshot capture(Level level, IGrid grid, ICraftingService svc,
            ICraftingSimulationRequester simRequester, AEKey what, long amount,
            CalculationStrategy strategy);

    public FallbackReason fallbackReason();      // null = 可進背景求解
    public AEKey what(); public long amount(); public CalculationStrategy strategy();

    // 套件內可見欄位（背景緒唯讀）：
    KeyCounter avail;                            // 求解用工作拷貝的母本（傳播時再 copy）
    KeyCounter availOriginal;                    // 原始快照值，audit(b) 用（含 ignore 歸零後的值）
    LinkedHashMap<AEKey, List<IPatternDetails>> patternsByKey;  // getCraftingFor 原始實例、原始順序
    LinkedHashMap<IPatternDetails, LpPattern> compiled;
    Set<AEKey> emitable;                         // canEmitFor 快照
    Set<AEKey> multiCandidate;                   // 候選樣板 ≥2 的 key（鐵則 10）
    Set<AEKey> fuzzyStocked;                     // 快照庫存 findFuzzy(K, IGNORE_ALL) 有主 key 以外變體的 key（鐵則 10）
    boolean anyMultiInput;                       // 任一樣板任一槽 possibleInputs.length > 1
}
```

### 4.5 LpPattern（編譯後樣板，不可變）
```java
final class LpPattern {
    final IPatternDetails details;   // 原始實例（鐵則 13；hashCode 已在快照期暖機）
    final AEKey[] inKey;             // 每槽解析後 key（唯一重導向已代入，§5.3）
    final long[]  inAmt;             // 每輪 = getPossibleInputs()[i].amount() * getMultiplier()
                                     //（與執行器 extractPatternInputs / topUpInputs 的槽位口徑一致）
    final AEKey[] outKey; final long[] outAmt;   // getOutputs() 全部
    final AEKey primaryOut; final long primaryOutAmt;    // getPrimaryOutput
    final boolean parallelOrUnknown; // §5.4；true ⇒ 啟動料權重 w≥2 ＋ audit 死角規則（鐵則 1）
    final boolean multiInput;        // 任一槽 possibleInputs.length > 1
    final boolean ambiguousRedirect; // 任一槽重導向多候選（§5.3）
}
```

### 4.6 LpGraph（圖＋SCC＋拓撲）
```java
final class LpGraph {
    static LpGraph build(LpCraftSnapshot snap) throws LpFallbackException;
    IPatternDetails selectedPattern(AEKey k);    // 首位候選；無 → null
    boolean multiplePaths();                     // 任一 key 候選 ≥2（CraftingPlan 第 4 參數）
    int sccOf(AEKey k); int sccOfPattern(IPatternDetails p);
    boolean isCyclicScc(int sccId);              // 大小 >1 或含自迴圈
    int[] topoOrder();                           // 縮點 DAG 反拓撲序：消費者在前、生產者在後
    List<AEKey> keysOfScc(int sccId); List<IPatternDetails> patternsOfScc(int sccId);
    boolean reachable(int fromScc, int toScc);   // 祖先可達性 bitset（surplus 折抵無環守衛）
    void addWaitEdge(int consumerScc, int producerScc);  // 折抵決策累積判環（§6.2）
}
```
圖模型（兩層邊集，關鍵設計；理由見 §6.1）：
- **路由邊**（傳播用）：`K → selectedPattern(K)`、`P → 每個解析後輸入 key`。
- **副產供給邊**（僅 SCC 偵測用）：`K → Q`，當 `getCraftingFor(K)` 為空且閉包內
  樣板 Q 的任何輸出含 K。Tarjan 跑「路由邊 ∪ 副產供給邊」——這樣 Kroll 的
  跨樣板環（MgCl2 只是 A 的副產物、無自己的樣板）才會被縮成一個 SCC。
  副產供給邊**不參與**傳播路由（絕不因副產物需求驅動 runs，SCC 內除外）。

### 4.7 LpLedger（帳本）
```java
final class LpLedger {
    final KeyCounter used, emitted, missing, surplus;
    final Object2LongOpenHashMap<IPatternDetails> runs;      // 插入序 = 宣告序（audit 重放順序之一）
    final Object2LongOpenHashMap<AEKey> flow;                // 每 key 毛需求流量（bytes 用）
    final KeyCounter bootstrap;                              // SCC 啟動料（已含在 used 內，另記供 log/audit）
    // 需求佇列：每 key 的 (增量, 來源) 佇列；來源 ∈ {FINAL, PATTERN, BOOTSTRAP(sccId)}
    void addDemand(AEKey k, long amt, DemandOrigin origin);
}
```

### 4.8 LpSolver（協調器）＋各階段類
```java
final class LpSolver {
    /** 背景執行緒進入點。成功回 CraftingPlan；一切回退拋 LpFallbackException。 */
    static appeng.crafting.CraftingPlan solve(LpCraftSnapshot snap) throws LpFallbackException;
}
final class LpDemandPropagator {
    static LpLedger propagate(LpCraftSnapshot snap, LpGraph g, long amount) throws LpFallbackException;
}
final class LpCycleSolver {
    /** 處理單一循環 SCC：自迴圈閉式解或高斯消去＋整數化＋啟動料＋波次證書。 */
    static void solve(LpCraftSnapshot snap, LpGraph g, int sccId, LpLedger ledger)
            throws LpFallbackException;
}
final class LpRates {   // CRAFT_LESS 專用，§7
    static LpRates compute(LpCraftSnapshot snap, LpGraph g) throws LpFallbackException;
    long rHi(KeyCounter avail); long rLo(KeyCounter avail);
}
final class LpAuditor {
    /** 守恆＋雙順序波次重放；不過拋 AUDIT_FAIL。 */
    static void verify(LpCraftSnapshot snap, LpGraph g, LpLedger ledger,
                       AEKey what, long amount) throws LpFallbackException;
}
final class LpPlanBuilder {
    static appeng.crafting.CraftingPlan build(LpCraftSnapshot snap, LpGraph g,
                                              LpLedger ledger, AEKey what, long amount);
}
```
實作提示：LpGraph 建構時把閉包內 AEKey 與樣板 intern 成密集 int 索引，
LpCycleSolver / LpAuditor 內部全用 int 索引與 long[]（純數字，無 AE2 型別）——
高斯與波次重放可寫純 JUnit 測試，不需 MC bootstrap。

### 4.9 LpCalcTask（背景任務）
```java
public final class LpCalcTask implements Runnable {
    public LpCalcTask(LpCraftSnapshot snap, LpFallbackQueue.Request req,
                      CompletableFuture<ICraftingPlan> future);
    @Override public void run();
}
```
`run()` 行為：
1. `LpSolver.solve(snap)` 成功 → `future.complete(plan)`＋`LpStats.hit()`。
2. 拋 `LpFallbackException` → `LpStats.fallback(reason)` → `LpFallbackQueue.enqueue(req, reason, future)`。
3. 拋其他 `Throwable` → 同上以 `ANY_THROWABLE` 入佇列（log 節流帶堆疊）。
4. 誠實 sim 結果（missing 非空且 §6.7 全過）且 `shadowVerifyOnMissing` 開
   → `LpFallbackQueue.enqueueShadow(req, lpSimPlan, future)`；關 → 直接 complete。

**絕不**在此執行緒建 CraftingCalculation 或讀 grid（鐵則 5）。

### 4.10 LpFallbackQueue（伺服器 tick 回退佇列）
```java
public final class LpFallbackQueue {
    public record Request(Level level, IGrid grid, ICraftingSimulationRequester simRequester,
                          AEKey what, long amount, CalculationStrategy strategy,
                          java.util.concurrent.ExecutorService pool) {}
    public static void enqueue(Request req, FallbackReason reason,
                               CompletableFuture<ICraftingPlan> future);
    public static void enqueueShadow(Request req, ICraftingPlan lpSimPlan,
                                     CompletableFuture<ICraftingPlan> future);
    /** 只在伺服器執行緒呼叫（Mixin onServerEndTick 新增行，§10.2）。 */
    public static void drainOnServerTick();
}
```
`drainOnServerTick()`：把佇列抽乾（ConcurrentLinkedQueue，量少）；每筆 try-catch：
- 一般回退：`var calc = new com.gtocraftfix.calc.CraftingCalculation(req.level(), req.grid(),
  req.simRequester(), new GenericStack(req.what(), req.amount()), req.strategy());`
  （ctor 的 grid 讀取就在此刻、伺服器執行緒完成，Calc:57-70）→
  `req.pool().submit(() -> { try { future.complete(calc.run()); }
  catch (Throwable t) { future.completeExceptionally(t); } });`
  calc.run() 自行 register CalcTicker，預算由既有 Mixin tick 鉤子泵送。
- shadow 筆：同法建樹狀版；背景完成時比較——
  `treePlan != null && !treePlan.simulation()` → `future.complete(treePlan)`＋
  `LpStats.shadowAdopted()`；否則 `future.complete(lpSimPlan)`（樹狀版也失敗
  ⇒ LP 的誠實 sim 是對的）。樹狀版拋例外 → `future.complete(lpSimPlan)`。
- 建構本身拋例外 → `future.completeExceptionally(t)`（與現行 run() 包
  RuntimeException 進 Future 的語意等價）。

### 4.11 LpEntry（mixin 唯一入口，讓 mixin diff 最小化）
```java
public final class LpEntry {
    /** 只在伺服器執行緒呼叫（beginCraftingCalculation HEAD 內）。 */
    public static Future<ICraftingPlan> beginMachineCalc(Level level, IGrid grid,
            ICraftingService svc, ICraftingSimulationRequester simRequester,
            AEKey what, long amount, CalculationStrategy strategy,
            java.util.concurrent.ExecutorService calcPool);
}
```
行為（鐵則 5、12）：
1. `!LpConfig.enabled()` → `LpStats.fallback(DISABLED)` → 當場建樹狀版＋
   `calcPool.submit(calc::run)` 回傳（維持現行樹狀回退的 Future 語意）。
2. `LpCraftSnapshot.capture(...)`；`fallbackReason != null` → 計數 → 當場建樹狀版回傳
   （伺服器執行緒、單份快照——樹狀版 ctor 自己拷貝庫存，LP 快照已丟棄）。
3. 成功 → `CompletableFuture` ＋ `calcPool.submit(new LpCalcTask(...))` → 回傳 future。

### 4.12 LpStats（§11）
```java
public final class LpStats {
    public static void hit();
    public static void fallback(FallbackReason r);
    public static void shadowAdopted();
    public static void maybeLog();   // 節流輸出；由 hit/fallback 內部呼叫
}
```

---

## 5. 快照層規格（LpCraftSnapshot.capture）

全程伺服器執行緒；這是整個 lpcalc **唯一**允許讀 grid/level 的地方。

### 5.1 庫存快照
沿用 NetSim:44-56 語意，逐條複刻：
- `simRequester.getActionSource() == null` → 庫存視為全空。
- 母本 = `grid.getStorageService().getCachedInventory()` 全量拷貝成 `KeyCounter`。
- `AEConfig.instance().isCraftingSimulatedExtraction()` 開啟 → 逐項
  `storage.getInventory().extract(key, cached, SIMULATE, actionSource)` 取小值。
- **ignore() 語意**（Calc:132-133、Sim:152-156）：頂層請求的**精確 key** 在
  `avail` 與 `availOriginal` 同時歸 0（模糊變體不歸零）——「要 N 個 X」＝真的做 N 個新的。
- `availOriginal` = 上述處理後的凍結拷貝，audit(b) 的上界。

### 5.2 需求閉包 BFS
```
queue = {what}; visited = {}
while queue 非空:
    K = pop; 若 K ∈ visited continue
    if svc.canEmitFor(K): emitable += K; continue        # emitable 不展開（Node:196-199）
    pats = svc.getCraftingFor(K)                          # 原始 List、原始順序
    patternsByKey[K] = pats; |pats| ≥ 2 → multiCandidate += K
    快照庫存 findFuzzy(K, IGNORE_ALL) 有主 key 以外變體 → fuzzyStocked += K
    for P in pats:                                        # 全部候選都編譯（回退判定要用）
        compile(P)（§5.3）；把 P 的解析後輸入 key 全部 enqueue
    每個 pop、每張樣板編譯前後都檢查 nanoTime 預算與 caps（§5.6）
```
注意：閉包收全部候選樣板（不只首位），因為鐵則 10 的粗化判定與 multiCandidate
需要完整名單；但傳播只用首位（§6.1）。

### 5.3 樣板編譯（compile）與輸入槽重導向
對每張 IPatternDetails：
- 輸入：每槽 `primary = getPossibleInputs()[0]`，`inAmt = primary.amount() * getMultiplier()`。
- **重導向**（findCraftedStack 等價，Node:78-106）：`primary.what()` 的
  `getCraftingFor` 為空時，在快照期逐一試 `svc.getFuzzyCraftable(primary.what(),
  cand -> input.isValid(cand, level) && 數量語意相同)`：
  - 恰一個候選 → `inKey[i] = 候選`（路由與重放都用代入後 key）。
  - 多候選 → `ambiguousRedirect = true`（求解期該樣板 runs>0 才回退，§8 REDIRECT_AMBIGUOUS）。
  - 零候選 → 維持 primary（走 missing / 鐵則 2 檢查）。
- `getRemainingKey(primary) != null`（容器物品）→ 整體 `fallbackReason = CONTAINER_ITEM`
  （Proc:95-97 的 limitQty 條件；容器輸出依實際消耗模板而變，不可線性化）。
- `primaryOutAmt <= 0` 或任一 `inAmt <= 0` → `BAD_PATTERN`。
- `possibleInputs.length > 1` → `multiInput = true`（樣板旗標＋snapshot.anyMultiInput）。

### 5.4 並行偵測（鐵則 1）
```java
// static 快取，只解析一次
Class<?> iface = Class.forName("com.gtolib.api.ae2.pattern.IParallelPatternDetails");
```
- 解析成功 → `parallelOrUnknown = iface.isInstance(details)`。
- **解析失敗（ClassNotFound 或任何 Throwable）→ 全部樣板 `parallelOrUnknown = true`**。
  理由：Cpu:222-238——`isParallel && 剩餘>1 && 庫存只夠 1 輪` 兩個分支都不抽料、
  永久跳過；topUpInputs 只補一輪也救不了。「證書會抓到」不成立：
  證書用同一個缺了規則的模型模擬，抓不到自己不知道的死角。偵測不到一律當並行，
  代價只是啟動料多備一輪（超產側，安全）。

### 5.5 hashCode 暖機（鐵則 6）
capture 內對閉包每張 IPatternDetails 呼叫一次 `details.hashCode()`（結果丟棄）。
防背景緒首次組 `Object2LongOpenHashMap` 時觸發樣板惰性 NBT 初始化的執行緒競爭。

### 5.6 規模上限與 nanoTime 預算（鐵則 14）
- `t0 = System.nanoTime()` 於 capture 開頭；每個 BFS pop、每張樣板編譯及每次可能較慢的
  grid/API 呼叫前後都檢查 `nanoTime() - t0 > LpConfig.snapshotBudgetNanos()`（預設 1ms），
  並在回傳成功 snapshot 前做最後一次檢查；超時 → `CLOSURE_CAP`。單一外部呼叫無法中途搶占，
  但不得再用「每 64／256 項才看一次」冒充硬預算。
- `visited.size() > maxKeys(4096)` 或編譯樣板數 > `maxPatterns(16384)` → `CLOSURE_CAP`。
- capture 內任何未預期 Throwable → `SNAPSHOT_ERROR`（不外拋，帶 reason 回傳）。

---

## 6. 求解演算法（背景執行緒，LpSolver.solve）

`solveBudgetNanos` 是整次求解的共用 deadline；圖建構／Tarjan、高斯消去、整數修補、傳播、
候選搜尋與 auditor 的內層迴圈都必須定期檢查同一個 `LpBudget`。只在各大階段開始前檢查不算達標，
因為單一病態 SCC 或重放本身就可能吃完整個預算；超時一律 `SOLVE_BUDGET` 回退。

### 6.1 圖建構與 SCC（LpGraph.build）
1. 每 key 選 `patternsByKey[K]` 首位為 `selectedPattern(K)`（樹狀版貪婪首選的近似；
   分攤語意不支援，靠鐵則 10 回退兜住）。
2. 建兩層邊集（§4.6）；Tarjan 跑聯集（含自迴圈偵測：P 的輸出 key 出現在 P 的輸入）。
3. 縮點 DAG 拓撲序（**消費者在前、生產者在後**）；祖先可達性 bitset。
4. `multiplePaths = multiCandidate 非空`。

為什麼 SCC 偵測必須含副產供給邊：Kroll 兩樣板形
`A: TiCl4+2Mg → Ti+2MgCl2`、`B: 2MgCl2 → 2Mg+Cl2` 中 MgCl2 通常沒有自己的樣板
（只是 A 的副產物）。只看路由邊時 MgCl2 是葉節點、環不存在，需求會走到
missing → 鐵則 2 → 永遠回退，循環修復完全失效。加上副產供給邊
`MgCl2 → A` 後 {A, B, Mg, MgCl2} 縮成一個 SCC，交給 §6.3。
事實包對 Kroll 實際形狀有矛盾（單樣板自迴圈 vs 跨樣板環）——本圖模型兩者皆涵蓋：
自迴圈是大小 1 的循環 SCC，跨樣板環靠副產供給邊偵測。

### 6.2 反拓撲批量傳播（LpDemandPropagator.propagate）
```
ledger.addDemand(what, amount, FINAL)
for pass in 1..4:                                    # 鐵則 7：後趟只吃增量
    for C in topoOrder:                              # 消費者先於生產者
        if C 無待處理需求增量: continue
        if isCyclicScc(C): LpCycleSolver.solve(C)（§6.3）; continue
        K = C 的 key
        foreach (D, origin) in K 的增量佇列:          # 逐筆保留來源標記
            # 順序比照 Node.request：surplus 折抵 → 庫存 → emit → 樣板 → 缺料
            # (1) surplus 折抵（僅非 SCC 來源的副產物；鐵則 3）
            if surplus[K] > 0 且折抵無環守衛通過（見下）:
                take = min(D, surplus[K]); surplus[K] -= take; D -= take   # 單次計入，扣掉就沒了（鐵則 7）
            # (2) 庫存
            take = min(D, avail[K]); used[K] += take; avail[K] -= take; D -= take
            # (3) emitable
            if K ∈ emitable: emitted[K] += D; flow[K] += 原始 D; D = 0; continue
            # (4) 樣板
            P = selectedPattern(K)
            if P == null: missing[K] += D; flow[K] += 原始 D; continue
            if sccOfPattern(P) 是循環 SCC:            # K 在 SCC 外、由 SCC 樣板生產
                origin == FINAL → 轉交 LpCycleSolver（pin x_P，§6.3 步驟 1）
                origin == PATTERN → throw SCC_FEEDS_PATTERN      # 鐵則 3
                origin == BOOTSTRAP → throw LOOP_NESTED          # 鐵則 7（環套環）
                continue
            if P.ambiguousRedirect: throw REDIRECT_AMBIGUOUS
            t = ceilDiv(D, P.primaryOutAmt)          # 一次除法，與數量無關
            runs[P] += t                              # multiplyExact/addExact，溢位 → OVERFLOW
            for 每輸入 i: addDemand(P.inKey[i], P.inAmt[i] * t, PATTERN)
            for 每輸出 o: surplus 記帳 —— 主產出超過 D 的餘數與全部副產出：
                surplus[o.key] += o.amt * t - (o.key==K ? D : 0)
            flow[K] += 原始 D
    if 本 pass 無任何新增量: break
else（4 趟未收斂）: throw PASS_LIMIT
```
**surplus 折抵無環守衛**（僅限非循環 SCC 來源的 surplus）：
折抵前查 `addWaitEdge(consumerScc(K 的消費者), producerScc(產生該 surplus 的樣板))`
加入後縮點圖是否仍無環（`reachable` bitset O(1) 查生產者不可達消費者即安全）；
等待邊**跨全部折抵決策累積**（一筆通過就記邊，影響後續判定）。守衛不過 → 不折抵、
照常排產（超產安全）。**循環 SCC 樣板產生的 surplus 一律不入折抵池**（鐵則 3）：
不記 surplus、不記任何計畫欄位——執行期留在 CPU 庫存、finishJob 倒回網路
（Cpu:632-654，超產永遠安全）。折抵決策**不得**反向影響 audit 重放的起始庫存（鐵則 4）。

多趟語意（鐵則 7）：pass 2+ 只處理 `addDemand` 新入佇列的增量（典型：SCC 啟動料
外部回灌）；`used/avail/runs/flow` 跨趟只增不減；surplus 折抵扣過即不可重算；
audit 最後對總帳跑一次全量檢查，不逐趟湊。

### 6.3 SCC 求解（LpCycleSolver.solve）——兩種形狀
輪到縮點 C（循環 SCC）時，拓撲序保證其所有外部消費者已處理完，外部需求已聚齊。

**記號**：SCC 樣板集 `P(C)`（keysOfScc 各 key 的 selectedPattern 去重＋副產供給邊
拉進來的樣板）；內部 key 集 `K(C)`；外部需求 `d[K]`（來源必須全為 FINAL，
PATTERN 來源在 §6.2 已擋 SCC_FEEDS_PATTERN）。

1. **外部 pin**（評審 mustFix：樣板橫跨 SCC 內外）：SCC 外的 key K'（如 Kroll 的 Ti）
   由 SCC 樣板 P 生產且需求 D（FINAL 來源）→ pin 下界
   `pin[P] = max(pin[P], ceilDiv(D, out_P[K']))`。同一樣板被多個外部 key pin 時取 max。
2. **方程**：未知數 = 每張 P ∈ P(C) 的 x_P（Rational）。對每個 K ∈ K(C)：
   `Σ_P (out_P[K] − in_P[K]) · x_P = d[K]`（純回收中間物 d=0）。
3. **高斯消去**（Rational）：
   - 線性相依列（Kroll 的 Mg 式與 MgCl2 式互為負向重複）→ 先把 pin 值當等式代入
     消元再解剩餘未知數。
   - 代入後仍欠定 / 奇異 / 任一 x_P 為負 → `SCC_UNSOLVABLE`（樹狀版此時也算不動，
     回退不丟功能）。
   - 解出後套 `x_P = max(x_P, pin[P])`。
   - **自迴圈特例（大小 1 SCC）閉式解**，同語意免高斯：樣板每輪耗 a 顆 K、產 b 顆 K：
     `b−a > 0` → `x = ceilDiv(d, b−a)`；`b−a ≤ 0` 且 d>0 → `SCC_UNSOLVABLE`。
4. **整數化＋修補迴圈**：逐分量 `ceilToLong()`；ceil 可能破壞內部守恆——對每個
   `Σ(out−in)·x − d[K] < 0` 的內部 key，其生產樣板 `x += ceilDiv(缺額, 產量)`；
   每輪嚴格消滅一個缺口，上限 `2 × |K(C)|` 輪，超限 → `SCC_INT_FIXUP_LIMIT`。
   超產部分不記任何欄位。
5. **內部淨耗（觸媒損耗環）**：整數化後仍有 `net[K] < 0` 的內部 key（如每輪損耗
   觸媒）→ 外部補給量 `|net[K]|` 以 BOOTSTRAP 來源回灌（步驟 7 同路徑）。
6. **最小啟動種子**（執行可行性核心）：
   - 不得再把 SCC 每張樣板的內部輸入全部相加；那會把 Kroll 的 Mg 與 MgCl2 兩側都列成
     必要啟動料，明明任一側就能啟動卻誤報 `LOOP_NO_BOOTSTRAP`。
   - `w_P = min(x_P, P.parallelOrUnknown ? 2 : 1)`；以每張 P 的「內部輸入 × w_P」分別作為
     單一入口候選，逐一做 §6.8 同語意的 SCC 波次重放。能讓整個 SCC 完成的候選才可採用。
   - 若單一入口都不足，才從全入口保守向量開始，依穩定 key 順序反覆移除／縮減種子並重放；
     每次移除後仍可完成就永久移除，直到任何剩餘分量再縮都會卡死。結果必須是
     **包含關係最小（inclusion-minimal）**的可行種子，不要求全域材料量最優。
   - 多個可行種子先選 `avail` 不足量最小者，再選總量較小者，最後以穩定 pattern/key id
     決勝；所有候選與縮減都受 solve budget 約束，超限即 `SOLVE_BUDGET` 回退。
   - SCC 外部 key 的輸入不算 bootstrap：`in_P[K'] × x_P` 全額以 PATTERN 來源
     addDemand 傳播下游（毛量，鐵則 15）。
7. **啟動料來源**：先扣 `avail` → `used`（同時記 `ledger.bootstrap`）；不足餘量以
   `addDemand(K, 餘量, BOOTSTRAP(C))` 回灌，交多趟傳播由 **SCC 外**樣板生產
   （notRecursive 對啟動料的語意保留）；該回灌鏈在 §6.2 落入循環 SCC → `LOOP_NESTED`；
   落到無樣板無庫存 → **不出 missing**，`LOOP_NO_BOOTSTRAP`（鐵則 2：啟動料 key
   幾乎必有樣板——本 SCC 自己的——repairPlan 會錯誤加 runs 沖銷）。
8. **SCC 波次可行性證書**（局部預檢，最終仍以 §6.8 全計畫 audit 為準）：
   起始庫存 = 選定的最小 bootstrap ＋ SCC 外部輸入毛量；對 P(C) 做批次波模擬
   （`可執行輪數 = min(剩餘, floor(庫存/每輪))`；parallelOrUnknown 且剩餘>1 且
   只夠 1 輪 → 0 輪）；穩態快轉與 64 波上限同 §6.8。卡死時只可在**同一候選 seed key 集**
   內倍增（`used/bootstrap` 同步加，超出 avail 的部分走步驟 7 回灌），至多 3 次；再失敗就
   換下一個已證明可行的最小候選，全部失敗才 `SCC_WAVE_STUCK`。不得為了重試無條件恢復成
   「SCC 每一側都要啟動料」。

### 6.4 啟動料與多趟的交互（鐵則 7）
- pass 2+ 因 BOOTSTRAP 回灌新增的 runs / used / flow 一律進總帳，最終 audit 與
  bytes 全量涵蓋（評審 mustFix：第二趟新增 runs 必須重新納入 audit 與 bytes）。
- BOOTSTRAP 需求在 §6.2 的樣板選擇排除本 SCC 樣板：實作為
  「`selectedPattern(K) ∈ 本 SCC` → 掃候選清單找第一張不在任何循環 SCC 的樣板；
  找到 → 用它排產；找到的在別的循環 SCC → LOOP_NESTED；沒有 → LOOP_NO_BOOTSTRAP」。

### 6.5 整數域紀律
全程 long ＋ `Math.addExact/multiplyExact`（ArithmeticException → OVERFLOW）；
正整數 ceil 統一 `d == 0 ? 0 : 1 + (d - 1) / p`，避免 `(d + p - 1)` 在 Long.MAX_VALUE
附近先溢位（p ≥ 1 由 §5.3 BAD_PATTERN 保證）。
patternTimes 永遠是正 long；runs==0 的樣板不進 patternTimes。

### 6.6 usedItems 峰值語意（鐵則 15）
樹狀版 usedItems =「相對網路起始庫存的最大下探水位」（Sim:102-107、122）。
lpcalc 的輸出定義：**used[K] = 需求傳播直接吃庫存的毛量 ＋ SCC 啟動料**。
這個量 ≥ 樹狀版水位（保守偏大合法、偏小違規）：
- 執行器開工一次全抽 usedItems（Helper:50-73、Cpu:86-93）——只要 (a) 每筆
  ≤ `availOriginal`（audit b，否則 repairPlan :272-282 當幻影缺口加料）、
  (b) 覆蓋所有非回補來源的需求＋啟動料，就同時滿足「抽得到」與「不死鎖」。
- 循環回收料不折抵、不進 used 之外的任何欄位；順序可行性由波次重放證明
  （取代樹狀版「模擬順序=執行順序」的隱式保證）。
- 偏大代價：庫存貼地時可能比樹狀版嚴一點點被 MISSING_INGREDIENT 退單；
  IgnoreMissing present-once 包裝下缺量進 waitingFor 由回流補——比死鎖好。

### 6.7 missing 不變量與粗化溯源（鐵則 2、10）
傳播與 SCC 全部完成、audit 之前依序檢查：

1. **鐵則 2 不變量**：對 missing 每個 key K：
   `patternsByKey[K]` 非空 **或** K 是任何 `runs>0` 樣板的主/副產出 → 不得出 sim 計畫，
   `throw LOOP_NO_BOOTSTRAP`。理由：repairPlan 會對有樣板的 missing
   自動加 runs 沖銷、:445-449 把 sim 翻 false，把不可行計畫當可執行提交。
2. **鐵則 10 粗化**：missing 非空 且（`multiCandidate` 非空 或 `fuzzyStocked` 非空
   或 `snapshot.anyMultiInput`）→ `throw MULTI_PATH_LOAD_BEARING` /
   `FUZZY_LOAD_BEARING` / `MULTI_INPUT_SHORT`（依命中者；同時命中取第一個）。
   不做精確缺口鏈溯源——寧可多回退，樹狀版的貪婪多分支/模糊模板可能救得回來。
3. 兩關全過 → 誠實 sim 計畫候選：`simulation = true`、missing 逐 key 缺額、
   used/emitted/patternTimes 保留已排定部分（≤ availOriginal 不變量照守）→ 交 §9.3
   shadow 流程。**本算料器只產出兩種形狀**：sim=false＋missing 空、或
   sim=true＋missing 非空（提交修補會把 sim=true＋missing 空翻成可執行；
   sim=false 帶 missing 在嚴格取料路徑是死路——missingItems 完全不被讀）。

「真缺料且無樣板無變體」不回退——直接出誠實 sim（回退樹狀版只會更慢得出同一結論），
交 blockSubmit 擋單＋聊天室廣播；廣播沿用 repair 既有去重，算料器不重複做。

### 6.8 LpAuditor.verify（守恆＋雙順序波次重放）
違反任一 → `AUDIT_FAIL`（絕不輸出爛帳）：

**(a) 守恆**：每 key
`used[K] + emitted[K] + Σ_P out_P[K]·runs[P] ≥ Σ_P in_P[K]·runs[P] + (K==what ? amount' : 0) + missing 抵減`
（amount' = 本計畫實際交付量；CRAFT_LESS 時為 R）。
**(b) 上界**：每 key `used[K] ≤ availOriginal[K]`。
**(c) patternTimes**：全部 ≥1、無零項。
**(d) finalOutput 可交付供給**：`emitted[what] + Σ 樣板產出×runs ≥ amount'`。
`used[what]` **不得**列入：GTOCore 不會把 CPU 開局吸入的 final key 交給 link；把它當供給會產生
抱著現貨卻永遠少交的計畫。先自查過關後，repair 的 final deficit 掃描也應無事可做。

**(e) 波次重放（鐵則 4）**——至少兩種順序都 PASS 才算過：
- 起始庫存：**只灌 usedItems**（絕不預灌任何折抵副產物；emitted 也不灌數字——
  emitable key 的輸入槽在重放中視為恆足，外部發射器語意）。
- 任務集：patternTimes 逐項（剩餘 runs）。
- 每波：依當前順序掃描任務，`可執行輪數 = min(剩餘, floor(庫存/每輪輸入))`；
  同一樣板若多個輸入槽解析成同一 AEKey，必須先把每輪量以 exact/saturating 規則聚合後再算
  floor 並一次扣除，不能逐槽各自用同一份庫存判斷而把庫存扣成負值仍判 PASS；
  **parallel 死角規則（鐵則 1）**：`parallelOrUnknown && 剩餘 > 1 && floor(...) == 1`
  → 本波 0 輪（複刻 Cpu:225-238）；執行即扣輸入、加全部輸出（含副產物——
  對應執行器 expectedOutputs→waitingFor→回收進 CPU 庫存，Cpu:340-342、715-718）。
- **順序 1（宣告序）**：runs 帳本插入序。
- **順序 2（對抗序）**：每波先跑所有「不屬於任何循環 SCC 的樣板」（SCC 外部消費者），
  再跑循環 SCC 樣板；組內維持宣告序。這是淨產型循環搶料死鎖的針對性反例序
  （hash 序先跑消費者吃掉啟動料）。
- 穩態快轉（取代早期草案的「連續兩波庫存向量相等」外插證書——該證書對任何有淨進度的
  計畫永不觸發：外部原料每波遞減使庫存向量不可能相等；且缺「每個未完成任務都在推進」
  守衛，凍結任務可被外插放行）：連續兩波 exec 向量相同 → 本波軌跡精確重複 ff 波，
  `ff = min( min over exec>0 的 floor(剩餘/exec)，min over 淨遞減 key 的
  floor(本波最低水位/每波淨減量) )`。這是模擬壓縮而非證書跳躍：跳過的每一波都可逐波
  平移論證依原順序執行；完成／卡死仍由全部 runs 歸零／無任務可推進裁決，
  凍結任務（exec==0 且剩餘>0）不會被放行（避免 O(數量) 波數的目的不變）。
- 完成判定：全部 runs 歸零，且 `emitted[what]＋累計樣板產出[what] ≥ finalOutput`；
  `used[what]` 仍不得當成已交付成品。
- 64 波（快轉計 1 波）內未完成 → FAIL。
- missing 非空的誠實 sim 計畫：重放時把 missing 量視為起始庫存追加（模擬
  IgnoreMissing 下缺料由 waitingFor 回流——sim 計畫本來就會被 blockSubmit 擋，
  這裡只驗證帳目自洽，不作為可執行證明）。

執行器可行轉移集合是本模擬的超集（湧現式推進、無拓撲序，Job:95-103、Cpu:187-238），
且 DAG 部分有歸納保底（拓撲最前的剩餘任務必可推進）；配合鐵則 3 封堵後，
順序敏感風險收斂到已被對抗序覆蓋的類型。

### 6.9 計畫回填（LpPlanBuilder.build）
```java
var patternTimes = new Object2LongOpenHashMap<IPatternDetails>();  // 鐵則 13：新容器
runs 逐項放入（key = getCraftingFor() 回傳的原始 IPatternDetails 實例，絕不包裝/複製；
 執行器強轉 (IDetails) 且靠 equals 對上 NetworkCraftingProviders 註冊實例）;
long bytes = Σ_K ceilDivExact(flow[K] * 8, K.getType().getAmountPerByte()) // Sim 口徑，整數／BigInteger
                 + Σ runs                                                 // 每 craft 1 byte
                 + 閉包涉及 key 數 * 8;                                   // nodeCount 近似，刻意輕微高估
boolean simulation = !missing.isEmpty();                                // §6.7 的兩形狀之一
return new appeng.crafting.CraftingPlan(new GenericStack(what, amount'), bytes, simulation,
        graph.multiplePaths(),
        usedCopy, emittedCopy, missingCopy,     // 各自 new KeyCounter，不共用快照
        patternTimes);
```
- 必須回 `appeng.crafting.CraftingPlan` 具體類（repair 防線會先做 instanceof 檢查）。
- `gtocore$allocations` 恆不設（null → 執行側整套 INSUFFICIENT_PRIORITY 邏輯跳過，Job:74-86）。
- bytes 全程用整數／BigInteger 向上取整，不得經 `double`（>2^53 會失去整數精度並可能向下）；
  超過 long 或 128-bit 護欄時 `OVERFLOW` 回退。bytes 只准高估不准低估（低估 → 小 CPU 接單爆容量；
  高估 → 換大 CPU，可見可解釋）。

---

## 7. CRAFT_LESS（單次求解＋候選驗證，鐵則 9）

`strategy == CalculationStrategy.CRAFT_LESS` 時取代樹狀版 log2(n) 次整樹重跑（Calc:96-110）：

1. 先跑全量 `amount`：§6 全流程（含 audit）成功 → 直接回傳，不進降量。
2. 失敗於 missing（其他回退原因照常回退）→ `LpRates.compute`：
   對選定樣板圖一次反向掃描（含 SCC：以符號需求 d 解一次得 x = f(d) 線性式），
   得每 key 有理速率 `r_K`（每 1 單位最終產物攤到 K 的毛需求）與常數鬆弛上界
   `c_K`（沿路 ceil 餘數＋SCC 啟動料總和上界；只與批量大小、圖深度有關，與請求量無關）。
3. 上下界：`R_hi = min over 受限 key floor(供給_K / r_K)`、
   `R_lo = min over 受限 key floor((供給_K − c_K) / r_K)`
   （供給_K = availOriginal；emitable 視為無限；r_K = 0 不設限；負值取 0）。
4. 候選驗證迴圈（**每個候選 R 都要過「精確整數傳播（§6.2-6.5）＋ §6.7 檢查 ＋
   完整 LpAuditor 雙順序重放」才可回傳**——不是只跑傳播）：
   - 從 `R = R_hi` 開始；通過 → 回傳。
   - 未過 → 取瓶頸 key 缺額換算 `R -= ceilDiv(缺額_K, r_K)`（Rational ceil）再驗，
     至多 4 次修正；未收斂 → 退 `R_lo` 再驗一次（理論保證可行，仍必須實跑驗證）。
   - 期間任何非 missing 類回退原因 → 整體回退。
5. `R_max ≥ 1` → `sim=false`、`finalOutput=(what, R_max)` 的可執行計畫
   （做多少先交多少，追蹤器下輪補餘量——與既有降量段語意一致但精確）。
6. `R_max == 0` → 對原始 amount 出誠實 sim 計畫，**同樣受 §6.7 鐵則 2/10 約束**
   （違反 → 回退樹狀版），過關才走 §9.3 shadow。
7. 已知次優性：回傳 R 可能比理論最大小 ≤ `max(c_K/r_K)` 個單位——不依賴可行集
   對 R 單調（整模板取整可破壞單調性）；每個回傳值都實測可行。寫進 README 防日後當 bug 追。

---

## 8. FallbackReason 完整清單與觸發點

| enum 值 | 觸發時點 | 條件 |
|---|---|---|
| `DISABLED` | LpEntry（伺服器緒） | `-Dgtodiag.lpcalc.enabled=false`（鐵則 12） |
| `SNAPSHOT_ERROR` | capture（伺服器緒） | 快照期任何未預期 Throwable |
| `CLOSURE_CAP` | capture（伺服器緒） | BFS 超過 maxKeys/maxPatterns 或 nanoTime 預算 ~1ms（鐵則 14） |
| `SOLVE_BUDGET` | 求解期 | 背景求解累計超過 solveBudgetNanos；回退樹狀版 |
| `CONTAINER_ITEM` | capture（伺服器緒） | 任一輸入 `getRemainingKey(primary) != null`（Proc:95-97） |
| `BAD_PATTERN` | capture（伺服器緒） | 主產出 ≤0、輸入量 ≤0 等編譯異常 |
| `REDIRECT_AMBIGUOUS` | 求解期 | 多候選重導向槽所屬樣板 runs > 0 |
| `SCC_FEEDS_PATTERN` | 求解期 §6.2 | 循環 SCC 產出 key 承接 PATTERN 來源需求（鐵則 3 封堵） |
| `SCC_UNSOLVABLE` | 求解期 §6.3 | 高斯奇異／代入 pin 後仍欠定／負解；自迴圈 b−a≤0 且 d>0 |
| `SCC_INT_FIXUP_LIMIT` | 求解期 §6.3 | 整數化修補迴圈超過 2×|K(C)| |
| `SCC_WAVE_STUCK` | 求解期 §6.3 | 波次證書 3 次啟動料倍增後仍卡死 |
| `LOOP_NO_BOOTSTRAP` | 求解期 §6.3/§6.7 | 啟動料缺口無外部解；或 missing 落在有樣板／計畫產出 key（鐵則 2） |
| `LOOP_NESTED` | 求解期 §6.2/§6.4 | 啟動料外部回灌鏈落入另一個循環 SCC（鐵則 7，不遞迴解環套環） |
| `MULTI_PATH_LOAD_BEARING` | 求解期 §6.7 | missing 非空 且閉包任一 key 候選樣板 ≥2（鐵則 10 粗化） |
| `FUZZY_LOAD_BEARING` | 求解期 §6.7 | missing 非空 且閉包任一 key 有模糊變體存量（鐵則 10 粗化） |
| `MULTI_INPUT_SHORT` | 求解期 §6.7 | missing 非空 且任一樣板槽 possibleInputs.length > 1（粗化） |
| `OVERFLOW` | 求解期 | long/Rational/BigInteger 任何溢位或 >128 bit |
| `PASS_LIMIT` | 求解期 §6.2 | 多趟傳播 4 趟未收斂 |
| `AUDIT_FAIL` | 求解期 §6.8 | 守恆或任一順序波次重放不過 |
| `ANY_THROWABLE` | LpCalcTask 兜底 | 任何未分類 Throwable（log 節流帶堆疊） |

伺服器緒觸發（capture/LpEntry 列）→ 當場建樹狀版（鐵則 5 前半）；
求解期觸發 → LpFallbackQueue（鐵則 5 後半）。

---

## 9. 回退與影子驗證接線

### 9.1 kill switch（鐵則 12）
完整版接線後，`LpEntry` 第一件事查 `LpConfig.enabled()`；false → 完整走樹狀回退路徑
（new CraftingCalculation ＋ CALC_POOL.submit(calc::run)），LP 零介入。**目前 slim 更早就被
`gtocraftfix$SLIM` 擋住，根本不會呼叫 `LpEntry`；這個屬性只能停用，不能在 slim 啟用。**

### 9.2 LpFallbackQueue（鐵則 5）
- 佇列：`ConcurrentLinkedQueue<Entry>`；Entry 含 Request、reason 或 lpSimPlan、future。
- 入列端：LpCalcTask（背景緒）。出列端：`drainOnServerTick()`（只在伺服器緒，
  由 Mixin onServerEndTick 新增行呼叫，§10.2）。
- 語意見 §4.10。+1 tick 延遲可接受（機器追蹤器本來就每 2 秒重試）。
- 伺服器關閉／grid 失效：建構拋例外 → `completeExceptionally`，與現行
  run() 包 RuntimeException 進 Future 的呼叫端語意一致。
- `CompletableFuture.cancel` 不會中斷背景工作（現行 `pool.submit` 的
  `cancel(true)` 可以）——已知輕微行為差，呼叫端（AE2 CraftingService）未依賴中斷，接受。
- backpressure 必須涵蓋「等待 server tick 的 staging queue」與「已送入 executor、尚未完成」兩段；
  只檢查 `ConcurrentLinkedQueue.size()` 後再一次 drain 全部，無法限制固定執行緒池的無界工作佇列。
  建議以單一 outstanding permit（固定上限 256）從 enqueue 持有到 future 完成，並限制每個 server tick
  最多轉送 16 筆；無 permit 時直接走明確回退／拒絕，不得靜默堆積。

### 9.3 shadowVerifyOnMissing（鐵則 8）
LP 產出誠實 sim 計畫（§6.7 全過）時：
- 開關關 → 直接 `future.complete(lpSimPlan)`。
- 開關開（預設）→ `enqueueShadow`：下一 tick 伺服器緒建樹狀版 →
  背景跑 `calc.run()` →
  - 樹狀版回可執行計畫（`!simulation()`）→ `future.complete(treePlan)`＋
    `LpStats.shadowAdopted()`（分歧計數；護住 LP 因首選樣板/精確鍵模型誤報缺料的未知面）。
  - 樹狀版也 sim / 拋例外 → `future.complete(lpSimPlan)`（LP 的缺料結論被佐證）。
- 穩定上線後可 `-Dgtodiag.lpcalc.shadowVerifyOnMissing=false` 關閉省算力。
- shadow 節流鍵必須至少包含 grid identity＋requester identity＋AEKey；不同網路即使請求同 key，
  庫存與樣板閉包也可能完全不同，不能共用只以 AEKey 為鍵的 10 秒結論。

---

## 10. mixin 整合意圖（歷史 diff，重新啟用時須重做 review）

以下是最初接線意圖；相關程式已存在，但目前被 `gtocraftfix$SLIM` 硬閘門擋住。舊行號與
「恰兩處」不能再當成可直接套用的 patch。重新啟用時必須以目前符號與實際 diff 重新確認
執行緒、回退、修補及停機清理路徑。

### 10.1 machineSrc0 分支的預期接線
原：
```java
if (machineSrc0) {
    var calc = new com.gtocraftfix.calc.CraftingCalculation(level, grid, simRequester,
            new appeng.api.stacks.GenericStack(what, amount), strategy);
    cir.setReturnValue(gtocraftfix$CALC_POOL.submit(calc::run));
    return;
}
```
改為：
```java
if (machineSrc0) {
    // 機器源改走 lpcalc（不支援/失敗回退 com.gtocraftfix.calc 樹狀版；-Dgtodiag.lpcalc.enabled=false 一鍵停用）
    cir.setReturnValue(com.gtocraftfix.lpcalc.LpEntry.beginMachineCalc(
            level, grid, (ICraftingService) (Object) this, simRequester,
            what, amount, strategy, gtocraftfix$CALC_POOL));
    return;
}
```
- 回傳型別維持 `Future<ICraftingPlan>`（cir 泛型，:106）。
- `LpEntry` 內部自行 try-catch 一切（含 capture）；理論上不外拋，但外層 :232-234 的
  catch（不 setReturnValue → 退 GTO async）照舊當最後防線。

### 10.2 onServerEndTick 的預期接線
```java
com.gtocraftfix.calc.CalcTicker.tick(); // ← 既有回退預算泵
com.gtocraftfix.lpcalc.LpFallbackQueue.drainOnServerTick(); // ← LP 晚期回退/影子驗證的伺服器緒建構點
```

### 10.3 不可動語意（鐵則 13，驗收時依符號逐項核對）
- `CalcTicker.tick()` 呼叫點——回退路徑的預算泵；LP 路徑不註冊 CalcTicker，
  ACTIVE 空時 tick() 是 O(1) no-op，同池共存無干擾。
- beginCraftingCalculation inject 頭、machineSrc0 判定與 executeV2 反射失敗的回退鏈。
- 無樣板守衛與匿名誠實 sim 計畫（算料前置防線，LP 沿用其語意、不重複實作）。
- repairPlan 全套（LP 計畫過 audit ⇒ deficits 應為空，
   只在「算料後庫存漂移」時才動手——這正是要保留它的原因）。
- sim→false 翻轉、blockSubmit／退化計畫拒單。
- IgnoreMissing present-once 包裝、保母與 topUpInputs。
- 玩家路徑降量段（machineSrc0 若先被 lpcalc 攔走，須確認不會誤改玩家語意）。

---

## 11. LpStats 與觀測（鐵則 11）

- 計數器：`LongAdder hit`、`EnumMap<FallbackReason, LongAdder> fallbacks`、
  `LongAdder shadowAdopted`（執行緒安全，背景緒可寫）。
- 節流 log（沿用 [craftfix] 風格與 `gtocraftfix$sitterLog` 節流手法）：
  - 每次回退：前 200 次逐筆
    `LOG.info("[craftfix][lp] 回退 {} out={} x{}", reason, what, amount)`。
  - 彙總：每 6000 tick（5 分鐘）或每 512 個事件輸出一次
    `LOG.info("[craftfix][lp] 統計 hit={} ({}%) fallback={} shadowAdopted={}", ...)`
    （fallback 印非零項的 `REASON=次數` 列表）。
- shadow 採用樹狀版時逐筆（前 200 次）記
  `[craftfix][lp] shadow 分歧：LP 判缺料、樹狀版可行 out={}`——這是模型誤報的直接證據，
  上線一週以此數據決定 Phase 2（fuzzy/多樣板/SCC 供外部樣板）是否投資。

---

## 12. 系統屬性一覽

| 屬性 | 預設 | 語意 |
|---|---|---|
| `gtodiag.lpcalc.enabled` | `true` | 完整版內層 kill switch；false 走樹狀回退。slim 外層硬停用，所以 true 也不會啟用 LP |
| `gtodiag.lpcalc.shadowVerifyOnMissing` | `true` | missing 非空時影子跑樹狀版 |
| `gtodiag.lpcalc.snapshotBudgetNanos` | `1000000` | 快照期 nanoTime 硬預算 |
| `gtodiag.lpcalc.solveBudgetNanos` | `100000000` | 求解期背景緒預算；超限回退樹狀版 |
| `gtodiag.lpcalc.maxKeys` | `4096` | 閉包 key 上限 |
| `gtodiag.lpcalc.maxPatterns` | `16384` | 閉包樣板上限 |

---

## 13. 驗收測試清單

現況標記（2026-08-21，slim 3.13.2 修正中）：

| 範圍 | 現況 |
|---|---|
| 1～12 純數字單元測試 | **待驗收**；只有實際納入 `src/test` 且 `test` 成功的案例才能逐項改為通過 |
| 13～19 整合／遊戲內測試 | **未執行**；lpcalc 仍由 slim 外層硬停用 |
| 20 建置／版本 | **待本次發行流程驗證**；建置成功不等同 lpcalc 功能通過 |

3.13.2 新增的 `CraftingHotfixSupportTest` 驗證 final 供給、餵料 fail-closed、飽和算術與 deadline；
它屬於 slim hotfix 回歸測試，**不等同**下列 lpcalc 純數字模型測試。

待驗收單元測試（純數字模型，int 索引＋long[]，不需 MC bootstrap）：
1. **Kroll 二樣板環（跨樣板 SCC）**：A: TiCl4+2Mg→Ti+2MgCl2、B: 2MgCl2→2Mg+Cl2，
   MgCl2 無自己樣板 → 副產供給邊使 {A,B} 成 SCC；d[Ti]=10 → pin x_A=10、
   相依方程代入得 x_B=10；最小種子可以是 `bootstrap[Mg]=2·w_A` **或**
   `bootstrap[MgCl2]=2·w_B`，不得要求兩者同時存在。分別測「只有 Mg」「只有 MgCl2」皆 PASS、
   兩者皆無且無外部來源才 `LOOP_NO_BOOTSTRAP`；非並行 w=1、並行/未知 w=2；Cl2 不入任何欄位。
2. **Kroll 單樣板自迴圈變體**（同配方編成一張帶 Mg 自迴圈的樣板）：閉式解
   `x = ceilDiv(d, b−a)`，兩種形狀結果一致（事實包矛盾的雙保險）。
3. **NaOH 拜耳法環、蒽醌三步環**：3+ 樣板 SCC 高斯；線性相依列代入消元；
   整數化修補迴圈觸發與收斂。
4. **淨耗環（每輪損耗觸媒）**：內部 key 淨產為負 → 外部補給以 BOOTSTRAP 回灌；
   外部有樣板 → 排產；外部樣板又在循環 SCC → LOOP_NESTED；無解 → LOOP_NO_BOOTSTRAP。
5. **淨產環抵帳封堵（鐵則 3）**：自迴圈 1X→2X ＋ 外部樣板消費 X →
   `SCC_FEEDS_PATTERN` 回退，**不得**出任何計畫；直接請求 X（finalOutput）→ 可解，
   且對抗序重放（消費者先跑）PASS（必要時 bootstrap 倍增）。
6. **parallel==1 死角（鐵則 1）**：(a) 反射失敗情境 → 全樣板 w≥2、重放套死角規則；
   (b) 並行樣板剩餘 2、庫存僅 1 輪 → 重放判 0 輪（不推進）；(c) 剩餘恰 1 → 可推進；
   (d) 兩個輸入槽解析成同 key 時先聚合每輪需求，不得重複使用同一份庫存通過 audit。
7. **多趟帳本（鐵則 7）**：構造 bootstrap 回灌落在已處理節點 → pass 2 只吃增量、
   surplus 折抵不重複、總帳 audit 全量一致；5 趟不收斂 → PASS_LIMIT。
8. **surplus 折抵守衛**：電解水 H2+O2 場景折抵成功；構造折抵會成環 → 不折抵改超產；
   等待邊跨決策累積判環。
9. **鐵則 2 不變量**：missing 落在有樣板 key / 計畫副產出 key → LOOP_NO_BOOTSTRAP；
   真末端原料缺口 → 誠實 sim。
10. **鐵則 10 粗化**：missing 非空＋閉包某 key 有 2 張候選樣板 → MULTI_PATH_LOAD_BEARING；
    模糊變體存量 → FUZZY_LOAD_BEARING；多輸入槽 → MULTI_INPUT_SHORT。
11. **CRAFT_LESS（鐵則 9）**：候選 R 逐一過完整 audit；驗證回傳 R 可行；
    R_max==0 → sim 受鐵則 2 約束；「10000 鈦錠歸零病」重現測試：啟動料是 c_K 常數項，
    R_max ≈ 供給上界而非 0。
12. **Rational/溢位**：gcd、BigInteger 升格、>128 bit → OVERFLOW；bytes 在 2^53 前後與
    Long.MAX_VALUE 邊界仍只會精確向上或回退，禁止 double 捨入成較小 CPU 需求。

整合／遊戲內驗收：
13. **10000 大量請求**：求解毫秒級（與 10 個同數量級）；計畫形狀與小量一致。
14. **repair 無事可做**：LP 可執行計畫過 repairPlan 後 deficits 空、
    patternTimes/used/missing 未被改寫（log 無「計畫修補」字樣）。
15. **kill switch**：`-Dgtodiag.lpcalc.enabled=false` → 行為與改動前逐位一致。
16. **shadow**：人為讓 LP 誤報缺料（例：靠模糊模板才可行的布局其實會先被鐵則 10
    攔下——用首選樣板不可行、次選可行的布局）→ 樹狀版計畫被採用＋shadowAdopted++；
    兩張 grid 同時請求同 key 時各自驗證，不得被另一張 grid 的節流結果跳過。
17. **快照預算／backpressure**：超大閉包（>4096 key）→ CLOSURE_CAP 回退、tick 無尖峰、
    樹狀版照常出計畫；突發超過 outstanding 上限時，staging＋executor 待辦總量仍有界且 future
    全部得到明確完成／回退，不得無限堆積或懸空。
18. **執行緒安全走查**（code review checklist）：lpcalc 內 grep 不得出現
    `getCraftingFor|canEmitFor|getFuzzyCraftable|isValid\(|getStorageService|getInventory\(`
    於 capture 之外；`CraftingCalculation` 建構只出現在 LpEntry 與 LpFallbackQueue.drain。
19. **無樣板／純現貨／emitable 頂層請求**：機器來源在 begin 查無樣板時回誠實 sim；
    submit 收到 `patternTimes` 空的機器計畫時回 `INCOMPLETE_PLAN`，接口下輪自行拉現貨或重試。
    玩家來源不得因此被拒；lpcalc 重新啟用後必須保留這個 3.13.2 slim 安全語意。
20. **建置**：`.\build-jar.bat`（JDK 21）成功、無 build-error.log、JAR 含版本；
    VERSION 升 MINOR。

---

## 14. 與勝者原設計（structured-propagation）的差異決策記錄

1. **並行偵測失敗預設 false → true（w≥2）**：三份評審一致（死鎖級）；
   「證書會抓到」的原辯護不成立——證書模擬不含它不知道的規則。
2. **取消每請求預建 fallback CraftingCalculation**：原設計常態雙份主執行緒庫存拷貝；
   改為快照期回退當場建、求解期回退走 LpFallbackQueue +1 tick（鐵則 5；
   同時杜絕背景緒建構 CraftingCalculation 的任何路徑）。
3. **SCC 淨輸出供給同計畫外部樣板：原設計允許（wait-edge 守衛）→ 全面禁止
   （SCC_FEEDS_PATTERN 回退）**：淨產環有已證實 hash 序搶料死鎖反例（1X→2X＋外部
   消費者，單序證書會誤通過）。代價：「循環 SCC 產物 → 下游樣板加工」的請求
   （如 Kroll 鈦 → 鈦板）v1 一律回退樹狀版（樹狀版對此也算不動 → 誠實擋單）。
   這是 v1 最大的功能限制，由 LpStats 的 SCC_FEEDS_PATTERN 計數決定 Phase 2
   是否以「對抗序驗證後放行」的方式（評審 mustFix 2 選項 b）解禁。
4. **audit 重放由單序改雙序**（宣告序＋對抗序），起始庫存嚴格只灌 usedItems（鐵則 4）。
5. **missing 溯源由「缺口鏈精確追蹤」粗化為全閉包粗篩**（鐵則 10）：
   多回退換實作簡單與不漏報。
6. **新增：missing 不變量（鐵則 2）與 LOOP_NO_BOOTSTRAP**（原設計會對啟動料缺口
   出 sim 計畫，被 repairPlan 錯誤沖銷）。
7. **新增：shadowVerifyOnMissing、LpFallbackQueue、hashCode 暖機、nanoTime 快照預算、
   LpStats**（吸收 min-risk-hybrid 的機制，評審一致要求）。
8. **圖模型明確化**：SCC 偵測含副產供給邊（否則 Kroll 跨樣板形永遠不成環）；
   傳播路由不含副產供給邊（副產物只走 surplus 折抵或 SCC 內部平衡）。
9. **SCC 方程邊界寫死**（評審 mustFix 4）：外部 pin 取 max、相依列代入消元、
   代入後仍欠定/奇異/負解 → SCC_UNSOLVABLE。
10. **啟動料回灌落入另一 SCC → LOOP_NESTED 回退**（原設計未定義；鐵則 7 不遞迴解環套環）。

---

## 15. 重新啟用前的整改／驗收順序

現有類別不代表已通過；以下順序應理解為「逐層修正、補測與重新審核」，不是照舊碼直接打開硬閘門。

1. `Rational`、`FallbackReason`、`LpFallbackException`、`LpConfig`、`LpStats`（無相依，先測）。
2. `LpPattern`、`LpCraftSnapshot`（快照層＋暖機＋預算）。
3. `LpGraph`（雙邊集＋Tarjan＋拓撲＋可達 bitset）→ 單元測試（Kroll 兩形狀成環）。
4. `LpLedger`、`LpDemandPropagator`（先不含 SCC：純 DAG 傳播＋surplus 守衛）→ 測試。
5. `LpCycleSolver`（閉式＋高斯＋整數化＋啟動料＋局部證書）→ 測試 §13.1-5。
6. `LpAuditor`（守恆＋雙序重放＋死角規則＋外插證書）→ 測試 §13.6-9。
7. `LpRates`＋CRAFT_LESS 迴圈 → 測試 §13.11。
8. `LpPlanBuilder`、`LpCalcTask`、`LpFallbackQueue`、`LpEntry`。
9. Mixin 兩處 diff（§10.1、§10.2）→ `.\build-jar.bat` → 遊戲內驗收 §13.13-19。
10. VERSION 升 MINOR、commit（訊息以版本開頭）。
