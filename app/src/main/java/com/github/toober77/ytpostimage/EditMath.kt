package com.github.toober77.ytpostimage

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** 裁切框（單位：旋轉後畫布的像素，左上角為原點）。 */
data class CropRect(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width get() = right - left
    val height get() = bottom - top
    val cx get() = (left + right) / 2
    val cy get() = (top + bottom) / 2
    fun offset(dx: Double, dy: Double) = CropRect(left + dx, top + dy, right + dx, bottom + dy)
    fun lerp(o: CropRect, t: Double) = CropRect(
        left + (o.left - left) * t, top + (o.top - top) * t, right + (o.right - right) * t, bottom + (o.bottom - bottom) * t
    )
    companion object {
        fun centered(cx: Double, cy: Double, w: Double, h: Double) = CropRect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
    }
}

enum class Handle { MOVE, TL, TR, BL, BR, L, R, T, B }

/** 一張圖的編輯狀態。[crop] 位於「旋轉後畫布」座標；總旋轉角 = quarter×90 + fine（順時針為正）。 */
data class EditState(
    val srcW: Int,
    val srcH: Int,
    val quarter: Int,
    val fine: Double,
    val crop: CropRect,
    val mode: Int = MODE_CUSTOM,
    val customText: String = ""
) {
    val angle: Double get() = quarter * 90.0 + fine

    companion object {
        const val MODE_9_16 = 0
        const val MODE_2_3 = 1
        const val MODE_CUSTOM = 2
    }
}

/** 裁切／旋轉的純數學（不依賴 Android），方便單元測試。 */
object EditMath {
    private const val EPS = 1e-6

    fun frameSize(w: Double, h: Double, deg: Double): Pair<Double, Double> {
        val r = Math.toRadians(deg)
        val c = abs(cos(r))
        val s = abs(sin(r))
        return (w * c + h * s) to (w * s + h * c)
    }

    /** 解析比例文字：「9:16」「9：16」「9/16」「9x16」「0.5625」→ 寬/高；無效回傳 null。 */
    fun parseRatio(text: String?): Double? {
        if (text == null) return null
        val t = text.trim().replace('：', ':').replace('／', '/').replace('×', 'x').replace(" ", "")
        if (t.isEmpty()) return null
        val parts = t.split(':', '/', 'x', 'X', '*')
        val v = when (parts.size) {
            1 -> parts[0].toDoubleOrNull()
            2 -> {
                val a = parts[0].toDoubleOrNull()
                val b = parts[1].toDoubleOrNull()
                if (a != null && b != null && b > 0) a / b else null
            }
            else -> null
        } ?: return null
        return if (v.isFinite() && v in 0.05..20.0) v else null
    }

    /** [r]（旋轉後畫布座標）的四個角是否都落在旋轉後的圖片內。 */
    fun insideImage(r: CropRect, w: Double, h: Double, deg: Double): Boolean {
        val (bw, bh) = frameSize(w, h, deg)
        val rad = Math.toRadians(deg)
        val c = cos(rad)
        val s = sin(rad)
        val tol = 1e-6 * max(w, h) + 1e-6
        for ((x, y) in listOf(r.left to r.top, r.right to r.top, r.left to r.bottom, r.right to r.bottom)) {
            val px = x - bw / 2
            val py = y - bh / 2
            val sx = px * c + py * s
            val sy = -px * s + py * c
            if (abs(sx) > w / 2 + tol || abs(sy) > h / 2 + tol) return false
        }
        return true
    }

    /** 在旋轉後的圖片內、置中、指定比例（null＝與畫布同比例）的最大裁切框。 */
    fun largestCentered(w: Double, h: Double, deg: Double, aspect: Double?): CropRect {
        val (bw, bh) = frameSize(w, h, deg)
        val a = aspect ?: (bw / bh)
        var lo = 0.0
        var hi = max(bw, bh * a)
        repeat(50) {
            val mid = (lo + hi) / 2
            if (insideImage(CropRect.centered(bw / 2, bh / 2, mid, mid / a), w, h, deg)) lo = mid else hi = mid
        }
        return CropRect.centered(bw / 2, bh / 2, lo, lo / a)
    }

    /** 若 [proposed] 超出圖片，沿 prev→proposed 的路徑退回到最後一個合法位置（prev 必須合法）。 */
    fun clampStep(prev: CropRect, proposed: CropRect, valid: (CropRect) -> Boolean): CropRect {
        if (valid(proposed)) return proposed
        var lo = 0.0
        var hi = 1.0
        repeat(40) {
            val mid = (lo + hi) / 2
            if (valid(prev.lerp(proposed, mid))) lo = mid else hi = mid
        }
        return prev.lerp(proposed, lo)
    }

    /** 把 [r] 縮小並往中心靠，直到完全落在旋轉後的圖片內（保持比例）。 */
    fun fitInside(r: CropRect, w: Double, h: Double, deg: Double): CropRect {
        if (insideImage(r, w, h, deg)) return r
        val (bw, bh) = frameSize(w, h, deg)
        val k = 1e-4
        val tiny = CropRect.centered(bw / 2, bh / 2, max(r.width * k, 1e-3), max(r.height * k, 1e-3))
        return clampStep(tiny, r) { insideImage(it, w, h, deg) }
    }

    /**
     * 依拖曳的把手計算新的裁切框（不檢查是否在圖內，由呼叫端用 [clampStep] 限制）。
     * [aspect]（寬/高）不為 null 時鎖定比例。[minSize] 為最小邊長。
     */
    fun resize(r: CropRect, handle: Handle, dx: Double, dy: Double, aspect: Double?, minSize: Double): CropRect {
        if (handle == Handle.MOVE) return r.offset(dx, dy)
        if (aspect == null) {
            var l = r.left; var t = r.top; var rr = r.right; var b = r.bottom
            when (handle) {
                Handle.TL -> { l += dx; t += dy }
                Handle.TR -> { rr += dx; t += dy }
                Handle.BL -> { l += dx; b += dy }
                Handle.BR -> { rr += dx; b += dy }
                Handle.L -> l += dx
                Handle.R -> rr += dx
                Handle.T -> t += dy
                Handle.B -> b += dy
                else -> {}
            }
            if (rr - l < minSize) { if (handle == Handle.L || handle == Handle.TL || handle == Handle.BL) l = rr - minSize else rr = l + minSize }
            if (b - t < minSize) { if (handle == Handle.T || handle == Handle.TL || handle == Handle.TR) t = b - minSize else b = t + minSize }
            return CropRect(l, t, rr, b)
        }
        val mw = max(minSize, minSize * aspect)
        return when (handle) {
            Handle.TL, Handle.TR, Handle.BL, Handle.BR -> {
                val left = handle == Handle.TL || handle == Handle.BL
                val top = handle == Handle.TL || handle == Handle.TR
                val ax = if (left) r.right else r.left
                val ay = if (top) r.bottom else r.top
                val cx = (if (left) r.left else r.right) + dx
                val cy = (if (top) r.top else r.bottom) + dy
                val w0 = if (left) ax - cx else cx - ax
                val h0 = if (top) ay - cy else cy - ay
                val w = max(max(w0, h0 * aspect), mw)
                val h = w / aspect
                CropRect(if (left) ax - w else ax, if (top) ay - h else ay, if (left) ax else ax + w, if (top) ay else ay + h)
            }
            Handle.L, Handle.R -> {
                val w = max(if (handle == Handle.L) r.right - (r.left + dx) else (r.right + dx) - r.left, mw)
                val h = w / aspect
                if (handle == Handle.L) CropRect(r.right - w, r.cy - h / 2, r.right, r.cy + h / 2)
                else CropRect(r.left, r.cy - h / 2, r.left + w, r.cy + h / 2)
            }
            else -> {
                val h = max(if (handle == Handle.T) r.bottom - (r.top + dy) else (r.bottom + dy) - r.top, mw / aspect)
                val w = h * aspect
                if (handle == Handle.T) CropRect(r.cx - w / 2, r.bottom - h, r.cx + w / 2, r.bottom)
                else CropRect(r.cx - w / 2, r.top, r.cx + w / 2, r.top + h)
            }
        }
    }

    /** 整張圖順時針轉 90° 後，裁切框在新畫布的位置。[frameH] 為轉之前畫布的高。 */
    fun rotateRectCw(r: CropRect, frameH: Double) = CropRect(frameH - r.bottom, r.left, frameH - r.top, r.right)

    /** 整張圖逆時針轉 90° 後，裁切框在新畫布的位置。[frameW] 為轉之前畫布的寬。 */
    fun rotateRectCcw(r: CropRect, frameW: Double) = CropRect(r.top, frameW - r.right, r.bottom, frameW - r.left)

    /** 沒有任何實質編輯（沒旋轉、裁切框＝整張）。 */
    fun isIdentity(st: EditState): Boolean {
        if (((st.quarter % 4) + 4) % 4 != 0 || abs(st.fine) > 0.05) return false
        return st.crop.left <= 0.5 && st.crop.top <= 0.5 &&
            st.crop.right >= st.srcW - 0.5 && st.crop.bottom >= st.srcH - 0.5
    }

    fun outputSize(crop: CropRect): Pair<Int, Int> =
        max(1, crop.width.roundToInt()) to max(1, crop.height.roundToInt())

    /** 輸出格式：PNG／GIF 來源存 PNG，其餘存 JPEG（品質 95）。 */
    fun outputIsPng(ext: String) = ext == "png" || ext == "gif"

    fun clampFine(v: Double) = min(45.0, max(-45.0, v))
}
