# YTPostImage（YouTube 社群貼文存圖）

分享或貼上 YouTube 社群貼文連結，一鍵把貼文**所有圖片以原尺寸**存到相簿（Pictures/YTPostImage）。不需登入、不需 API key。

## 需求
- Android 9（API 28）以上
- 需要網路連線

## 安裝
到 [Releases](https://github.com/toober77/YTPostImage/releases/latest) 下載 `YTPostImage-v1.1.0.apk` 安裝（需允許「安裝不明來源應用程式」）。

## 使用方式

整個 App 固定使用深色主題（不跟隨系統）。

**方式一：從 YouTube 分享（最快）**
1. 在 YouTube app 開啟社群貼文，按「分享」。
2. 在分享清單選 **YTPostImage**。
3. App 會自動讀取貼文並列出所有圖片縮圖（預設全選）。
4. 按「儲存 N 張」，圖片會存到「圖片/YTPostImage」。**全部存成功後 App 會自動關閉**（有任何失敗則不關，並顯示錯誤）。

**方式二：貼上連結**
1. 複製貼文連結（貼文右上 ⋮ →「複製貼文連結」）。
2. 開啟 YTPostImage，按「**貼上並抓圖**」：自動讀剪貼簿、填入輸入框並立刻開始抓圖，提示文字顯示在按鈕右側。
3. 也可以直接在輸入框手動輸入／貼上連結，按鍵盤的「前往／Enter」即開始抓圖。
4. 點縮圖可取消／重新選取，按「全選／全不選」切換，再按「儲存」。

支援的連結格式（多餘的文字、`?si=` 之類追蹤參數都會自動忽略）：
- `https://www.youtube.com/post/<貼文ID>`
- `https://m.youtube.com/post/<貼文ID>`、`https://youtu.be/post/<貼文ID>`
- `https://www.youtube.com/channel/<頻道ID>/community?lb=<貼文ID>`、`https://www.youtube.com/@handle/community?lb=<貼文ID>`
- 直接貼貼文 ID（`Ugk…`）

## 編輯圖片（裁切／旋轉）

**長按縮圖**進入全螢幕編輯（會載入 `=d` 原尺寸圖）：
- **裁切**：拖曳裁切框移動，拖角落（自訂比例時也可拖邊）縮放。比例按鈕：**9:16**、**2:3**、**自訂**；選「自訂」時框可自由調整，也可在 `W:H` 欄位輸入比例（例如 `16:9`、`1:1`、`4/5`、`0.75`）。
- **旋轉**：`⟲ 90°`／`⟳ 90°` 按鈕，下方滑桿可 ±45° 微調（0.1° 為單位）；**歸零**會把旋轉全部回到 0°；**重設**把裁切框重設為（目前比例的）最大置中框。裁切框一定在圖片範圍內，微調角度時不會出現空白角落。
- **完成**保留編輯，**取消**放棄。已編輯的縮圖左下角會顯示「已編輯」，再次長按可接續上次的編輯狀態。
- 儲存時，有編輯的圖片會以**原圖解析度**套用旋轉＋裁切再存（JPEG 品質 95；PNG／GIF 來源存成 PNG，GIF 只取第一格）；沒編輯的圖片仍存原始位元組。檔名不變：`<貼文ID>_<序號>.<副檔名>`。
- 超大圖若記憶體不足，會自動降一級解析度並在訊息中提示。

## 運作方式
1. 把連結正規化成 `https://www.youtube.com/post/<id>`，用桌面瀏覽器的 User-Agent 與 `Accept-Language` 抓貼文頁（附 `SOCS`/`CONSENT` cookie 避開歐盟同意頁）。
2. 從頁面內貼文的 `backstageAttachment`（`backstageImageRenderer`／`postMultiImageRenderer`）取出圖片網址（`yt3.ggpht.com/<圖片ID>`），依圖片 ID 去重；排除頻道／留言者頭像。若找不到，依序退回 JSON-LD、全頁比對、`og:image`。
3. 把網址尾端的尺寸／裁切參數（例如 `=s1536-c-fcrop64=…`）換成 `=d` 取得未裁切的原圖；失敗再試 `=s0`。
4. 以 MediaStore 存到「圖片/YTPostImage」，檔名 `<貼文ID>_<序號>.<副檔名>`，副檔名依伺服器回傳的 Content-Type（jpg / png / webp / gif）。

Android 10 以上不需要任何儲存權限；Android 9 會在第一次儲存時要求「儲存空間」權限。

## 已知限制
- ⚠️ **這是解析 YouTube 網頁**，不是官方 API。YouTube 改版（頁面結構或圖片網址格式變動）可能讓解析失效，此時 App 會顯示「YouTube 版面已變動導致無法解析」，需要更新 App。
- 只支援公開貼文；會員專屬、已刪除或私人貼文會顯示找不到。
- 只存圖片；貼文內的影片、投票不會下載。
- GIF 貼文會存成原始 GIF（檔案可能很大）。
- 歐盟地區若仍被導到同意頁，會提示先用瀏覽器開啟 YouTube 並同意。

## 自行編譯
需要 JDK 17 與 Android SDK（platform 35）。
```sh
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew test assembleRelease
```
單元測試（`app/src/test`）：
- `PostParserTest`：網址正規化、用兩份真實貼文 HTML 快照（單張、多張）測圖片擷取、尺寸後綴改寫。
- `EditMathTest`：裁切框／旋轉／比例解析等純數學。
- `UiRobolectricTest`：用 Robolectric（NATIVE 繪圖，無頭環境）檢查主畫面與編輯器版面、旋轉＋裁切的實際像素結果、儲存後自動關閉／失敗不關閉；截圖輸出到 `app/build/screens/`。
連真實 YouTube 的實測（預設略過）：
```sh
YTPOST_LIVE="UgkxruCFoMbuqHeiXLzDnT2c3GPDomU4aju5,Ugkx…" ./gradlew testDebugUnitTest --tests '*LiveCheckTest*' -i
```

## 簽章
`app/build.gradle.kts` 預設讀取 `/workspace/keys/ytpostimage-signing.properties`
（可用環境變數 `YTPOSTIMAGE_SIGNING_PROPS` 指定其他路徑），內容：
```
storeFile=/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```
也可用 `YTPOSTIMAGE_STORE_FILE`、`YTPOSTIMAGE_STORE_PASSWORD`、`YTPOSTIMAGE_KEY_ALIAS`、`YTPOSTIMAGE_KEY_PASSWORD` 環境變數。
沒有簽章設定時 release APK 會是未簽章版本。keystore 請勿提交到 repo。

## 版本
- **v1.1.0**：永遠深色主題；「貼上」與「抓圖」合併為一個按鈕、提示放右側、版面更緊湊（輸入框按 Enter 也會抓圖）；全部儲存成功後自動關閉；長按縮圖進入裁切（9:16／2:3／自訂比例）與旋轉（±90°、微調、歸零）編輯，儲存編輯後的原解析度版本。
- **v1.0.0**：第一版。分享／貼上連結、縮圖格選取、原尺寸存圖。
