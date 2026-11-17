package com.github.toober77.ytpostimage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.GZIPInputStream

class PostParserTest {
    private val id = "UgkxruCFoMbuqHeiXLzDnT2c3GPDomU4aju5"
    private val canon = "https://www.youtube.com/post/$id"

    private fun fixture(name: String): String =
        GZIPInputStream(javaClass.classLoader!!.getResourceAsStream(name)!!).use { String(it.readBytes(), Charsets.UTF_8) }

    // ---------- 網址正規化 ----------

    @Test fun normalizeStandard() = assertEquals(canon, PostParser.normalize("https://www.youtube.com/post/$id"))
    @Test fun normalizeNoScheme() = assertEquals(canon, PostParser.normalize("youtube.com/post/$id"))
    @Test fun normalizeMobile() = assertEquals(canon, PostParser.normalize("https://m.youtube.com/post/$id"))
    @Test fun normalizeSi() = assertEquals(canon, PostParser.normalize("https://www.youtube.com/post/$id?si=AbC_123-xyz"))
    @Test fun normalizeFragment() = assertEquals(canon, PostParser.normalize("https://www.youtube.com/post/$id#comments"))
    @Test fun normalizeYoutuBe() = assertEquals(canon, PostParser.normalize("https://youtu.be/post/$id?si=abc"))
    @Test fun normalizeTrailingSlash() = assertEquals(canon, PostParser.normalize("https://www.youtube.com/post/$id/"))
    @Test fun normalizeChannelCommunityLb() =
        assertEquals(canon, PostParser.normalize("https://www.youtube.com/channel/UCZBxdXr_5HXL66NJfB6mKzA/community?lb=$id"))
    @Test fun normalizeHandleCommunityLb() =
        assertEquals(canon, PostParser.normalize("https://m.youtube.com/@NASA/community?lb=$id&si=zzz"))
    @Test fun normalizeHandlePostsLb() =
        assertEquals(canon, PostParser.normalize("https://www.youtube.com/@%E7%BE%8E/posts?foo=1&lb=$id"))
    @Test fun normalizeBareId() = assertEquals(canon, PostParser.normalize(id))
    @Test fun normalizeUppercaseHost() = assertEquals(canon, PostParser.normalize("HTTPS://WWW.YOUTUBE.COM/post/$id"))

    @Test fun normalizeFromShareText() {
        val share = "看看這則貼文 https://www.youtube.com/post/$id?si=Q1w2E3 好好看！"
        assertEquals("https://www.youtube.com/post/$id?si=Q1w2E3", PostParser.extractUrl(share))
        assertEquals(canon, PostParser.normalize(share))
    }

    @Test fun shareTextWithTitleLine() {
        val share = "Gemini 4 is here\nhttps://youtube.com/post/$id?si=abc\n"
        assertEquals(canon, PostParser.normalize(share))
    }

    @Test fun shareTextTrailingPunctuation() =
        assertEquals(canon, PostParser.normalize("連結：https://www.youtube.com/post/$id。"))

    @Test fun invalidInputs() {
        assertNull(PostParser.normalize(null))
        assertNull(PostParser.normalize(""))
        assertNull(PostParser.normalize("hello world"))
        assertNull(PostParser.normalize("https://example.com/post/$id"))
        assertNull(PostParser.normalize("https://notyoutube.com/post/$id"))
        assertNull(PostParser.normalize("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertNull(PostParser.normalize("https://www.youtube.com/@NASA/community"))
        assertNull(PostParser.normalize("https://www.youtube.com/post/"))
    }

    // ---------- 尺寸後綴改寫 ----------

    @Test fun rewriteSuffixCropped() = assertEquals(
        "https://yt3.ggpht.com/ABC=d",
        PostParser.rewriteSuffix("https://yt3.ggpht.com/ABC=s1536-c-fcrop64=1,00002000ffffdfff-nd-v1?days_since_epoch=20774")
    )
    @Test fun rewriteSuffixS0() = assertEquals(
        "https://yt3.ggpht.com/ABC=s0",
        PostParser.rewriteSuffix("https://yt3.ggpht.com/ABC=s640-rw-nd-v1", "s0")
    )
    @Test fun rewriteSuffixEscaped() = assertEquals(
        "https://yt3.ggpht.com/ABC=d",
        PostParser.rewriteSuffix("https:\\/\\/yt3.ggpht.com\\/ABC\\u003ds288-c-fcrop64\\u003d1,0000")
    )
    @Test fun rewriteSuffixNoSuffix() = assertEquals("https://yt3.ggpht.com/ABC=d", PostParser.rewriteSuffix("https://yt3.ggpht.com/ABC"))
    @Test fun rewriteSuffixProtocolRelative() = assertEquals("https://yt3.ggpht.com/ABC=d", PostParser.rewriteSuffix("//yt3.ggpht.com/ABC=s48"))

    @Test fun postImageUrls() {
        val img = PostImage("A".repeat(40), "yt3.ggpht.com")
        assertEquals("https://yt3.ggpht.com/${"A".repeat(40)}=d", img.originalUrl)
        assertEquals("https://yt3.ggpht.com/${"A".repeat(40)}=s0", img.fallbackUrl)
    }

    // ---------- 頭像判斷 ----------

    @Test fun avatarDetection() {
        assertTrue(PostParser.isAvatarLike("=s32-c-k-c0x00ffffff-no-rj-mo"))
        assertTrue(PostParser.isAvatarLike("=s48-c-k-c0x00ffffff-no-rj"))
        assertTrue(PostParser.isAvatarLike("=s88-c-k-c0x00ffffff-no-rj"))
        assertTrue(PostParser.isAvatarLike("=s64-c"))
        assertFalse(PostParser.isAvatarLike("=s1536-c-fcrop64=1,00002000ffffdfff-nd-v1"))
        assertFalse(PostParser.isAvatarLike("=s288-c-fcrop64=1,00000000ffffffff-rw-nd-v1"))
        assertFalse(PostParser.isAvatarLike("=s640-rw-nd-v1"))
        assertFalse(PostParser.isAvatarLike(null))
    }

    // ---------- 真實貼文 HTML 快照 ----------

    @Test fun parseSingleImageFixture() {
        val html = fixture("post_single.html.gz")
        val imgs = PostParser.parseImages(html, "UgkxzpmkfWMHr_dBio38Y67wQPgGwdnepZpm")
        assertEquals(1, imgs.size)
        assertEquals("LFQ1c5bK3r2YLN4zgx9yAU2EK8OuNHQF6V4ko-kzVqa0KvsCxIzO5bnBGhjJy3d9nlVKxFljFvKhiQ", imgs[0].id)
        assertEquals("yt3.ggpht.com", imgs[0].host)
        assertTrue(imgs[0].originalUrl.endsWith("=d"))
    }

    @Test fun parseMultiImageFixture() {
        val html = fixture("post_multi.html.gz")
        val imgs = PostParser.parseImages(html, id)
        assertEquals(3, imgs.size)
        assertEquals(
            listOf(
                "lv_RoK3BBql3ZfIqxJAhhy8GtdJ5Ju7JJNBNGOTMMHR8E9PLUys1rwr2IJEUxb1Pvy3R6l7WLv7E",
                "b3rPZN7iqKUFk1obo0krg-Ubci-rnDtTTOTSH38SD2r8ULEYP6u8ZagZzI3yqWe-PVPAlLYXrHPadA",
                "fKtHEVmD8HCHX_QbUCz7uUxW99kF0zkza2sp3M-bbIjU1gpba2He7kOwyUR3RB9KDYtH_YywT9ysckE"
            ),
            imgs.map { it.id }
        )
        // 不可混入頻道頭像（yt3.googleusercontent.com/...-no-rj）
        assertTrue(imgs.none { it.host.contains("googleusercontent") })
    }

    @Test fun parseWithoutPostIdStillWorks() {
        assertEquals(3, PostParser.parseImages(fixture("post_multi.html.gz")).size)
    }

    @Test fun regexFallbackFiltersAvatars() {
        // 沒有 backstageAttachment 與 JSON-LD 時，退回全頁 regex：頭像要被排除
        val html = """<html><script>
            var a = "https://yt3.googleusercontent.com/${"Z".repeat(60)}=s48-c-k-c0x00ffffff-no-rj-mo";
            var b = "https://yt3.ggpht.com/${"Y".repeat(60)}=s800-c-fcrop64=1,00000000ffffffff-rw-nd-v1";
            var c = "https://yt3.ggpht.com/${"Y".repeat(60)}=s1080-c-fcrop64=1,00000000ffffffff-rw-nd-v1";
            var d = "https://yt3.ggpht.com/ytc/AIdro_kAbCdEfGhIjKlMnOpQrStUvWxYz=s88-c-k-c0x00ffffff-no-rj";
            </script></html>"""
        val imgs = PostParser.parseImages(html)
        assertEquals(listOf("Y".repeat(60)), imgs.map { it.id })
    }

    @Test fun escapedJsonForms() {
        val img1 = "A".repeat(50)
        val img2 = "B".repeat(50)
        val html = """"postId":"$id","backstageAttachment":{"postMultiImageRenderer":{"images":[
            {"backstageImageRenderer":{"image":{"thumbnails":[{"url":"https:\/\/yt3.ggpht.com\/$img1\u003ds288-c-fcrop64\u003d1,00002000ffffdfff-nd-v1","width":288}]}}},
            {"backstageImageRenderer":{"image":{"thumbnails":[{"url":"https:\/\/yt3.ggpht.com\/$img2\u003ds288-c-fcrop64\u003d1,00002000ffffdfff-nd-v1"},{"url":"https:\/\/yt3.ggpht.com\/$img2\u003ds400-c"}]}}}
            ]}},"other":{"url":"https://yt3.ggpht.com/${"C".repeat(50)}=s1024-rw-nd-v1"}"""
        val imgs = PostParser.parseImages(html, id)
        // 只取 backstageAttachment 內的圖片（"other" 內的不算），並依圖片 ID 去重
        assertEquals(listOf(img1, img2), imgs.map { it.id })
    }

    @Test fun noImagesReturnsEmpty() {
        assertTrue(PostParser.parseImages("<html><body>no images here</body></html>", id).isEmpty())
    }

    @Test fun balancedObjectHandlesBracesInStrings() {
        val s = """x{"a":"}{\"}","b":{"c":1}}tail"""
        assertEquals("""{"a":"}{\"}","b":{"c":1}}""", PostParser.balancedObject(s, 1))
    }

    @Test fun extensionDetection() {
        assertEquals("jpg", YouTubeClient.extensionFor("image/jpeg", ByteArray(0)))
        assertEquals("png", YouTubeClient.extensionFor("image/png; charset=x", ByteArray(0)))
        assertEquals("webp", YouTubeClient.extensionFor("image/webp", ByteArray(0)))
        assertEquals("png", YouTubeClient.extensionFor("application/octet-stream", byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)))
        assertEquals("jpg", YouTubeClient.extensionFor(null, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
        assertEquals("post_1.jpg".replace("post_", "X_"), ImageSaver.fileName("X", 1, "jpg"))
    }
}
