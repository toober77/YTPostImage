package com.github.toober77.ytpostimage

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException

/** 把圖片存到「圖片/YTPostImage」。API 29+ 用 MediaStore；API 28 直接寫檔再通知媒體庫。 */
object ImageSaver {
    const val FOLDER = "YTPostImage"

    fun fileName(postId: String, index: Int, ext: String) = "${postId}_$index.$ext"

    fun save(ctx: Context, name: String, img: DownloadedImage) {
        if (Build.VERSION.SDK_INT >= 29) saveMediaStore(ctx, name, img) else saveLegacy(ctx, name, img)
    }

    private fun saveMediaStore(ctx: Context, name: String, img: DownloadedImage) {
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, img.mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$FOLDER")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("insert failed")
        try {
            resolver.openOutputStream(uri)!!.use { it.write(img.bytes) }
            val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(ctx: Context, name: String, img: DownloadedImage) {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), FOLDER)
        if (!dir.exists() && !dir.mkdirs()) throw IOException("mkdirs failed")
        var f = File(dir, name)
        var n = 1
        while (f.exists()) {
            f = File(dir, name.substringBeforeLast('.') + " ($n)." + name.substringAfterLast('.'))
            n++
        }
        f.outputStream().use { it.write(img.bytes) }
        MediaScannerConnection.scanFile(ctx, arrayOf(f.absolutePath), arrayOf(img.mimeType), null)
    }
}
