# NL mod 規則

CLAUDE.md 只放指引；實作細節寫在 `README.md`／程式碼。

- 通用規則見工作區根 `CLAUDE.md`（本檔只列 mod 專屬）。

## mod 專屬約束

- 僅 `1.20.1/forge` 單平台（GTO 整合包專用），**不抽 common**；建置用 ModDevGradle legacyforge（非 ForgeGradle），依賴 GTO 版 AE2（`maven.gtodyssey.com`）。
- modid `gto_craft_fix`、package `com.gtocraftfix`；系統屬性沿用舊名 `gtodiag.lpcalc.*`（已對外公布，勿改）。
- mixin 只准掛 AE2 類（`appeng.*`）；GTOCore/gtolib 類一律用反射。（v1.7.1 考證：當年「mixin 無聲失效」的實證真因是 AE2-gto fork 把 `CraftingCpuLogic` 挖成純抽象殼、目標方法體不存在；執行器現已開源在 `com.gtocore.api.ae2.crafting`，GTOCore 類能否 mixin **未重驗**——專門實驗版驗證前維持反射。）
- 禁止生成巢狀/代下合成請求——**兩度實證**：早期「每缺口每秒代下」碎單佔滿 CPU；v1.5.0 斷料救援（60 秒門檻＋上限 4＋冷卻）在深層連鎖斷料下仍一小時滾動開出 19+ 張巨量單（NOR 晶片 x60000、陶瓷磚 x79040…）致伺服器過載，v1.8.0 整套移除。機器源 sim 計畫必須擋單或回退，不可假裝可做。
- 前身是 `other_mod/gto_repo/gtocraftdiag`（GitHub 公開、被上游 ISSUE 引用）——那份只留檔不再開發；新改動一律在本 repo。
- log 前綴 `[craftfix]`／`[craftfix][lp]` 保持不變（除錯連續性）。
