# Novel Reader (小說閱讀與離線下載 Android App)

本專案為使用 **Kotlin + Jetpack Compose + Room + WorkManager** 開發的 Android 小說閱讀器，支援貼上網址或透過內建瀏覽器過人機驗證後，整部下載離線閱讀。

---

## 核心功能特色

1. **直接貼網址匯入**：
   - 點選右上角 `+` 直接輸入小說首頁或目錄網址，自動抓取小說名稱、作者、簡介與完整章節目錄。
2. **內建 WebView 瀏覽器（過人機驗證）**：
   - 若遇到 Cloudflare 驗證（如 czbooks.net 等防爬網站），可點擊右上角「地球」圖示進入內建瀏覽器正常瀏覽。
   - 瀏覽到喜歡的小說後，直接點擊右上角「**加入書架**」，自動擷取 DOM 匯入章節目錄。
3. **整部小說離線下載**：
   - 透過 Android **WorkManager** 在背景排隊抓取每一章節內文。
   - 支援關閉 App 繼續背景下載、中斷後自動斷點續傳。
4. **離線閱讀器**：
   - 全本地 Room SQLite 儲存，無網路狀態下隨時打開看。
   - 支援字級放大/縮小調整（`12sp` ~ `36sp`）。
   - 支援深色/夜間閱讀模式（深底灰字護眼）。
   - 自動記錄最後閱讀章節與閱讀時間。

---

## 專案架構

- `app/src/main/java/com/novelreader/app/data/local/`: Room Database、DAO、實體資料庫模型（`NovelEntity`, `ChapterEntity`）。
- `app/src/main/java/com/novelreader/app/data/parser/`: `UniversalNovelParser`（使用 Jsoup 自動分析並抽取標題、目錄與內文）。
- `app/src/main/java/com/novelreader/app/data/download/`: `NovelDownloadWorker`（WorkManager 背景下載排程）。
- `app/src/main/java/com/novelreader/app/data/repository/`: `NovelRepository` 資料存取層。
- `app/src/main/java/com/novelreader/app/ui/`:
  - `BookshelfScreen`: 書架首頁、已下載章節進度、整部下載與刪除。
  - `NovelDetailScreen`: 小說簡介、完整章節目錄。
  - `ReaderScreen`: 離線閱讀器、字級與夜間模式切換。
  - `WebBrowserScreen`: 內建瀏覽器模式。

---

## 如何建置與執行

1. 使用 **Android Studio** 開啟目錄：
   ```
   D:\git\novel-reader
   ```
2. 等待 Gradle Sync 完成。
3. 連接 Android 手機（或啟動模擬器），點擊 **Run (Shift + F10)** 即可安裝執行。

---

## GitHub Actions：固定簽章 + 自動發布最新版 APK

每次推送到 `main` 或在 `main` 手動執行 `Build and Publish Signed APK`：

1. 使用 Java 17 執行單元測試並編譯 Debug APK。Pull Request 僅執行測試與 Debug 建置，不接觸簽章金鑰，也不發布 Release。
2. 使用 GitHub Actions Secrets 中的**固定金鑰**建置正式 Release APK。未提供金鑰、簽章驗證失敗或測試失敗時，工作會失敗，不會改用 Debug key。
3. 驗證 APK 簽章，並比對上一個正式 Release 的憑證 SHA-256，避免意外更換簽章導致無法升級。
4. 將 `NovelReader-latest.apk` 與 `SIGNING-INFO.txt` 發布至 [Releases 最新版](https://github.com/jimmy0933b3-sudo/novel-reader/releases/latest)；**確認新版本發布成功後**，清理所有舊 Releases 與其標籤，只保留最新 Release；也清理舊的 signed-APK Actions artifacts。
5. GitHub Actions 的 `run_number` 作為 `versionCode`，`versionName` 為 `1.0.<run_number>`。請保留同一把簽章金鑰，以便直接覆蓋安裝。

在本儲存庫的 **Settings → Secrets and variables → Actions** 設定下列 Repository secrets（名稱須完全一致；如使用 organization secrets，需授權給本儲存庫）：

| Secret 名稱 | 用途 |
| --- | --- |
| `KEYSTORE_BASE64` | 固定 Android 簽章 keystore（JKS / PKCS12）的 Base64 內容 |
| `KEYSTORE_PASSWORD` | keystore 密碼 |
| `KEY_ALIAS` | 簽章用私鑰的 alias |
| `KEY_PASSWORD` | 私鑰密碼 |

Linux/macOS 範例：`base64 < release.jks | tr -d '\n'`，將結果設為 `KEYSTORE_BASE64`；其他密碼與 alias 分別填入相對應的 Secret。**切勿將 keystore、密碼或 Base64 內容提交到 Git。**

只要在 `main` 成功完成 CI，就能透過固定網址取得最新正式版：
`https://github.com/jimmy0933b3-sudo/novel-reader/releases/latest`
