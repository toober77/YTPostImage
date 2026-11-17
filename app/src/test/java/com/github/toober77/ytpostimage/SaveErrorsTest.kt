package com.github.toober77.ytpostimage

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class SaveErrorsTest {
    @Test fun network() {
        assertEquals(FailReason.NETWORK, SaveErrors.classify(Stage.DOWNLOAD, UnknownHostException("x")))
        assertEquals(FailReason.NETWORK, SaveErrors.classify(Stage.DOWNLOAD, SocketTimeoutException("x")))
        assertEquals(FailReason.NETWORK, SaveErrors.classify(Stage.DOWNLOAD, IOException("wrap", UnknownHostException())))
    }
    @Test fun download() {
        assertEquals(FailReason.DOWNLOAD, SaveErrors.classify(Stage.DOWNLOAD, HttpStatusException(404)))
        assertEquals(FailReason.DOWNLOAD, SaveErrors.classify(Stage.DOWNLOAD, IOException("empty")))
    }
    @Test fun memory() {
        assertEquals(FailReason.MEMORY, SaveErrors.classify(Stage.EDIT, OutOfMemoryError()))
        assertEquals(FailReason.MEMORY, SaveErrors.classify(Stage.DOWNLOAD, IOException("記憶體不足", OutOfMemoryError())))
        assertEquals(FailReason.MEMORY, SaveErrors.classify(Stage.STORAGE, OutOfMemoryError()))
    }
    @Test fun editAndStorage() {
        assertEquals(FailReason.EDIT, SaveErrors.classify(Stage.EDIT, IllegalStateException("x")))
        assertEquals(FailReason.STORAGE, SaveErrors.classify(Stage.STORAGE, IOException("insert failed")))
    }
}
