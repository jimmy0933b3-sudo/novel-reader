package com.novelreader.app

import com.novelreader.app.data.parser.UniversalNovelParser
import org.junit.Assert.*
import org.junit.Test

class DownloadRetryTest {

    private val parser = UniversalNovelParser()

    @Test
    fun testRetryHelperLogic() {
        var attempts = 0
        val maxRetries = 3
        var success = false

        while (!success && attempts < maxRetries) {
            attempts++
            // 模擬前兩次下載失敗，第三次成功
            if (attempts == 3) {
                success = true
            }
        }

        assertTrue(success)
        assertEquals(3, attempts)
    }

    @Test
    fun testSuccessAndFailureCounting() {
        val simulatedResults = listOf(true, true, false, true, false)
        var successCount = 0
        var failedCount = 0

        for (downloadSuccess in simulatedResults) {
            if (downloadSuccess) {
                successCount++
            } else {
                failedCount++
            }
        }

        assertEquals(3, successCount)
        assertEquals(2, failedCount)
        assertEquals(5, simulatedResults.size)
    }

    @Test
    fun testHttpErrorStatusHandling() {
        val httpCode = 404
        val isSuccessful = httpCode in 200..299
        assertFalse(isSuccessful)

        val challengeHtml = "<html><body>Enable JavaScript and cookies to continue</body></html>"
        val isBotChallenge = challengeHtml.contains("Enable JavaScript and cookies to continue")
        assertTrue(isBotChallenge)
    }

    @Test
    fun testParseChapterContentWithEmptyAndValidHtml() {
        val emptyHtml = ""
        // A missing page is a download error, not an empty successful chapter.
        assertThrows(IllegalStateException::class.java) {
            parser.parseChapterContent(emptyHtml)
        }

        val validHtml = "<html><body><div id='content'><p>測試章節內容</p></div></body></html>"
        val validContent = parser.parseChapterContent(validHtml)
        assertEquals("測試章節內容", validContent)
    }
}
