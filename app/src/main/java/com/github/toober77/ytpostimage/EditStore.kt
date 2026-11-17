package com.github.toober77.ytpostimage

/** 行程內共用：每張圖的編輯狀態，以及已下載的原圖位元組（避免編輯／儲存時重複下載）。 */
object EditStore {
    private val edits = HashMap<String, EditState>()
    private val originals = object : LinkedHashMap<String, DownloadedImage>(8, 0.75f, true) {}
    private var originalBytes = 0L
    private const val MAX_CACHE = 96L * 1024 * 1024

    @Synchronized fun getEdit(id: String): EditState? = edits[id]
    @Synchronized fun setEdit(id: String, st: EditState?) { if (st == null) edits.remove(id) else edits[id] = st }
    @Synchronized fun hasEdit(id: String) = edits.containsKey(id)

    @Synchronized fun clear() {
        edits.clear()
        originals.clear()
        originalBytes = 0
    }

    @Synchronized private fun cached(id: String) = originals[id]

    @Synchronized private fun put(id: String, d: DownloadedImage) {
        if (d.bytes.size > MAX_CACHE) return
        originals.put(id, d)?.let { originalBytes -= it.bytes.size }
        originalBytes += d.bytes.size
        val it = originals.entries.iterator()
        while (originalBytes > MAX_CACHE && it.hasNext()) {
            val e = it.next()
            if (e.key == id) continue
            originalBytes -= e.value.bytes.size
            it.remove()
        }
    }

    /** 測試用：直接放入原圖快取（不經網路）。 */
    internal fun seedOriginal(id: String, d: DownloadedImage) = put(id, d)

    /** 取得原圖（`=d`）：先看快取，沒有才下載。 */
    fun original(img: PostImage, cache: Boolean = true): DownloadedImage {
        cached(img.id)?.let { return it }
        val d = YouTubeClient.downloadImage(img)
        if (cache) put(img.id, d)
        return d
    }
}
