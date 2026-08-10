# NL mod 規則

CLAUDE.md 只放指引；實作細節寫在 `README.md`／程式碼。

- 通用規則見工作區根 `CLAUDE.md`（本檔只列 mod 專屬）。

## mod 專屬約束

- 僅 `1.20.1/forge` 單平台（GTO 整合包專用），**不抽 common**；建置用 ModDevGradle legacyforge（非 ForgeGradle），依賴 GTO 版 AE2（`maven.gtodyssey.com`）。
- modid `gto_craft_fix`、package `com.gtocraftfix`；系統屬性沿用舊名 `gtodiag.lpcalc.*`（已對外公布，勿改）。
- mixin 只准掛 AE2 類（`appeng.*`）；GTOCore/gtolib 類的 mixin 無聲失效（實驗證實），一律用反射。
- 禁止「每缺口即代下」巢狀合成請求（實測生出大量小任務佔滿 CPU）；唯一豁免＝斷料救援（v1.5.0）：斷料 60 秒＋有樣板＋單一頂層單＋深度 1＋併發 4＋per-key 冷卻。機器源 sim 計畫必須擋單或回退，不可假裝可做。
- 前身是 `other_mod/gto_repo/gtocraftdiag`（GitHub 公開、被上游 ISSUE 引用）——那份只留檔不再開發；新改動一律在本 repo。
- log 前綴 `[craftfix]`／`[craftfix][lp]` 保持不變（除錯連續性）。
