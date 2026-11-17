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
import android.view.WindowInsets
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
    private lateinit var pasteBtn: Button
    private lateinit var fetchBtn: Button
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
            setBackgroundColor(Color.WHITE)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnApplyWindowInsetsListener { v, insets ->
                @Suppress("DEPRECATION")
                v.setPadding(
                    dp(12) + insets.systemWindowInsetLeft, dp(8) + insets.systemWindowInsetTop,
                    dp(12) + insets.systemWindowInsetRight, dp(8) + insets.systemWindowInsetBottom
                )
                insets
            }
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF222222.toInt())
            setPadding(0, dp(4), 0, dp(8))
        })
        input = EditText(this).apply {
            hint = getString(R.string.input_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_VARIATION_URI
            minLines = 2
            maxLines = 4
            gravity = Gravity.TOP or Gravity.START
            textSize = 15f
        }
        root.addView(input, LinearLayout.LayoutParams(-1, -2))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pasteBtn = Button(this).apply { text = getString(R.string.paste); setOnClickListener { pasteFromClipboard() } }
        fetchBtn = Button(this).apply { text = getString(R.string.fetch); setOnClickListener { startFetch(input.text.toString()) } }
        row.addView(pasteBtn, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(fetchBtn, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(row, LinearLayout.LayoutParams(-1, -2))
        status = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFF555555.toInt())
            setPadding(dp(2), dp(6), dp(2), dp(6))
            text = getString(R.string.hint_idle)
        }
        root.addView(status, LinearLayout.LayoutParams(-1, -2))
        grid = GridView(this).apply {
            numColumns = 3
            horizontalSpacing = dp(6)
            verticalSpacing = dp(6)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            adapter = ThumbAdapter()
            setOnItemClickListener { _, _, pos, _ -> toggle(pos) }
        }
        root.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; visibility = View.GONE }
        selectBtn = Button(this).apply { setOnClickListener { toggleAll() } }
        saveBtn = Button(this).apply { setOnClickListener { startSave() } }
        bar.addView(selectBtn, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(saveBtn, LinearLayout.LayoutParams(0, -2, 1.4f))
        root.addView(bar, LinearLayout.LayoutParams(-1, -2))
        bottomBar = bar
        setContentView(root)
    }

    private fun setStatus(msg: String, error: Boolean = false) {
        status.text = msg
        status.setTextColor(if (error) 0xFFC62828.toInt() else 0xFF555555.toInt())
    }

    private fun setBusy(b: Boolean) {
        busy = b
        pasteBtn.isEnabled = !b
        fetchBtn.isEnabled = !b
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

    // ---------------- 輸入來源 ----------------

    private fun handleIntent(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND && i.type?.startsWith("text/") == true) {
            val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: i.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            if (text != null) {
                val url = PostParser.extractUrl(text) ?: text.trim()
                input.setText(url)
                startFetch(text)
            }
        }
    }

    private fun pasteFromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrBlank()) {
            setStatus(getString(R.string.err_clipboard_empty), true)
            return
        }
        input.setText(PostParser.extractUrl(text) ?: text.trim())
        input.setSelection(input.text.length)
        setStatus(getString(R.string.hint_pasted))
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
            var firstError: String? = null
            for ((n, pair) in picks.withIndex()) {
                val (index, img) = pair
                ui.post { setStatus(getString(R.string.saving, n + 1, picks.size)) }
                try {
                    val data = YouTubeClient.downloadImage(img)
                    ImageSaver.save(this, ImageSaver.fileName(id, index, data.extension), data)
                    ok++
                } catch (e: Exception) {
                    fail++
                    if (firstError == null) firstError = e.javaClass.simpleName
                }
            }
            ui.post {
                if (isDestroyed) return@post
                setBusy(false)
                refreshBar()
                if (fail == 0) {
                    val msg = getString(R.string.saved_n, ok)
                    setStatus(msg)
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                } else {
                    val msg = getString(R.string.saved_partial, ok, fail)
                    setStatus(msg + "（" + (firstError ?: "") + "）", true)
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
            }
        }
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
        val image = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0xFFEEEEEE.toInt()) }
        val dim = View(ctx).apply { setBackgroundColor(0x88FFFFFF.toInt()) }
        val check = TextView(ctx).apply {
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x99000000.toInt()); setStroke(dp(2), Color.WHITE) }
        }

        init {
            addView(image, LayoutParams(-1, -1))
            addView(dim, LayoutParams(-1, -1))
            addView(check, LayoutParams(dp(24), dp(24), Gravity.TOP or Gravity.END).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) })
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
