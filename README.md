# NL_gto_craft_fix（GTO Craft Fix）

修復 GregTech-Odyssey（GTO）整合包「合成樹超過一步就無法（正確）自動合成」的獨立 mod。
不改 GTOCore、不動 gtolib，**所有 mixin 只掛在 AE2 類上**（掛 gtocore 的類已二度實證無聲失效，
`com/gtocore/mixin` 是簽章＋Sealed jar，此路封死）。

- 環境：Minecraft 1.20.1 / Forge 47.x / Java 21 / `gtocore` **0.5.6-beta**
  （其 jarjar 內含 gtolib 26.7.4、gtceu 26.7.3、AE2-gto 15.267.4）。
- ⚠ `mods.toml` 的 `gtocore` 範圍要對著 **`0.5.6-beta`** 寫。26.7.x 是 gtolib／gtceu 的版號，
  寫成 `[26.7.4-alpha],[26.7.5-alpha]` 會讓 Forge 判定缺相依、整包起不來（3.13.2 途中實際踩過，
  jar 建得出來、測試也全過，只有真的進遊戲才會炸）。現行：`gtocore [0.5.6-beta,)`、
  `ae2 [15.267.4,)`、`minecraft [1.20.1]`、`forge [47,48)`。
- 根因分析與上游修法建議：見 [gtocraftdiag repo 的 ISSUE.md](https://github.com/dead53216/gtocraftdiag/blob/main/ISSUE.md)（本 mod 前身，同源）。
- 本檔以 `slim` 分支的實際行為為準。`lpcalc` 與完整版真缺料守衛的原始碼仍在 JAR 中，但
  `CraftingServiceSyncMixin.gtocraftfix$SLIM=true` 在外層硬停用（`-Dgtodiag.lpcalc.enabled=true`
  也繞不過）。[DESIGN-lpcalc.md](DESIGN-lpcalc.md) 是未啟用的草案，不是現行承諾。

## 凍結的四種形狀（病因互不相同）

| 形狀 | 現場特徵 | 真因 | 對應修法 |
|---|---|---|---|
| 算料不返回 | 終端 ctrl+左鍵毫無反應 | GTO 單執行緒 async 算料，多步時 Future 不返回 | 改伺服器執行緒同步 `executeV2` |
| 機器源被無限拒單 | 接口／請求器／合成卡一直 `MISSING_INGREDIENT` | `usedItems` 含「執行期才回流的中間產物」，嚴格取料必失敗；GTO 只讓有 player 的來源走 IgnoreMissing | present-once 包一層：`player()` 首呼回 present（過條件判斷），其後回 empty（取料用 machine 身分） |
| 計畫本身就不可能完成 | `waiting` 全是「等N／網0／**無任務產它**」，供應器全 `忙:0`、`results` 全 BREAK | **ISSUE.md 根因二**：算料器把「網路 0 個的批量餘數」寫進 `usedItems` 卻不排樣板 → IgnoreMissing 把它變成永遠等不到的 `waitingFor` | **計畫修補**：把缺口補成真正的樣板輪次排進同一張計畫 |
| 料齊卻完全不動 | `results={}`、零錯誤 | ①上游 `parallel==1` 取料死角（`OptimizedCraftingCpuLogic.executeCrafting:221-238` 漏了分支）②全部供應器 `isBusy()` 時直接 `continue` | ①保母把輸入補到 2 輪份，讓 GTO 走 `parallel>1` 分支 ②探針印出忙碌機器座標 |

根因二的定性實驗（2026-08-15）：把保母與修補全關、只留診斷跑一整晚，凍結案例全部收斂到根因二，
且抓到提交當下的證據——`ferrite_mixture_dust 要37/網0` 這個 37 直接變成永遠等不到的 `waitingFor`。
其餘假說都有反證：跨 CPU 誤認領（認領日誌 0 筆）、產物回流未認領（在途料一律 `網0`＝貨根本不存在）、
機器忙碌（全 `忙:0`）、並行死角（缺口多為 `0/N`，不符指紋）。

## 目前行為（slim，3.13.3）

| 機制 | 狀態 | 說明 |
|---|---|---|
| 算料同步化 | 啟用 | 見上表 |
| 機器源 present-once IgnoreMissing | 啟用 | 見上表 |
| 計畫修補（五維）| 啟用 | ①`missingItems` ②`usedItems` 批量餘數幻影 ③最終產出總量短缺 ④循環自舉缺口 ⑤內部配平。缺口直接補樣板 runs 進同一張計畫，不生新任務 |
| 並行死角解鎖 | 啟用 | 命中指紋（`min⌊庫存/每輪⌋==1` 且剩 >1 輪）時把輸入補到 2 輪份；只補料，不代推送、不碰帳 |
| 無樣板守衛 | 啟用（3.13.2）| begin 階段查無樣板 → 機器來源收到「缺完整量、零任務」的誠實 sim；玩家不擋 |
| 退化計畫拒收 | 啟用（3.13.2）| 機器來源且無任何正輪數任務 → `INCOMPLETE_PLAN`；玩家不拒 |
| 可證明的 final 交付不足拒收 | 啟用（3.13.2）| `emittedItems + 樣板產出 < final 需求` 才拒（**不計 `usedItems(final)`**：GTO 不會把開局吸入的成品送進 link）；讀不到就不擋，玩家不擋 |
| 保母餵料 | 啟用（只餵中間料）| 只補「無任務產它＋確認無樣板在途」的幻影中間料，**final key 永不餵**；預設 1 Hz |
| 帳本／探針診斷 | 啟用 | 純唯讀，見下方「診斷 log 導覽」 |
| 機器源降量重算 | **預設關** | 實證是純空轉，見「不要再走回頭路」 |
| 保母補輸入（top-up）| **預設關** | 沒有 `waitingFor` 當額度上限，實測會把單一料的全網存量吸進一顆 CPU |
| 卡死救援（取消整張單）| **預設關** | 兩種真實卡單它一種都碰不到，見「不要再走回頭路」 |
| 一般真缺料擋單 | **預設關** | slim 原則＝只修計畫、不擋單（上面三道機器源守衛是明確例外）|
| lpcalc 接管 | **預設關** | 見文末 |

> 不支援「只把 `gtocraftfix$SLIM` 改成 false」來做完整版；重新啟用前必須先完成 DESIGN 的單元與遊戲內驗收。

## 旗標

**刻意不做「一鍵全開」的總開關**——3.9.0～3.10.2 兩次退步都是多個行為綁在一起上線、出事無法定位。
每項可單獨 A/B。啟動時 log 會印 `[craftfix] 修補旗標 …` 一覽。

### 計畫修補

| 系統屬性 | 預設 | 作用 |
|---|---|---|
| `gtodiag.repairGuard` | `20000` | 缺口數上限（耗盡＝整組還原）。3.8.0 是 4000，實測有機陽／陰液「已處理 4001 項、仍剩 9 項」就整組還原 → 幻影缺口變成永遠等不到的 `waitingFor` |
| `gtodiag.repairBudgetMs` | `200` | 整次修補（含循環自舉）共用的時間預算，**真正的發散護欄**。`0`／負數＝停用；極大值飽和成無上限，不會因 `ms×1e6` 溢位而每次立即超時 |
| `gtodiag.repairRunCap` | `2000000` | 可新增總輪數的**底線** |
| `gtodiag.repairRunFactor` | `4` | 比例項：實際上限 = min(`runHardCap`, max(`runCap`, 原計畫總輪數 × 本係數))。`0`＝停用比例項 |
| `gtodiag.repairRunHardCap` | `50000000` | 比例上限的絕對天花板 |
| `gtodiag.repairDeficitSrc` | `on` | 缺口沖銷只准動真的來自 `usedItems` 的帳：`on`＝完整來源判定／`clamp`＝不分來源但不寫負值／`off`＝3.8.0 原樣 |
| `gtodiag.repairStrictRounds` | `false` | 外圈 4 輪後仍含 hard 缺口一律整組還原；確定全為 soft 自舉猜測時預設只 WARN 並送出已修計畫，設 `true` 才連全 soft 也還原 |
| `gtodiag.bootstrapMaxPass` | `200000` | 循環自舉的 pass 上限（與修補共用 deadline）。撞 pass 上限只跳過本次自舉判定、保留已完成的硬缺口修補；撞時間預算才整組中止還原 |
| `gtodiag.repairUpdateBytes` | `false` | 依新增輪次等比例調高 `plan.bytes()`（開啟後原本擠得上小 CPU 的計畫會改吃 `CPU_TOO_SMALL`）|
| `gtodiag.repairNetSpot` | `false` | 缺口優先吃網路現貨（否則一律排樣板）|
| `gtodiag.repairBalance` / `…OnAbort` / `…Log` | `false` / `false` / `true` | 第五維內部配平：補齊／中止時也補齊／只觀測不補 |
| `gtodiag.repairBlockOnAbort` | `off` | 中止後擋機器源提交：`off`／`on`（slim 不生效）／`force` |
| `gtodiag.repairAbortBroadcast` | `false` | 擋單時聊天室廣播（**對全伺服器玩家**送出，多人會洗頻）|
| `gtodiag.repairFreezeProbe` | `false` | 不擋單也跑「必凍」判定並留 log |

> ⚠ 預設值 ≠ 3.8.0：`repairDeficitSrc=on` 與 `bootstrapMaxPass=200000` 刻意不同，都是修 3.8.0 自己的 bug。
> 要純 3.8.0 對照組：`-Dgtodiag.repairGuard=4000 -Dgtodiag.repairBudgetMs=0 -Dgtodiag.repairDeficitSrc=off`
> `-Dgtodiag.bootstrapMaxPass=2147483647 -Dgtodiag.repairBalanceLog=false`

### 機器源降量重算（預設關，僅供明確 A/B）

| 系統屬性 | 預設 | 作用 |
|---|---|---|
| `gtodiag.machineDownscale` | `false` | 開啟砍半重算；玩家來源不受影響 |
| `gtodiag.machineDownscaleBudgetMs` | `50` | 同一次工作全部重算共用的時間預算；`0`＝不設上限 |
| `gtodiag.machineDownscaleCooldownSec` | `600` | 同一 grid／requester／key 的重試冷卻 |

### 執行期救援

修補只在 `submitJob` 那一瞬間跑，**下單後才長出來的缺口它看不到也補不到**。

| 系統屬性 | 預設 | 作用 |
|---|---|---|
| `gtodiag.sitterFeed` | `true` | 把網路現貨補進 CPU 的**中間料** `waitingFor` 缺口 |
| `gtodiag.sitterFeedPhantomOnly` | `true` | 只餵幻影中間料（無剩餘任務產它＋明確確認無樣板在途）。pending 反射未知或已有在途一律不餵；設 `false` 只是略過 task-output 條件，pending 仍須明確為無、final 仍禁餵 |
| `gtodiag.sitterTopUp` | `false` | 保母補輸入（見上表，維持停用）|
| `gtodiag.stallCancel` | `false` | 卡死救援（見下節）|
| `gtodiag.stallCancelSec` / `…CooldownSec` / `…Broadcast` | `300` / `600` / `true` | 零進度秒數／同顆 CPU 冷卻／救援時廣播 |

## 不要再走回頭路（實證結論）

每一條都有實錄，改動前先讀。

- **修一半比不修更糟 → 全有全無**（3.8.0）。舊版 `guard++ < 96` 讓大電路第一輪就撞牆，17 次修補 7 次貼著 96 停：
  輪次加了、輸入沒補＝計畫內部不平衡＝必凍（ZPM 電路實錄：epoxy／quantum_processor／lubricant／ceramics_dust
  全標「本單無人產」，網路各有 1448／4／122903／95 卻進不去，CPU 靜止 3 分鐘）。任一上限耗盡即整組還原。
- **固定輪數上限是錯的門檻**（3.13.0）。UHV 通用電路 112 萬輪的計畫只超上限 **0.56%** 就整組還原 → 17 種
  「網存 0 且無任務產它」→ 靜止 600s+。改成跟著計畫規模縮放；發散護欄交給時間預算，不是輪數。
- **「補樣板」這條路只能由算料器做，修補這層只補得起網路現貨**（3.9.0 回退）。把配平缺口丟回缺口佇列
  會製造新輸入需求 → 新缺口 → 再補，遞迴發散：glowstone 缺口 6 輪內從 147456 膨脹到 3833856，
  LUV 交付從 466/500 掉到 **8/500**。配平模型也必須用**供給扣除法**（逐槽分配、用掉就扣）；
  「取供給最多的變體」會自我增強，是發散的另一半原因。
- **「中止就擋單」會擋掉正常計畫**（3.10.0 → 3.10.1 修正）。157 任務／217 萬輪的正常 UHV 計畫被每 10 秒
  擋一次（玩家手動做得起來、機器永遠下不了單）。擋單條件必須是「還原後的計畫必凍」——
  `usedItems` 要的量網路給不出來 **且** 計畫裡沒有任何任務會產它。
- **配平補齊不該被中止連坐**（3.10.2）。補齊只吸網路現貨、有界又便宜；順序必須是
  還原 → 補齊 → 再判擋不擋（還原排在補齊之後會把補進 `usedItems` 的現貨洗掉）。
- **成品不可由本 mod 搬進 CPU**。GTO 沒有「把開局吸入的成品交給 link」的步驟，吸進去只會抱著現貨永凍
  （NAND 625 實錄）。所以：缺口吃網路現貨時成品例外、保母 final key 永不餵、final 交付量不計 `usedItems(final)`。
- **機器源降量重算＝純空轉**（3.13.2 改預設關）。`2026-08-16-7` 那場 182 次降量：`fermented_biomass`
  從 1000000 一路砍到 244 共 13 級全部真的上機，`[帳本] 單離場` **無一例外「存活 1s／推送 0 輪／交付 0」**——
  174 張單 0 交付；`rocket_fuel_h8n4c2o4` 1000000000→62500000 同形。每次砍半迴圈要多付最多 12 趟
  `executeV2`（跑主緒），換到的是純空轉。空轉迴圈的閉環：請求器判定 `網路現貨+在途 < 目標量` 對
  「產出即被抽乾」的物品**永遠成立** → 每 10 秒送一次 batch → 降量硬開單 → 配方時長沒到就收單離場 → 網存回不去。
- **卡死救援（取消整張單）碰不到真實卡單**（3.13.1 改預設關）。2026-08-19 實測兩種卡單：
  控制倉三道全過卻卡在「無樣板在途」這道一票否決（那筆推送已 21 分鐘零進度，早就不是在途而是弄丟了）；
  密銀每 4~6 秒開一張新單、每張活 2 秒，零進度計時器永遠累積不到。現階段只有誤殺風險、沒有實際效益。
- **不做「執行期補單」**。那等於巢狀／代下合成請求，歷史上兩度觀察到它滾出巨量碎單拖垮伺服器。
  只搬網路現貨，不代下合成請求。
- **`getGtocore$allocations` 不存在，別再加**（3.13.2 移除）。反編譯 `gtocore-0.5.6-beta` 證實沒有這組
  accessor；`allocations` 是 `com.gtocore.api.ae2.crafting.ExecutingCraftingJob` 的執行期欄位，
  `CraftingPlan` 上無物可清。那段反射恆為 no-op。
- **搬料對帳不可用 before/after 全網 SIMULATE 差額**（3.13.2 改掉）。`CraftingServiceStorage` 以
  `Integer.MAX_VALUE` 最高優先權掛在網路上，回補的貨只要有 CPU 在等就當場被認領、不會進可查詢庫存 →
  `after==before` → 差額 0 ≠ 回傳量 → 第一次回補就永久隔離整張 grid，而回補的觸發條件恰好就是
  「有 CPU 在等這個 key」＝保證誤觸。改以 AE2 契約回傳值為準，順帶省掉每次搬料兩趟全網 SIMULATE。
  只有 mutation 真的拋例外且差額也推不出來、或回傳值越出 `[0, requested]`，才進隔離。
- **mixin 只准掛 AE2 類**（2.2.0 實測、2.3.0 撤除）。掛 `OptimizedCraftingCpuLogic` 無聲失效：jar 內有類、
  config 正常載入、同檔 AE2 mixin 照常運作、全程零錯誤，但欄位普查證實注入欄位不存在
  （gtocore 為簽章 jar、`com/gtocore/mixin` 標 `Sealed: true`）。
- **`com.gtocraftfix.mixin` 底下只能放 mixin 本身，不能放工具類**（3.13.2 踩、3.13.3 修）。
  那是 `gto_craft_fix.mixins.json` 宣告的 mixin package，Mixin 禁止直接參照裡面的類：
  `IllegalClassLoadError: com.gtocraftfix.mixin.CraftingHotfixSupport is in a defined mixin package
  … and cannot be referenced directly`，在 `CraftingService.<clinit>` 就炸，**世界完全載不進去**。
  `gradlew build` 與單元測試都抓不到（編譯期一切正常，是 classload 期才炸）。工具類請放
  `com.gtocraftfix.support` 之類的獨立 package 並開放為 public。
  <br>反過來說，**mixin 自己的巢狀類是安全的**：Mixin 0.8.5 的 `InnerClassGenerator` 會把它們重定位到
  目標類（`getUniqueReference` 對匿名 `^[0-9]+$` 與具名內部類走同一條 `%s$%s$%s` 路徑），本 mod 的
  匿名 `$1`／`$2` 也已在正式環境跑了十幾個版本。
- **兩道機器源守衛在 slim 啟用是有依據的**（3.13.2）。翻過整個 `logs/`：`無樣板，擋下機器源請求`
  全歷史 5 場觸發，近期 6 筆全是真的沒編樣板的料；唯一可疑的 `universal_circuit` 批次出現在 v1.1.0 那場，
  **該場對 universal_circuit 的提交是 0 筆**（樣板當時還沒編），之後每場都正常出 157 種任務、守衛未再誤擋。
  `退化計畫（無合成任務）` 那行舊版在 slim 也會印，**全歷史命中 0 次**；前移到 `submitJob` HEAD 也不吃掉
  修補機會——零任務計畫的缺口必然無樣板可補，修補對它本來就是 no-op。

## 診斷 log 導覽

`com.gtocraftfix.diag.CraftDiag`，**純唯讀**。核心是每 tick 對每顆有單的 CPU 驗一條不變量：

```
Δ庫存(k) + Δ在途(k) + Δ已交付(k=成品) == 產出(k) − 消耗(k) + 本 mod 自補(k)
    產出/消耗 = 本 tick 推掉的樣板輪數 × 每輪產出／每輪輸入
```

推送＝庫存↓在途↑、交付＝在途↓庫存↑，所以**任何違反都是帳外流動**（多吃、被別顆 CPU 領走、
產出沒掛帳、貨被抽走）。逐 key 累加成「累計帳外差額」，凍結時一併報出。

| log 前綴 | 內容 |
|---|---|
| `[提交]` | 來源（玩家／機器＋座標）、sim、bytes、任務種數／總輪數、used/missing/emitted、**第幾次下單／距上次幾秒**（抓請求器反覆重下單）|
| `[計畫]` | 計畫出生留底：任務清單（產物×輪數）、usedItems、missingItems |
| `[開單帳本]` | 提交返回後對帳「計畫 usedItems vs CPU 實吸庫存＋掛上的在途」。差額不落在任一邊＝取料階段吞掉；落在在途＝正常 IgnoreMissing。**「開單即缺」是沒取到還是沒記帳，只有這裡分得出來** |
| `[帳本] 新單上機／單離場` | 上機當下的完整狀態；離場印存活秒數、累計推送輪數、**訂N/交付M**、剩餘輪／在途／庫存／待交付與累計帳外差額 |
| `[帳本] 對不上` | 不變量違反：庫存Δ／在途Δ／交付／自補／應為（產N-吃M）／差額＋本 tick 推了哪些樣板。同顆 CPU 20 tick 內只印一行 |
| `[帳本] 任務新增／輪數倒增` | 執行中計畫被擴張（該 tick 對帳失真故跳過並重置凍結計時）；正常做完移除的任務按「剩餘輪數歸零」完整入帳 |
| `[帳本] 交付` | 每 5 秒（有交付才印）：近 5 秒／累計交付／下單總量／待交付——分辨「慢」與「停」|
| `[帳本] **提前收單**` | 離場時 `交付 < 下單量` 直接點名差多少 |
| `[警報]` | 不必等 60 秒：**料齊卻不推**（料夠 ≥1 輪、供應器不忙卻 10 秒沒推）、**在途沒回**（掛在途 ≥2 分鐘且網存 0）|
| `[一覽]` | 每 30 秒一行：每顆 CPU 的 剩輪／在途／庫存／待交付／**近 30 秒推了幾輪**／靜止幾秒 |
| `[凍結]` | 靜止 60 秒觸發（之後每 5 分鐘重播）：逐任務全部輸入格 have/need＋網存＋在途＋誰產它＋替代數，供應器 prov/忙/座標，可跑幾輪；在途明細（等N／網M／等了Ns／另K顆也等／推給座標／無任務產它）；**饑餓鏈根源**；最後一行 **判定** |
| `[認領]` | `<key> x<量> 交付：N顆 CPU 同時在等 → …`，候選 ≥2 才印。AE2 的 `insertIntoCpus` 對 `craftingCPUClusters`（HashSet，順序任意）逐顆 `insert`，**不認這批貨是誰訂的**——「A 訂的貨被 B 領走」由此而生 |

判定分類：CPU 被暫停／料齊卻不推（`parallel==1` 死角、供應器沉默）／樣板失聯（`prov:0`）／
供應器全忙／缺料鏈斷在根／推出去沒回來。

系統屬性：`-Dgtodiag.ledger=false` 整組關閉、`-Dgtodiag.diagLines=N` 行數上限（預設 40000）、
`-Dgtodiag.stallTicks` / `-Dgtodiag.stallRepeat` / `-Dgtodiag.overviewTicks` 調間隔。
另有內部微調旗標，正常不必動、要用時直接看宣告處（`CraftDiag` 與 `CraftingServiceSyncMixin` 檔頭）：
`linkVerdict`（true）、`auditOnChange`（true）、`strictAltInputs`（false）、`stateGc`（true）、
`gcIdleTicks`（200）、`submitCap`（256）、`topupRounds`（0＝不限，top-up 開啟時的每輪收斂上限）。

**兩個不可用的欄位**（3.13.1 記錄，別再被誤導）：

- **「累計交付N」**：由每 tick 的待交付差分累加而成，加總後恆等於「首次待交付 − 離場待交付」，
  單在同一 tick 內收單就必印 0。跟「推送N輪」是同一條差分、同一個盲點，**不能拿來證明「沒交付」**。
- **「某訊息今天 0 筆」**：受 `diagLines` 統管，降量重算與保母另共用 200 行額度。額度燒完後靜音，
  **沒印 ≠ 沒發生**。

## 建置

```bat
.\build-jar.bat
```

JDK／JRE 21。產物在 `dist`（鏡射到 `..\_NL_mod\1.20.1\forge`），檔名
`NL_gto_craft_fix-forge-1.20.1-<版本>.jar`。同一個 `mods/` 只能放一個版本，並須移除舊的
gtocraftdiag JAR（相同 modId 或重複 mixin 會阻止啟動）。

`build` 會一併跑 JUnit 5 純 Java 測試（不得啟動 Minecraft）；單獨跑：`cd 1.20.1\forge && .\gradlew.bat test`。

`gto_craft_fix.mixins.json` 暫留 `JAVA_17`：Forge 47 內建的 Mixin 0.8.5 不認得 `JAVA_21` 這個
compatibility 名稱。它是 Mixin 語言功能基線，不是執行期版本宣告——真正的 class target 與
`mods.toml` 的 Java feature 都是 21。

## lpcalc（未啟用）

`com.gtocraftfix.lpcalc` 與 `com.gtocraftfix.calc` 都會編進 JAR，但 slim 讓機器來源略過 lpcalc、
直接走同步 `executeV2`。因此 `gtodiag.lpcalc.enabled` 只是**內層** kill switch（設 true 也不會啟用），
shadow／budget／maxKeys 與 `[craftfix][lp]` 統計在 slim 都不可達。
[DESIGN-lpcalc.md](DESIGN-lpcalc.md) 裡的 CRAFT_LESS／SCC／純現貨計畫等敘述是**未來規格**，
未完成其中的自動測試與遊戲內驗收前不得當成現行保證。

## 授權

**檔案級混合授權**：原創檔案為 MIT；`com.gtocraftfix.calc` 中保留 Applied Energistics 2
著作權／授權標頭的衍生檔案，依各檔標頭為 LGPL-3.0-or-later。

- `LICENSE-MIT.txt`：原創檔案的 MIT 全文
- `COPYING` / `COPYING.LESSER`：GNU GPLv3／LGPLv3 canonical 全文
- `NOTICE`：AE2／AlgorithmX2／TeamAppliedEnergistics 衍生來源與檔案範圍歸屬

建置會把上述檔案自動放進 JAR 的 `META-INF/`。各原始檔的既有標頭與 `NOTICE` 的逐檔規則優先，
**不得把整個 JAR 簡化宣稱為單一 MIT 授權**。
