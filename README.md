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

## 修正內容

| 修正 | 解決 |
|---|---|
| 算料同步化 | 終端 ctrl+左鍵多步計算卡死（單執行緒 async Future 不返回）|
| 機器源 lpcalc 算料 | 機器來源請求優先走結構化需求傳播算料器（SCC 縮點＋反拓撲批量傳播＋SCC 內高斯），不支援的形狀自動回退內置樹狀版——見下方「lpcalc」節 |
| 機器源 present-once 走 IgnoreMissing | 接口/請求器/合成卡多步被 `MISSING_INGREDIENT` 無限拒單 |
| 計畫修補（四維）| ①sim 計畫的 missingItems ②usedItems 批量餘數幻影 ③最終產出總量短缺 ④循環自舉缺口（可執行性模擬）——缺口直接補樣板 runs 進同一張計畫，不生新任務 |
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
