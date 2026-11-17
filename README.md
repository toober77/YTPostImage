# YTPostImage（YouTube 社群貼文存圖）

分享或貼上 YouTube 社群貼文連結，一鍵把貼文**所有圖片以原尺寸**存到相簿（Pictures/YTPostImage）。不需登入、不需 API key。

## 需求
- Android 9（API 28）以上
- 需要網路連線

## 安裝
到 [Releases](https://github.com/toober77/YTPostImage/releases/latest) 下載 `YTPostImage-v1.0.0.apk` 安裝（需允許「安裝不明來源應用程式」）。

## 使用方式

**方式一：從 YouTube 分享（最快）**
1. 在 YouTube app 開啟社群貼文，按「分享」。
2. 在分享清單選 **YTPostImage**。
3. App 會自動讀取貼文並列出所有圖片縮圖（預設全選）。
4. 按「儲存 N 張」，圖片會存到「圖片/YTPostImage」，完成後會顯示共存了幾張。

**方式二：手動貼上連結**
1. 複製貼文連結（貼文右上 ⋮ →「複製貼文連結」）。
2. 開啟 YTPostImage，按「貼上」（或直接在輸入框貼上），再按「抓圖」。
3. 點縮圖可取消／重新選取，按「全選／全不選」切換，再按「儲存」。

支援的連結格式（多餘的文字、`?si=` 之類追蹤參數都會自動忽略）：
- `https://www.youtube.com/post/<貼文ID>`
- `https://m.youtube.com/post/<貼文ID>`、`https://youtu.be/post/<貼文ID>`
- `https://www.youtube.com/channel/<頻道ID>/community?lb=<貼文ID>`、`https://www.youtube.com/@handle/community?lb=<貼文ID>`
- 直接貼貼文 ID（`Ugk…`）

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
單元測試（`app/src/test`）只測純 Kotlin 的 `PostParser`：網址正規化、用兩份真實貼文 HTML 快照（單張、多張）測圖片擷取、尺寸後綴改寫。
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
- **v1.0.0**：第一版。分享／貼上連結、縮圖格選取、原尺寸存圖。
