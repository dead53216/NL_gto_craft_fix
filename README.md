# NL_gto_craft_fix（GTO Craft Fix）

修復 GregTech-Odyssey（GTO）整合包「合成樹超過一步就無法（正確）自動合成」的獨立 mod。
不改 GTOCore、不動 gtolib，全部修正掛在 AE2 類上。

- 環境：Minecraft 1.20.1 / Forge 47.x / Java 21 / `gtocore` **0.5.6-beta**（其 jarjar 內含 gtolib 26.7.4、
  gtceu 26.7.3、AE2-gto 15.267.4）。
  ⚠ **`mods.toml` 的 `gtocore` 版本範圍要對著 `0.5.6-beta` 寫，不是 26.7.x**：26.7.x 是 gtolib／gtceu 的版號，
  gtocore 自己的 `[[mods]] version` 是 `0.5.6-beta`。寫成 `[26.7.4-alpha],[26.7.5-alpha]` 會讓 Forge 判定缺相依、
  整包起不來（3.13.2 開發途中實際踩過）。現行：`ae2 [15.267.4,)`、`gtocore [0.5.6-beta,)`、`minecraft [1.20.1]`、
  `forge [47,48)`。
- 根因分析與上游修法建議：見 [gtocraftdiag repo 的 ISSUE.md](https://github.com/dead53216/gtocraftdiag/blob/main/ISSUE.md)（本 mod 前身，同源）

> **本檔以目前 `slim` 分支的實際行為為準。** `lpcalc` 與完整版的一般真缺料守衛原始碼仍在 JAR 中，
> 但 `CraftingServiceSyncMixin.gtocraftfix$SLIM=true` 會在外層硬停用它們；設定
> `-Dgtodiag.lpcalc.enabled=true` 也不能繞過此閘門。[DESIGN-lpcalc.md](DESIGN-lpcalc.md)
> 是尚未啟用的完整版草案，不是目前執行路徑的承諾。

## 目前 slim 行為（3.13.2）

- 啟用：伺服器執行緒同步 `executeV2`、機器源 present-once IgnoreMissing、計畫修補、並行 `parallel==1`
  死角解鎖、機器來源無樣板守衛、退化計畫拒收，以及帳本／探針診斷。
- 機器來源在 begin 階段查無樣板時直接回「缺完整請求量、零任務」的誠實 sim；若提交階段仍收到
  `patternTimes` 空的退化計畫，回 `INCOMPLETE_PLAN`。這兩道只拒機器來源，玩家請求不拒。
- 修補後若能明確證明 `emittedItems + 樣板產出` 仍少於 final 需求，機器單同樣回
  `INCOMPLETE_PLAN`；驗證本身讀取失敗時不誤擋，玩家提交也維持原流程。
- 執行期保母只可補 `waitingFor` 的**中間料**；最終成品 key 一律禁止餵入，避免 GTOCore
  在 link 未全收時仍把完整送入量從 `remainingAmount` 扣除而提前收單。預設仍只掃幻影中間料。
- 預設停用：機器源降量重算、lpcalc 接管、一般真缺料擋單（不含上述可證明的 final 交付不足）與輸入 top-up；`repairBlockOnAbort=force`
  等明確除錯旗標不在此概述內。
- 不支援只改 `gtocraftfix$SLIM` 常數來製作「完整版」；重新啟用前必須完成 DESIGN 的單元與遊戲內驗收。

## 根因二實證（2026-08-15，slim 對照實驗）

把保母與計畫修補全部關掉、只留診斷跑一整晚，凍結案例全部收斂到 **ISSUE.md 根因二**（`executeV2` 把
「網路中數量為 0 的批量餘數」寫進 `usedItems` 且不為其安排樣板運行），且首次抓到**提交當下**的證據：

```
22:33:36 提交  [craftfix] 開單即缺（計畫未排生產）out=universal_circuit_zpm x100
                → gtceu:ferrite_mixture_dust 要37/網0
22:35:58 探針  gtceu:ferrite_mixture_dust(等37/網0/無任務產它)
               gtceu:polyvinyl_chloride_foil(等2/網0/無任務產它)
               gtocore:tungsten_tetraboride_ceramics_brick(等96/網0/無任務產它)
               剩餘任務全部被自己的輸入餓死、供應器全 忙:0、results 全 BREAK
```

同一個數字（37）從「計畫以為網路有」直接變成「永遠等不到的 `waitingFor`」。同場另兩張單同構
（qbit 晶片：雙酚A 74000／表氯醇 10000／電子級矽 16000 全標「無任務產它」）。

**排除的其他假說**（都有量測佐證，不是推測）：

| 假說 | 反證 |
|---|---|
| 跨 CPU 誤認領（`insertIntoCpus` 對 HashSet 任意序發貨）| 認領日誌（候選 ≥2 才印）**0 筆**；在途料幾乎都只有本 CPU 在等 |
| 產物回流網路未觸發認領 | 在途料一律 `網0`——貨根本不存在，不是沒被認領 |
| 機器忙碌／吞樣板 | 供應器全 `忙:0`，且缺的料本單**零任務會產出**（`無任務產它`）|
| 並行死角（`parallel==1`）| 缺口多為 `0/N`，連一輪都不到，不符指紋 |

**結論**：凍結源自**計畫本身**，與執行器、認領、機器現場無關。上游正解＝gtolib 批量餘數向上取整
（多排一次樣板）；mod 這層的等效根治＝**計畫修補**把缺口補成真正的樣板輪次排進同一張計畫——
因此 3.3.0 當時在 slim 重新啟用它（該次對照期間保母／lpcalc 維持關閉，證明「不靠補料也能解」）。

## 帳本診斷（3.7.0，`com.gtocraftfix.diag.CraftDiag`，純唯讀）

「出生收支平衡、跑一跑就短缺」的漂移要抓在**每一 tick**。帳本對每顆有單的 CPU 記
{剩餘輪數、CPU 庫存、在途、待交付}，用一條可證的不變量比對：

```
Δ庫存(k) + Δ在途(k) + Δ已交付(k=成品) == 產出(k) − 消耗(k) + 本 mod 自補(k)
    產出/消耗 = 本 tick 推掉的樣板輪數 × 每輪產出／每輪輸入
```

推送＝庫存↓在途↑、交付＝在途↓庫存↑，所以**任何違反都是帳外流動**（多吃、被別顆 CPU 領走、
產出沒掛帳、貨被抽走）。逐 key 累加成「累計帳外差額」，凍結時一併報出。

| log 前綴 | 內容 |
|---|---|
| `[craftfix][提交]` | 每次 submitJob：來源（玩家/機器＋座標）、sim、bytes、任務種數/總輪數、used/missing/emitted 種數與總量、**第幾次下單／距上次幾秒**（抓請求器反覆重下單）|
| `[craftfix][計畫]` | 計畫出生留底：任務清單（產物×輪數）、usedItems、missingItems |
| `[craftfix][開單帳本]` | 提交返回後對帳：**計畫 usedItems vs CPU 實吸庫存＋掛上的在途**。差額不落在任一邊＝取料階段直接吞掉；落在在途＝正常 IgnoreMissing。**「開單即缺」到底是沒取到還是沒記帳，只有這裡分得出來** |
| `[craftfix][帳本] 新單上機／開局在途明細／開局庫存明細` | 上機當下的完整狀態（開局在途＝提交當下的 waitingFor）|
| `[craftfix][帳本] 對不上` | 不變量違反：印該 key 的 庫存Δ／在途Δ／交付／自補／應為（產N-吃M）／差額，附本 tick 推了哪些樣板。同一顆 CPU 20 tick 內只印一行、其餘計數 |
| `[craftfix][帳本] 任務新增／輪數倒增` | 執行中計畫被擴張（會使該 tick 的對帳失真，故跳過該 tick 並重置凍結計時）；正常做完後移除的任務則按「剩餘輪數歸零」完整入帳 |
| `[craftfix][帳本] 交付` | 每 5 秒（有交付才印）：近 5 秒交付量／累計交付／下單總量／待交付——分辨「慢」與「停」|
| `[craftfix][帳本] 單離場` | 存活秒數、累計推送輪數、**訂N/交付M**、剩餘輪／在途／庫存／待交付；剩餘>0 即「沒做完就離場」，並補印累計帳外差額 |
| `[craftfix][帳本] **提前收單**` | 單離場時 `交付 < 下單量` 直接點名差多少（1.8.x 雙重銷帳型錯誤的自動檢出）|
| `[craftfix][警報]` | 不必等 60 秒：**料齊卻不推**（某樣板料夠 ≥1 輪、供應器不忙卻 10 秒沒推＝parallel==1 死角／樣板失聯／供應器全忙，逐項判定）、**在途沒回**（某 key 掛在途 ≥2 分鐘且網存 0，附另幾顆也等／推給座標）|
| `[craftfix][一覽]` | 每 30 秒一行：每顆 CPU 的 剩輪/在途/庫存/待交付/**近 30 秒推了幾輪**/靜止幾秒——分辨「慢」與「死」 |
| `[craftfix][凍結]` | 靜止 60 秒觸發（之後每 5 分鐘重播）：逐任務**全部輸入格** have/need＋網存＋在途＋誰產它＋替代數，供應器 prov/忙/座標，可跑幾輪；在途明細（等N/網M/等了Ns/另K顆也等/推給座標/無任務產它）；庫存；累計帳外差額；**饑餓鏈根源**（一路往上游追到「本單無人產」或「料齊卻沒動」）；最後一行 **判定** |

判定分類：CPU 被暫停／**料齊卻不推**（parallel==1 死角、供應器沉默）／**樣板失聯**（prov:0）／
**供應器全忙**／**缺料鏈斷在根**／**推出去沒回來**。

系統屬性：`-Dgtodiag.ledger=false` 整組關閉、`-Dgtodiag.diagLines=N` 行數上限（預設 40000）、
`-Dgtodiag.stallTicks` / `-Dgtodiag.stallRepeat` / `-Dgtodiag.overviewTicks` 調間隔。

## 修補旗標（3.11.0，預設＝3.8.0 行為）

3.9.0～3.10.2 四個版本裡有兩次實測退步（3.9.0 讓 LUV 電路交付從 466/500 掉到 **8/500**；3.10.0 把
157 任務／217 萬輪的**正常** UHV 計畫每 10 秒擋一次）。原因是多個行為綁在一起上線，出事無法定位。
3.11.0 把三個上限退回 3.8.0 值、3.9–3.10 新增的行為**程式碼保留但預設關閉**，每項可單獨開關以便遊戲內 A/B。
**刻意不做「一鍵全開」的總開關**——那正是釀成退步的做法。啟動時 log 會印 `[craftfix] 修補旗標 …` 一覽。

| 系統屬性 | 預設 | 作用 |
|---|---|---|
| `gtodiag.repairGuard` | `20000`（3.11.1）| 修補迴圈可處理的缺口數上限（耗盡＝整組還原）。3.8.0 是 4000，實測有機陽液／陰液「已處理 4001 項、仍剩 9 項」就整組還原→幻影缺口變成永遠等不到的 `waitingFor` |
| `gtodiag.repairRunCap` | `2000000` | 修補可新增的總輪數上限**底線**（3.8.0 值）|
| `gtodiag.repairRunFactor` | `4`（3.13.0）| 上限的比例項：實際上限 = min(`runHardCap`, max(`runCap`, 原計畫總輪數 × 本係數))。設 `0` ＝停用比例項、退回純固定上限 |
| `gtodiag.repairRunHardCap` | `50000000`（3.13.0）| 比例上限的絕對天花板 |
| `gtodiag.repairBudgetMs` | `200`（3.11.1）| 整次修補共用的時間預算，包含循環自舉；用 `0`／負數停用時間上限，極大值會飽和成無上限，不再因 `ms×1e6` 溢位而每次立即超時 |
| `gtodiag.repairDeficitSrc` | `on`（3.12.0）| 缺口沖銷只准動「真的來自 usedItems」的那本帳：`on`＝完整來源判定／`clamp`＝不分來源但不寫負值／`off`＝3.8.0 原樣 |
| `gtodiag.repairStrictRounds` | `false` | 外圈 4 輪後若仍含 hard 真實缺口，一律整組還原；確定全為 soft 自舉猜測時，預設只 WARN 並送出已修計畫，設成 `true` 才連全 soft 也還原 |
| `gtodiag.repairUpdateBytes` | `false` | 依新增輪次等比例調高 `plan.bytes()`（開啟後原本擠得上小 CPU 的計畫會改吃 `CPU_TOO_SMALL`）|
| `gtodiag.bootstrapMaxPass` | `200000` | 循環自舉模擬的 pass 上限，且每輪共用 `repairBudgetMs` deadline。碰到 pass 上限會明確警告並只跳過本次自舉判定（保留已完成的硬缺口修補）；碰到共用時間預算才整組中止並還原 |
| `gtodiag.repairNetSpot` | `false` | 缺口優先吃網路現貨（否則一律排樣板）|
| `gtodiag.repairBalance` | `false` | 內部配平缺口用網路現貨補齊（第五維）|
| `gtodiag.repairBalanceOnAbort` | `false` | 配平補齊在「修補中止」時也照做（僅 `repairBalance=true` 時有意義）|
| `gtodiag.repairBalanceLog` | `true` | 即使不補齊也照算一次配平缺口並印 log（純唯讀觀測）|
| `gtodiag.repairBlockOnAbort` | `off` | 中止後擋下機器源提交：`off`／`on`（slim 分支自動不生效）／`force` |
| `gtodiag.repairAbortBroadcast` | `false` | 擋單時聊天室廣播缺料（**對全伺服器玩家**送出，多人會洗頻）|
| `gtodiag.repairFreezeProbe` | `false` | 不擋單也跑「必凍」判定並留 log |

> ⚠ **3.12.0 的預設值不等於 3.8.0**：`repairDeficitSrc=on`（不再把「從沒加進 usedItems 的量」倒扣成負值）
> 與 `bootstrapMaxPass=200000`（自舉模擬有上限）兩項刻意不同，都是修 3.8.0 自己的 bug。
> 要做純 3.8.0 對照組請加：
> `-Dgtodiag.repairGuard=4000 -Dgtodiag.repairBudgetMs=0 -Dgtodiag.repairDeficitSrc=off`
> `-Dgtodiag.bootstrapMaxPass=2147483647 -Dgtodiag.repairBalanceLog=false`

## 機器源降量重算旗標（3.13.2）

降量重算會把原本誠實的 CRAFT_LESS 缺料結果硬開成短命單，並與請求器重下單形成吸料空轉，
所以 3.13.2 起預設關閉。

**實測依據（`logs/2026-08-16-7.log.gz`，同一場 182 次降量）**：`gtceu:fermented_biomass` 從 1000000 一路砍到
244（500000／250000／125000／62500／31250／15625／7812／3906／1953／976／488／244），每一級都真的上機，
而 `[帳本] 單離場` 顯示 **無一例外都是「存活 1s／推送 0 輪／交付 0」**——174 張單、0 交付。
同場的 `gtocore:rocket_fuel_h8n4c2o4` 1000000000→62500000 也是同樣形狀。也就是說砍半迴圈每次要多付最多
12 趟 `executeV2`（跑在伺服器主緒），換到的是純粹的空轉，**沒有任何一次讓貨真的做出來**。

只在明確 A/B 時暫時開啟：

| 系統屬性 | 預設 | 作用 |
|---|---|---|
| `gtodiag.machineDownscale` | `false` | 開啟機器來源的砍半重算；玩家來源不受影響 |
| `gtodiag.machineDownscaleBudgetMs` | `50` | 同一次工作全部重算共用的時間預算；`0` 代表不設時間上限 |
| `gtodiag.machineDownscaleCooldownSec` | `600` | 同一 grid／requester／key 再次嘗試的冷卻秒數 |

## 兩道機器源守衛在 slim 啟用的依據（3.13.2）

`無樣板守衛` 與 `退化計畫拒收` 在 2.x～3.13.1 的 slim 是「只印 log、不拒單」，3.13.2 起真的拒。依據是把
整個 `logs/` 目錄的歷史紀錄翻出來對過：

- **無樣板守衛**：`無樣板，擋下機器源請求` 全歷史共 5 個 session 觸發。近期（08-17～08-20）只有 6 筆，
  分別是 `fermented_biomass`／`mithril`／`thorium`／`iron`／`epoxy_printed_circuit_board`——都是真的沒編樣板的
  料。唯一看起來可疑的是 v1.1.0 那場（08-14）一次噴出 `universal_circuit_ulv`～`zpm`，但**同一場的
  `[craftfix][提交]` 對 universal_circuit 是 0 筆**（那時還沒編這些樣板）；等樣板編好之後的每一場，
  universal_circuit 都是正常出 157 種任務的計畫，守衛一次都沒再誤擋。→ **無實證的偽陽性**。
- **退化計畫拒收**：舊版在 slim 也會印 `退化計畫（無合成任務）…（slim：不拒單，僅紀錄）`，
  **全歷史 log 命中 0 次**。3.13.2 把它前移到 `submitJob` HEAD（修補之前）也不會吃掉修補機會：零任務計畫的
  缺口只會是「沒樣板可補」（有樣板算料器就會排任務），修補對它本來就是 no-op，提早拒掉只是省下白跑一輪。

## 執行期救援旗標（3.13.0）

修補只在 `submitJob` 那一瞬間跑，**下單後才長出來的缺口它看不到也補不到**。3.13.0 補上執行期的兩道。

| 系統屬性 | 預設 | 作用 |
|---|---|---|
| `gtodiag.sitterFeed` | `true` | 保母餵料：把網路現貨補進 CPU 的**中間料** `waitingFor` 缺口（slim 自 2.x 停用，3.13.0 重開；3.13.2 起 final key 永不餵）|
| `gtodiag.sitterFeedPhantomOnly` | `true` | 只餵**幻影中間料 key**（無剩餘任務產它＋明確確認無樣板押在供應器上）。pending 反射未知或已有在途一律不餵；設 `false` 只會略過 task-output 條件，pending 仍須明確為無且 final 仍禁餵 |
| `gtodiag.sitterTopUp` | `false` | 保母補輸入（把剩餘任務的輸入補進 CPU 庫存）。**維持停用**：它沒有 `waitingFor` 當額度上限，實測會把單一料的全網存量吸進一顆 CPU |
| `gtodiag.stallCancel` | **`false`**（3.13.1 改）| 卡死救援：零進度 ＋ 證明等不到貨 → 取消整張單。**預設關閉**，理由見下方「3.13.1」 |
| `gtodiag.stallCancelSec` | `300` | 判定卡死所需的零進度秒數 |
| `gtodiag.stallCancelCooldownSec` | `600` | 同一顆 CPU 兩次救援的最短間隔（防取消→重下→再卡的高頻空轉）|
| `gtodiag.stallCancelBroadcast` | `true` | 救援時聊天室廣播（玩家單不會自動重下，不廣播＝無聲吞單）|

## 3.13.2：安全邊界修正

- 最終成品供給只計 `emittedItems + 樣板產出`，不再把 `usedItems(final)` 當成可交付量；
  GTOCore 不會把 CPU 開局吸入的成品送進 link。修補後仍可證明不足的機器單會拒收；玩家不擋，
  驗證讀取失敗也不把未知誤判為不足。
- 即使初始 deficits 為空，也會繼續執行循環自舉、第五維配平／觀測、最終供給與退化計畫檢查。
- 修補算術改成飽和運算；`repairBudgetMs` 與 bootstrap 共用同一 deadline，超時整組還原。
  ⚠ 開發途中一度加了「修補後清除 GTO 過時 `allocations`」的反射（`getGtocore$allocations`／`set…`），
  **已移除**：反編譯 `gtocore-0.5.6-beta` 證實根本沒有這組 accessor，`allocations` 是
  `com.gtocore.api.ae2.crafting.ExecutingCraftingJob` 的欄位、在執行期才由 job 自己建，`CraftingPlan` 上
  沒有可清的東西。那段是恆為 no-op 的臆測 API，別再依「GTO 可能有」重新加回來。
- 機器來源的無樣板請求回誠實 sim，機器提交的空 `patternTimes` 計畫一律拒收；玩家來源不拒。
- 機器源降量重算預設關閉，僅供帶共用時間預算與同 grid／requester／key 冷卻的明確 A/B。
- 保母永不餵 final key；非 final 的 task／pending 證據只要未知就 fail-closed。CPU insert 例外或部分拒收
  會先回補網路，回補失敗量留帳重試；清完回補帳以前不再抽新料。
- 網路搬料對帳以 **AE2 契約的回傳值**為準（`MEStorage.extract`／`insert` 回傳「實際搬了多少」）。
  ⚠ 開發途中一度改成「MODULATE 前後各做一次全網 `SIMULATE extract(Long.MAX_VALUE)`，回傳值與差額不符就
  永久隔離整張 grid 的自動搬料」，**已改掉**：`insert` 這一側註定誤觸——`CraftingServiceStorage` 以
  `Integer.MAX_VALUE` 最高優先權掛在網路上，回補的貨只要有 CPU 在等就當場被認領、不會進可查詢庫存，
  於是 after==before → 差額 0 ≠ 回傳量 → 第一次回補就把整張 grid 鎖死；而回補的觸發條件恰好就是
  「有 CPU 在等這個 key」。順帶省掉每次搬料兩趟全網 SIMULATE 的主緒成本。現在只有
  **mutation 真的拋例外且差額也推不出來**、或回傳值跑到 `[0, requested]` 之外，才進隔離。
- CraftDiag 的狀態與 GC 時間改用伺服器全域 tick，多張網路每個 server tick 最多掃一次；停服會清除
  狀態、提交紀錄、反射快取與行數額度。standalone／link 缺失或不可讀一律標「性質無法判定」，
  只有非 standalone 且 link 證據可讀時才判異常／疑提前收單。
- 診斷的可執行性與饑餓鏈改按 AE2 的 fuzzy template＋`isValid` 規則分配，同一份庫存在多輸入槽間
  共用扣除；Level／反射證據未知時不以 partial 結果下結論。暫停或 paused 未知時，久等與凍結計時
  全面停住，恢復後重新累積完整門檻。
- `diagLines` 以原子上限封頂；額度耗盡只通知一次，之後 tick／submit／dumpPlan／外部補料記帳都
  快速短路，並立即釋放帳本／提交快照，不再持續支付完整診斷成本，直到停服重置。

## 3.13.1：卡死救援改為預設關閉

3.13.0 上線隔天（2026-08-19）實測，網路上同時存在兩種真實卡單，**這道救援一種都碰不到**：

| 閘門 | 並行控制倉 `uhv_parallel_hatch` | 密銀 `gtocore:mithril` |
|---|---|---|
| 零進度 300s | ✅ 已 21 分鐘 | ❌ **單只活 2 秒**，計時器永遠累積不到 |
| 無任務產它 | ✅ | ✅ |
| 網路抽不到 | ✅ 網存 0 | ✅ 網存 0 |
| 無樣板在途 | ❌ `推給78262,140,-46471` → **一票否決** | — |

- **控制倉**：三道全過，卡在第四道。`getPendingRequests` 回報樣板還押在供應器上——但那筆推送
  已經 21 分鐘零進度，早就不是「在途」而是「弄丟了」。這道閘門本來是要保護「機器跑很慢但真的在跑」
  的單，實作成**一票否決**是錯的；正確作法是「有在途 → 把零進度門檻拉高（例如 30 分鐘）」。
- **密銀／索륨**：每 4~6 秒開一張新單、每張活 2 秒就離場，救援假設的「卡住＝不動」根本不成立。
  這類是**空轉迴圈**不是凍結，成因在下面那段。

也就是說它現階段**只有誤殺風險、沒有實際效益** → 預設關閉（`-Dgtodiag.stallCancel=true` 可開）。
程式碼保留不刪，比照 3.11.0 的作法，等 pendingAt 誤判修好後可直接 A/B。

### 空轉迴圈的真因（`netherite_scrap` / `mithril` / `thorium` 同型）

1. merequester 請求器的滿足判定是 `網路現貨 + 在途 < 目標量` → 成立就送出**整個 batch**
   （`StorageManager.computeAmountToCraft`）。對「產出即被下游抽乾、網存長期為 0」的物品，
   這個判定**永遠成立**，於是每 10 秒送一次 batch。
2. GTO 的 `executeV2` 對大量直接回 simulation（CRAFT_LESS 語意沒實作成「最多可做量」）。
3. **本 mod 的降量重算接手**（`CraftingServiceSyncMixin` 機器源砍半迴圈）把 10000 砍成
   1250/625/312/156 硬是開單 → 每張單全額吸走原料、把整批輪數推進樣板總成、然後在配方時長
   （240 tick＝12 秒）都還沒到的 3 秒內收單離場。
4. 網存回不去 → 回到 1，迴圈閉合，且每繞一圈再鎖一份原料。

→ 治這個要動的是**降量重算**（加獨立開關、失敗分支留 log、同 key 抑制），不是取消單。

### 兩個不可用的診斷欄位（3.13.1 記錄，避免再被誤導）

- **「累計交付N」**：`delivered = max(0, 上次待交付 − 這次待交付)` 的每 tick 差分累加
  （`CraftDiag.java:604-606`），加總後恆等於「首次待交付 − 離場待交付」。單在同一 tick 內收單就必印 0。
  **跟「推送N輪」是同一條差分、同一個盲點**，不能拿來證明「沒交付」。
- **「某訊息今天 0 筆」**：`[帳本]`／`[一覽]` 受 `-Dgtodiag.diagLines`（預設 40000）統管，
  降量重算與保母共用 `sitterLog` 的 200 行額度。額度燒完後靜音，**沒印 ≠ 沒發生**。

## 3.13.0：三種卡單、三種病、三種修法

2026-08-18 的實錄（3.12.0 執行中）同時卡住三張單，**病因互不相同**，這是設計這三道修法的直接證據。

### ① `universal_circuit_uhv x100` —— 修補撞上限後整組還原

```
提交 任務214種/總輪1,119,456 missing=0
**計畫修補放棄**（新增輪數 2,011,267 超過上限 2,000,000）→ 已還原成原計畫，照原樣送出
未解缺口：naquadria x1,179,648; soldering_alloy x1,179,648; naquadah_ingot x6,135; …
→ CPU 等 17 種「網存 0 且無任務產它」的料，靜止 600s 以上
```

只超過上限 **0.56%** 就整組還原。固定常數的問題是它與計畫規模無關：小計畫的 200 萬形同無限，
112 萬輪的計畫卻在正常修補量就撞牆。**修法＝上限跟著原計畫規模縮放**（`repairRunFactor`）；
真正的發散護欄是時間預算（`repairBudgetMs`）與絕對天花板（`repairRunHardCap`），不是這條。

「全有全無」的還原策略本身沒錯（3.8.0 實證：半套計畫比不修更糟），錯的是門檻。
另加一行 WARN：新增輪數 > 原計畫輪數時明說，因為 3.9.0 的遞迴發散就是先出現這個形狀。

### ② `helium_plasma x1000000` —— 幻影 `waitingFor`，網路也沒貨

```
剩餘輪=0 在途113 庫存0 待交付113 靜止621s
waiting[1]=helium_plasma(等113/網0/無任務產它)  剩餘任務=[(無)]
```

計畫只有 8000 輪、`missing=0`、開單時不缺，**跟修補完全無關**。輪次全推完了，帳上卻還記著
「在等 113」，而網路沒貨、也沒有任何任務會再產它 → 永遠等一個不會來的東西。
網路無貨時餵料無能為力，只能走 ③ 的取消。

### ③ `gtocore:order` —— 開單時不缺，跑到一半才缺

```
提交 來源=玩家 任務301種/總輪1,840,818 used=264種(2.97億) missing=0（修補完全沒觸發）
waiting[5]= supercritical_steam 等78.5億/網2450億   ← 等一個網路裡堆滿的東西
             wetware_processor_computer 等752/網0/無任務產它
             enriched_naquadah_trinium_europium_duranide_single_wire 等12032/網0/無任務產它
```

`supercritical_steam` 這種「網路有貨、但沒人會送來銷帳」的幻影缺口，只有**保母餵料**解得掉。
其餘網存 0 的，仍然只能取消。

### 為什麼不做「執行期補單」

那等於巢狀／代下合成請求——本 mod 的歷史實錄已兩度觀察到它會滾出巨量碎單並拖垮伺服器，
所以目前只搬運網路現貨，不會代下合成請求。
取消則相反：把半成品全退回網路，機器請求器 10 秒後自己重下，新計畫拿**當下**存量重算，
剛退回的中間產物都算得到，通常小很多也就做得完。代價是玩家單要手動重下（故一律廣播）。

### 取消的三道安全閘

缺一不可，任何一個放寬都可能誤殺正在慢慢前進的單：

1. **零進度**：進度指紋（在途總量／在途 key 數／待交付／剩餘輪數／CPU 庫存總量）連續 `stallCancelSec` 秒不變。
   涵蓋推樣板、機器回貨、交付三種前進方式；暫停中的單一律重置計時器。
2. **證明等不到**：至少一筆 `waitingFor` 同時滿足「無剩餘任務產它」「無樣板押在供應器上（`getPendingRequests`）」
   「網路 SIMULATE 一滴都抽不到」。讀不到 job 就不判（寧可不救也不誤判）。
3. **冷卻**：同一顆 CPU `stallCancelCooldownSec` 秒內不再開刀。

## 3.12.0 修掉的 18 個 bug（稽核＋兩輪對抗式覆核）

**修補側（7 個，其中 5 個 3.8.0 就有）**

| # | 問題 | 後果 | 退路旗標 |
|---|---|---|---|
| B1 | 缺口沖銷對「非來自 usedItems」的缺口也照扣 → `usedItems` 寫成負值 | `KeyCounter` 不擋負數、`extract` 負量回 0、連 `waitingFor` 都不掛 → **該量無聲蒸發**，網路無貨的中間料在遞迴補料時必踩 | `repairDeficitSrc=off` |
| B2 | ③最終產出短缺：加了輪次又從 usedItems 扣掉等量 | 供給原地踏步，修了等於沒修，還多吃一輪原料 | 同上 |
| B3 | `catch(Throwable)` 不還原（快照宣告在 try 內，catch 看不到）| 例外時送出半套計畫＝必凍，違反「全有全無」核心不變式；含一個可達的 NPE | 無（純修復）|
| B5 | 中止路徑 `return` 讓真缺料守衛永遠碰不到 | 非 slim 建置上真缺料守衛在「修補中止」時整個消失 | 無 |
| B6 | 外圈 4 輪跑滿仍有缺口時靜默放行 | 不還原、不記錄，log 還印「補N項」看起來成功 | `repairStrictRounds` |
| B8 | 修補加了輪次卻沒更新 `plan.bytes()` | CPU 大小保護從未觸發（挑 CPU 是拿 bytes 比 `getAvailableStorage()`）| `repairUpdateBytes` |
| B9 | 自舉模擬 `while(progress)` 無上限、跑主緒且不受時間預算約束 | 回饋型配方每 pass 只前進 1 輪 → 單次提交可卡主緒數秒 | `bootstrapMaxPass` |

**診斷側（11 個，全部 3.8.0 就有，不影響合成路徑）**

| # | 問題 | 後果 |
|---|---|---|
| B10 | 「提前收單」判定：`insert()` 在 `remaining` 歸零的同一次呼叫內就 `finishJob()`＋`job=null`，每 tick 取樣永遠看不到末批 | **每張正常完成的單都誤報提前收單** → 改用 link 三態（取消／完成／未結案），standalone 與 link 讀不到一律標「無法判定」|
| B11 | 替代輸入用「庫存掉最多的變體」回推歸戶；且 `extractPatternInputs` 實際會**跨變體混扣**（原註解寫反）| 兩個 key 同時累加等量反號的假 drift、假「對不上」→ 改成不可審計群組，drift 照記但報告分可信／不可信兩段 |
| B12 | `Snap.job` 強引用經 `link → cpu → craftingLogic` 繞回 `WeakHashMap` 的 key | 弱鍵失效、拆 CPU 後整張 tasks 圖永久滯留 → 改弱引用＋閒置回收 |
| B13 | 早期警報不查 `paused` | 暫停中的 CPU 被逐樣板誤判成「執行器沉默：疑 parallel==1 死角」（本 mod 最想抓的指紋）|
| B14 | 快照回寫在方法尾端、外層 `catch` 靜默吞例外 | `ext` 沒清空 → 下一 tick 二次計入 → 假 drift；回寫改一律放 `finally` |
| B15 | `getField("value")` 每 entry 每 tick 未快取 | 500 任務 × 30 CPU × 20tps ≈ 每秒 30 萬次反射，全在主緒 |
| B16 | 不變量審計每 tick 無條件跑完，`changed` 卻在之後才算 | 絕大多數 tick 白建 7-8 個容器算出全 0 |
| B17 | 額度耗盡後靜默且仍付計算成本；`SPENT` 溢位後額度自己復活 | 分不出「沒異常」與「被靜音」 |
| B18 | `SUBMITS` 的 key 含數量、滿 256 整表 clear | 「反覆重下單」計數幾乎永遠顯示首次 |
| B19 | 「供應器全忙」拿 `allBusy.size()` 比 `curRounds.size()`（後者含已完成任務）| 該分支實務上幾乎不成立，被誤判成「缺料鏈斷在根」或「未分類」|
| B20 | `runnableRounds` 重複計算 | 純浪費 |

## 修正內容

| 修正 | 解決 |
|---|---|
| 算料同步化 | 終端 ctrl+左鍵多步計算卡死（單執行緒 async Future 不返回）|
| 機器源 lpcalc 算料（完整版草案）| 結構化需求傳播算料器的程式碼已保留；目前 slim 由外層硬停用，機器來源仍走同步 `executeV2`。重新啟用條件見下方「lpcalc」節 |
| 機器源 present-once 走 IgnoreMissing | 接口/請求器/合成卡多步被 `MISSING_INGREDIENT` 無限拒單 |
| 計畫修補（五維）| ①sim 計畫的 missingItems ②usedItems 批量餘數幻影 ③最終產出總量短缺 ④循環自舉缺口（可執行性模擬）**⑤內部配平（3.9.0）**——缺口直接補樣板 runs 進同一張計畫，不生新任務 |
| 內部配平（3.9.1，第五維）| 前四維只檢查「計畫對網路的引用」與「可執行性」，**沒人檢查修補後的計畫自己配不配得平**：對每個 key 驗 `Σ(每輪輸入×runs) ≤ usedItems＋emittedItems＋Σ(每輪產出×runs)`，負差**只用網路現貨補**（一趟做完、不排樣板、不遞迴、成品除外）。實錄：LUV 通用電路修補完仍差 lubricant 132／copper_block 4／platinum_single_wire 6／naquadah_boule 2／electronic_grade_silicon 6912，做到剩最後 34 個成品時全鏈餓死，而網路各有 115209／6／13／3／564480。<br>⚠ **3.9.0 的做法（把配平缺口丟回缺口佇列＝補樣板輪次、外圈 6 輪）已實測失敗並回退**：補樣板會製造新輸入需求 → 新缺口 → 再補，遞迴發散（glowstone 缺口 6 輪內從 147456 膨脹到 3833856），計畫被灌大且仍不平，LUV 交付量從 466/500 掉到 8/500。**補樣板這條路只能由算料器做，修補這層只補得起網路現貨。**<br>配平模型用**供給扣除法**（逐槽把輪數分配給吃得下的變體、用掉就扣）；3.9.0 的「取供給最多的變體」會自我增強（補了誰誰就繼續吸走全部需求），是發散的另一半原因 |
| 缺口先吃網路現貨（3.9.0）| 缺口原本一律補樣板輪次；改成**先看網路有沒有現貨**（有就記進 usedItems、開局一次取進 CPU），沒有才排樣板。取量以計畫需求為界，不會像 1.8.3 那樣把全網存量吸進單顆 CPU。**成品本身例外**——GTO 沒有「把開局吸入的成品交給 link」的步驟，吸進去只會抱著現貨永凍 |
| 配平補齊不被中止連坐（3.10.2）| 順序改成 **還原 → 網路補齊（無論有無中止都做）→ 再判要不要擋單**。原本補齊掛在 `abortReason == null` 下，UV 通用電路（修補要加 2001 萬輪、破上限而中止）整段被跳過 → 原樣送出 → 開跑後餓死在 epichlorohydrin／hot_platinum_ingot／niobium_titanium_ingot…（全標「本單無人產」，網路各有 499 萬／1920／20034）。補齊只吸網路現貨、不排樣板、有界又便宜，不該被中止連坐；還原必須排在補齊之前（否則補進 `usedItems` 的現貨會被還原洗掉），中止時 `reserved` 以還原後的 `usedItems` 重建 |
| 擋單條件＝會不會必凍（3.10.1）| **只有還原後的計畫符合「`usedItems` 要的量網路給不出來 ＋ 計畫裡沒有任何任務會產它」才擋**（＝唯一實測會變成永久 `waitingFor` 的形狀）；大計畫只是修補沒跑完照送。3.10.0 是「中止就擋」，把 157 任務／217 萬輪的正常 UHV 計畫也每 10 秒擋一次（玩家手動做得起來、機器永遠下不了單）。同版把缺口數上限 4000→100000，改用 **200ms 時間預算**（`-Dgtodiag.repairBudgetMs`）當真正的閘門——跑在主緒該省的是時間不是次數 |
| 中止即擋單（3.10.0）| 修補中止後擋下機器源提交（`INCOMPLETE_PLAN`＋聊天室點名缺什麼，同成品去重），玩家路徑不擋。實錄證明「還原後照樣送出」＝保證凍結：UHV 通用電路的計畫是「從網路拿 100 個 wetware_processor_mainframe」但網路只有 64，補那 36 個要排 200 萬輪 → 超上限中止 → 還原 → 送出 → IgnoreMissing 把 36 個變成永遠等不到的 `waitingFor`，CPU 就此鎖死（剩 1 個任務、`無任務產它`、靜止 60s）。擋下來則 CPU 保持空閒、請求器 10 秒後自己重試，網路補到貨自然成功。同版把輪數上限 200 萬→2000 萬（深階合成的合法修補就會破 200 萬；輪數不吃 CPU 時間，時間由缺口數上限擋，計畫太大 GTO 自己會回 `NO_SUITABLE_CPU_FOUND`）|
| 修補全有全無（3.8.0）| **修一半比不修更糟**：舊版 `guard++ < 96` 是幻影缺口時代的值，大電路（60+ 任務）第一輪缺口就破百，實測 17 次修補 7 次貼著 96 停——已加進計畫的輪次留著、輸入沒補完＝計畫內部不平衡＝必凍（ZPM 通用電路實錄：epoxy/quantum_processor/lubricant/ceramics_dust 全標「本單無人產」，網路各有 1448/4/122903/95 卻進不去，CPU 靜止 3 分鐘）。改法：①上限拉到 4000（`-Dgtodiag.repairGuard`）②另設「新增總輪數」上限 200 萬（`-Dgtodiag.repairRunCap`）防遞迴膨脹——真正該防的用輪數擋，不用次數硬砍 ③**任一上限耗盡就整組還原**（patternTimes／usedItems／missingItems 全部倒回修補前）並印 `**計畫修補放棄**` ＋未解缺口清單，計畫原樣送出 |
| 機器源降量重算 | 大數量請求被 CRAFT_LESS 整張歸 0 時可砍半重算；目前 lpcalc 停用，所以只有此段能處理 slim 機器來源，但 3.13.2 起預設關閉，明確啟用後才受共用時間預算與 grid/requester/key 冷卻保護；玩家來源不降量 |
| 無樣板守衛（3.13.2 slim 啟用）| begin 階段查無樣板時，機器來源直接收到 missing=完整量、patternTimes=空的誠實 sim；玩家來源維持原流程 |
| 退化計畫拒收（3.13.2 slim 啟用）| submit 階段遇到機器來源且 `patternTimes` 空，一律回 `INCOMPLETE_PLAN`，避免 used-only／純現貨計畫把材料抱進 CPU 永凍；玩家來源不拒 |
| 真缺料擋單 | 無樣板可補的硬缺口 → 擋下提交防凍結，聊天室點名缺什麼 |
| 保母（只餵中間料）| 網路既有中間料不被 waitingFor 認領時，由保母直接搬入；3.13.2 起 final key 永不餵，避免 link 部分拒收造成提前收單 |
| 保母全速全額（2.4.0 歷史行為）| 舊版曾把保母改成每 tick 並以輸入 top-up 補到剩餘輪數需求，用來打破兩單搶料僵局；top-up 會吸乾全網，現行 slim 預設停用。幻影模式現為 1 Hz，中間料無差別模式才是每 tick；這段僅保留歷史背景，不代表目前預設 |
| 並行死角解鎖（2.1.0 / 2.3.0 重啟用）| **疑似上游 bug**（`OptimizedCraftingCpuLogic.executeCrafting:221-238`）：並行分支漏了 `parallel==1` 的取料路徑——「並行樣板＋剩餘輪數>1＋庫存恰夠 1 輪」每 tick 無聲跳過。催化劑返還配方（吃 9216 還 4608 錫鐵合金的中子反射板實錄：剩 2 輪、CPU 有 13824＝1.5 輪）按淨需求備料必然踩中，小量下單 100% 重現、與算料器無關（三種算料一致、拔 mod 也卡）。修法：保母命中指紋（min⌊庫存/每輪⌋==1 且剩>1 輪）時把輸入補到 2 輪份，讓 GTO 自己的 `parallel>1` 分支正常取料——只補料，不代推送不碰帳 |
| ~~mixin 直接根治（2.2.0）~~ **實測無效、2.3.0 撤除** | 掛 GTOCore 開源類 `OptimizedCraftingCpuLogic` 補上缺失的 else——**mixin 無聲失效**：jar 內有類、config 正常載入、同檔 AE2 mixin 照常運作、全程零錯誤，但欄位普查證實注入欄位不存在（gtocore 為簽章 jar、`com/gtocore/mixin` 標 `Sealed: true`）。**「mixin 只准掛 AE2 類」鐵則二度實證，此路封死** |
| 供應器忙碌診斷（2.3.0，2.3.1 加座標）| 探針 X 光加印 `忙:N@類型(x,y,z)忙`——全部供應器 `isBusy()` 時 executeCrafting 直接 `continue`：**不推送、不留任何結果**，與並行死角外觀完全相同（`results={}`、零錯誤），這是「料齊卻完全不動」最常見的真因。實錄：ZPM 場發生器單的 5 個任務中 4 個 `忙:1`，泵/發射器料備足 2 輪仍不推，整鏈凍結。2.3.1 起一併印出忙碌機器座標（BlockEntity 直取，GTO 機器走 `getPos`/`gto$getPos` 反射），可直接到現場查機器 |

## 認領歸屬追蹤（slim 3.2.0，純診斷）

AE2 的交付認領走 `CraftingServiceStorage`（以 `Integer.MAX_VALUE` 最高優先權掛進網路儲存）→
`CraftingService.insertIntoCpus`，而該方法對 `craftingCPUClusters`（**HashSet，順序任意**）逐顆呼叫
`craftingLogic.insert`，**完全不認這批貨是哪張單訂的**——誰的 `waitingFor` 有這個 key 就先給誰。
多單共用中間料時「A 訂的貨被 B 領走、A 永遠等不到」即由此而生（潤滑油／焊錫粉／環氧／鎵錠實錄）。

- `[craftfix][認領] <key> x<量> 交付：N顆 CPU 同時在等 → <產物>(等X→吃下Y) …`：候選 ≥2 才印（上限 300 行）。
- 探針 `waiting` 欄改印 `key(等N/網M/另K顆也等)`：分辨「貨沒回網路」（網M=0）與「貨被別人領走」（另K顆>0）。

## lpcalc（未啟用的完整版草案）

`com.gtocraftfix.lpcalc` 與 `com.gtocraftfix.calc` 原始碼目前都會編入 JAR，但 slim 的
`gtocraftfix$SLIM=true` 會讓機器來源略過 lpcalc，直接走同步 GTO `executeV2`。因此：

- `gtodiag.lpcalc.enabled` 只是 lpcalc **內層** kill switch；目前設成 true 不會啟用功能。
- shadow、snapshot／solve budget、maxKeys／maxPatterns 與 `[craftfix][lp]` 統計在 slim 都不可達。
- CRAFT_LESS、SCC 與純現貨計畫等敘述是 [DESIGN-lpcalc.md](DESIGN-lpcalc.md) 的未來規格，
  未完成其中的自動測試與遊戲內驗收前，不得當成現行保證。

## 建置

```bat
.\build-jar.bat
```

建置與執行都要求 JDK／JRE 21。產物在 `dist`（同步鏡射到 `..\_NL_mod\1.20.1\forge`），
檔名 `NL_gto_craft_fix-forge-1.20.1-<版本>.jar`。同一個 `mods/` 只能放一個版本，並須移除舊的
gtocraftdiag JAR，否則相同 modId 或重複 mixin 會阻止啟動。

`build` 會一併執行 JUnit 5 純 Java 測試；測試不得啟動 Minecraft。需要單獨跑測試時：

```bat
cd 1.20.1\forge
.\gradlew.bat test
```

`gto_craft_fix.mixins.json` 暫留 `JAVA_17`，因 Forge 47 內建的 Mixin 0.8.5 不認得
`JAVA_21` 這個 compatibility 名稱；它是 Mixin 語言功能基線，不是本 mod 的執行期版本宣告。
真正的 class target 與 `mods.toml` Java feature 都是 21。

## 授權

本專案採**檔案級混合授權**：原創檔案目前標示為 MIT；`com.gtocraftfix.calc` 中保留
Applied Energistics 2 著作權／授權標頭的衍生檔案，依各檔標頭為 LGPL-3.0-or-later。

- `LICENSE-MIT.txt`：本 mod 原創檔案的 MIT 全文。
- `COPYING` 與 `COPYING.LESSER`：GNU GPLv3／LGPLv3 canonical 全文；保留 LGPL 所引用的完整條款。
- `NOTICE`：AE2／AlgorithmX2／TeamAppliedEnergistics 衍生來源與檔案範圍歸屬。

建置會把上述檔案自動放入 JAR 的 `META-INF/`。各原始檔既有授權標頭與 `NOTICE` 的逐檔規則優先，
不得把整個 JAR 簡化宣稱為單一 MIT 授權。
