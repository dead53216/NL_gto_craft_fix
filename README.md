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
| 保母（只餵料）| 網路既有庫存不被 waitingFor 認領（GTO 認領只在插入事件觸發）→ 每 5 秒餵入。**訂單 job（`gtocore:order`／臨時訂單）的成品單據不代餵、不自我認領**：玩家單 link 的交付地就是網路儲存，「已交付舊單據」與「在途單據」同 key 無法區分，代餵＝收據充數銷 `remainingAmount` → 實推 4/10 輪就偽完單（下單十份剩四份案例）|
| 完單法醫 | 保母每輪記錄各 CPU 現任 job 的（交付帳剩、任務剩輪）；job 消失當下欠帳 >0 印「完單快照」並分流死因（`link 已取消→撤單棄殺` vs `link 未取消→執行器自行完單`）、requester 撤單當下即時 WARN、訂單交付帳每次變動印 `訂單交付帳 remaining X→Y`。探針缺口欄加 `有料不推⚠` 與每任務最後 PushResult（`結果:[INSUFFICIENT_PRIORITY]`…）|
| 配額解鎖 | GTO 優先名額（allocations）扣到剛好 0 就把樣板定義整本抹除（`purgePatternEverywhere`）→ 該樣板剩餘輪次過閘 `allocKey==null` → 永遠 `INSUFFICIENT_PRIORITY`、料在手上卻不推（有料不推⚠ 指紋；lpcalc/修補包裝計畫配額帳空、天然免疫，只有 AE2 原生計畫踩雷）。滯留 ≥30 秒且全 job 零進度 → 清空配額帳退回原版行為 |
| link 判死寬限 | **開機連殺真兇**：AE2 `CraftingLinkNexus.isDead` 兩側 link 任一側缺席 `tickOfDeath++`、逾 60 tick（3 秒）即 cancel；兩側都在但 requester 節點尚未併進本 grid → `+=60` 一發即死。GTO 數千節點大網開機要多 tick 拼 grid → **每次重開世界，所有進行中 job 開機幾秒內被 AE2 自己判死**（實錄開機 30 秒連殺 3 job、在途 66 萬 blaze 蒸發回儲存）；中場 chunk 邊界/子網重組同理。修法（`CraftingLinkNexusMixin`）：缺席一律 +1、拔掉 +=60 即死，門檻 60→2400 tick（2 分鐘）——真死 link 多掛 2 分鐘才回收，誤殺歸零 |
| 擱淺成品歸宿（v1.3.0 認知修正）| ME Requester（merequester，本包自動下單來源）為**網路存量水位制**（`IdleState` 比 `knownAmount`，不記交付帳）：死單在途成品回流落 ME 儲存後水位自動反映、requester 自行收斂——**成品留在 ME 儲存就是正確歸宿，不需補送**。v1.2.2 的「經原 link 補送」已移除：LinkState 完單/取消即轉走，補送必炸 `No CraftingLinkState found`，且原實作例外路徑未回補已抽貨（貨損，實錄最多 6588 個 ULV 電路——已修） |

## lpcalc（機器源結構化算料器）

機器來源的算料請求優先走 `com.gtocraftfix.lpcalc`；任何不支援/超限/驗不過的情形都回退
`com.gtocraftfix.calc` 樹狀版（絕不輸出未經重放驗證的計畫）。設計文件：[DESIGN-lpcalc.md](DESIGN-lpcalc.md)。

- **一鍵停用**：`-Dgtodiag.lpcalc.enabled=false` → 機器路徑完全走現行樹狀版（預設 true）。
  其他系統屬性：`gtodiag.lpcalc.shadowVerifyOnMissing`（LP 判缺料時影子跑樹狀版複核，預設 true）、
  `gtodiag.lpcalc.snapshotBudgetNanos`（快照期伺服器緒預算，預設 1ms）、
  `gtodiag.lpcalc.solveBudgetNanos`（求解期背景緒預算，預設 100ms）、
  `gtodiag.lpcalc.maxKeys` / `maxPatterns`（閉包規模上限，預設 4096 / 16384）、
  `gtodiag.lpcalc.topUpRoundsCap`（保母補輸入單次最多補幾輪份量，預設 4096——防長單把網路庫存整鍋吸進單一 CPU）。
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
