# YTPostImage（YouTube 社群貼文／IG 貼文存圖）

分享或貼上 YouTube 社群貼文連結（**v1.3.0 起另有實驗性的 Instagram 貼文支援**），一鍵把貼文**所有圖片以原尺寸**存到相簿（Pictures/YTPostImage）。不需登入、不需 API key。

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
2. 開啟 YTPostImage，按右上角的「**貼上並抓圖**」：自動讀剪貼簿、填入輸入框並立刻開始抓圖，提示文字顯示在輸入框下方。
3. 也可以直接在輸入框手動輸入／貼上連結，按鍵盤的「前往／Enter」即開始抓圖。
4. 縮圖以**兩欄瀑布流**顯示，**保持每張圖原本的比例**（不裁成正方形），縮圖來源為 `=s1080`，比較大也比較清楚。點圖片可取消／重新選取（右上角打勾），按「全選／全不選」切換，再按「儲存」。

**儲存失敗時**：成功存好的圖會自動取消勾選，失敗的保持勾選，並說明原因（網路連線中斷／下載失敗／記憶體不足／套用編輯失敗／寫入相簿失敗）；儲存鈕會變成「重試失敗的 N 張」，不會重複存已成功的。只有**全部成功**才會自動關閉。

支援的連結格式（多餘的文字、`?si=` 之類追蹤參數都會自動忽略）：
- `https://www.youtube.com/post/<貼文ID>`
- `https://m.youtube.com/post/<貼文ID>`、`https://youtu.be/post/<貼文ID>`
- `https://www.youtube.com/channel/<頻道ID>/community?lb=<貼文ID>`、`https://www.youtube.com/@handle/community?lb=<貼文ID>`
- 直接貼貼文 ID（`Ugk…`）

## Instagram 貼文（實驗性）

v1.3.0 起可貼上或從 Instagram 分享 IG 貼文連結，App 會**自動判斷是 YouTube 還是 IG**，後續縮圖、編輯、儲存、重試、自動關閉都和 YouTube 完全相同。

- 支援的連結：`instagram.com/p/<代碼>`、`/reel/<代碼>`、`/tv/<代碼>`、`/<使用者>/p/<代碼>/`，含或不含 `www.`、`m.`，後面的 `?igsh=…` 等參數會自動忽略。
- 檔名：`ig_<代碼>_<序號>.<副檔名>`（YouTube 仍是 `<貼文ID>_<序號>.<副檔名>`），同樣存到「圖片/YTPostImage」。
- 輪播貼文（多張）會抓到全部照片，通常是 1440 寬（最大約 2160×2700）的原尺寸；**輪播中的影片會被略過**，並在訊息中標示略過幾個。
- **Reels／純影片貼文不支援**：IG 只提供封面，會顯示說明訊息而不下載。
- 私人、已刪除或被 IG 擋下的貼文會顯示「IG 擋下來了，或這篇是私人／已刪除貼文（IG 功能為實驗性）」。
- 運作方式：讀取公開的 `https://www.instagram.com/p/<代碼>/embed/captioned/` 頁面（User-Agent 為 `YTPostImage/1.3`，不偽裝成瀏覽器或搜尋引擎），輪播從頁面內的 `contextJSON` 取出每張圖的原尺寸網址，單張照片讀 `<img class="EmbeddedMediaImage">`。
- ⚠️ IG 的圖片網址是**帶簽章、幾天內過期**的，所以抓完請立刻儲存（過期後重新按「貼上並抓圖」即可）。
- ⚠️ 這是**實驗性**功能：IG 可能隨時改版、限流或封鎖，導致無法使用，之後可能需要更新 App。

## 編輯圖片（裁切／旋轉）

點縮圖**右下角的鉛筆按鈕**進入全螢幕編輯（會載入 `=d` 原尺寸圖）：
- **裁切**：拖曳裁切框移動，拖角落（自訂比例時也可拖邊）縮放。比例按鈕：**9:16**、**2:3**、**自訂**；選「自訂」時框可自由調整，也可在 `W:H` 欄位輸入比例（例如 `16:9`、`1:1`、`4/5`、`0.75`）。
- **旋轉**：`⟲ 90°`／`⟳ 90°` 按鈕，下方滑桿可 ±45° 微調（0.1° 為單位）；**歸零**會把旋轉全部回到 0°；**重設**把裁切框重設為（目前比例的）最大置中框。裁切框一定在圖片範圍內，微調角度時不會出現空白角落。
- **完成**保留編輯，**取消**放棄。完成後**縮圖會直接顯示編輯後的樣子**（連比例也一起改變），並在左下角顯示「已編輯」；再次點鉛筆可接續上次的編輯狀態。若編輯回到「沒有變更」，縮圖會恢復成原圖。
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
- `InstagramParserTest`：IG 連結格式、平台判斷、用 4 份真實 embed 頁快照（橫向輪播、直向輪播、單張照片、Reels）測解析，另有合成資料測影片投影片略過與各種跳脫格式。
- `EditMathTest`：裁切框／旋轉／比例解析等純數學。
- `SaveErrorsTest`：儲存失敗原因分類。
- `UiRobolectricTest`：用 Robolectric（NATIVE 繪圖，無頭環境）檢查主畫面（縮圖保持比例、鉛筆按鈕、點選切換）、編輯後縮圖、編輯器、旋轉＋裁切的實際像素結果、儲存後自動關閉／部分失敗後重試；截圖輸出到 `app/build/screens/`。
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
- **v1.3.0**：新增實驗性 Instagram 貼文支援（`/p/`、`/reel/`、`/tv/`，自動判斷平台，檔名 `ig_<代碼>_<序號>`）；輸入框提示改為「YouTube 社群貼文或 IG 貼文」。YouTube 行為與介面不變。
- **v1.2.0**：縮圖改為兩欄瀑布流並保持原圖比例（`=s1080` 更清楚）；編輯完成後縮圖直接顯示編輯結果；「貼上並抓圖」移到右上；每張縮圖右下角加鉛筆編輯鈕（取消長按）；儲存部分失敗時自動取消已成功的勾選、按鈕變「重試失敗的 N 張」，錯誤訊息改為中文原因。
- **v1.1.0**：永遠深色主題；「貼上」與「抓圖」合併為一個按鈕、提示放右側、版面更緊湊（輸入框按 Enter 也會抓圖）；全部儲存成功後自動關閉；長按縮圖進入裁切（9:16／2:3／自訂比例）與旋轉（±90°、微調、歸零）編輯，儲存編輯後的原解析度版本。
- **v1.0.0**：第一版。分享／貼上連結、縮圖格選取、原尺寸存圖。
