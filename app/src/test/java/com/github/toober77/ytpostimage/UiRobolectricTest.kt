package com.github.toober77.ytpostimage

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 以 Robolectric（NATIVE 繪圖）在無頭環境檢查版面、編輯器與「旋轉＋裁切套用到原圖」。
 * 截圖輸出到 build/screens/（環境變數 YTPOST_SHOTS 指定其他目錄）。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class UiRobolectricTest {
    private val outDir = File(System.getenv("YTPOST_SHOTS") ?: "build/screens").apply { mkdirs() }

    @Before fun reset() = EditStore.clear()

    private fun synthetic(w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        // 四象限不同顏色，方便驗證旋轉方向
        p.color = Color.RED; c.drawRect(0f, 0f, w / 2f, h / 2f, p)        // 左上 紅
        p.color = Color.GREEN; c.drawRect(w / 2f, 0f, w.toFloat(), h / 2f, p) // 右上 綠
        p.color = Color.BLUE; c.drawRect(0f, h / 2f, w / 2f, h.toFloat(), p)  // 左下 藍
        p.color = Color.YELLOW; c.drawRect(w / 2f, h / 2f, w.toFloat(), h.toFloat(), p) // 右下 黃
        p.color = Color.BLACK; p.textSize = w / 8f
        c.drawText("TOP", w * 0.1f, h * 0.2f, p)
        return b
    }

    private fun jpeg(b: Bitmap, q: Int = 95): ByteArray = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, q, it) }.toByteArray()
    private fun png(b: Bitmap): ByteArray = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    private fun near(c: Int, expected: Int, tol: Int = 40): Boolean =
        Math.abs(Color.red(c) - Color.red(expected)) <= tol && Math.abs(Color.green(c) - Color.green(expected)) <= tol && Math.abs(Color.blue(c) - Color.blue(expected)) <= tol

    private fun shot(v: View, name: String) {
        val w = v.width.takeIf { it > 0 } ?: 1080
        val h = v.height.takeIf { it > 0 } ?: 2337
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        File(outDir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("SHOT ${File(outDir, name).absolutePath} ${w}x$h")
    }

    // ---------- ImageEditor：真正的像素驗證 ----------

    @Test fun applyRotate90Cw() {
        val src = DownloadedImage(png(synthetic(400, 200)), "image/png", "x")
        val st = EditState(400, 200, 1, 0.0, CropRect(0.0, 0.0, 200.0, 400.0)) // 順時針 90° 後畫布 200x400
        val out = ImageEditor.apply(src, st)
        assertEquals(1, out.downscale)
        val bmp = BitmapFactory.decodeByteArray(out.image.bytes, 0, out.image.bytes.size)
        assertEquals(200, bmp.width); assertEquals(400, bmp.height)
        // 原：左上紅、右上綠、左下藍、右下黃 → 順時針 90°：左上=藍、右上=紅、左下=黃、右下=綠
        assertTrue("top-right red", near(bmp.getPixel(180, 20), Color.RED))
        assertTrue("bottom-left yellow", near(bmp.getPixel(20, 380), Color.YELLOW))
        assertTrue("top-left blue", near(bmp.getPixel(20, 20), Color.BLUE))
        assertTrue("bottom-right green", near(bmp.getPixel(180, 380), Color.GREEN))
        assertEquals("image/png", out.image.contentType)
    }

    @Test fun applyCropOnlyJpeg() {
        val src = DownloadedImage(jpeg(synthetic(400, 800)), "image/jpeg", "x")
        // 9:16 取右下角 200x355
        val crop = CropRect(200.0, 445.0, 400.0, 800.0)
        val st = EditState(400, 800, 0, 0.0, crop)
        val out = ImageEditor.apply(src, st)
        val bmp = BitmapFactory.decodeByteArray(out.image.bytes, 0, out.image.bytes.size)
        assertEquals(200, bmp.width); assertEquals(355, bmp.height)
        assertTrue(near(bmp.getPixel(100, 100), Color.YELLOW))
        assertEquals("image/jpeg", out.image.contentType)
        assertEquals("jpg", out.image.extension)
    }

    @Test fun applyFineRotationHasNoBlankCorners() {
        val src = DownloadedImage(jpeg(synthetic(600, 800)), "image/jpeg", "x")
        val crop = EditMath.largestCentered(600.0, 800.0, 7.5, 9.0 / 16)
        val st = EditState(600, 800, 0, 7.5, crop)
        val out = ImageEditor.apply(src, st)
        val bmp = BitmapFactory.decodeByteArray(out.image.bytes, 0, out.image.bytes.size)
        val (ow, oh) = EditMath.outputSize(crop)
        assertEquals(ow, bmp.width); assertEquals(oh, bmp.height)
        // 四個角的像素都不應是（JPEG 填的）黑色背景
        for ((x, y) in listOf(2 to 2, bmp.width - 3 to 2, 2 to bmp.height - 3, bmp.width - 3 to bmp.height - 3)) {
            val c = bmp.getPixel(x, y)
            assertTrue("corner $x,$y = ${Integer.toHexString(c)}", Color.red(c) + Color.green(c) + Color.blue(c) > 150)
        }
    }

    @Test fun boundsReader() {
        assertEquals(400 to 200, ImageEditor.bounds(png(synthetic(400, 200))))
    }

    // ---------- EditActivity ----------

    private fun launchEditor(id: String, w: Int, h: Int): org.robolectric.android.controller.ActivityController<EditActivity> {
        EditStore.seedOriginal(id, DownloadedImage(jpeg(synthetic(w, h)), "image/jpeg", "x"))
        val intent = Intent(RuntimeEnvironment.getApplication(), EditActivity::class.java)
            .putExtra(EditActivity.EXTRA_ID, id).putExtra(EditActivity.EXTRA_HOST, "yt3.ggpht.com")
        return Robolectric.buildActivity(EditActivity::class.java, intent).setup()
    }

    private fun waitLoaded(ctrl: org.robolectric.android.controller.ActivityController<EditActivity>) {
        val a = ctrl.get()
        val done = a.findViewById<View>(android.R.id.content)
        repeat(100) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            if (findDone(done)?.isEnabled == true) return
            Thread.sleep(50)
        }
    }

    private fun allButtons(v: View, out: MutableList<Button> = ArrayList()): List<Button> {
        if (v is Button) out.add(v)
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) allButtons(v.getChildAt(i), out)
        return out
    }
    private fun findDone(root: View) = allButtons(root).firstOrNull { it.text == "完成" }
    private fun btn(a: Activity, text: String) = allButtons(a.findViewById(android.R.id.content)).first { it.text.toString().contains(text) }

    @Test fun editorFlow() {
        val id = "A".repeat(50)
        val ctrl = launchEditor(id, 1536, 2048)
        waitLoaded(ctrl)
        val a = ctrl.get()
        val root = a.window.decorView
        shot(root, "editor_initial.png")

        // 9:16
        btn(a, "9:16").performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        // 順時針 90° 後再微調 + 歸零
        btn(a, "⟳").performClick()
        val seek = allViews(root).filterIsInstance<SeekBar>().first()
        seek.progress = 45 * 10 + 85 // +8.5°
        val l = SeekBar::class.java.getDeclaredField("mOnSeekBarChangeListener").apply { isAccessible = true }.get(seek) as SeekBar.OnSeekBarChangeListener
        l.onProgressChanged(seek, seek.progress, true)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        shot(root, "editor_rotated.png")
        val cv = allViews(root).filterIsInstance<CropView>().first().state!!
        assertEquals("rotated crop keeps 9:16", 9.0 / 16, cv.crop.width / cv.crop.height, 1e-3)
        assertEquals(1, cv.quarter)
        assertEquals(8.5, cv.fine, 1e-6)

        btn(a, "歸零").performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(45 * 10, seek.progress)
        shot(root, "editor_reset.png")

        btn(a, "完成").performClick()
        val st = EditStore.getEdit(id)
        assertNotNull(st)
        assertEquals(0, st!!.quarter)
        assertEquals(0.0, st.fine, 1e-9)
        assertEquals(EditState.MODE_9_16, st.mode)
        assertEquals(9.0 / 16, st.crop.width / st.crop.height, 1e-3)
        assertTrue(a.isFinishing)
    }

    @Test fun editorCancelKeepsNothing() {
        val id = "B".repeat(50)
        val ctrl = launchEditor(id, 800, 600)
        waitLoaded(ctrl)
        val a = ctrl.get()
        btn(a, "⟳").performClick()
        btn(a, "取消").performClick()
        assertEquals(null, EditStore.getEdit(id))
        assertTrue(a.isFinishing)
    }

    @Test fun editorIdentityIsNotStored() {
        val id = "C".repeat(50)
        val ctrl = launchEditor(id, 800, 600)
        waitLoaded(ctrl)
        btn(ctrl.get(), "完成").performClick()
        assertEquals(null, EditStore.getEdit(id))
    }

    @Test fun editorRestoresPreviousState() {
        val id = "D".repeat(50)
        val first = launchEditor(id, 1536, 2048)
        waitLoaded(first)
        btn(first.get(), "2:3").performClick()
        btn(first.get(), "⟲").performClick()
        btn(first.get(), "完成").performClick()
        val saved = EditStore.getEdit(id)!!
        assertEquals(3, saved.quarter)
        assertEquals(EditState.MODE_2_3, saved.mode)
        val second = launchEditor(id, 1536, 2048)
        waitLoaded(second)
        btn(second.get(), "完成").performClick()
        val again = EditStore.getEdit(id)!!
        assertEquals(saved.quarter, again.quarter)
        assertEquals(saved.crop.width, again.crop.width, 1.0)
    }

    private fun allViews(v: View, out: MutableList<View> = ArrayList()): List<View> {
        out.add(v)
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) allViews(v.getChildAt(i), out)
        return out
    }

    // ---------- MainActivity 版面 ----------

    @Test fun mainScreenLayout() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        shot(a.window.decorView, "main_empty.png")
        val ids = listOf("a", "b", "c", "d", "e").map { (it.repeat(50)) }
        a.showImagesForTest(ids.map { PostImage(it, "yt3.ggpht.com") }, "UgkxTestPost0000000")
        EditStore.setEdit(ids[1], EditState(100, 100, 1, 0.0, CropRect(0.0, 0.0, 100.0, 100.0)))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        shot(a.window.decorView, "main_grid.png")
        assertTrue(allButtons(a.window.decorView).any { it.text.toString().contains("儲存 5 張") })
    }

    /** 連真實 YouTube 的截圖（需 YTPOST_LIVE=貼文ID）。 */
    @Test fun liveShots() {
        val pid = System.getenv("YTPOST_LIVE")?.split(',')?.first()?.trim()
        org.junit.Assume.assumeTrue(!pid.isNullOrBlank())
        val imgs = PostParser.parseImages(YouTubeClient.fetchHtml(PostParser.canonicalUrl(pid!!)), pid)
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        a.showImagesForTest(imgs, pid)
        repeat(100) { shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(50) }
        EditStore.setEdit(imgs[1].id, EditState(1080, 1080, 0, 0.0, CropRect(100.0, 0.0, 700.0, 1080.0)))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        shot(a.window.decorView, "live_main.png")
        EditStore.clear()
        val intent = Intent(RuntimeEnvironment.getApplication(), EditActivity::class.java)
            .putExtra(EditActivity.EXTRA_ID, imgs[0].id).putExtra(EditActivity.EXTRA_HOST, imgs[0].host)
        val ctrl = Robolectric.buildActivity(EditActivity::class.java, intent).setup()
        waitLoaded(ctrl)
        val e = ctrl.get()
        btn(e, "9:16").performClick()
        val root = e.window.decorView
        val seek = allViews(root).filterIsInstance<SeekBar>().first()
        seek.progress = 45 * 10 - 40
        val l = SeekBar::class.java.getDeclaredField("mOnSeekBarChangeListener").apply { isAccessible = true }.get(seek) as SeekBar.OnSeekBarChangeListener
        l.onProgressChanged(seek, seek.progress, true)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        shot(root, "live_editor.png")
    }

    // ---------- 儲存流程（含自動關閉）----------

    class FakeMedia : android.content.ContentProvider() {
        companion object { val saved = ArrayList<Pair<String, File>>(); var failInsert = false }
        override fun onCreate() = true
        override fun query(u: android.net.Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): android.database.Cursor? = null
        override fun getType(u: android.net.Uri): String? = null
        override fun insert(u: android.net.Uri, v: android.content.ContentValues?): android.net.Uri? {
            if (failInsert) return null
            val f = File.createTempFile("saved", ".bin")
            saved.add((v?.getAsString("_display_name") ?: "?") to f)
            return android.net.Uri.parse("content://media/external/images/media/${saved.size}")
        }
        override fun delete(u: android.net.Uri, s: String?, a: Array<out String>?) = 0
        override fun update(u: android.net.Uri, v: android.content.ContentValues?, s: String?, a: Array<out String>?) = 1
        override fun openFile(u: android.net.Uri, mode: String): android.os.ParcelFileDescriptor {
            val idx = u.lastPathSegment!!.toInt() - 1
            return android.os.ParcelFileDescriptor.open(saved[idx].second, android.os.ParcelFileDescriptor.MODE_READ_WRITE or android.os.ParcelFileDescriptor.MODE_TRUNCATE)
        }
    }

    private fun saveScenario(fail: Boolean): Pair<MainActivity, List<Pair<String, File>>> {
        FakeMedia.saved.clear(); FakeMedia.failInsert = fail
        Robolectric.setupContentProvider(FakeMedia::class.java, "media")
        val id = "S".repeat(50)
        val src = DownloadedImage(jpeg(synthetic(800, 1200)), "image/jpeg", "x")
        EditStore.seedOriginal(id, src)
        val st = EditState(800, 1200, 1, 0.0, CropRect(0.0, 0.0, 1200.0, 800.0))
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        a.showImagesForTest(listOf(PostImage(id, "yt3.ggpht.com")), "UgkxSavePost00000")
        EditStore.setEdit(id, st)
        allButtons(a.window.decorView).first { it.text.toString().startsWith("儲存") }.performClick()
        repeat(100) { shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(30) }
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(3))
        return a to FakeMedia.saved.toList()
    }

    @Test fun saveEditedThenAutoClose() {
        val (a, saved) = saveScenario(false)
        assertEquals(1, saved.size)
        assertEquals("UgkxSavePost00000_1.jpg", saved[0].first)
        val bmp = BitmapFactory.decodeFile(saved[0].second.path)
        assertEquals(1200, bmp.width); assertEquals(800, bmp.height) // 旋轉 90° 後的尺寸
        assertTrue("auto close after success", a.isFinishing)
    }

    @Test fun saveFailureDoesNotClose() {
        val (a, saved) = saveScenario(true)
        assertEquals(0, saved.size)
        assertTrue("must stay open on failure", !a.isFinishing)
    }
}
