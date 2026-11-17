package com.github.toober77.ytpostimage

import android.Manifest
import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.LruCache
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.IOException
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(4)
    private val thumbCache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private lateinit var input: EditText
    private lateinit var goBtn: Button
    private lateinit var status: TextView
    private lateinit var scroll: ScrollView
    private lateinit var colLeft: LinearLayout
    private lateinit var colRight: LinearLayout
    private lateinit var selectBtn: Button
    private lateinit var saveBtn: Button
    private lateinit var bottomBar: View

    private var postId: String? = null
    private var images: List<PostImage> = emptyList()
    private var selected = BooleanArray(0)
    private var cells: List<Cell> = emptyList()
    /** 上次儲存失敗、尚未成功的圖片索引（用來顯示「重試失敗的 N 張」）。 */
    private var failedIdx: Set<Int> = emptySet()
    private var busy = false
    private var pendingSave = false
    private var fromShare = false

    private companion object {
        const val COL_BG = 0xFF121212.toInt()
        const val COL_TEXT = 0xFFE6E6E6.toInt()
        const val COL_SUB = 0xFFAAAAAA.toInt()
        const val COL_ERR = 0xFFFF8A80.toInt()
        const val REQ_EDIT = 7
        const val GAP_DP = 4
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdownNow()
    }

    override fun onResume() {
        super.onResume()
        // 從編輯器回來：更新縮圖（編輯後的樣子）與徽章
        if (cells.isNotEmpty()) { cells.forEach { it.bind() }; scheduleRelayout() }
    }

    // ---------------- UI ----------------

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COL_BG)
            setPadding(dp(6), dp(4), dp(6), dp(4))
            setOnApplyWindowInsetsListener { v, insets ->
                @Suppress("DEPRECATION")
                v.setPadding(
                    dp(6) + insets.systemWindowInsetLeft, dp(4) + insets.systemWindowInsetTop,
                    dp(6) + insets.systemWindowInsetRight, dp(4) + insets.systemWindowInsetBottom
                )
                insets
            }
        }
        // 上列：左邊（輸入框＋提示），右邊（貼上並抓圖）
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        input = EditText(this).apply {
            hint = getString(R.string.input_hint)
            setHintTextColor(COL_SUB)
            setTextColor(COL_TEXT)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_GO
            textSize = 14f
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE ||
                    (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                ) {
                    startFetch(text.toString())
                    true
                } else false
            }
        }
        left.addView(input, LinearLayout.LayoutParams(-1, -2))
        status = TextView(this).apply {
            textSize = 13f
            maxLines = 4
            setTextColor(COL_SUB)
            setPadding(dp(4), 0, dp(4), dp(2))
            text = getString(R.string.hint_idle)
        }
        left.addView(status, LinearLayout.LayoutParams(-1, -2))
        top.addView(left, LinearLayout.LayoutParams(0, -2, 1f))
        goBtn = Button(this).apply {
            text = getString(R.string.paste_and_fetch)
            textSize = 14f
            minHeight = 0; minimumHeight = dp(44)
            minWidth = 0; minimumWidth = dp(72)
            setPadding(dp(10), 0, dp(10), 0)
            setOnClickListener { pasteAndFetch() }
        }
        top.addView(goBtn, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
        root.addView(top, LinearLayout.LayoutParams(-1, -2))

        // 兩欄瀑布流（各欄獨立堆疊，依高度分配）
        scroll = ScrollView(this).apply { isFillViewport = false; overScrollMode = View.OVER_SCROLL_NEVER }
        val cols = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(GAP_DP), 0, dp(GAP_DP)) }
        colLeft = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        colRight = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cols.addView(colLeft, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(GAP_DP / 2) })
        cols.addView(colRight, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(GAP_DP / 2) })
        scroll.addView(cols, FrameLayout.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; visibility = View.GONE }
        selectBtn = Button(this).apply { minHeight = 0; minimumHeight = dp(44); setOnClickListener { toggleAll() } }
        saveBtn = Button(this).apply { minHeight = 0; minimumHeight = dp(44); setOnClickListener { startSave() } }
        bar.addView(selectBtn, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(saveBtn, LinearLayout.LayoutParams(0, -2, 1.4f))
        root.addView(bar, LinearLayout.LayoutParams(-1, -2))
        bottomBar = bar
        setContentView(root)
    }

    private fun setStatus(msg: String, error: Boolean = false) {
        status.text = msg
        status.setTextColor(if (error) COL_ERR else COL_SUB)
    }

    private fun setBusy(b: Boolean) {
        busy = b
        goBtn.isEnabled = !b
        selectBtn.isEnabled = !b
        refreshBar()
    }

    private fun refreshBar() {
        val n = selected.count { it }
        val retry = failedIdx.isNotEmpty() && selected.indices.filter { selected[it] }.toSet() == failedIdx
        saveBtn.text = if (retry) getString(R.string.retry_n, n) else getString(R.string.save_n, n)
        saveBtn.isEnabled = !busy && n > 0
        selectBtn.text = getString(if (n == selected.size) R.string.select_none else R.string.select_all)
        bottomBar.visibility = if (images.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun toggle(pos: Int) {
        if (busy || pos !in selected.indices) return
        selected[pos] = !selected[pos]
        cells[pos].bind()
        refreshBar()
    }

    private fun toggleAll() {
        val all = selected.all { it }
        selected.fill(!all)
        cells.forEach { it.bind() }
        refreshBar()
    }

    // ---------------- 瀑布流縮圖 ----------------

    private fun rebuildCells() {
        colLeft.removeAllViews()
        colRight.removeAllViews()
        cells = images.indices.map { Cell(this, it) }
        cells.forEach { it.bind() }
        relayout()
        cells.forEach { it.loadThumb() }
    }

    private val relayoutRunnable = Runnable { relayout() }
    private fun scheduleRelayout() { ui.removeCallbacks(relayoutRunnable); ui.postDelayed(relayoutRunnable, 120) }

    /** 依各圖「高/寬」比，依序放進目前較矮的那一欄；圖片載入後比例改變會重新分配。 */
    private fun relayout() {
        colLeft.removeAllViews()
        colRight.removeAllViews()
        var hl = 0.0
        var hr = 0.0
        for (c in cells) {
            val lp = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(GAP_DP) }
            if (hl <= hr) { colLeft.addView(c, lp); hl += c.aspect } else { colRight.addView(c, lp); hr += c.aspect }
        }
    }

    /** 目前該顯示的縮圖：有編輯結果就用編輯後的，否則是原圖縮圖。 */
    private fun bitmapFor(pos: Int): Bitmap? =
        EditStore.getEditedThumb(images[pos].id) ?: thumbCache.get(images[pos].thumbUrl)

    /** 測試用：塞入縮圖快取（不經網路）。 */
    internal fun seedThumb(img: PostImage, bmp: Bitmap) { thumbCache.put(img.thumbUrl, bmp) }

    private inner class Cell(ctx: Context, val pos: Int) : FrameLayout(ctx) {
        /** 高 / 寬；圖片載入前先假設 4:3 直式。 */
        var aspect = 4.0 / 3
        val image = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0xFF2A2A2A.toInt()) }
        val dim = View(ctx).apply { setBackgroundColor(0x99121212.toInt()) }
        val check = TextView(ctx).apply {
            gravity = Gravity.CENTER
            textSize = 15f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x99000000.toInt()); setStroke(dp(2), Color.WHITE) }
        }
        val badge = TextView(ctx).apply {
            text = getString(R.string.badge_edited)
            textSize = 11f
            setTextColor(Color.WHITE)
            setPadding(dp(6), dp(2), dp(6), dp(2))
            background = GradientDrawable().apply { cornerRadius = dp(9).toFloat(); setColor(0xCCEF5350.toInt()) }
            visibility = View.GONE
        }
        val edit = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_edit)
            setPadding(dp(9), dp(9), dp(9), dp(9))
            contentDescription = getString(R.string.edit)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xCC1E1E1E.toInt()); setStroke(dp(1), 0x55FFFFFF) }
            setOnClickListener { openEditor(pos) }
        }

        init {
            addView(image, LayoutParams(-1, -1))
            addView(dim, LayoutParams(-1, -1))
            addView(check, LayoutParams(dp(26), dp(26), Gravity.TOP or Gravity.END).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) })
            addView(badge, LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) })
            addView(edit, LayoutParams(dp(40), dp(40), Gravity.BOTTOM or Gravity.END).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) })
            setOnClickListener { toggle(pos) }
        }

        fun bind() {
            val on = selected.getOrElse(pos) { false }
            check.text = if (on) "✓" else ""
            (check.background as GradientDrawable).setColor(if (on) 0xFFD32F2F.toInt() else 0x99000000.toInt())
            dim.visibility = if (on) View.GONE else View.VISIBLE
            badge.visibility = if (EditStore.hasEdit(images[pos].id)) View.VISIBLE else View.GONE
            val bmp = bitmapFor(pos)
            if (bmp != null) {
                image.setImageBitmap(bmp)
                val a = bmp.height.toDouble() / bmp.width
                if (kotlin.math.abs(a - aspect) > 1e-3) { aspect = a; requestLayout() }
            }
        }

        fun loadThumb() {
            val img = images[pos]
            val url = img.thumbUrl
            if (thumbCache.get(url) != null) { bind(); return }
            val targetW = resources.displayMetrics.widthPixels / 2
            io.execute {
                try {
                    val data = YouTubeClient.downloadBytes(url)
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(data, 0, data.size, o)
                    var sample = 1
                    while (o.outWidth / (sample * 2) >= targetW) sample *= 2
                    val bmp = BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
                        ?: return@execute
                    thumbCache.put(url, bmp)
                    ui.post { if (!isDestroyed && cells.getOrNull(pos) === this) { bind(); scheduleRelayout() } }
                } catch (_: Throwable) {
                }
            }
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val h = (w * aspect).toInt().coerceAtLeast(dp(80))
            super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        }
    }

    private fun showImages(imgs: List<PostImage>) {
        images = imgs
        selected = BooleanArray(imgs.size) { true }
        failedIdx = emptySet()
        rebuildCells()
        refreshBar()
    }

    /** 測試用：不經網路直接顯示一組圖片。 */
    internal fun showImagesForTest(imgs: List<PostImage>, id: String) {
        postId = id
        setStatus(getString(R.string.found_n, imgs.size))
        showImages(imgs)
    }

    internal fun cellAspectForTest(pos: Int) = cells[pos].aspect
    internal fun cellBitmapForTest(pos: Int): Bitmap? = bitmapFor(pos)

    // ---------------- 輸入來源 ----------------

    private fun handleIntent(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND && i.type?.startsWith("text/") == true) {
            val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: i.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            if (text != null) {
                fromShare = true
                val url = PostParser.extractUrl(text) ?: text.trim()
                input.setText(url)
                startFetch(text)
            }
        }
    }

    /** 讀剪貼簿 → 填入輸入框 → 立刻抓圖；剪貼簿沒有貼文連結時改用輸入框現有內容。 */
    private fun pasteAndFetch() {
        if (busy) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (!clip.isNullOrBlank() && PostParser.postIdFromInput(clip) != null) {
            input.setText(PostParser.extractUrl(clip) ?: clip.trim())
            input.setSelection(input.text.length)
            startFetch(clip)
            return
        }
        val typed = input.text.toString()
        if (typed.isNotBlank()) {
            startFetch(typed)
        } else if (clip.isNullOrBlank()) {
            setStatus(getString(R.string.err_clipboard_empty), true)
        } else {
            setStatus(getString(R.string.err_invalid_link), true)
        }
    }

    // ---------------- 抓圖 ----------------

    private fun startFetch(raw: String) {
        if (busy) return
        val id = PostParser.postIdFromInput(raw)
        if (id == null) {
            setStatus(getString(R.string.err_invalid_link), true)
            return
        }
        input.setText(PostParser.canonicalUrl(id))
        postId = id
        EditStore.clear()
        showImages(emptyList())
        setBusy(true)
        setStatus(getString(R.string.fetching))
        io.execute {
            val result: Pair<List<PostImage>?, String?> = try {
                val html = YouTubeClient.fetchHtml(PostParser.canonicalUrl(id))
                val imgs = PostParser.parseImages(html, id)
                if (imgs.isEmpty()) null to getString(R.string.err_no_images) else imgs to null
            } catch (e: ConsentException) {
                null to getString(R.string.err_consent)
            } catch (e: HttpStatusException) {
                null to if (e.code == 404 || e.code == 410) getString(R.string.err_not_found) else getString(R.string.err_http, e.code)
            } catch (e: IOException) {
                null to getString(R.string.err_network)
            } catch (e: Exception) {
                null to getString(R.string.err_parse)
            }
            ui.post {
                if (isDestroyed) return@post
                setBusy(false)
                val imgs = result.first
                if (imgs == null) {
                    setStatus(result.second ?: "", true)
                } else {
                    setStatus(getString(R.string.found_n, imgs.size))
                    showImages(imgs)
                }
            }
        }
    }

    // ---------------- 儲存 ----------------

    private fun startSave() {
        if (busy || selected.none { it }) return
        if (Build.VERSION.SDK_INT < 29 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingSave = true
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
            return
        }
        doSave()
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && pendingSave) {
            pendingSave = false
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) doSave()
            else setStatus(getString(R.string.err_permission), true)
        }
    }

    private fun reasonText(r: FailReason) = getString(
        when (r) {
            FailReason.NETWORK -> R.string.reason_network
            FailReason.DOWNLOAD -> R.string.reason_download
            FailReason.MEMORY -> R.string.reason_memory
            FailReason.EDIT -> R.string.reason_edit
            FailReason.STORAGE -> R.string.reason_storage
        }
    )

    private fun doSave() {
        val id = postId ?: return
        val picks = images.indices.filter { selected[it] }
        if (picks.isEmpty()) return
        setBusy(true)
        io.execute {
            val okIdx = ArrayList<Int>()
            val failed = LinkedHashMap<Int, FailReason>()
            var lowRes = 0
            for ((n, i) in picks.withIndex()) {
                val img = images[i]
                ui.post { setStatus(getString(R.string.saving, n + 1, picks.size)) }
                var stage = Stage.DOWNLOAD
                try {
                    val edit = EditStore.getEdit(img.id)
                    var data = if (edit == null) YouTubeClient.downloadImage(img) else EditStore.original(img)
                    if (edit != null) {
                        stage = Stage.EDIT
                        val r = ImageEditor.apply(data, edit)
                        if (r.downscale > 1) lowRes++
                        data = r.image
                    }
                    stage = Stage.STORAGE
                    ImageSaver.save(this, ImageSaver.fileName(id, i + 1, data.extension), data)
                    okIdx.add(i)
                } catch (e: Throwable) {
                    failed[i] = SaveErrors.classify(stage, e)
                }
            }
            ui.post {
                if (isDestroyed) return@post
                // 成功的取消勾選；失敗的保持勾選，方便直接重試
                okIdx.forEach { selected[it] = false }
                failedIdx = failed.keys.toSet()
                cells.forEach { it.bind() }
                setBusy(false)
                if (failed.isEmpty()) {
                    var msg = getString(R.string.saved_n, okIdx.size)
                    if (lowRes > 0) msg += getString(R.string.saved_lowres, lowRes)
                    setStatus(msg)
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    // 全部成功：稍等 toast 出現後自動關閉（分享進來的只關自己，避免把 YouTube 的 task 一起移除）
                    ui.postDelayed({
                        if (isDestroyed || isFinishing) return@postDelayed
                        if (fromShare) finish() else finishAndRemoveTask()
                    }, 1000)
                } else {
                    val reasons = failed.values.distinct().joinToString("、") { reasonText(it) }
                    val msg = getString(R.string.saved_partial, okIdx.size, failed.size, reasons, failed.size)
                    setStatus(msg, true)
                    Toast.makeText(this, getString(R.string.saved_partial_short, okIdx.size, failed.size), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ---------------- 編輯 ----------------

    private fun openEditor(pos: Int) {
        if (busy || pos !in images.indices) return
        val img = images[pos]
        @Suppress("DEPRECATION")
        startActivityForResult(
            Intent(this, EditActivity::class.java)
                .putExtra(EditActivity.EXTRA_ID, img.id).putExtra(EditActivity.EXTRA_HOST, img.host),
            REQ_EDIT
        )
    }
}
