package com.github.toober77.ytpostimage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.GZIPInputStream

class InstagramParserTest {
    private fun fixture(name: String): String =
        GZIPInputStream(javaClass.classLoader!!.getResourceAsStream(name)!!).use { String(it.readBytes(), Charsets.UTF_8) }

    // ---------- 連結 ----------
    private fun code(s: String?) = InstagramParser.parseLink(s)?.code
    private fun kind(s: String?) = InstagramParser.parseLink(s)?.kind

    @Test fun linkForms() {
        val c = "DA4eKVWJTTT"
        assertEquals(c, code("https://www.instagram.com/p/$c/"))
        assertEquals(c, code("https://instagram.com/p/$c"))
        assertEquals(c, code("instagram.com/p/$c/"))
        assertEquals(c, code("https://m.instagram.com/p/$c/?igsh=MTIzNDU2"))
        assertEquals(c, code("https://www.instagram.com/p/$c/?utm_source=ig_web_copy_link&igsh=abc"))
        assertEquals(c, code("https://www.instagram.com/natgeo/p/$c/?hl=en"))
        assertEquals(c, code("https://www.instagram.com/p/$c/embed/captioned/"))
        assertEquals(c, code("https://instagr.am/p/$c/"))
        assertEquals("reel", kind("https://www.instagram.com/reel/$c/?igsh=x"))
        assertEquals("reel", kind("https://www.instagram.com/nasa/reel/$c/"))
        assertEquals("reel", kind("https://www.instagram.com/reels/$c/"))
        assertEquals("tv", kind("https://www.instagram.com/tv/$c/"))
        assertEquals("p", kind("https://www.instagram.com/p/$c/"))
    }

    @Test fun codesWithUnderscoreAndDash() {
        assertEquals("DLk7k_txiKY", code("https://www.instagram.com/natgeo/p/DLk7k_txiKY/"))
        assertEquals("C-ab_c-d12", code("https://www.instagram.com/p/C-ab_c-d12/"))
    }

    @Test fun shareText() {
        assertEquals("DA4eKVWJTTT", code("看這個 https://www.instagram.com/p/DA4eKVWJTTT/?igsh=abc 超美！"))
        assertEquals("DA4eKVWJTTT", code("https://www.instagram.com/reel/DA4eKVWJTTT/?igsh=abc。"))
    }

    @Test fun invalidLinks() {
        assertNull(code(null))
        assertNull(code(""))
        assertNull(code("hello"))
        assertNull(code("https://www.instagram.com/"))
        assertNull(code("https://www.instagram.com/nasa/"))
        assertNull(code("https://www.instagram.com/stories/nasa/123456789/"))
        assertNull(code("https://www.instagram.com/explore/tags/nasa/"))
        assertNull(code("https://notinstagram.com/p/DA4eKVWJTTT/"))
        assertNull(code("https://example.com/p/DA4eKVWJTTT/"))
        assertNull(code("https://www.instagram.com/p/"))
        assertNull(code("https://www.instagram.com/p/abc/")) // 太短
    }

    @Test fun canonical() {
        val l = InstagramParser.parseLink("https://m.instagram.com/natgeo/p/DA4eKVWJTTT/?igsh=1")!!
        assertEquals("https://www.instagram.com/p/DA4eKVWJTTT/", l.canonicalUrl)
        assertEquals("https://www.instagram.com/p/DA4eKVWJTTT/embed/captioned/", l.embedUrl)
    }

    // ---------- 平台自動判斷 ----------
    @Test fun platformDetection() {
        val yt = LinkParser.parse("https://www.youtube.com/post/UgkxruCFoMbuqHeiXLzDnT2c3GPDomU4aju5?si=x")
        assertEquals(Link.YouTube("UgkxruCFoMbuqHeiXLzDnT2c3GPDomU4aju5"), yt)
        assertEquals("UgkxruCFoMbuqHeiXLzDnT2c3GPDomU4aju5", yt!!.filePrefix)
        val ig = LinkParser.parse("https://www.instagram.com/p/DA4eKVWJTTT/?igsh=1")
        assertTrue(ig is Link.Instagram)
        assertEquals("ig_DA4eKVWJTTT", ig!!.filePrefix)
        assertEquals("https://www.instagram.com/p/DA4eKVWJTTT/", LinkParser.extractUrl("看 https://www.instagram.com/p/DA4eKVWJTTT/?igsh=1 好看"))
        assertNull(LinkParser.parse("https://example.com/x"))
        assertEquals("ig_DA4eKVWJTTT_1.jpg", ImageSaver.fileName(ig.filePrefix, 1, "jpg"))
        assertEquals("UgkxruCFoMbuqHeiXLzDnT2c3GPDomU4aju5_2.png", ImageSaver.fileName(yt.filePrefix, 2, "png"))
    }

    @Test fun youtubeStillWorksWhenTextAlsoMentionsNothingElse() {
        assertEquals("UgkxruCFoMbuqHeiXLzDnT2c3GPDomU4aju5",
            (LinkParser.parse("看 https://youtu.be/post/UgkxruCFoMbuqHeiXLzDnT2c3GPDomU4aju5 !") as Link.YouTube).postId)
    }

    // ---------- 真實 embed 頁快照 ----------
    @Test fun carouselLandscape() {
        val link = InstagramParser.parseLink("https://www.instagram.com/p/DA4eKVWJTTT/")!!
        val r = InstagramParser.parse(fixture("ig_DA4eKVWJTTT.html.gz"), link)
        assertEquals(4, r.images.size)
        assertEquals(0, r.skippedVideos)
        assertFalse(r.isVideoPost)
        assertEquals(4, r.images.map { it.id }.toSet().size)
        for (i in r.images) {
            assertTrue(i.fullUrl!!.startsWith("https://"))
            assertFalse("no JSON escapes left: ${i.fullUrl}", i.fullUrl!!.contains("\\"))
            assertFalse(i.fullUrl!!.contains("&amp;"))
            assertTrue(i.fullUrl!!.contains("cdninstagram.com"))
            assertEquals(i.fullUrl, i.originalUrl)
            // 縮圖是同比例較小的變體
            assertNotNull(i.thumb)
            assertTrue(i.thumb != i.fullUrl)
        }
    }

    @Test fun carouselPortrait() {
        val r = InstagramParser.parse(fixture("ig_C_lb34cSmob.html.gz"), InstagramParser.parseLink("https://www.instagram.com/p/C_lb34cSmob/"))
        assertEquals(2, r.images.size)
        assertTrue(r.images.all { it.fullUrl!!.contains("%3D") || !it.fullUrl!!.contains("u0025") })
    }

    @Test fun singlePhotoFromImgTag() {
        val html = fixture("ig_DBPp0PNtizI.html.gz")
        assertNull(InstagramParser.shortcodeMedia(html)) // contextJSON 為 null
        val r = InstagramParser.parse(html, InstagramParser.parseLink("https://www.instagram.com/p/DBPp0PNtizI/"))
        assertEquals(1, r.images.size)
        val i = r.images[0]
        assertFalse(i.fullUrl!!.contains("&amp;"))
        assertTrue(i.fullUrl!!.contains("&")) // 有簽章參數
        assertNotNull(i.thumb)
    }

    @Test fun reelIsVideoPost() {
        val r = InstagramParser.parse(fixture("ig_DcBx_I1iHZW.html.gz"), InstagramParser.parseLink("https://www.instagram.com/reel/DcBx_I1iHZW/"))
        assertTrue(r.isVideoPost)
        assertTrue(r.images.isEmpty())
    }

    @Test fun reelWithoutContextJsonTreatedAsVideo() {
        val html = fixture("ig_DBPp0PNtizI.html.gz")
        val r = InstagramParser.parse(html, IgLink("DBPp0PNtizI", "reel"))
        assertTrue(r.isVideoPost)
        assertTrue(r.images.isEmpty())
    }

    @Test fun emptyOrBlockedPage() {
        val r = InstagramParser.parse("<html><body>Login • Instagram</body></html>")
        assertTrue(r.images.isEmpty()); assertFalse(r.isVideoPost); assertEquals(0, r.skippedVideos)
    }

    // ---------- 合成資料：影片投影片、跳脫格式 ----------
    private fun synthetic(children: String, extra: String = ""): String {
        val inner = """{"context":{"type":"GraphSidecar"},"gql_data":{"shortcode_media":{"is_video":false,"display_url":"https:\/\/cdn.example.cdninstagram.com\/v\/top.jpg?a=1\u0026b=2","dimensions":{"width":1080,"height":1350},"edge_sidecar_to_children":{"edges":[$children]}$extra}}}"""
        val lit = com.github.toober77.ytpostimage.MiniJsonTestHelper.quote(inner)
        return """<html><script>{"contextJSON":$lit}</script></html>"""
    }

    private fun node(id: String, video: Boolean, w: Int = 1080, h: Int = 1350) =
        """{"node":{"id":"$id","is_video":$video,"dimensions":{"width":$w,"height":$h},"display_url":"https:\/\/x.cdninstagram.com\/v\/$id.jpg?sig=a\u0026e=2%3D","display_resources":[{"src":"https:\/\/x.cdninstagram.com\/v\/${id}_big.jpg","config_width":$w,"config_height":$h},{"src":"https:\/\/x.cdninstagram.com\/v\/${id}_720.jpg","config_width":720,"config_height":${720 * h / w}},{"src":"https:\/\/x.cdninstagram.com\/v\/${id}_sq.jpg","config_width":720,"config_height":720},{"src":"https:\/\/x.cdninstagram.com\/v\/${id}_150.jpg","config_width":150,"config_height":150}]}}"""

    @Test fun videoSlidesSkipped() {
        val html = synthetic(listOf(node("1", false), node("2", true), node("3", false)).joinToString(","))
        val r = InstagramParser.parse(html)
        assertEquals(listOf("ig_1", "ig_3"), r.images.map { it.id })
        assertEquals(1, r.skippedVideos)
        assertFalse(r.isVideoPost)
        // 解跳脫：&、%3D
        assertEquals("https://x.cdninstagram.com/v/1.jpg?sig=a&e=2%3D", r.images[0].fullUrl)
        // 縮圖：同比例、寬 ≥ 640、比原圖小 → 720 版（不是方形裁切版）
        assertEquals("https://x.cdninstagram.com/v/1_720.jpg", r.images[0].thumb)
    }

    @Test fun allVideoSlides() {
        val html = synthetic(listOf(node("1", true), node("2", true)).joinToString(","))
        val r = InstagramParser.parse(html)
        assertTrue(r.images.isEmpty()); assertEquals(2, r.skippedVideos)
    }

    @Test fun duplicateChildrenDeduped() {
        val html = synthetic(listOf(node("1", false), node("1", false)).joinToString(","))
        assertEquals(1, InstagramParser.parse(html).images.size)
    }

    @Test fun unescapedObjectContextJson() {
        val html = """<script>"contextJSON":{"gql_data":{"shortcode_media":{"is_video":false,"id":"99","display_url":"https://x.cdninstagram.com/v/a.jpg","dimensions":{"width":10,"height":10}}}}</script>"""
        val r = InstagramParser.parse(html)
        assertEquals(1, r.images.size)
        assertEquals("ig_99", r.images[0].id)
    }

    @Test fun doubleEscapedPage() {
        val inner = synthetic(node("7", false))
        val esc = inner.replace("\\", "\\\\").replace("\"", "\\\"")
        val r = InstagramParser.parse("<script>\"data\":\"$esc\"</script>")
        assertEquals(listOf("ig_7"), r.images.map { it.id })
    }

    @Test fun imgTagSrcsetPicksLargestOfFirstRun() {
        val html = """<img class="EmbeddedMediaImage" alt="x" src="https://x.cdninstagram.com/v/s.jpg?a=1&amp;b=2" srcset="https://x.cdninstagram.com/v/l.jpg?a=1&amp;b=2 1439w,https://x.cdninstagram.com/v/m.jpg 720w,https://x.cdninstagram.com/v/n.jpg 640w,https://x.cdninstagram.com/v/q.jpg 959w" />"""
        val r = InstagramParser.parse(html)
        assertEquals("https://x.cdninstagram.com/v/l.jpg?a=1&b=2", r.images[0].fullUrl)
        assertEquals("https://x.cdninstagram.com/v/n.jpg", r.images[0].thumb)
    }

    // ---------- MiniJson ----------
    @Test fun miniJson() {
        val v = MiniJson.obj(MiniJson.parse("""{"a":[1,2.5,"x\n\u0041\/",true,null,{"b":{}}],"c":-3e2}"""))!!
        val a = MiniJson.arr(v["a"])!!
        assertEquals(1.0, a[0]); assertEquals(2.5, a[1]); assertEquals("x\nA/", a[2]); assertEquals(true, a[3]); assertNull(a[4])
        assertEquals(-300.0, v["c"])
        assertNull(MiniJson.parseOrNull("{\"a\":"))
        assertNull(MiniJson.parseOrNull("[1,2,]x"))
    }
}

object MiniJsonTestHelper {
    /** 把字串轉成 JSON 字串字面量。 */
    fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            else -> sb.append(c)
        }
        return sb.append('"').toString()
    }
}
