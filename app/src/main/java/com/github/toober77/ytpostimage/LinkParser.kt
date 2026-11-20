package com.github.toober77.ytpostimage

/** 輸入的連結屬於哪個平台。 */
sealed class Link {
    abstract val canonicalUrl: String
    /** 存檔檔名前綴：YouTube＝貼文 ID；IG＝`ig_<shortcode>`。 */
    abstract val filePrefix: String

    data class YouTube(val postId: String) : Link() {
        override val canonicalUrl get() = PostParser.canonicalUrl(postId)
        override val filePrefix get() = postId
    }
    data class Instagram(val link: IgLink) : Link() {
        override val canonicalUrl get() = link.canonicalUrl
        override val filePrefix get() = "ig_${link.code}"
    }
}

/** 自動判斷 YouTube 社群貼文或 Instagram 貼文。YouTube 的解析維持原本的 [PostParser]。 */
object LinkParser {
    fun parse(input: String?): Link? {
        if (input.isNullOrBlank()) return null
        PostParser.postIdFromInput(input)?.let { return Link.YouTube(it) }
        InstagramParser.parseLink(input)?.let { return Link.Instagram(it) }
        return null
    }

    /** 分享文字中的第一個可辨識連結（用來填入輸入框）；找不到回傳 null。 */
    fun extractUrl(text: String?): String? {
        val link = parse(text) ?: return null
        return when (link) {
            is Link.YouTube -> PostParser.extractUrl(text) ?: link.canonicalUrl
            is Link.Instagram -> link.canonicalUrl
        }
    }
}
