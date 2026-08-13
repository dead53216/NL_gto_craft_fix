# NL mod 規則

CLAUDE.md 只放指引；實作細節寫在 `README.md`／程式碼。

- 通用規則見工作區根 `CLAUDE.md`（本檔只列 mod 專屬）。

## mod 專屬約束

- 僅 `1.20.1/forge` 單平台（GTO 整合包專用），**不抽 common**；建置用 ModDevGradle legacyforge（非 ForgeGradle），依賴 GTO 版 AE2（`maven.gtodyssey.com`）。
- modid `gto_craft_fix`、package `com.gtocraftfix`；系統屬性沿用舊名 `gtodiag.lpcalc.*`（已對外公布，勿改）。
- mixin **只准**掛 AE2 類（`appeng.*`）；GTOCore/gtolib 類一律用反射。**兩度實證無聲失效**：2.2.0 用 `targets` 字串掛 `com.gtocore.api.ae2.crafting.OptimizedCraftingCpuLogic`，jar 內有 mixin 類、config 正常載入（同檔其他 AE2 mixin 照常運作）、**全程零錯誤零警告**，但欄位普查證明注入欄位不存在＝未套用（gtocore jar 為簽章 jar／`com/gtocore/mixin` 標 `Sealed: true`）。此路已封，勿再試。
- 禁止生成巢狀/代下合成請求——**兩度實證**：早期「每缺口每秒代下」碎單佔滿 CPU；v1.5.0 斷料救援（60 秒門檻＋上限 4＋冷卻）在深層連鎖斷料下仍一小時滾動開出 19+ 張巨量單致伺服器過載。機器源 sim 計畫必須擋單或回退，不可假裝可做。
- 前身是 `other_mod/gto_repo/gtocraftdiag`（GitHub 公開、被上游 ISSUE 引用）——那份只留檔不再開發；新改動一律在本 repo。
- log 前綴 `[craftfix]`／`[craftfix][lp]` 保持不變（除錯連續性）。
