package com.novelreader.app

import com.novelreader.app.data.parser.UniversalNovelParser
import org.junit.Assert.*
import org.junit.Test

class UniversalNovelParserTest {

    private val parser = UniversalNovelParser()

    @Test
    fun testParseNovelDetail() {
        val sampleHtml = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta property="og:title" content="《神奇小說》" />
                <meta property="og:novel:author" content="作者：張三" />
                <meta property="og:description" content="這是一本非常精彩的小說簡介" />
            </head>
            <body>
                <ul id="chapter-list">
                    <li><a href="https://example.com/chapter1">第一章 冒險開始</a></li>
                    <li><a href="https://example.com/chapter2">第二章 神秘城堡</a></li>
                </ul>
            </body>
            </html>
        """.trimIndent()

        val detail = parser.parseNovelDetail(sampleHtml, "https://example.com")

        assertEquals("神奇小說", detail.title)
        assertEquals("張三", detail.author)
        assertEquals("這是一本非常精彩的小說簡介", detail.intro)
        assertEquals(2, detail.chapters.size)
        assertEquals("第一章 冒險開始", detail.chapters[0].title)
        assertEquals("https://example.com/chapter1", detail.chapters[0].url)
        assertEquals("第二章 神秘城堡", detail.chapters[1].title)
    }

    @Test
    fun testParseChapterContent() {
        val sampleHtml = """
            <!DOCTYPE html>
            <html>
            <body>
                <div id="content">
                    <script>var ad = 1;</script>
                    <p>這是第一段話。</p>
                    <p>這是第二段話。</p>
                </div>
            </body>
            </html>
        """.trimIndent()

        val content = parser.parseChapterContent(sampleHtml)

        assertTrue(content.contains("這是第一段話。"))
        assertTrue(content.contains("這是第二段話。"))
        assertFalse(content.contains("var ad = 1;"))
    }
    @Test
    fun keepsMixedTextAndBreaksInOriginalOrder() {
        val html = """
            <html><body>
              <div class="content"><a href="/nav">章節目錄</a></div>
              <div id="chapter-content">
                前言文字<br/>第一行<p>第二段<span>內容</span></p>
                <div>第三段<br>接續的文字</div>
                <div class="ad">廣告不能混進來</div>
                <p>第四段</p>
              </div>
            </body></html>
        """.trimIndent()
        val content = parser.parseChapterContent(html)
        assertEquals(
            listOf("前言文字", "第一行", "第二段內容", "第三段", "接續的文字", "第四段"),
            content.split("\n\n")
        )
        assertFalse(content.contains("章節目錄"))
        assertFalse(content.contains("廣告不能混進來"))
    }

    @Test
    fun rejectsWrongChapterHeading() {
        val html = "<html><body><h1>第12章 不同內容</h1><div id='content'><p>正文內容</p></div></body></html>"
        val error = assertThrows(IllegalStateException::class.java) {
            parser.parseChapterContent(html, "第十一章 原章節")
        }
        assertTrue(error.message.orEmpty().contains("章節不符"))
    }

    @Test
    fun refusesLoginOrMenuInsteadOfChapter() {
        assertThrows(IllegalStateException::class.java) {
            parser.parseChapterContent("<html><head><title>Just a moment...</title></head><body><p>驗證中</p></body></html>")
        }
        assertThrows(IllegalStateException::class.java) {
            parser.parseChapterContent("<html><body><p>首頁 / 章節目錄</p></body></html>")
        }
    }

}
