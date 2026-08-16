# NL_gto_craft_fix（GTO Craft Fix）

修復 GregTech-Odyssey（GTO）整合包「合成樹超過一步就無法（正確）自動合成」的獨立 mod。
不改 GTOCore、不動 gtolib，全部修正掛在 AE2 類上。

- 環境：Minecraft 1.20.1 / Forge 47.x / GTOCore 26.7.5-alpha / AE2-gto 15.267.4
- 根因分析與上游修法建議：見 [gtocraftdiag repo 的 ISSUE.md](https://github.com/dead53216/gtocraftdiag/blob/main/ISSUE.md)（本 mod 前身，同源）

> **本檔說明完整版（`v2` 分支）。目前所在的 `slim` 分支保留五項會改行為的修正**：算料同步化（終端 ctrl+左鍵）、機器源 present-once IgnoreMissing（請求器／接口／合成卡）、並行死角解鎖、機器源降量重算（3.1.0）、**計畫修補（3.3.0，見下方「根因二實證」）**。仍停用：無樣板守衛、lpcalc 接管、**兩個拒單守衛**（真缺料擋單／退化計畫拒收改為只記 log——slim 原則是「只修計畫、不擋單」）、保母餵料／補輸入。停用處**程式碼保留、log 照印**；`CraftingServiceSyncMixin.gtocraftfix$SLIM` 改 false 即恢復完整行為。診斷（探針 X 光／忙碌座標／欄位普查／提交失敗）全部保留。

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
因此 3.3.0 在 slim 重新啟用它（保母／lpcalc 維持關閉，證明「不靠補料也能解」）。

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
| `[craftfix][帳本] 任務消失／任務新增／輪數倒增` | 執行中計畫被改動（會使該 tick 的對帳失真，故跳過該 tick）|
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
| `gtodiag.repairRunCap` | `2000000` | 修補可新增的總輪數上限（3.8.0 值）|
| `gtodiag.repairBudgetMs` | `200`（3.11.1）| 修補時間預算——護欄從「數到 N 就放棄」改成時間制（用固定次數擋遞迴補料迴圈本身是錯的設計：96→4000→10 萬三次都設錯）。**用 0 停用，不要設超大值**（`ms×1e6` 會溢位成負數→變成每次都超時）|
| `gtodiag.repairDeficitSrc` | `on`（3.12.0）| 缺口沖銷只准動「真的來自 usedItems」的那本帳：`on`＝完整來源判定／`clamp`＝不分來源但不寫負值／`off`＝3.8.0 原樣 |
| `gtodiag.repairStrictRounds` | `false` | 外圈 4 輪後仍有殘留缺口就整組還原。**預設 false**：唯一能帶著殘留缺口離開外圈的路徑只剩 soft 自舉猜測，還原＝退回幻影計畫＝必凍 |
| `gtodiag.repairUpdateBytes` | `false` | 依新增輪次等比例調高 `plan.bytes()`（開啟後原本擠得上小 CPU 的計畫會改吃 `CPU_TOO_SMALL`）|
| `gtodiag.bootstrapMaxPass` | `200000` | 循環自舉模擬的 pass 上限，超過即跳過該次自舉補齊（3.8.0 無上限、可卡主緒數秒）|
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
| 機器源 lpcalc 算料 | 機器來源請求優先走結構化需求傳播算料器（SCC 縮點＋反拓撲批量傳播＋SCC 內高斯），不支援的形狀自動回退內置樹狀版——見下方「lpcalc」節 |
| 機器源 present-once 走 IgnoreMissing | 接口/請求器/合成卡多步被 `MISSING_INGREDIENT` 無限拒單 |
| 計畫修補（五維）| ①sim 計畫的 missingItems ②usedItems 批量餘數幻影 ③最終產出總量短缺 ④循環自舉缺口（可執行性模擬）**⑤內部配平（3.9.0）**——缺口直接補樣板 runs 進同一張計畫，不生新任務 |
| 內部配平（3.9.1，第五維）| 前四維只檢查「計畫對網路的引用」與「可執行性」，**沒人檢查修補後的計畫自己配不配得平**：對每個 key 驗 `Σ(每輪輸入×runs) ≤ usedItems＋emittedItems＋Σ(每輪產出×runs)`，負差**只用網路現貨補**（一趟做完、不排樣板、不遞迴、成品除外）。實錄：LUV 通用電路修補完仍差 lubricant 132／copper_block 4／platinum_single_wire 6／naquadah_boule 2／electronic_grade_silicon 6912，做到剩最後 34 個成品時全鏈餓死，而網路各有 115209／6／13／3／564480。<br>⚠ **3.9.0 的做法（把配平缺口丟回缺口佇列＝補樣板輪次、外圈 6 輪）已實測失敗並回退**：補樣板會製造新輸入需求 → 新缺口 → 再補，遞迴發散（glowstone 缺口 6 輪內從 147456 膨脹到 3833856），計畫被灌大且仍不平，LUV 交付量從 466/500 掉到 8/500。**補樣板這條路只能由算料器做，修補這層只補得起網路現貨。**<br>配平模型用**供給扣除法**（逐槽把輪數分配給吃得下的變體、用掉就扣）；3.9.0 的「取供給最多的變體」會自我增強（補了誰誰就繼續吸走全部需求），是發散的另一半原因 |
| 缺口先吃網路現貨（3.9.0）| 缺口原本一律補樣板輪次；改成**先看網路有沒有現貨**（有就記進 usedItems、開局一次取進 CPU），沒有才排樣板。取量以計畫需求為界，不會像 1.8.3 那樣把全網存量吸進單顆 CPU。**成品本身例外**——GTO 沒有「把開局吸入的成品交給 link」的步驟，吸進去只會抱著現貨永凍 |
| 配平補齊不被中止連坐（3.10.2）| 順序改成 **還原 → 網路補齊（無論有無中止都做）→ 再判要不要擋單**。原本補齊掛在 `abortReason == null` 下，UV 通用電路（修補要加 2001 萬輪、破上限而中止）整段被跳過 → 原樣送出 → 開跑後餓死在 epichlorohydrin／hot_platinum_ingot／niobium_titanium_ingot…（全標「本單無人產」，網路各有 499 萬／1920／20034）。補齊只吸網路現貨、不排樣板、有界又便宜，不該被中止連坐；還原必須排在補齊之前（否則補進 `usedItems` 的現貨會被還原洗掉），中止時 `reserved` 以還原後的 `usedItems` 重建 |
| 擋單條件＝會不會必凍（3.10.1）| **只有還原後的計畫符合「`usedItems` 要的量網路給不出來 ＋ 計畫裡沒有任何任務會產它」才擋**（＝唯一實測會變成永久 `waitingFor` 的形狀）；大計畫只是修補沒跑完照送。3.10.0 是「中止就擋」，把 157 任務／217 萬輪的正常 UHV 計畫也每 10 秒擋一次（玩家手動做得起來、機器永遠下不了單）。同版把缺口數上限 4000→100000，改用 **200ms 時間預算**（`-Dgtodiag.repairBudgetMs`）當真正的閘門——跑在主緒該省的是時間不是次數 |
| 中止即擋單（3.10.0）| 修補中止後擋下機器源提交（`INCOMPLETE_PLAN`＋聊天室點名缺什麼，同成品去重），玩家路徑不擋。實錄證明「還原後照樣送出」＝保證凍結：UHV 通用電路的計畫是「從網路拿 100 個 wetware_processor_mainframe」但網路只有 64，補那 36 個要排 200 萬輪 → 超上限中止 → 還原 → 送出 → IgnoreMissing 把 36 個變成永遠等不到的 `waitingFor`，CPU 就此鎖死（剩 1 個任務、`無任務產它`、靜止 60s）。擋下來則 CPU 保持空閒、請求器 10 秒後自己重試，網路補到貨自然成功。同版把輪數上限 200 萬→2000 萬（深階合成的合法修補就會破 200 萬；輪數不吃 CPU 時間，時間由缺口數上限擋，計畫太大 GTO 自己會回 `NO_SUITABLE_CPU_FOUND`）|
| 修補全有全無（3.8.0）| **修一半比不修更糟**：舊版 `guard++ < 96` 是幻影缺口時代的值，大電路（60+ 任務）第一輪缺口就破百，實測 17 次修補 7 次貼著 96 停——已加進計畫的輪次留著、輸入沒補完＝計畫內部不平衡＝必凍（ZPM 通用電路實錄：epoxy/quantum_processor/lubricant/ceramics_dust 全標「本單無人產」，網路各有 1448/4/122903/95 卻進不去，CPU 靜止 3 分鐘）。改法：①上限拉到 4000（`-Dgtodiag.repairGuard`）②另設「新增總輪數」上限 200 萬（`-Dgtodiag.repairRunCap`）防遞迴膨脹——真正該防的用輪數擋，不用次數硬砍 ③**任一上限耗盡就整組還原**（patternTimes／usedItems／missingItems 全部倒回修補前）並印 `**計畫修補放棄**` ＋未解缺口清單，計畫原樣送出 |
| 機器源降量重算 | 大數量請求被 CRAFT_LESS 整張歸 0 → 砍半重算取最大可執行量。**機器源已由 lpcalc 接管（CRAFT_LESS 於 lpcalc 內處理），此段僅玩家路徑殘留、實際不觸發（玩家刻意不降量）** |
| 無樣板守衛 | 無樣板物品的機器源請求直接擋下（原版語意），聊天室提示 |
| 真缺料擋單 | 無樣板可補的硬缺口 → 擋下提交防凍結，聊天室點名缺什麼 |
| 保母（只餵料）| 網路既有庫存不被 waitingFor 認領（GTO 認領只在插入事件觸發）→ 每 tick 餵入 |
| 保母全速全額（2.4.0）| 三處改動治「一點一點給」：①**掛 HEAD 不掛 TAIL**——GTOCore 偶數 tick 提前 `ci.cancel()`，掛 TAIL 整段實跑半速（原「5 秒」實為 10 秒、探針 20 秒實為 40 秒，log 探針間隔 40 秒實證）；②**保母改每 tick**（原 %100）；③**補輸入改全額**（補到剩餘輪數×每輪需求，原固定一輪）。目的：兩單搶同一批中間料時靠「先到先贏」序列化，打破「各持不足一輪、網路抽乾、互相卡死」的僵局（ZPM 電路 vs UV 線程倉搶砷化鎵/奈米晶圓實錄）。另**放寬補給閘門**：原本只在 `waiting` 空時補，現在「本輪沒餵到料」也補（在途料擋住整個補給的實錄）。⚠ 全額是舊版吸乾全網的成因，出事用 `-Dgtodiag.topupRounds=N` 收斂（N=1 即回一輪制）|
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

## lpcalc（機器源結構化算料器）

機器來源的算料請求優先走 `com.gtocraftfix.lpcalc`；任何不支援/超限/驗不過的情形都回退
`com.gtocraftfix.calc` 樹狀版（絕不輸出未經重放驗證的計畫）。設計文件：[DESIGN-lpcalc.md](DESIGN-lpcalc.md)。

- **一鍵停用**：`-Dgtodiag.lpcalc.enabled=false` → 機器路徑完全走現行樹狀版（預設 true）。
  其他系統屬性：`gtodiag.lpcalc.shadowVerifyOnMissing`（LP 判缺料時影子跑樹狀版複核，預設 true）、
  `gtodiag.lpcalc.snapshotBudgetNanos`（快照期伺服器緒預算，預設 1ms）、
  `gtodiag.lpcalc.solveBudgetNanos`（求解期背景緒預算，預設 100ms）、
  `gtodiag.lpcalc.maxKeys` / `maxPatterns`（閉包規模上限，預設 4096 / 16384）。
- **CRAFT_LESS 已知次優性（非 bug）**：回傳的可做量 R 可能比理論最大值小
  ≤ `max(c_K/r_K)` 個單位（`c_K` = 批次取整餘數＋SCC 啟動料常數上界）——有界搜尋
  不依賴可行集對 R 單調；每個回傳值都經完整雙序波次重放驗證可執行。
- **純現貨／emitable 頂層請求被拒單（刻意行為，非 bug）**：計畫無任何合成任務
  （patternTimes 空）會被提交守衛拒收——GTO 執行器沒有「把開局吸入的現貨交給 link」
  的步驟，這種 job 會抱著現貨永凍；拒掉後接口下一輪自己從網路拉現貨，自然收斂。
- 統計：log 搜 `[craftfix][lp]`（hit%、FallbackReason 逐項計數、shadow 分歧/跳過）。

## 建置

```
.uild-jar.bat
```

JDK 21；產物在 `dist`（同步鏡射到 `.._NL_mod.20.1orge`），檔名 `NL_gto_craft_fix-forge-1.20.1-<版本>.jar`，丟進整合包 `mods/` 即可（記得移除舊的 gtocraftdiag jar，兩者 mixin 重複會衝突）。

## 授權

MIT
