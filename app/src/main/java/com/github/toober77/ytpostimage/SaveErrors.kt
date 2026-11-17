package com.github.toober77.ytpostimage

import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** 儲存流程中失敗發生在哪個階段。 */
enum class Stage { DOWNLOAD, EDIT, STORAGE }

/** 給使用者看的失敗原因（不顯示例外類別名稱）。 */
enum class FailReason { NETWORK, DOWNLOAD, MEMORY, EDIT, STORAGE }

object SaveErrors {
    fun classify(stage: Stage, t: Throwable): FailReason {
        var c: Throwable? = t
        while (c != null) {
            if (c is OutOfMemoryError) return FailReason.MEMORY
            c = c.cause
        }
        return when (stage) {
            Stage.EDIT -> FailReason.EDIT
            Stage.STORAGE -> FailReason.STORAGE
            Stage.DOWNLOAD -> when {
                t is HttpStatusException -> FailReason.DOWNLOAD
                t is UnknownHostException || t is SocketTimeoutException || t is ConnectException ||
                    t is SocketException || t is SSLException -> FailReason.NETWORK
                t is IOException && t.cause != null && classify(stage, t.cause!!) == FailReason.NETWORK -> FailReason.NETWORK
                else -> FailReason.DOWNLOAD
            }
        }
    }
}
