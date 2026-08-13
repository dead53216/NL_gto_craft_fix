# NL_gto_craft_fix（GTO Craft Fix）

修復 GregTech-Odyssey（GTO）整合包「合成樹超過一步就無法（正確）自動合成」的獨立 mod。
不改 GTOCore、不動 gtolib，全部修正掛在 AE2 類上。

- 環境：Minecraft 1.20.1 / Forge 47.x / GTOCore 26.7.5-alpha / AE2-gto 15.267.4
- 根因分析與上游修法建議：見 [gtocraftdiag repo 的 ISSUE.md](https://github.com/dead53216/gtocraftdiag/blob/main/ISSUE.md)（本 mod 前身，同源）

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
| 保母（只餵料）| 網路既有庫存不被 waitingFor 認領（GTO 認領只在插入事件觸發）→ 每 5 秒餵入 |
| 並行死角根治（2.2.0，mixin 手術）| **上游 bug**（`OptimizedCraftingCpuLogic.executeCrafting:221-238`，已回報）：並行分支漏了 `parallel==1` 的取料路徑——「並行樣板＋剩餘輪數>1＋庫存恰夠 1 輪」被每 tick 無聲跳過、永久卡死。催化劑返還配方（吃 9216 還 4608 錫鐵合金的中子反射板實錄）按淨需求備料必然踩中，小量下單 100% 重現、與算料器無關。修法（`OptimizedCpuParallelFixMixin`，等價補上缺失的 else）：@Redirect 攔 `ObjHolder` 建構子記住本輪容器＋攔 `getMaxParallel`——算出 1 時就地用**原樣板做單輪取料**塞進容器再照樣回傳 1，上游後續原生流程照走（配額按 1 折算、推送原樣板、`progress -= 1`）。取料失敗容器留 null＝回退原狀；欄位改名（上游更新）自動永久停用。~~2.1.0 保母補料版~~已由本修法取代移除。**本 mixin 掛 GTOCore 類（targets 字串綁定、不引編譯依賴）＝「mixin 只掛 AE2」鐵則的首個例外（使用者核准）**，datasynclib holder 以 `libs/` 內抽出的迷你 jar 供編譯 |

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
