package com.github.toober77.ytpostimage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import java.io.ByteArrayOutputStream
import java.io.IOException

/** 把旋轉＋裁切套用到原尺寸圖片。 */
object ImageEditor {
    class Result(val image: DownloadedImage, val downscale: Int)

    /** 讀取圖片寬高（不解碼像素）。 */
    fun bounds(bytes: ByteArray): Pair<Int, Int>? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        return if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
    }

    /** 以全解析度套用編輯；記憶體不足時自動降一級解析度（[Result.downscale] > 1 表示有降）。 */
    fun apply(src: DownloadedImage, st: EditState): Result {
        var sample = 1
        while (true) {
            try {
                return Result(render(src, st, sample), sample)
            } catch (e: OutOfMemoryError) {
                if (sample >= 8) throw IOException("記憶體不足", e)
                sample *= 2
            }
        }
    }

    /** 位元圖像素 →（旋轉、裁切後）輸出像素的矩陣。[k]＝bmp 寬 / 原圖寬；[s]＝輸出像素 / 原圖像素。 */
    private fun matrixFor(st: EditState, k: Double, s: Double): Matrix {
        val (bw, bh) = EditMath.frameSize(st.srcW.toDouble(), st.srcH.toDouble(), st.angle)
        val m = Matrix()
        m.postScale((1.0 / k).toFloat(), (1.0 / k).toFloat()) // 先回到原圖像素
        m.postTranslate(-st.srcW / 2f, -st.srcH / 2f)
        m.postRotate(st.angle.toFloat())
        m.postTranslate((bw / 2 - st.crop.left).toFloat(), (bh / 2 - st.crop.top).toFloat())
        m.postScale(s.toFloat(), s.toFloat())
        return m
    }

    private fun render(src: DownloadedImage, st: EditState, sample: Int): DownloadedImage {
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeByteArray(src.bytes, 0, src.bytes.size, opts) ?: throw IOException("decode failed")
        var out: Bitmap? = null
        try {
            val k = bmp.width.toDouble() / st.srcW // ≈ 1/sample
            val (ow, oh) = EditMath.outputSize(CropRect(0.0, 0.0, st.crop.width * k, st.crop.height * k))
            val png = EditMath.outputIsPng(src.extension)
            out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            if (!png) c.drawColor(Color.BLACK)
            c.drawBitmap(bmp, matrixFor(st, k, k), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            val bos = ByteArrayOutputStream(max(1, ow * oh / 4))
            if (png) out.compress(Bitmap.CompressFormat.PNG, 100, bos) else out.compress(Bitmap.CompressFormat.JPEG, 95, bos)
            return DownloadedImage(bos.toByteArray(), if (png) "image/png" else "image/jpeg", src.url)
        } finally {
            bmp.recycle()
            out?.recycle()
        }
    }

    /** 用已降採樣的預覽圖 [bmp]（對應原圖 [EditState.srcW]）產生套用編輯後的縮圖，長邊不超過 [maxSide]。 */
    fun renderPreview(bmp: Bitmap, st: EditState, maxSide: Int): Bitmap {
        val k = bmp.width.toDouble() / st.srcW
        val cw = st.crop.width * k
        val ch = st.crop.height * k
        val sc = kotlin.math.min(1.0, maxSide / kotlin.math.max(cw, ch))
        val (ow, oh) = EditMath.outputSize(CropRect(0.0, 0.0, cw * sc, ch * sc))
        val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(bmp, matrixFor(st, k, k * sc), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    private fun max(a: Int, b: Int) = if (a > b) a else b
}
