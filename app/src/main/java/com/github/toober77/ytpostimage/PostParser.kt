package com.github.toober77.ytpostimage

/**
 * 貼文中的一張圖片。YouTube：[id] 是圖片 ID（yt3 網址 `=` 之前那一段），[host] 是圖床主機名稱，網址由 id 組出；
 * Instagram：[fullUrl] 直接給原尺寸網址（簽章網址，會過期），[thumb] 為較小的縮圖變體（可為 null）。
 */
data class PostImage(val id: String, val host: String, val fullUrl: String? = null, val thumb: String? = null) {
    /** 縮圖網址（用於預覽格狀清單）。 */
    val thumbUrl: String get() = thumb ?: fullUrl ?: "https://$host/$id=s1080"
    /** 原尺寸（未裁切）網址，優先使用。 */
    val originalUrl: String get() = fullUrl ?: PostParser.withSuffix(this, "d")
    /** 備用的原尺寸網址（`=d` 失敗時使用；IG 沒有備用）。 */
    val fallbackUrl: String get() = fullUrl ?: PostParser.withSuffix(this, "s0")
}

/**
 * 純 Kotlin（不依賴 Android）的 YouTube 社群貼文解析邏輯：
 * 擷取分享文字中的網址、正規化成 https://www.youtube.com/post/<id>、
 * 從貼文 HTML 取出圖片、把尺寸後綴換成原圖。
 */
object PostParser {
    private val URL_REGEX = Regex(
        """(?<![A-Za-z0-9@.-])(?:https?://)?(?:[A-Za-z0-9-]+\.)*(?:youtube\.com|youtu\.be|youtube-nocookie\.com)(?:[/?#][^\s<>"'「」『』（）]*)?""",
        RegexOption.IGNORE_CASE
    )
    private val ID_CHARS = Regex("^[A-Za-z0-9_-]{10,}$")
    private val YT_HOSTS = Regex("""^(?:[a-z0-9-]+\.)*(?:youtube\.com|youtu\.be|youtube-nocookie\.com)$""")

    /** 從分享文字（可能夾雜其他字）中取出第一個 YouTube 網址；找不到回傳 null。 */
    fun extractUrl(text: String?): String? {
        if (text.isNullOrBlank()) return null
        for (m in URL_REGEX.findAll(text)) {
            val u = m.value.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '，', '。', '、')
            if (postIdFromUrl(u) != null) return u
        }
        // 分享文字裡沒有符合貼文格式的網址：回傳第一個 YouTube 網址（讓呼叫端顯示「不是貼文連結」）
        return URL_REGEX.find(text)?.value?.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '，', '。', '、')
    }

    /**
     * 從網址取得貼文 ID。支援：
     * - https://www.youtube.com/post/<id>（含 m.、music. 等子網域、?si=… 追蹤參數）
     * - youtube.com/channel/…、/@handle/、/c/… 底下的 community / posts 分頁，且帶 ?lb=<id>
     * - youtu.be/post/<id>
     * - 單純的貼文 ID（Ugk…）
     */
    fun postIdFromUrl(input: String?): String? {
        if (input == null) return null
        var s = input.trim()
        if (s.isEmpty()) return null
        if (ID_CHARS.matches(s) && s.startsWith("Ug")) return s
        if (!s.contains("://")) s = "https://$s"
        val schemeEnd = s.indexOf("://")
        val rest = s.substring(schemeEnd + 3)
        val hostEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
        val host = rest.substring(0, hostEnd).substringAfter('@').substringBefore(':').lowercase()
        if (!YT_HOSTS.matches(host)) return null
        val afterHost = rest.substring(hostEnd)
        val noFragment = afterHost.substringBefore('#')
        val path = noFragment.substringBefore('?')
        val query = if (noFragment.contains('?')) noFragment.substringAfter('?') else ""
        // lb=<id>（社群分頁中開啟某則貼文）
        for (kv in query.split('&')) {
            val k = kv.substringBefore('=')
            if (k == "lb") {
                val v = kv.substringAfter('=', "")
                if (ID_CHARS.matches(v)) return v
            }
        }
        val segs = path.split('/').filter { it.isNotEmpty() }
        val i = segs.indexOf("post")
        if (i >= 0 && i + 1 < segs.size && ID_CHARS.matches(segs[i + 1])) return segs[i + 1]
        return null
    }

    fun canonicalUrl(postId: String) = "https://www.youtube.com/post/$postId"

    /** 輸入（可含多餘文字的分享內容）→ 正規化網址 https://www.youtube.com/post/<id>；無效回傳 null。 */
    fun normalize(input: String?): String? {
        val id = postIdFromInput(input) ?: return null
        return canonicalUrl(id)
    }

    fun postIdFromInput(input: String?): String? {
        if (input.isNullOrBlank()) return null
        postIdFromUrl(input.trim())?.let { return it }
        return postIdFromUrl(extractUrl(input))
    }

    // ---------------- 圖片擷取 ----------------

    /** 把 JSON 內的跳脫字元（\u003d、\u0026、\/）還原。 */
    fun unescape(s: String): String {
        if (!s.contains('\\')) return s
        return s.replace("\\\\u003d", "=").replace("\\\\u0026", "&").replace("\\\\/", "/")
            .replace("\\u003d", "=").replace("\\u003D", "=")
            .replace("\\u0026", "&").replace("\\u002f", "/").replace("\\u002F", "/")
            .replace("\\/", "/")
    }

    // 圖片網址：host/<圖片ID>=<後綴>  （ID 為 URL-safe base64，長度通常 ≥ 40）
    private val IMG_REGEX = Regex(
        """(?:https?:)?//(yt3\.ggpht\.com|yt3\.googleusercontent\.com|lh[0-9]\.googleusercontent\.com)/([A-Za-z0-9_-]{20,})(=[^"'\s\\<>]*)?"""
    )
    private val SIZE_REGEX = Regex("""=(?:[a-z]*?)(\d+)""")

    /** 頭像樣式（頻道頭像、留言者頭像）的網址：後綴含 -k-c0x00ffffff / -no-rj，或小尺寸圓形裁切。 */
    fun isAvatarLike(suffix: String?): Boolean {
        if (suffix == null) return false
        if (suffix.contains("-no-rj") || suffix.contains("c0x00ffffff") || Regex("""-c-k(?:-|$)""").containsMatchIn(suffix)) return true
        val size = SIZE_REGEX.find(suffix)?.groupValues?.get(1)?.toIntOrNull()
        if (size != null && size <= 176 && Regex("""-c(?:-|$)""").containsMatchIn(suffix) && !suffix.contains("fcrop")) return true
        return false
    }

    private fun collect(text: String, out: LinkedHashMap<String, PostImage>, filterAvatars: Boolean = true) {
        for (m in IMG_REGEX.findAll(text)) {
            val host = m.groupValues[1]
            val id = m.groupValues[2]
            val suffix = m.groupValues[3].ifEmpty { null }
            if (filterAvatars && isAvatarLike(suffix)) continue
            out.putIfAbsent(id, PostImage(id, host))
        }
    }

    /** 從 [start]（必須指向 '{'）起，用括號配對取出一個 JSON 物件文字（略過字串內的括號）。 */
    internal fun balancedObject(s: String, start: Int): String? {
        if (start < 0 || start >= s.length || s[start] != '{') return null
        var depth = 0
        var inStr = false
        var i = start
        while (i < s.length) {
            val c = s[i]
            if (inStr) {
                if (c == '\\') i++ else if (c == '"') inStr = false
            } else when (c) {
                '"' -> inStr = true
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return s.substring(start, i + 1) }
            }
            i++
        }
        return null
    }

    /**
     * 解析貼文頁 HTML，回傳貼文圖片（已依圖片 ID 去重、保持貼文中的順序）。
     * 優先順序：
     * 1. 貼文的 backstageAttachment（backstageImageRenderer / postMultiImageRenderer）
     * 2. 頁面內 JSON-LD 的 "image" 陣列
     * 3. 全頁正規表示式（過濾頭像樣式網址）
     * 4. og:image
     */
    fun parseImages(html: String, postId: String? = null): List<PostImage> {
        val out = LinkedHashMap<String, PostImage>()

        // 1. backstageAttachment
        val markers = if (postId != null) listOf("\"postId\":\"$postId\"") else emptyList()
        var searchFrom = 0
        for (mk in markers) {
            val i = html.indexOf(mk)
            if (i >= 0) searchFrom = i
        }
        val key = "\"backstageAttachment\":"
        var k = html.indexOf(key, searchFrom)
        if (k >= 0) {
            val objStart = html.indexOf('{', k + key.length)
            val obj = if (objStart == k + key.length) balancedObject(html, objStart) else null
            if (obj != null) collect(unescape(obj), out)
        }
        if (out.isNotEmpty()) return out.values.toList()

        // 2. JSON-LD "image":[...]
        val ld = Regex(""""image"\s*:\s*\[([^\]]*)\]""").findAll(html)
        for (m in ld) collect(unescape(m.groupValues[1]), out)
        if (out.isNotEmpty()) return out.values.toList()

        // 3. 全頁 regex（僅在頁面確實包含貼文圖片相關結構時才有意義，仍過濾頭像）
        collect(unescape(html), out)
        if (out.isNotEmpty()) return out.values.toList()

        // 4. og:image
        Regex("""<meta property="og:image" content="([^"]+)"""").find(html)?.let { collect(unescape(it.groupValues[1]), out, false) }
        return out.values.toList()
    }

    // ---------------- 網址改寫 ----------------

    /** 把網址 `=` 之後的尺寸／裁切後綴換成 [suffix]（如 "d" 或 "s0"），其餘部分（含 query）丟棄。 */
    fun rewriteSuffix(url: String, suffix: String = "d"): String {
        val clean = unescape(url).let { if (it.startsWith("//")) "https:$it" else it }
        val q = clean.indexOf('?').let { if (it < 0) clean.length else it }
        val base = clean.substring(0, q)
        val eq = base.indexOf('=')
        val head = if (eq >= 0) base.substring(0, eq) else base
        return "$head=$suffix"
    }

    fun withSuffix(img: PostImage, suffix: String) = "https://${img.host}/${img.id}=$suffix"
}
