package com.github.toober77.ytpostimage

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

class ConsentException : IOException("consent page")
class HttpStatusException(val code: Int) : IOException("HTTP $code")

/** 下載好的圖片資料。 */
class DownloadedImage(val bytes: ByteArray, val contentType: String?, val url: String) {
    /** 依 Content-Type（或檔頭魔術位元組）決定副檔名。 */
    val extension: String get() = YouTubeClient.extensionFor(contentType, bytes)
    val mimeType: String get() = when (extension) {
        "png" -> "image/png"; "webp" -> "image/webp"; "gif" -> "image/gif"
        "heic" -> "image/heic"; "avif" -> "image/avif"; else -> "image/jpeg"
    }
}

/** 不依賴 Android 的網路層（HttpURLConnection），方便在 JVM 上直接做實測。 */
object YouTubeClient {
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    const val ACCEPT_LANGUAGE = "zh-TW,zh;q=0.9,en-US;q=0.8,en;q=0.7"
    // 歐盟同意頁用的 cookie（SOCS 為「已接受」的標記）；一律先帶上，可避免被導到 consent.youtube.com
    const val CONSENT_COOKIE = "SOCS=CAI; CONSENT=YES+1"
    private const val MAX_HTML = 12 * 1024 * 1024
    private const val MAX_IMAGE = 64 * 1024 * 1024

    /** IG 一律用非瀏覽器 UA（瀏覽器 UA 只會拿到空殼 JS 頁）；不偽裝成搜尋引擎爬蟲。 */
    const val IG_USER_AGENT = "YTPostImage/1.3"

    private fun isIgHost(host: String) = host.endsWith("instagram.com") || host.endsWith("cdninstagram.com") || host.endsWith("fbcdn.net")

    private fun open(url: String, forImage: Boolean): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        val ig = isIgHost(URL(url).host)
        c.instanceFollowRedirects = false
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.setRequestProperty("User-Agent", if (ig) IG_USER_AGENT else USER_AGENT)
        c.setRequestProperty("Accept-Language", ACCEPT_LANGUAGE)
        c.setRequestProperty("Accept-Encoding", "gzip")
        if (forImage) {
            c.setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
        } else {
            c.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            if (!ig) c.setRequestProperty("Cookie", CONSENT_COOKIE)
        }
        return c
    }

    private fun stream(c: HttpURLConnection): InputStream {
        val raw = c.inputStream
        return if ("gzip".equals(c.contentEncoding, true)) GZIPInputStream(raw) else raw
    }

    private fun readAll(i: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = i.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > limit) throw IOException("response too large")
        }
        return out.toByteArray()
    }

    /** GET 並手動跟隨最多 6 次轉址；若被導到同意頁丟出 [ConsentException]。 */
    private fun get(url: String, forImage: Boolean): Pair<HttpURLConnection, ByteArray> {
        var u = url
        repeat(7) {
            val c = open(u, forImage)
            try {
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: throw HttpStatusException(code)
                    u = URL(URL(u), loc).toString()
                    if (URL(u).host.startsWith("consent.")) throw ConsentException()
                    return@repeat
                }
                if (code != 200) throw HttpStatusException(code)
                val data = stream(c).use { readAll(it, if (forImage) MAX_IMAGE else MAX_HTML) }
                return c to data
            } finally {
                c.disconnect()
            }
        }
        throw IOException("too many redirects")
    }

    /** 下載一般 URL 的位元組（縮圖預覽用）。 */
    fun downloadBytes(url: String): ByteArray = get(url, true).second

    /** 下載 IG 的 embed 頁（`/p/<code>/embed/captioned/`）。 */
    fun fetchInstagramEmbed(link: IgLink): String = String(get(link.embedUrl, false).second, Charsets.UTF_8)

    /** 下載貼文頁 HTML。 */
    fun fetchHtml(postUrl: String): String {
        val (c, data) = get(postUrl, false)
        val html = String(data, Charsets.UTF_8)
        if (URL(c.url.toString()).host.startsWith("consent.") || html.contains("action=\"https://consent.youtube.com")) throw ConsentException()
        return html
    }

    /** 下載圖片；先試 `=d`，失敗再試 `=s0`。 */
    fun downloadImage(img: PostImage): DownloadedImage {
        var last: Exception? = null
        for (u in listOf(img.originalUrl, img.fallbackUrl).distinct()) {
            try {
                val (c, data) = get(u, true)
                val ct = c.contentType
                if (data.isEmpty()) throw IOException("empty")
                if (ct != null && !ct.startsWith("image/") && !ct.startsWith("application/octet")) throw IOException("not image: $ct")
                return DownloadedImage(data, ct, u)
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IOException("download failed")
    }

    fun extensionFor(contentType: String?, bytes: ByteArray): String {
        when (contentType?.substringBefore(';')?.trim()?.lowercase()) {
            "image/jpeg", "image/jpg" -> return "jpg"
            "image/png" -> return "png"
            "image/webp" -> return "webp"
            "image/gif" -> return "gif"
            "image/heic", "image/heif" -> return "heic"
            "image/avif" -> return "avif"
        }
        fun b(i: Int) = if (i < bytes.size) bytes[i].toInt() and 0xFF else -1
        return when {
            b(0) == 0xFF && b(1) == 0xD8 -> "jpg"
            b(0) == 0x89 && b(1) == 0x50 -> "png"
            b(0) == 0x47 && b(1) == 0x49 -> "gif"
            b(0) == 0x52 && b(1) == 0x49 && b(8) == 0x57 -> "webp"
            else -> "jpg"
        }
    }
}
