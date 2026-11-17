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

    private fun render(src: DownloadedImage, st: EditState, sample: Int): DownloadedImage {
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeByteArray(src.bytes, 0, src.bytes.size, opts) ?: throw IOException("decode failed")
        var out: Bitmap? = null
        try {
            val k = bmp.width.toDouble() / st.srcW // ≈ 1/sample
            val (bw, bh) = EditMath.frameSize(st.srcW.toDouble(), st.srcH.toDouble(), st.angle)
            val m = Matrix()
            m.postTranslate(-st.srcW / 2f, -st.srcH / 2f)
            m.postRotate(st.angle.toFloat())
            m.postTranslate((bw / 2 - st.crop.left).toFloat(), (bh / 2 - st.crop.top).toFloat())
            m.postScale(k.toFloat(), k.toFloat())
            val (ow, oh) = EditMath.outputSize(CropRect(0.0, 0.0, st.crop.width * k, st.crop.height * k))
            val png = EditMath.outputIsPng(src.extension)
            out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            if (!png) c.drawColor(Color.BLACK)
            // 原圖尺寸 = bmp 尺寸 / k；matrix 以原圖像素為單位，先縮放 bmp 回原圖尺寸
            val full = Matrix()
            full.postScale((1.0 / k).toFloat(), (1.0 / k).toFloat())
            full.postConcat(m)
            c.drawBitmap(bmp, full, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            val bos = ByteArrayOutputStream(max(1, ow * oh / 4))
            if (png) out.compress(Bitmap.CompressFormat.PNG, 100, bos) else out.compress(Bitmap.CompressFormat.JPEG, 95, bos)
            return DownloadedImage(bos.toByteArray(), if (png) "image/png" else "image/jpeg", src.url)
        } finally {
            bmp.recycle()
            out?.recycle()
        }
    }

    private fun max(a: Int, b: Int) = if (a > b) a else b
}
