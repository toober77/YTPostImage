package com.github.toober77.ytpostimage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/** 顯示（降採樣後的）圖片，支援任意角度旋轉與可拖曳／縮放的裁切框。所有座標運算用原圖像素。 */
class CropView(ctx: Context) : View(ctx) {
    private var bitmap: Bitmap? = null
    private var srcW = 0
    private var srcH = 0
    private var quarter = 0
    private var fine = 0.0
    private var crop = CropRect(0.0, 0.0, 1.0, 1.0)
    /** 使用者意圖的裁切框（相對畫布中心），旋轉角改變時由它重新套用，避免來回滑動越縮越小。 */
    private var desired = crop
    var aspect: Double? = null
        private set
    var onChanged: (() -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dimPaint = Paint().apply { color = 0xB3000000.toInt() }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 1.5f * d }
    private val gridPaint = Paint().apply { color = 0x66FFFFFF; style = Paint.Style.STROKE; strokeWidth = 1f }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 4f * d; strokeCap = Paint.Cap.ROUND }

    private val angle get() = quarter * 90.0 + fine
    val state: EditState?
        get() = if (bitmap == null) null else EditState(srcW, srcH, quarter, fine, crop, 0, "")

    // 畫面座標轉換參數
    private var f = 1.0
    private var ox = 0.0
    private var oy = 0.0
    private val pad get() = 12 * d

    fun setImage(bmp: Bitmap, srcW: Int, srcH: Int, st: EditState?) {
        bitmap = bmp
        this.srcW = srcW
        this.srcH = srcH
        if (st != null) {
            quarter = st.quarter
            fine = st.fine
            crop = st.crop
            desired = toCenterRel(crop)
        } else {
            quarter = 0; fine = 0.0
            resetCrop()
        }
        invalidate()
    }

    private fun frame() = EditMath.frameSize(srcW.toDouble(), srcH.toDouble(), angle)
    private fun toCenterRel(r: CropRect): CropRect { val (bw, bh) = frame(); return r.offset(-bw / 2, -bh / 2) }
    private fun fromCenterRel(r: CropRect): CropRect { val (bw, bh) = frame(); return r.offset(bw / 2, bh / 2) }
    private fun valid(r: CropRect) = EditMath.insideImage(r, srcW.toDouble(), srcH.toDouble(), angle)

    private fun setCrop(r: CropRect, keepDesired: Boolean = false) {
        crop = r
        if (!keepDesired) desired = toCenterRel(r)
        invalidate()
        onChanged?.invoke()
    }

    // ---------------- 外部操作 ----------------

    fun setAspect(a: Double?) {
        aspect = a
        if (bitmap == null) return
        if (a != null) setCrop(EditMath.largestCentered(srcW.toDouble(), srcH.toDouble(), angle, a))
    }

    fun resetCrop() {
        if (srcW == 0) return
        setCrop(EditMath.largestCentered(srcW.toDouble(), srcH.toDouble(), angle, aspect))
    }

    fun rotateQuarter(cw: Boolean) {
        if (bitmap == null) return
        val (bw, bh) = frame()
        val nr = if (cw) EditMath.rotateRectCw(crop, bh) else EditMath.rotateRectCcw(crop, bw)
        quarter = ((quarter + if (cw) 1 else 3) % 4)
        val a = aspect
        // 鎖定比例時維持相同比例（不隨畫面轉成橫的）；自由比例時裁切框跟著圖片一起轉
        if (a != null) setCrop(EditMath.largestCentered(srcW.toDouble(), srcH.toDouble(), angle, a))
        else setCrop(EditMath.fitInside(nr, srcW.toDouble(), srcH.toDouble(), angle))
    }

    fun setFine(v: Double) {
        if (bitmap == null) return
        fine = EditMath.clampFine(v)
        setCrop(EditMath.fitInside(fromCenterRel(desired), srcW.toDouble(), srcH.toDouble(), angle), keepDesired = true)
    }

    val fineAngle get() = fine

    /** 歸零：旋轉角度全部回到 0，裁切框重新置中（維持目前比例）。 */
    fun resetRotation() {
        if (bitmap == null) return
        quarter = 0
        fine = 0.0
        resetCrop()
    }

    // ---------------- 繪製 ----------------

    private fun layoutParams() {
        val (bw, bh) = frame()
        val aw = width - 2 * pad
        val ah = height - 2 * pad
        f = min(aw / bw, ah / bh)
        ox = (width - bw * f) / 2
        oy = (height - bh * f) / 2
    }

    private fun viewRect(r: CropRect) = RectF(
        (ox + r.left * f).toFloat(), (oy + r.top * f).toFloat(), (ox + r.right * f).toFloat(), (oy + r.bottom * f).toFloat()
    )

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = bitmap ?: return
        if (width == 0 || height == 0) return
        layoutParams()
        val (bw, bh) = frame()
        canvas.save()
        canvas.translate((ox + bw * f / 2).toFloat(), (oy + bh * f / 2).toFloat())
        canvas.rotate(angle.toFloat())
        val s = (f * srcW / bmp.width).toFloat()
        canvas.scale(s, s)
        canvas.drawBitmap(bmp, -bmp.width / 2f, -bmp.height / 2f, bmpPaint)
        canvas.restore()

        val r = viewRect(crop)
        canvas.drawRect(0f, 0f, width.toFloat(), r.top, dimPaint)
        canvas.drawRect(0f, r.bottom, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawRect(0f, r.top, r.left, r.bottom, dimPaint)
        canvas.drawRect(r.right, r.top, width.toFloat(), r.bottom, dimPaint)
        canvas.drawRect(r, linePaint)
        for (i in 1..2) {
            val x = r.left + r.width() * i / 3
            val y = r.top + r.height() * i / 3
            canvas.drawLine(x, r.top, x, r.bottom, gridPaint)
            canvas.drawLine(r.left, y, r.right, y, gridPaint)
        }
        // 角落把手
        val L = min(min(r.width(), r.height()) / 3, 22 * d)
        fun corner(x: Float, y: Float, sx: Int, sy: Int) {
            canvas.drawLine(x, y, x + sx * L, y, handlePaint)
            canvas.drawLine(x, y, x, y + sy * L, handlePaint)
        }
        corner(r.left, r.top, 1, 1); corner(r.right, r.top, -1, 1)
        corner(r.left, r.bottom, 1, -1); corner(r.right, r.bottom, -1, -1)
        if (aspect == null) {
            val e = min(min(r.width(), r.height()) / 6, 12 * d)
            canvas.drawLine(r.centerX() - e, r.top, r.centerX() + e, r.top, handlePaint)
            canvas.drawLine(r.centerX() - e, r.bottom, r.centerX() + e, r.bottom, handlePaint)
            canvas.drawLine(r.left, r.centerY() - e, r.left, r.centerY() + e, handlePaint)
            canvas.drawLine(r.right, r.centerY() - e, r.right, r.centerY() + e, handlePaint)
        }
    }

    // ---------------- 觸控 ----------------

    private var active: Handle? = null
    private var startCrop = crop
    private var downX = 0f
    private var downY = 0f

    private fun hit(x: Float, y: Float): Handle? {
        val r = viewRect(crop)
        val slop = 30 * d
        val corners = listOf(
            Handle.TL to (r.left to r.top), Handle.TR to (r.right to r.top),
            Handle.BL to (r.left to r.bottom), Handle.BR to (r.right to r.bottom)
        )
        val bestCorner = corners.minByOrNull { hypot(x - it.second.first, y - it.second.second) }!!
        if (hypot(x - bestCorner.second.first, y - bestCorner.second.second) <= slop) return bestCorner.first
        if (aspect == null) {
            if (abs(y - r.top) <= slop && x in r.left..r.right) return Handle.T
            if (abs(y - r.bottom) <= slop && x in r.left..r.right) return Handle.B
            if (abs(x - r.left) <= slop && y in r.top..r.bottom) return Handle.L
            if (abs(x - r.right) <= slop && y in r.top..r.bottom) return Handle.R
        }
        if (r.contains(x, y)) return Handle.MOVE
        return null
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (bitmap == null) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                active = hit(e.x, e.y)
                if (active == null) return false
                startCrop = crop
                downX = e.x; downY = e.y
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val h = active ?: return false
                val dx = (e.x - downX) / f
                val dy = (e.y - downY) / f
                val minSize = 48 * d / f
                val proposed = EditMath.resize(startCrop, h, dx, dy, aspect, minSize)
                var r = EditMath.clampStep(startCrop, proposed) { valid(it) }
                if (h == Handle.MOVE) {
                    // 碰到邊界後仍可沿邊滑動：把剩餘位移分別在 x、y 方向各試一次
                    val remX = proposed.left - r.left
                    r = EditMath.clampStep(r, r.offset(remX, 0.0)) { valid(it) }
                    val remY = proposed.top - r.top
                    r = EditMath.clampStep(r, r.offset(0.0, remY)) { valid(it) }
                }
                setCrop(r)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                active = null
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(e)
    }
}
