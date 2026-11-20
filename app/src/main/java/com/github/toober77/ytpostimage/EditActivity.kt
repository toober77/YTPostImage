package com.github.toober77.ytpostimage

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.util.concurrent.Executors

/** 全螢幕編輯器：旋轉＋裁切。完成後把 [EditState] 放進 [EditStore]。 */
class EditActivity : Activity() {
    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_HOST = "host"
        const val FINE_SCALE = 10 // SeekBar 單位 0.1°
        private const val COL_BG = 0xFF121212.toInt()
        private const val COL_TEXT = 0xFFE6E6E6.toInt()
        private const val COL_SUB = 0xFFAAAAAA.toInt()
        private const val COL_ACCENT = 0xFFEF5350.toInt()
        private const val COL_CHIP = 0xFF2A2A2A.toInt()
    }

    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var img: PostImage
    private lateinit var cropView: CropView
    private lateinit var loading: TextView
    private lateinit var angleLabel: TextView
    private lateinit var seek: SeekBar
    private lateinit var customField: EditText
    private lateinit var doneBtn: Button
    private val chips = ArrayList<Button>()
    private var mode = EditState.MODE_CUSTOM
    private var loaded = false
    private var preview: android.graphics.Bitmap? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val iid = intent.getStringExtra(EXTRA_ID) ?: run { finish(); return }
        img = EditStore.image(iid) ?: PostImage(iid, intent.getStringExtra(EXTRA_HOST) ?: "yt3.ggpht.com")
        buildUi()
        val prev = EditStore.getEdit(img.id)
        if (prev != null) {
            mode = prev.mode
            customField.setText(prev.customText)
        }
        selectMode(mode, apply = false)
        io.execute { load(prev) }
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdownNow()
    }

    private fun load(prev: EditState?) {
        try {
            val data = EditStore.original(img)
            val (w, h) = ImageEditor.bounds(data.bytes) ?: throw IllegalStateException("bounds")
            var sample = 1
            while (maxOf(w, h) / sample > 2048) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(data.bytes, 0, data.bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: throw IllegalStateException("decode")
            ui.post {
                if (isDestroyed) return@post
                preview = bmp
                cropView.setAspect(currentAspect())
                cropView.setImage(bmp, w, h, prev?.takeIf { it.srcW == w && it.srcH == h })
                if (prev == null) cropView.setAspect(currentAspect())
                loading.visibility = View.GONE
                loaded = true
                doneBtn.isEnabled = true
                syncSeek()
            }
        } catch (e: Throwable) {
            ui.post { loading.text = getString(R.string.err_edit_load) }
        }
    }

    // ---------------- UI ----------------

    private fun flatButton(text: String, onClick: () -> Unit) = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
        this.text = text
        setTextColor(COL_TEXT)
        textSize = 14f
        minWidth = 0; minimumWidth = 0
        minHeight = 0; minimumHeight = dp(40)
        setPadding(dp(10), 0, dp(10), 0)
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COL_BG)
            setOnApplyWindowInsetsListener { v, insets ->
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                insets
            }
        }
        // 上列：取消 / 標題 / 完成
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), 0, dp(4), 0) }
        top.addView(flatButton(getString(R.string.cancel)) { finish() })
        top.addView(TextView(this).apply {
            text = getString(R.string.edit_title); setTextColor(COL_TEXT); textSize = 16f; gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(0, -2, 1f))
        doneBtn = flatButton(getString(R.string.done)) { finishWithEdit() }.apply { setTextColor(COL_ACCENT); isEnabled = false }
        top.addView(doneBtn)
        root.addView(top, LinearLayout.LayoutParams(-1, -2))

        // 圖片區
        val frame = android.widget.FrameLayout(this)
        cropView = CropView(this)
        cropView.onChanged = { updateAngleLabel() }
        frame.addView(cropView, android.widget.FrameLayout.LayoutParams(-1, -1))
        loading = TextView(this).apply { text = getString(R.string.loading_original); setTextColor(COL_SUB); gravity = Gravity.CENTER }
        frame.addView(loading, android.widget.FrameLayout.LayoutParams(-1, -1))
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))

        // 比例列
        val ratioRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(2), dp(6), 0) }
        ratioRow.addView(TextView(this).apply { text = getString(R.string.ratio); setTextColor(COL_SUB); textSize = 13f; setPadding(dp(4), 0, dp(4), 0) })
        listOf(EditState.MODE_9_16 to "9:16", EditState.MODE_2_3 to "2:3", EditState.MODE_CUSTOM to getString(R.string.custom)).forEach { (m, label) ->
            val b = flatButton(label) { selectMode(m, apply = true) }
            chips.add(b)
            ratioRow.addView(b, LinearLayout.LayoutParams(-2, dp(36)).apply { setMargins(dp(2), 0, dp(2), 0) })
        }
        customField = EditText(this).apply {
            hint = "W:H"
            setHintTextColor(COL_SUB)
            setTextColor(COL_TEXT)
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSingleLine()
            setPadding(dp(6), 0, dp(6), 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { if (mode == EditState.MODE_CUSTOM && loaded) cropView.setAspect(currentAspect()) }
            })
        }
        ratioRow.addView(customField, LinearLayout.LayoutParams(dp(84), dp(40)))
        root.addView(ratioRow, LinearLayout.LayoutParams(-1, -2))

        // 旋轉列
        val rotRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), 0, dp(6), 0) }
        rotRow.addView(flatButton("⟲ 90°") { cropView.rotateQuarter(false); syncSeek() }, LinearLayout.LayoutParams(0, -2, 1f))
        rotRow.addView(flatButton("⟳ 90°") { cropView.rotateQuarter(true); syncSeek() }, LinearLayout.LayoutParams(0, -2, 1f))
        rotRow.addView(flatButton(getString(R.string.reset_rotation)) { cropView.resetRotation(); syncSeek() }, LinearLayout.LayoutParams(0, -2, 1f))
        rotRow.addView(flatButton(getString(R.string.reset_crop)) { cropView.resetCrop() }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(rotRow, LinearLayout.LayoutParams(-1, -2))

        // 微調角度
        val seekRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, dp(12), dp(4)) }
        angleLabel = TextView(this).apply { setTextColor(COL_TEXT); textSize = 13f; text = "0.0°"; minWidth = dp(56) }
        seekRow.addView(angleLabel)
        seek = SeekBar(this).apply {
            max = 90 * FINE_SCALE
            progress = 45 * FINE_SCALE
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) cropView.setFine((p - 45 * FINE_SCALE).toDouble() / FINE_SCALE)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        seekRow.addView(seek, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(seekRow, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
    }

    private fun syncSeek() {
        seek.progress = ((cropView.fineAngle + 45) * FINE_SCALE).toInt()
        updateAngleLabel()
    }

    private fun updateAngleLabel() {
        val st = cropView.state
        val total = if (st == null) 0.0 else st.angle
        var a = ((total % 360) + 360) % 360
        if (a > 180) a -= 360
        angleLabel.text = String.format("%.1f°", a)
    }

    private fun currentAspect(): Double? = when (mode) {
        EditState.MODE_9_16 -> 9.0 / 16
        EditState.MODE_2_3 -> 2.0 / 3
        else -> EditMath.parseRatio(customField.text.toString())
    }

    private fun selectMode(m: Int, apply: Boolean) {
        mode = m
        chips.forEachIndexed { i, b ->
            val on = i == m
            b.background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(if (on) COL_CHIP else Color.TRANSPARENT)
                setStroke(dp(1), if (on) COL_ACCENT else 0xFF444444.toInt())
            }
            b.setTextColor(if (on) COL_ACCENT else COL_TEXT)
        }
        customField.isEnabled = m == EditState.MODE_CUSTOM
        customField.alpha = if (m == EditState.MODE_CUSTOM) 1f else 0.4f
        if (apply && loaded) cropView.setAspect(currentAspect())
    }

    private fun finishWithEdit() {
        val st = cropView.state ?: return
        val full = st.copy(mode = mode, customText = customField.text.toString())
        if (EditMath.isIdentity(full)) {
            EditStore.setEdit(img.id, null)
        } else {
            val thumb = try { preview?.let { ImageEditor.renderPreview(it, full, 900) } } catch (e: Throwable) { null }
            EditStore.setEdit(img.id, full, thumb)
        }
        setResult(RESULT_OK)
        finish()
    }
}
