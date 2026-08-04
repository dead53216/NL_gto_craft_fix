# NL mod 規則

- 使用繁體中文與 UTF-8（無 BOM）。
- 只處理 `AGENTS.md`，不要處理 `CLAUDE.md`。
- 禁止批量刪除；每次只能刪除一個明確檔案，否則請使用者手動處理。

## 流程

1. 先讀 `README.md` 與 `AGENTS.md`。
2. 共用程式放 `common/`；平台程式放版本／loader 目錄。
3. 根目錄 `VERSION` 第一行是目前版本，第二行是 `PREVIOUS_VERSION=<上一版>`。
4. 修正升 PATCH；新功能升 MINOR；破壞性變更升 MAJOR。
5. Gradle 與 `build-jar.bat` 直接讀取 `VERSION`，不得重複版本號。
6. 程式或資源變更後執行 `.\build-jar.bat`；需成功、無 `build-error.log`，且 JAR 檔名含版本。
7. 建置成功後直接 commit；訊息以版本開頭。建置失敗不得 commit。
8. 任務完成時 `git add -A` 並提交全部變更，確保 `git status` 乾淨。

純文件變更不升版、不建置。`build-jar.bat` 與本檔由 `_mod_template` 統一維護。
