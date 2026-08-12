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
| 保母（只餵料）| 網路既有庫存不被 waitingFor 認領（GTO 認領只在插入事件觸發）→ 每 1 秒餵入。**訂單 job（`gtocore:order`／臨時訂單）的成品單據不代餵、不自我認領**（收據充數偽完單）。**成品基線防偽（v1.3.3）**：成品只餵「開單後新增」的量（基線＝job 首見時網路存量）——餵到開單前現貨＝拿玩家庫存充產出銷帳：硫酸氫鉀粉實錄 emitable 頂層單 waitingFor 預填 774k、餵現貨→秒完單→requester 重下→再餵，每 40 秒一輪空轉 6 小時零產出（「停住又開始」）；me_pattern_buffer 救援（繞過認領的真產出＝基線之上）不受影響 |
| 完單法醫 | 保母每輪記錄各 CPU 現任 job 的（交付帳剩、任務剩輪）；job 消失當下欠帳 >0 印「完單快照」並分流死因（`link 已取消→撤單棄殺` vs `link 未取消→執行器自行完單`）、requester 撤單當下即時 WARN、訂單交付帳每次變動印 `訂單交付帳 remaining X→Y`。探針缺口欄加 `料齊未推⚠` 與每任務最後 PushResult（`結果:[INSUFFICIENT_PRIORITY]`…）|
| 配額解鎖 | GTO 優先名額（allocations）扣到剛好 0 就把樣板定義整本抹除（`purgePatternEverywhere`）→ 該樣板剩餘輪次過閘 `allocKey==null` → 永遠 `INSUFFICIENT_PRIORITY`、料在手上卻不推（料齊未推⚠ 指紋；lpcalc/修補包裝計畫配額帳空、天然免疫，只有 AE2 原生計畫踩雷）。滯留 ≥30 秒且全 job 零進度 → 清空配額帳退回原版行為 |
| link 判死寬限 | **開機連殺真兇**：AE2 `CraftingLinkNexus.isDead` 兩側 link 任一側缺席（開機載入時序不齊的主路徑）`tickOfDeath++`、門檻 `>60` 即 cancel；兩側都在但 hasCpu/grid 錯配（chunk 邊界/子網重組）`+=60` 連續兩次掃描即死。GTO 大網開機要多 tick 拼 grid → **每次重開世界，進行中 job 幾秒內被 AE2 自己判死**（實錄開機 30 秒連殺 3 job、在途 66 萬 blaze 退回儲存）。修法（`CraftingLinkNexusMixin`）：缺席/錯配一律 +1、拔掉 +=60，門檻 60→1200 次掃描（GTOCore 偶數 tick 掐斷掃描＝半頻，實效 ≈2 分鐘）——真死 link 多掛 ~2 分鐘才回收，誤殺歸零 |
| 半頻補正 | GTOCore 的 `CraftingServiceMixin` 在偶數 tick 掐斷 `onServerEndTick`（節能），v1.3.1 以前本 mod 掛 TAIL 的整段週期工作（算料預算泵/保母/探針）實跑半速（保母 10 秒、探針 40 秒）。v1.3.2 改掛 HEAD（取消點之前）恢復全速 |
| 擱淺成品歸宿（v1.3.0 認知修正）| ME Requester（merequester，本包自動下單來源）為**網路存量水位制**（`IdleState` 比 `knownAmount`，不記交付帳）：死單在途成品回流落 ME 儲存後水位自動反映、requester 自行收斂——**成品留在 ME 儲存就是正確歸宿，不需補送**。v1.2.2 的「經原 link 補送」已移除：LinkState 完單/取消即轉走，補送必炸 `No CraftingLinkState found`，且原實作例外路徑未回補已抽貨（貨損，實錄最多 6588 個 ULV 電路——已修） |
| 斷料/下單聊天提示（v1.4.0）| ①**跑單斷料點名**：job 某任務「連一輪都湊不齊且網路已乾」持續 60 秒 → 聊天室點名 job 產物＋缺料＋每輪需求（同任務同料 10 分鐘冷卻；計時綁 job 身分、換單/補齊即重計）。背景：計畫修補的現貨複核只在提交當下有效，**多張大單同刻開跑會對同一池現貨重複記帳**（液態氦實錄：17:26 三大單並發、兩單計畫零生產輪全記現貨、被搶後靜默凍結 30+ 分鐘，log 每秒刷 WARN 玩家無感）——AE2 跑單中不重排。②**玩家擋單點名**：手動下單被真缺料擋下 → 對該玩家定向列出缺什麼（不受全域去重影響，每次點擊都有回饋；3 秒防連點）。③**玩家提交失敗回饋**：NO_SUITABLE_CPU_FOUND 等錯誤碼人話化進聊天室（先前玩家源提交失敗完全無聲） |
| ~~斷料救援自動下單（v1.5.0）~~ **已移除（v1.8.0）** | 五道閘（60 秒門檻、一 key 一整單、深度 1、上限 4、per-key 冷卻）擋不住**深層連鎖斷料**：一張 LuV 大單 30+ 種料同時過門檻，「每輪需求×剩餘輪數」的補單量對深斷鏈完全不可控——實錄一小時滾動開出 19+ 張巨量救援單（NOR 晶片 x60000、陶瓷磚 x79040、鋼錠 x35376、量子位晶片 x31878、量子處理器超算 x14450…）把 CPU/產線塞爆、伺服器過載（Can't keep up 49 tick）。整套移除，回歸「點名＋玩家自行補料、保母自動餵入解凍」；鐵則「禁止代下合成請求」恢復無豁免（兩度實證） |
| ~~完單時點復原（v1.7.0）~~ **已撤回（v1.7.1）** | 原始碼對版（GTOCore-Main a87df9c9，四個合成核心檔與遊戲版 md5 相同）推翻前提：**非訂單 job 沒有「全部派完即 finishJob」路徑**——唯一收單點是 `insert()` 實際交付最終產物打穿 `remainingAmount`（OptimizedCraftingCpuLogic.java:470-475），**非訂單收單時數量必然正確**；「機器還在做就收單」是訂單收據制（:134-155，刻意設計），完單快照的「帳剩 N」多為保母取樣滯後假象。節流改不了收單時點、反會卡住 >64 台並行產線的餵料窗口 → 撤除（`finalInflightRounds` 屬性一併移除） |
| 移除自動補單＋斷料訊息整併（v1.8.0）| **自動補單整套移除**（斷料救援與完單短交補產，見上兩列）——所有「缺料」情形一律只通知、由玩家決定補不補。**斷料點名整併**：深層連鎖斷料會 30+ 種料同時過 60 秒門檻，逐料一則訊息＝聊天室瞬間洗版（09:19 實錄）——改為**一 cluster 一則**：前 3 項含量、其餘計數（「…等共 N 項，詳見伺服器紀錄」），各料細節仍在「任務缺料」WARN |
| 備料吸乾與燒帳修正（v1.8.3）| 兩個互相放大的帳務 bug：①**總成滿補無界**（v1.3.6）把單一料的全網存量抽進一顆 CPU（實錄液態氦 6.5 億 mB＋氦氣 3.75 億 mB 囤在銩錠單），其他要同料的單全部餓死 → 放寬改有界（cap×8=65536 輪）；②**自庫認領超額判準用 capped 需求**（8192 輪）對不上無界塞入的備料——99% 工作備料被誤判為「繞過認領的產出」，每秒拿去燒 `waitingFor` 帳（實錄每秒 3800 萬→1 億遞增）→ `remainingDemand` 改全量不截斷。啟動橫幅版本改讀 ModList 動態值（先前寫死 v1.8.0 誤導法醫）。**本次考證同時確認：非訂單玩家單的「提前收單」實為數量足額的正常收單**——單子被平行產線/繞過認領的真實產出提前餵飽（貨都在 CPU 託管、收單時全數退進 ME），剩餘輪次顯示未跑完只是計畫過時，非短交（本場 0 筆完單短交、抽查收單持有量皆 ≥ 訂購量）|
| 斷料點名防誤報（v1.8.1，v1.8.2 補深鏈）| 「合成時間久」≠卡住，兩層凍結：①（v1.8.1）缺料項**全網有在途生產**（GTO 版 `getRequestedAmount`＝各 CPU `waitingFor` 合計，涵蓋玩家另開的補料單；API 例外退看本單）；②（v1.8.2）**本單 `waitingFor` 非空＝深鏈上游在製**——缺鉑導線但熱鉑錠正在 EBF 裡燒（23:17 實錄：缺的料本身零在途、上游活著照樣點名）。任一成立即凍結 60 秒計時；只有「缺料＋網路乾＋整單完全靜止」連續 60 秒才通知（訊息註「期間無任何機器在製」）。「任務缺料」WARN 補印在途量與「本單在製中」標記；聊天**同文 60 秒去重**（兩顆 CPU 跑同產物會同秒連發相同訊息）|
| 原始碼對版修正（v1.7.1）| 深讀 GTOCore-Main 開源執行器後的一批修正：①**PushResult 語意翻案**——正碼＝成功（`SUCCESS=0 推送中`、`BREAK=1 已派完`（推送完成收尾碼，非機器忙碌！）、`BREAK_TASK_LOOP=2 本tick推滿`）、負碼＝失敗（`NOWHERE_TO_PUSH=-3 機器滿載`（官方語意＝推送達機器上限）、`PATTERN_PROVIDER_LOCKED=-4 供應器鎖定`、`REJECTED=-5 讀不到相鄰機器`（落進 results 的唯一路徑＝方向快取缺失；各面忙碌的 REJECTED 會被折疊成 NOWHERE_TO_PUSH）、`INSUFFICIENT_PRIORITY=-6 配額拒推`…），探針人話化、括號保留原碼供 grep；②「料齊未推⚠」佐證碼補 REJECTED（**LOCKED 刻意不收**——lock-until-result 阻塞流程的常態碼，收了會重演健康任務每輪誤報）；③⚠ 時加印**嘗試溯源**（公開 API `getPendingRequests(key)` 前 2 個供應器座標；語意＝「最近嘗試過（可能失敗/陳舊）」非實際送達）；④**paused CPU 排除**——GTO 有 CPU 暫停 API（隨 NBT 存檔，paused 時執行器直接不跑），保母餵料/自庫認領/補輸入/斷料救援/配額解鎖全部跳過 paused CPU、滯留計時表一併清除（防解除暫停後把暫停時長算進滯留門檻誤觸發），探針 out= 後加「（已暫停）」標記（保住 `CPU探針 out=` grep 錨點）、法醫照記。另證實**跨 CPU 誤認領**確實存在（`insertIntoCpus` 任意序整批餵第一顆 waitingFor 同 key 的 CPU、完單 storeItems 退庫也重過認領閘）——液態氦 153 萬「跳槽」之謎的機制 |
| 完單短交監看（v1.6.0，v1.6.1 認知修正）| GTO 執行器**預測性收單**（單據全數推入機器即 finishJob，帳未清照收）攔不到（fork 把 `CraftingCpuLogic` 挖成抽象殼、`finishJob` 在 gtolib 閉源子類，mixin 無聲失效）、也**不該立即補差額**——帳差多半是「機器還在做」，成品稍後自行落庫，CPU 抱著的成品完單時也會 storeItems 退庫（v1.6.0 立即補＝重複生產；鈦/鎵類完單常態帳剩 55 萬/911 萬全是名存實亡的死帳）。v1.6.1 改三道閘後只補真損失：①**只看玩家單**（submitJob 成功時標記 link；機器源有 requester 水位制自我修復）②完單瞬間**先扣網路現貨**抵帳（死帳直接歸零）③剩餘「應到未到」量**監看 5 分鐘**（cachedInventory 每 tick 正差分累計到貨），到帳蓋過即結案零動作；期滿仍未到的餘額才聊天室通知「完單短交確認：實際少 N——如有需要請自行補單」（v1.8.0 起不代補）。訂單型維持 GTO 收據制；撤單不看（尊重玩家取消）。提前收單「以前看不到」的原因：多步大單過去根本卡死、到不了「全部派完」，本 mod 修順補料後大單數十秒派完，該行為才天天可見（完單法醫 v1.2.0 起即有記錄） |

## lpcalc（機器源結構化算料器）

機器來源的算料請求優先走 `com.gtocraftfix.lpcalc`；任何不支援/超限/驗不過的情形都回退
`com.gtocraftfix.calc` 樹狀版（絕不輸出未經重放驗證的計畫）。設計文件：[DESIGN-lpcalc.md](DESIGN-lpcalc.md)。

- **一鍵停用**：`-Dgtodiag.lpcalc.enabled=false` → 機器路徑完全走現行樹狀版（預設 true）。
  其他系統屬性：`gtodiag.lpcalc.shadowVerifyOnMissing`（LP 判缺料時影子跑樹狀版複核，預設 true）、
  `gtodiag.lpcalc.snapshotBudgetNanos`（快照期伺服器緒預算，預設 1ms）、
  `gtodiag.lpcalc.solveBudgetNanos`（求解期背景緒預算，預設 100ms）、
  `gtodiag.lpcalc.maxKeys` / `maxPatterns`（閉包規模上限，預設 4096 / 16384）、
  `gtodiag.lpcalc.topUpRoundsCap`（保母補輸入單次最多補幾輪份量，預設 8192——防長單把網路庫存整鍋吸進單一 CPU；**供應器為樣板總成系列（PatternBuffer，無限槽）時放寬至 cap×8**（v1.8.3 前無界，實錄把 6.5 億 mB 液態氦抽進單一 CPU 令其他單全網餓死）。
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
