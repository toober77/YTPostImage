package com.github.toober77.ytpostimage

import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 連真實 YouTube 的實測。預設略過；執行方式：
 * `YTPOST_LIVE="UgkxAAA…,UgkxBBB…" ./gradlew test --tests '*LiveCheckTest*' -i`
 */
class LiveCheckTest {
    @Test fun live() {
        val ids = System.getenv("YTPOST_LIVE")
        assumeTrue(!ids.isNullOrBlank())
        for (raw in ids!!.split(',')) {
            val link = LinkParser.parse(raw.trim()) ?: error("bad link $raw")
            val imgs: List<PostImage>
            when (link) {
                is Link.YouTube -> imgs = PostParser.parseImages(YouTubeClient.fetchHtml(link.canonicalUrl), link.postId)
                is Link.Instagram -> {
                    val r = InstagramParser.parse(YouTubeClient.fetchInstagramEmbed(link.link), link.link)
                    println("LIVE IG skippedVideos=${r.skippedVideos} isVideoPost=${r.isVideoPost}")
                    imgs = r.images
                }
            }
            println("LIVE ${link.canonicalUrl} -> ${imgs.size} images")
            for ((i, img) in imgs.withIndex()) {
                val d = YouTubeClient.downloadImage(img)
                val dim = dimensions(d.bytes)?.let { "${it.first}x${it.second}" } ?: "?"
                val th = if (link is Link.Instagram && img.thumb != null) {
                    val tb = YouTubeClient.downloadBytes(img.thumbUrl)
                    " thumb=" + (dimensions(tb)?.let { "${it.first}x${it.second}" } ?: "?")
                } else ""
                println("LIVE   #${i + 1} ${img.id.take(14)}… ${d.contentType} ${d.bytes.size}B $dim$th -> ${ImageSaver.fileName(link.filePrefix, i + 1, d.extension)}")
            }
        }
    }

    /** 讀 JPEG / PNG / WebP / GIF 檔頭取得寬高（不依賴 Android）。 */
    private fun dimensions(b: ByteArray): Pair<Int, Int>? {
        fun u8(i: Int) = b[i].toInt() and 0xFF
        fun be16(i: Int) = (u8(i) shl 8) or u8(i + 1)
        fun be32(i: Int) = (be16(i) shl 16) or be16(i + 2)
        fun le16(i: Int) = u8(i) or (u8(i + 1) shl 8)
        fun le24(i: Int) = u8(i) or (u8(i + 1) shl 8) or (u8(i + 2) shl 16)
        if (b.size < 30) return null
        if (u8(0) == 0xFF && u8(1) == 0xD8) {
            var i = 2
            while (i + 9 < b.size) {
                if (u8(i) != 0xFF) { i++; continue }
                val m = u8(i + 1)
                if (m in 0xC0..0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC) return be16(i + 7) to be16(i + 5)
                i += 2 + be16(i + 2)
            }
            return null
        }
        if (u8(0) == 0x89 && u8(1) == 0x50) return be32(16) to be32(20)
        if (u8(0) == 0x47 && u8(1) == 0x49) return le16(6) to le16(8)
        if (u8(0) == 0x52 && u8(8) == 0x57) {
            when (String(b, 12, 4)) {
                "VP8X" -> return (le24(24) + 1) to (le24(27) + 1)
                "VP8 " -> return (le16(26) and 0x3FFF) to (le16(28) and 0x3FFF)
                "VP8L" -> { val v = u8(21) or (u8(22) shl 8) or (u8(23) shl 16) or (u8(24) shl 24); return ((v and 0x3FFF) + 1) to (((v shr 14) and 0x3FFF) + 1) }
            }
        }
        return null
    }
}
