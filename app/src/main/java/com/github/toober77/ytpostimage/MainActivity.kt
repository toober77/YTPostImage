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
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.IOException
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(4)
    private val thumbCache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private lateinit var input: EditText
    private lateinit var goBtn: Button
    private lateinit var status: TextView
    private lateinit var grid: GridView
    private lateinit var selectBtn: Button
    private lateinit var saveBtn: Button
    private lateinit var bottomBar: View

    private var postId: String? = null
    private var images: List<PostImage> = emptyList()
    private var selected = BooleanArray(0)
    private var busy = false
    private var pendingSave = false
    private var fromShare = false

    private companion object {
        const val COL_BG = 0xFF121212.toInt()
        const val COL_TEXT = 0xFFE6E6E6.toInt()
        const val COL_SUB = 0xFFAAAAAA.toInt()
        const val COL_ERR = 0xFFFF8A80.toInt()
        const val COL_ACCENT = 0xFFEF5350.toInt()
        const val REQ_EDIT = 7
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
        root.addView(input, LinearLayout.LayoutParams(-1, -2))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        goBtn = Button(this).apply {
            text = getString(R.string.paste_and_fetch)
            textSize = 14f
            minHeight = 0; minimumHeight = dp(40)
            setOnClickListener { pasteAndFetch() }
        }
        row.addView(goBtn, LinearLayout.LayoutParams(-2, -2))
        status = TextView(this).apply {
            textSize = 12f
            maxLines = 4
            setTextColor(COL_SUB)
            setPadding(dp(8), 0, 0, 0)
            text = getString(R.string.hint_idle)
        }
        row.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(row, LinearLayout.LayoutParams(-1, -2))
        grid = GridView(this).apply {
            numColumns = 3
            horizontalSpacing = dp(4)
            verticalSpacing = dp(4)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            adapter = ThumbAdapter()
            setOnItemClickListener { _, _, pos, _ -> toggle(pos) }
            setOnItemLongClickListener { _, _, pos, _ -> openEditor(pos); true }
        }
        root.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(4) })
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; visibility = View.GONE }
        selectBtn = Button(this).apply { minHeight = 0; minimumHeight = dp(40); setOnClickListener { toggleAll() } }
        saveBtn = Button(this).apply { minHeight = 0; minimumHeight = dp(40); setOnClickListener { startSave() } }
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
        saveBtn.isEnabled = !b
        selectBtn.isEnabled = !b
    }

    private fun refreshBar() {
        val n = selected.count { it }
        saveBtn.text = getString(R.string.save_n, n)
        saveBtn.isEnabled = !busy && n > 0
        selectBtn.text = getString(if (n == selected.size) R.string.select_none else R.string.select_all)
        bottomBar.visibility = if (images.isEmpty()) View.GONE else View.VISIBLE
        (grid.adapter as BaseAdapter).notifyDataSetChanged()
    }

    private fun toggle(pos: Int) {
        if (busy || pos !in selected.indices) return
        selected[pos] = !selected[pos]
        refreshBar()
    }

    private fun toggleAll() {
        val all = selected.all { it }
        selected.fill(!all)
        refreshBar()
    }

    /** 測試用：不經網路直接顯示一組圖片。 */
    internal fun showImagesForTest(imgs: List<PostImage>, id: String) {
        postId = id
        images = imgs
        selected = BooleanArray(imgs.size) { true }
        setStatus(getString(R.string.found_n, imgs.size))
        refreshBar()
    }

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
        images = emptyList()
        selected = BooleanArray(0)
        refreshBar()
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
                    images = imgs
                    selected = BooleanArray(imgs.size) { true }
                    setStatus(getString(R.string.found_n, imgs.size))
                }
                refreshBar()
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

    private fun doSave() {
        val id = postId ?: return
        val picks = images.mapIndexedNotNull { i, img -> if (selected[i]) (i + 1) to img else null }
        if (picks.isEmpty()) return
        setBusy(true)
        io.execute {
            var ok = 0
            var fail = 0
            var lowRes = 0
            var firstError: String? = null
            for ((n, pair) in picks.withIndex()) {
                val (index, img) = pair
                ui.post { setStatus(getString(R.string.saving, n + 1, picks.size)) }
                try {
                    val edit = EditStore.getEdit(img.id)
                    val data = if (edit == null) {
                        YouTubeClient.downloadImage(img)
                    } else {
                        val r = ImageEditor.apply(EditStore.original(img), edit)
                        if (r.downscale > 1) lowRes++
                        r.image
                    }
                    ImageSaver.save(this, ImageSaver.fileName(id, index, data.extension), data)
                    ok++
                } catch (e: Throwable) {
                    fail++
                    if (firstError == null) firstError = e.javaClass.simpleName
                }
            }
            ui.post {
                if (isDestroyed) return@post
                setBusy(false)
                refreshBar()
                if (fail == 0) {
                    var msg = getString(R.string.saved_n, ok)
                    if (lowRes > 0) msg += getString(R.string.saved_lowres, lowRes)
                    setStatus(msg)
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    // 全部成功：稍等 toast 出現後自動關閉（分享進來的只關自己，避免把 YouTube 的 task 一起移除）
                    ui.postDelayed({
                        if (isDestroyed || isFinishing) return@postDelayed
                        if (fromShare) finish() else finishAndRemoveTask()
                    }, 1000)
                } else {
                    val msg = getString(R.string.saved_partial, ok, fail)
                    setStatus(msg + "（" + (firstError ?: "") + "）", true)
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
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

    override fun onResume() {
        super.onResume()
        if (images.isNotEmpty()) (grid.adapter as BaseAdapter).notifyDataSetChanged()
    }

    // ---------------- 縮圖格 ----------------

    private inner class ThumbAdapter : BaseAdapter() {
        override fun getCount() = images.size
        override fun getItem(position: Int) = images[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val cell = (convertView as? Cell) ?: Cell(this@MainActivity)
            val img = images[position]
            val on = selected.getOrElse(position) { false }
            cell.setChecked(on)
            cell.badge.visibility = if (EditStore.hasEdit(img.id)) View.VISIBLE else View.GONE
            val url = img.thumbUrl
            if (cell.url != url) {
                cell.url = url
                cell.image.setImageDrawable(null)
                val cached = thumbCache.get(url)
                if (cached != null) cell.image.setImageBitmap(cached) else loadThumb(url, cell)
            }
            return cell
        }
    }

    private fun loadThumb(url: String, cell: Cell) {
        io.execute {
            try {
                val data = YouTubeClient.downloadBytes(url)
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(data, 0, data.size, opts)
                var sample = 1
                while (opts.outWidth / (sample * 2) >= 512) sample *= 2
                val bmp = BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?: return@execute
                thumbCache.put(url, bmp)
                ui.post { if (cell.url == url) cell.image.setImageBitmap(bmp) }
            } catch (_: Exception) {
            }
        }
    }

    private inner class Cell(ctx: Context) : FrameLayout(ctx) {
        var url: String? = null
        val image = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0xFF2A2A2A.toInt()) }
        val dim = View(ctx).apply { setBackgroundColor(0x99121212.toInt()) }
        val check = TextView(ctx).apply {
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x99000000.toInt()); setStroke(dp(2), Color.WHITE) }
        }

        val badge = TextView(ctx).apply {
            text = getString(R.string.badge_edited)
            textSize = 10f
            setTextColor(Color.WHITE)
            setPadding(dp(5), dp(1), dp(5), dp(1))
            background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(0xCCEF5350.toInt()) }
            visibility = View.GONE
        }

        init {
            addView(image, LayoutParams(-1, -1))
            addView(dim, LayoutParams(-1, -1))
            addView(check, LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.END).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
            addView(badge, LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
        }

        fun setChecked(on: Boolean) {
            check.text = if (on) "✓" else ""
            (check.background as GradientDrawable).setColor(if (on) 0xFFD32F2F.toInt() else 0x99000000.toInt())
            dim.visibility = if (on) View.GONE else View.VISIBLE
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, widthMeasureSpec) // 正方形
        }
    }
}
