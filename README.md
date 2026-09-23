# UsageTracker

追蹤 OpenAI（ChatGPT Plus / Pro）與 Claude（Pro / Max）訂閱**剩餘用量**的 Android App，附主畫面小工具。

## 功能

- **多帳號登入**：可同時登入多個 OpenAI、Claude 帳號；所有資料只存在手機本機，登入權杖以 Android Keystore 加密。
- **自動偵測可追蹤的限制**
  - OpenAI：5 小時限制、每週限制（以及官方回傳的其他模型額度，例如 Codex Spark）。
  - Claude：5 小時限制、每週限制，以及 Fable 等模型的獨立每週限制。
- **主畫面小工具**：進度條 + 百分比 + 距離下次重置的時間。可選擇顯示「全部」、「單一供應商（多帳號合併）」或「單一帳號」。
- **多帳號合併**：同一家的多個帳號可合併顯示，數值為各帳號平均（例如一個帳號用完、另一個沒用 → 剩餘 50%），重置時間顯示最快的一個。每個帳號可在「帳號」頁決定是否納入合併。
- **百分比顯示方式**：可切換「剩餘的 %」或「已使用的 %」。
- **用量提醒**：可針對單一帳號或合併後的用量，設定某個限制（或全部限制）剩餘低於多少 % 時發送通知；重置回升後會自動重新啟用。
- **背景更新**：每 15 分鐘～2 小時自動更新（Android 系統最短 15 分鐘）。

## 登入方式

| 供應商 | 方式 |
| --- | --- |
| OpenAI | 瀏覽器登入（與 Codex CLI 相同的 OAuth 流程，完成後自動回到 App）；或使用裝置代碼登入。 |
| Claude | 瀏覽器登入並授權後，複製頁面上的授權碼貼回 App（與 Claude Code 相同的 OAuth 流程）。 |

登入頁預設以「無痕分頁」開啟，方便登入第二個帳號而不沿用瀏覽器既有的登入狀態。

> 用量資料取自 OpenAI Codex（`chatgpt.com/backend-api/wham/usage`）與 Claude Code（`api.anthropic.com/api/oauth/usage`）使用的非公開介面，官方調整時可能暫時失效。

## 建置

需求：JDK 17、Android SDK（platform 36、build-tools 36.0.0）。

```bash
./gradlew assembleRelease      # 產出 app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest    # 單元測試
```

Release 版使用本機 debug 金鑰簽署，方便直接側載安裝。

## 專案結構

```
app/src/main/java/com/mamekyo/usagetracker/
├── data/     資料模型、加密儲存（Store / SecureBox）
├── net/      OpenAI、Claude OAuth 與用量 API
├── domain/   更新流程、多帳號合併、提醒判斷、顯示格式
├── work/     WorkManager 背景更新
├── widget/   Glance 主畫面小工具與設定畫面
└── ui/       Jetpack Compose 主程式畫面
```
