package com.github.toober77.ytpostimage

/** IG 貼文連結。[kind] 為 p / reel / tv。 */
data class IgLink(val code: String, val kind: String) {
    val canonicalUrl get() = "https://www.instagram.com/p/$code/"
    val embedUrl get() = "https://www.instagram.com/p/$code/embed/captioned/"
}

/** 解析結果。[images] 只含照片；影片投影片會被略過並計入 [skippedVideos]；整篇是影片／Reels 則 [isVideoPost]＝true。 */
data class IgResult(val images: List<PostImage>, val skippedVideos: Int, val isVideoPost: Boolean)

/**
 * 純 Kotlin 的 Instagram 解析（實驗性）：從 `/p/<code>/embed/captioned/` 頁面取出照片。
 * 輪播（carousel）讀 `contextJSON`；單張照片（contextJSON 為 null）讀 `<img class="EmbeddedMediaImage">`。
 */
object InstagramParser {
    private val URL_REGEX = Regex(
        """(?<![A-Za-z0-9@.-])(?:https?://)?(?:[A-Za-z0-9-]+\.)*(?:instagram\.com|instagr\.am)(?:[/?#][^\s<>"'「」『』（）]*)?""",
        RegexOption.IGNORE_CASE
    )
    private val IG_HOSTS = Regex("""^(?:[a-z0-9-]+\.)*(?:instagram\.com|instagr\.am)$""")
    private val CODE = Regex("^[A-Za-z0-9_-]{6,}$")
    private val KINDS = mapOf("p" to "p", "reel" to "reel", "reels" to "reel", "tv" to "tv")
    private val TRAIL = charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '，', '。', '、')

    /** 從輸入（網址或夾雜其他字的分享文字）取出 IG 貼文連結；不是 IG 貼文回傳 null。 */
    fun parseLink(input: String?): IgLink? {
        if (input.isNullOrBlank()) return null
        fromUrl(input.trim())?.let { return it }
        for (m in URL_REGEX.findAll(input)) fromUrl(m.value.trimEnd(*TRAIL))?.let { return it }
        return null
    }

    fun fromUrl(url: String): IgLink? {
        var s = url.trim()
        if (s.isEmpty() || s.any { it.isWhitespace() }) return null
        if (!s.contains("://")) s = "https://$s"
        val rest = s.substring(s.indexOf("://") + 3)
        val hostEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
        val host = rest.substring(0, hostEnd).substringAfter('@').substringBefore(':').lowercase()
        if (!IG_HOSTS.matches(host)) return null
        val path = rest.substring(hostEnd).substringBefore('#').substringBefore('?')
        val segs = path.split('/').filter { it.isNotEmpty() }
        // /p/<code>、/reel/<code>、/tv/<code>，或 /<username>/p/<code>
        for (i in 0..minOf(1, segs.size - 2)) {
            val kind = KINDS[segs[i]] ?: continue
            val code = segs[i + 1]
            if (CODE.matches(code)) return IgLink(code, kind)
        }
        return null
    }

    // ---------------- 解析 embed 頁 ----------------

    private val IMG_TAG = Regex("""<img\b[^>]*\bEmbeddedMediaImage\b[^>]*>""", RegexOption.IGNORE_CASE)

    fun htmlUnescape(s: String) = s.replace("&amp;", "&").replace("&#038;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&#064;", "@")

    fun parse(html: String, link: IgLink? = null): IgResult {
        val media = shortcodeMedia(html)
        if (media != null) {
            val edges = MiniJson.arr(MiniJson.obj(media["edge_sidecar_to_children"])?.get("edges"))
            if (!edges.isNullOrEmpty()) {
                val out = ArrayList<PostImage>()
                var skipped = 0
                for (e in edges) {
                    val node = MiniJson.obj(MiniJson.obj(e)?.get("node")) ?: continue
                    if (node["is_video"] == true) { skipped++; continue }
                    imageFromNode(node)?.let { out.add(it) }
                }
                return IgResult(out.distinctBy { it.id }, skipped, false)
            }
            if (media["is_video"] == true) return IgResult(emptyList(), 0, true)
            imageFromNode(media)?.let { return IgResult(listOf(it), 0, false) }
        }
        // contextJSON 為 null（單張照片）或結構不符：讀 <img class="EmbeddedMediaImage">
        val tag = IMG_TAG.find(html)?.value
        if (tag != null) {
            val img = imageFromTag(tag)
            if (img != null) {
                // Reels／影片的 embed 若沒有 contextJSON，這張只是封面
                if (link?.kind == "reel") return IgResult(emptyList(), 0, true)
                return IgResult(listOf(img), 0, false)
            }
        }
        return IgResult(emptyList(), 0, false)
    }

    /** 取出 contextJSON 內的 gql_data.shortcode_media（contextJSON 是被二次 JSON 跳脫的字串）。 */
    internal fun shortcodeMedia(html: String): Map<String, Any?>? =
        extractMedia(html)
            // 若整頁資料本身又被跳脫一層（\"contextJSON\":\"{...），還原一層再試
            ?: if (html.contains("\\\"contextJSON\\\"")) extractMedia(html.replace("\\\"", "\"").replace("\\\\", "\\")) else null

    private fun extractMedia(html: String): Map<String, Any?>? {
        val key = "\"contextJSON\":"
        val k = html.indexOf(key)
        if (k < 0) return null
        var i = k + key.length
        while (i < html.length && html[i].isWhitespace()) i++
        if (i >= html.length || html.startsWith("null", i)) return null
        val inner: Any? = when (html[i]) {
            '"' -> {
                val end = stringEnd(html, i) ?: return null
                val lit = MiniJson.parseOrNull(html.substring(i, end + 1)) as? String ?: return null
                MiniJson.parseOrNull(lit)
            }
            '{' -> PostParser.balancedObject(html, i)?.let { MiniJson.parseOrNull(it) }
            else -> null
        }
        val root = MiniJson.obj(inner) ?: return null
        return MiniJson.obj(MiniJson.obj(root["gql_data"])?.get("shortcode_media"))
            ?: MiniJson.obj(root["shortcode_media"])
    }

    /** 回傳字串字面量（從 [start] 的 `"` 起）結束引號的位置。 */
    private fun stringEnd(s: String, start: Int): Int? {
        var i = start + 1
        while (i < s.length) {
            when (s[i]) {
                '\\' -> i++
                '"' -> return i
            }
            i++
        }
        return null
    }

    private fun num(v: Any?): Double? = (v as? Double)

    private fun imageFromNode(node: Map<String, Any?>): PostImage? {
        val full = (node["display_url"] as? String)?.takeIf { it.startsWith("http") }
            ?: MiniJson.arr(node["display_resources"])?.mapNotNull { MiniJson.obj(it) }
                ?.maxByOrNull { num(it["config_width"]) ?: 0.0 }?.get("src") as? String
            ?: return null
        val id = "ig_" + ((node["id"] as? String) ?: (node["shortcode"] as? String) ?: full.substringBefore('?').substringAfterLast('/'))
        val dims = MiniJson.obj(node["dimensions"])
        val dw = num(dims?.get("width"))
        val dh = num(dims?.get("height"))
        // 縮圖：同比例的較小變體（寬 ≥ 640 中最小的），沒有就用原圖
        var thumb: String? = null
        if (dw != null && dh != null && dw > 0) {
            val ratio = dh / dw
            thumb = MiniJson.arr(node["display_resources"])?.mapNotNull { MiniJson.obj(it) }
                ?.filter {
                    val w = num(it["config_width"]) ?: 0.0
                    val h = num(it["config_height"]) ?: 0.0
                    w >= 640 && w < dw && w > 0 && kotlin.math.abs(h / w - ratio) < 0.02 && (it["src"] as? String)?.startsWith("http") == true
                }?.minByOrNull { num(it["config_width"]) ?: 0.0 }?.get("src") as? String
        }
        return PostImage(id, hostOf(full), full, thumb)
    }

    private val SRC = Regex("""\bsrc="([^"]+)"""")
    private val SRCSET = Regex("""\bsrcset="([^"]+)"""")

    private fun imageFromTag(tag: String): PostImage? {
        val src = SRC.find(tag)?.groupValues?.get(1)?.let { htmlUnescape(it) }
        // srcset：第一段（由大到小）才是原比例；後面又從大到小的是方形裁切變體
        val cands = ArrayList<Pair<Int, String>>()
        SRCSET.find(tag)?.groupValues?.get(1)?.split(',')?.forEach { part ->
            val bits = part.trim().split(Regex("\\s+"))
            if (bits.size == 2 && bits[1].endsWith("w")) bits[1].dropLast(1).toIntOrNull()?.let { cands.add(it to htmlUnescape(bits[0])) }
        }
        val run = ArrayList<Pair<Int, String>>()
        for (c in cands) { if (run.isNotEmpty() && c.first > run.last().first) break; run.add(c) }
        val full = run.firstOrNull()?.second ?: src ?: return null
        val thumb = run.filter { it.first in 640 until (run.first().first) }.minByOrNull { it.first }?.second
        val id = "ig_" + full.substringBefore('?').substringAfterLast('/').substringBeforeLast('.')
        return PostImage(id, hostOf(full), full, thumb)
    }

    private fun hostOf(url: String) = url.substringAfter("://").substringBefore('/')
}
