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
import org.junit.Assert.assertNull
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

    private fun solid(w: Int, h: Int, color: Int, label: String): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(color)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.WHITE; textSize = w / 5f }
        c.drawText(label, w * 0.1f, h / 2f, p)
        return b
    }

    private fun editButtons(root: View) = allViews(root).filter { it is android.widget.ImageView && it.contentDescription == "編輯" }

    private fun makeMain(ratios: List<Pair<Int, Int>>): Triple<org.robolectric.android.controller.ActivityController<MainActivity>, List<PostImage>, String> {
        val ctrl = Robolectric.buildActivity(MainActivity::class.java).setup()
        val a = ctrl.get()
        val ids = ratios.indices.map { ('a' + it).toString().repeat(50) }
        val imgs = ids.map { PostImage(it, "yt3.ggpht.com") }
        val colors = listOf(0xFF1565C0, 0xFF2E7D32, 0xFF6A1B9A, 0xFFEF6C00, 0xFF00838F, 0xFFAD1457).map { it.toInt() }
        imgs.forEachIndexed { i, img -> a.seedThumb(img, solid(ratios[i].first, ratios[i].second, colors[i % colors.size], "${i + 1}")) }
        a.showImagesForTest(imgs, "UgkxTestPost0000000")
        shadowOf(android.os.Looper.getMainLooper()).idle()
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        return Triple(ctrl, imgs, "UgkxTestPost0000000")
    }

    @Test fun mainScreenLayoutKeepsAspectRatios() {
        val ratios = listOf(1080 to 1080, 810 to 1080, 1080 to 608, 810 to 1080, 640 to 427)
        val (ctrl, _, _) = makeMain(ratios)
        val a = ctrl.get()
        shot(a.window.decorView, "main_grid.png")
        ratios.forEachIndexed { i, (w, h) ->
            assertEquals("cell $i aspect", h.toDouble() / w, a.cellAspectForTest(i), 1e-2)
        }
        assertTrue(allButtons(a.window.decorView).any { it.text.toString().contains("儲存 5 張") })
        assertEquals(5, editButtons(a.window.decorView).size)
    }

    @Test fun noLongPressAndTapTogglesSelection() {
        val (ctrl, _, _) = makeMain(listOf(1080 to 1080, 810 to 1080))
        val a = ctrl.get()
        val cell = (editButtons(a.window.decorView)[0].parent as View)
        assertTrue(!cell.isLongClickable)
        assertTrue(cell.isClickable)
        cell.performClick()
        assertTrue(allButtons(a.window.decorView).any { it.text.toString() == "儲存 1 張" })
        cell.performClick()
        assertTrue(allButtons(a.window.decorView).any { it.text.toString() == "儲存 2 張" })
    }

    @Test fun editButtonOpensEditor() {
        val (ctrl, imgs, _) = makeMain(listOf(1080 to 1080, 810 to 1080))
        val a = ctrl.get()
        editButtons(a.window.decorView)[1].performClick()
        val started = shadowOf(a).nextStartedActivity
        assertEquals(EditActivity::class.java.name, started.component!!.className)
        assertEquals(imgs[1].id, started.getStringExtra(EditActivity.EXTRA_ID))
    }

    @Test fun editedThumbnailShowsEditedResultAndResetsToOriginal() {
        val (ctrl, imgs, _) = makeMain(listOf(1080 to 1080, 810 to 1080, 1080 to 608))
        val a = ctrl.get()
        val id = imgs[1].id
        // 原圖 810x1080（直式）；編輯：9:16 裁切 + 順時針 90°
        val origAspect = a.cellAspectForTest(1)
        assertEquals(1080.0 / 810, origAspect, 1e-2)
        EditStore.seedOriginal(id, DownloadedImage(jpeg(synthetic(1536, 2048)), "image/jpeg", "x"))
        val ed = launchEditor(id, 1536, 2048)
        waitLoaded(ed)
        val e = ed.get()
        btn(e, "9:16").performClick()
        btn(e, "⟳").performClick()
        btn(e, "完成").performClick()
        val st = EditStore.getEdit(id)!!
        val thumb = EditStore.getEditedThumb(id)
        assertNotNull(thumb)
        assertTrue("edited thumb max side <= 900", maxOf(thumb!!.width, thumb.height) <= 900)
        assertEquals(st.crop.height / st.crop.width, thumb.height.toDouble() / thumb.width, 2e-2)
        // 回到主畫面：縮圖變成編輯後的比例（9:16 鎖定、旋轉後仍直式 16/9 高寬比）
        ctrl.pause().resume()
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        val newAspect = a.cellAspectForTest(1)
        assertEquals(16.0 / 9, newAspect, 2e-2)
        assertTrue(a.cellBitmapForTest(1) === thumb)
        shot(a.window.decorView, "main_edited.png")
        // 徽章可見
        assertTrue(allViews(a.window.decorView).any { it is android.widget.TextView && it.text == "已編輯" && it.visibility == View.VISIBLE })

        // 重設成沒編輯 → 回到原圖
        val ed2 = launchEditor(id, 1536, 2048)
        waitLoaded(ed2)
        btn(ed2.get(), "歸零").performClick()
        btn(ed2.get(), "自訂").performClick()
        btn(ed2.get(), "重設").performClick()
        btn(ed2.get(), "完成").performClick()
        assertEquals(null, EditStore.getEdit(id))
        assertEquals(null, EditStore.getEditedThumb(id))
        ctrl.pause().resume()
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        assertEquals(origAspect, a.cellAspectForTest(1), 1e-2)
        assertTrue(allViews(a.window.decorView).none { it is android.widget.TextView && it.text == "已編輯" && it.visibility == View.VISIBLE })
    }

    @Test fun cancelledEditKeepsOriginalThumbnail() {
        val (ctrl, imgs, _) = makeMain(listOf(1080 to 1080, 810 to 1080))
        val a = ctrl.get()
        val id = imgs[1].id
        EditStore.seedOriginal(id, DownloadedImage(jpeg(synthetic(810, 1080)), "image/jpeg", "x"))
        val ed = launchEditor(id, 810, 1080)
        waitLoaded(ed)
        btn(ed.get(), "⟳").performClick()
        btn(ed.get(), "取消").performClick()
        ctrl.pause().resume()
        assertEquals(1080.0 / 810, a.cellAspectForTest(1), 1e-2)
        assertNull(EditStore.getEditedThumb(id))
    }

    /** 連真實 YouTube 的截圖（需 YTPOST_LIVE=貼文ID[,貼文ID…]）。 */
    @Test fun liveShots() {
        val pids = System.getenv("YTPOST_LIVE")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        org.junit.Assume.assumeTrue(!pids.isNullOrEmpty())
        pids!!.forEachIndexed { n, pid ->
            EditStore.clear()
            val imgs = PostParser.parseImages(YouTubeClient.fetchHtml(PostParser.canonicalUrl(pid)), pid)
            val ctrl = Robolectric.buildActivity(MainActivity::class.java).setup()
            val a = ctrl.get()
            a.showImagesForTest(imgs, pid)
            repeat(160) { shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(50) }
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
            println("LIVE $pid images=${imgs.size} aspects=" + imgs.indices.joinToString { "%.2f".format(a.cellAspectForTest(it)) })
            shot(a.window.decorView, "live_main_${n + 1}.png")
            // 編輯第 2 張（9:16 + 順時針 90° + 微調 -3°），回主畫面截圖
            val target = imgs[minOf(1, imgs.size - 1)]
            val intent = Intent(RuntimeEnvironment.getApplication(), EditActivity::class.java)
                .putExtra(EditActivity.EXTRA_ID, target.id).putExtra(EditActivity.EXTRA_HOST, target.host)
            val ed = Robolectric.buildActivity(EditActivity::class.java, intent).setup()
            waitLoaded(ed)
            val e = ed.get()
            btn(e, "9:16").performClick()
            val seek = allViews(e.window.decorView).filterIsInstance<SeekBar>().first()
            seek.progress = 45 * 10 - 30
            val l = SeekBar::class.java.getDeclaredField("mOnSeekBarChangeListener").apply { isAccessible = true }.get(seek) as SeekBar.OnSeekBarChangeListener
            l.onProgressChanged(seek, seek.progress, true)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            if (n == 0) shot(e.window.decorView, "live_editor.png")
            btn(e, "完成").performClick()
            ctrl.pause().resume()
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
            shot(a.window.decorView, "live_main_${n + 1}_edited.png")
        }
    }

    // ---------- 儲存流程（含自動關閉）----------

    class FakeMedia : android.content.ContentProvider() {
        companion object { val saved = ArrayList<Pair<String, File>>(); var failInsert = false; val failNames = HashSet<String>() }
        override fun onCreate() = true
        override fun query(u: android.net.Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): android.database.Cursor? = null
        override fun getType(u: android.net.Uri): String? = null
        override fun insert(u: android.net.Uri, v: android.content.ContentValues?): android.net.Uri? {
            if (failInsert || FakeMedia.failNames.contains(v?.getAsString("_display_name"))) return null
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

    @Test fun partialFailureUnselectsSavedAndRetries() {
        FakeMedia.saved.clear(); FakeMedia.failInsert = false; FakeMedia.failNames.clear()
        Robolectric.setupContentProvider(FakeMedia::class.java, "media")
        val pid = "UgkxRetryPost000000"
        val ids = listOf("p", "q", "r").map { it.repeat(50) }
        ids.forEach {
            EditStore.seedOriginal(it, DownloadedImage(jpeg(synthetic(300, 400)), "image/jpeg", "x"))
        }
        val ctrl = Robolectric.buildActivity(MainActivity::class.java).setup()
        val a = ctrl.get()
        a.showImagesForTest(ids.map { PostImage(it, "yt3.ggpht.com") }, pid)
        ids.forEach { EditStore.setEdit(it, EditState(300, 400, 1, 0.0, CropRect(0.0, 0.0, 400.0, 300.0))) }
        FakeMedia.failNames.add("${pid}_2.jpg")
        fun wait() {
            repeat(100) { shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(30) }
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(3))
        }
        fun saveBtn() = allButtons(a.window.decorView).first { it.text.toString().startsWith("儲存") || it.text.toString().startsWith("重試") }
        saveBtn().performClick(); wait()
        assertEquals(listOf("${pid}_1.jpg", "${pid}_3.jpg"), FakeMedia.saved.map { it.first })
        assertTrue("must stay open", !a.isFinishing)
        assertEquals("重試失敗的 1 張", saveBtn().text.toString())
        val statusText = allViews(a.window.decorView).filterIsInstance<android.widget.TextView>().map { it.text.toString() }.first { it.contains("失敗") }
        assertTrue(statusText, statusText.contains("寫入相簿失敗"))
        assertTrue(statusText, !statusText.contains("Exception") && !statusText.contains("IOException"))
        shot(a.window.decorView, "main_partial_failure.png")
        // 重試：只存失敗那張，成功後自動關閉
        FakeMedia.failNames.clear()
        saveBtn().performClick(); wait()
        assertEquals(3, FakeMedia.saved.size)
        assertEquals("${pid}_2.jpg", FakeMedia.saved.last().first)
        assertTrue("auto close after full success", a.isFinishing)
    }
}
