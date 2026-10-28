package com.novelreader.app.data.parser

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

data class ParsedNovelDetail(
    val title: String,
    val author: String,
    val coverUrl: String,
    val intro: String,
    val chapters: List<ParsedChapterInfo>
)

data class ParsedChapterInfo(
    val title: String,
    val url: String,
    val index: Int
)

class UniversalNovelParser {

    /**
     * 從書籍首頁或章節目錄頁 HTML 解析小說資訊與章節清單
     */
    fun parseNovelDetail(html: String, baseUrl: String): ParsedNovelDetail {
        val doc: Document = Jsoup.parse(html, baseUrl)

        // 1. 解析標題 (嘗試常見 meta 或 h1/標題選擇器)
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: doc.selectFirst("h1, .novel-title, .title, .name")?.text()
            ?: doc.title()

        // 2. 解析作者
        val author = doc.selectFirst("meta[property=og:novel:author]")?.attr("content")
            ?: doc.selectFirst(".author, .book-author")?.text()
            ?: "未知作者"

        // 3. 解析封面圖片
        val coverUrl = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst(".cover img, .book-img img, img.thumbnail")?.attr("abs:src")
            ?: ""

        // 4. 解析簡介
        val intro = doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?: doc.selectFirst(".description, .intro, .summary, #intro")?.text()
            ?: ""

        // 5. 解析章節清單
        // 優先精準鎖定章節容器 (例如 czbooks 的 #chapter-list 或 ul.chapter-list)
        val chapterContainer = doc.selectFirst("#chapter-list, ul.chapter-list, .chapters, #chapters")
        val rawLinks = if (chapterContainer != null) {
            chapterContainer.select("li a, a")
        } else {
            // 次要通用選擇器，並明確排除推薦書籍區塊 (.style-recommend, .recommend, .novel-list 等)
            doc.select(".chapter-detail a, .volume a, .chapter-item a")
        }

        val chapterLinks = if (rawLinks.isNotEmpty()) {
            val valid = org.jsoup.select.Elements()
            for (elem in rawLinks) {
                // 排除位在推薦書單或評論區內的 a 標籤
                if (elem.parents().any { it.hasClass("style-recommend") || it.hasClass("novel-list") || it.hasClass("comment-list") || it.hasClass("novel-detail-function-bar") }) {
                    continue
                }

                val href = elem.attr("abs:href")
                val text = elem.text().trim()
                if (href.contains("javascript:") || href.isBlank() || text.isBlank()) continue

                val isChapterLink = href.contains("/cp/") || href.contains("/chapter") || href.contains("/read/") ||
                        href.contains("chapterNumber=") || text.contains("第") || text.contains("章") ||
                        text.contains("Chapter") || text.matches(Regex(".*\\d+.*"))

                if (isChapterLink && !text.startsWith("新書") && !text.contains("發布") && !text.contains("評論區") && !text.contains("留言")) {
                    valid.add(elem)
                }
            }
            if (valid.isNotEmpty()) valid else rawLinks
        } else {
            // 後備：所有 a 標籤但嚴格排除推薦與評論導航
            val allLinks = doc.select("a[href]")
            val filtered = org.jsoup.select.Elements()
            for (elem in allLinks) {
                if (elem.parents().any { it.hasClass("style-recommend") || it.hasClass("novel-list") || it.hasClass("comment-list") || it.hasClass("novel-detail-function-bar") }) {
                    continue
                }
                val text = elem.text().trim()
                val href = elem.attr("abs:href")
                if (href.contains("javascript:") || href.isBlank() || text.isBlank()) continue

                if (href.contains("/cp/") || href.contains("/chapter") || href.contains("/read/") ||
                    href.contains("chapterNumber=") || text.contains("第") || text.contains("章") ||
                    text.contains("Chapter")
                ) {
                    if (!text.startsWith("新書") && !text.contains("發布") && !text.contains("評論區") && !text.contains("留言")) {
                        filtered.add(elem)
                    }
                }
            }
            filtered
        }

        val collectedChapters = mutableListOf<ParsedChapterInfo>()
        val seenUrls = mutableSetOf<String>()

        for (link in chapterLinks) {
            val href = link.attr("abs:href")
            val linkTitle = link.text().trim()
            if (href.isNotBlank() && linkTitle.isNotBlank() && !seenUrls.contains(href)) {
                seenUrls.add(href)
                collectedChapters.add(
                    ParsedChapterInfo(
                        title = linkTitle,
                        url = href,
                        index = 0
                    )
                )
            }
        }

        // 依章節目錄原網頁順序或章節數字排序
        // 檢查原網頁抓取的章節是否已大致依章節號排序（例如第1章比第89章更早出現）
        val hasNumbers = collectedChapters.count { extractChapterNumber(it.title) != null }
        val sortedChapters = if (hasNumbers > collectedChapters.size / 2) {
            // 如果大部分章節都有數字，但可能有些雜項
            val firstNum = collectedChapters.firstNotNullOfOrNull { extractChapterNumber(it.title) } ?: 0L
            val lastNum = collectedChapters.reversed().firstNotNullOfOrNull { extractChapterNumber(it.title) } ?: 0L

            if (firstNum > lastNum) {
                // 原網頁是倒序（最新章節在最上），將其翻轉為正序
                collectedChapters.reversed()
            } else {
                // 原網頁本身就是正序，依照網頁結構保留正確章節次序；有數字的排前，無數字的沉底
                collectedChapters.sortedWith(Comparator { a, b ->
                    val numA = extractChapterNumber(a.title)
                    val numB = extractChapterNumber(b.title)
                    when {
                        numA != null && numB != null -> numA.compareTo(numB)
                        numA != null && numB == null -> -1
                        numA == null && numB != null -> 1
                        else -> 0
                    }
                })
            }
        } else {
            collectedChapters
        }.mapIndexed { idx, chapter ->
            chapter.copy(index = idx)
        }

        return ParsedNovelDetail(
            title = cleanTitle(title),
            author = cleanAuthor(author),
            coverUrl = coverUrl,
            intro = intro.trim(),
            chapters = sortedChapters
        )
    }

    private fun cleanTitle(raw: String): String {
        return raw.replace(Regex("【.*?】"), "")
            .replace(Regex("《(.*?)》"), "$1")
            .split("|", "_", "-").firstOrNull()?.trim()
            ?.ifBlank { raw.trim() } ?: raw.trim()
    }

    private fun cleanAuthor(raw: String): String {
        return raw.replace(Regex("^作者\\s*[:：]\\s*"), "").trim()
    }

    /**
     * Read only the real chapter body, not login pages or site navigation.
     * Preserve text outside <p> and line breaks expressed with <br>.
     */
    fun parseChapterContent(html: String, expectedTitle: String? = null): String {
        val doc = Jsoup.parse(html)
        val pageTitle = doc.title().trim()
        val pageText = doc.body()?.text().orEmpty()
        val errorPattern = Regex(
            "(?i)(just a moment|access denied|attention required|404\\s*not found|captcha|cf-challenge|enable javascript|登入後閱讀|請先登入|找不到這個頁面)"
        )
        if (errorPattern.containsMatchIn(pageTitle) ||
            (pageText.length < 500 && errorPattern.containsMatchIn(pageText))) {
            throw IllegalStateException("來源網站回傳登入、驗證或錯誤頁，未儲存為正文")
        }

        if (!expectedTitle.isNullOrBlank()) {
            val expectedNo = chapterSequenceNumber(expectedTitle)
            val heading = doc.selectFirst(
                ".chapter-title, #chapter-title, .read-title, .article-title, h1"
            )?.text().orEmpty()
            val actualNo = chapterSequenceNumber(heading)
            if (expectedNo != null && actualNo != null && expectedNo != actualNo) {
                throw IllegalStateException("目錄章節與網頁章節不符：$expectedTitle / $heading")
            }
        }

        // Prefer actual reading containers to the generic .content wrapper.
        val selectors = listOf(
            "#chapter-content", "#read-content", ".chapter-content",
            ".read-content", ".novel-content", "#content", "#nr1",
            ".article-content", ".txtnav", "article", ".content"
        )
        val selected = selectors.asSequence()
            .mapNotNull { selector ->
                doc.select(selector).firstOrNull { el ->
                    val textSize = el.text().trim().length
                    val linkSize = el.select("a").text().length
                    textSize >= 3 && (linkSize == 0 || linkSize * 2 < textSize)
                }
            }.firstOrNull()
            ?: doc.body()?.takeIf { body ->
                body.text().length >= 200 &&
                    body.select("a").text().length * 3 < body.text().length
            }
            ?: throw IllegalStateException("找不到可信的小說正文，未將導覽頁存成章節")

        val content = selected.clone()
        content.select(
            "script, style, noscript, iframe, nav, header, footer, form, button, " +
                ".ad, .ads, .advertisement, .share, .related, .recommend, " +
                ".chapter-list, #chapter-list, .pagination, .page-nav, " +
                ".next-prev, .booklist, [hidden], [style*=display:none]"
        ).remove()

        val result = StringBuilder()
        fun walk(node: Node) {
            when (node) {
                is TextNode -> result.append(node.wholeText)
                is Element -> {
                    val tag = node.normalName()
                    if (tag == "br" || tag == "hr") {
                        result.append('\n')
                        return
                    }
                    val block = tag in setOf("p", "div", "section", "article", "li", "h1", "h2", "h3")
                    if (block && result.isNotEmpty()) result.append('\n')
                    node.childNodes().forEach { walk(it) }
                    if (block) result.append('\n')
                }
                else -> node.childNodes().forEach { walk(it) }
            }
        }
        content.childNodes().forEach { walk(it) }
        val lines = result.toString()
            .replace('\u00a0', ' ')
            .lines()
            .map { it.trim().replace(Regex("[ \\t]+"), " ") }
            .filter { it.isNotBlank() }
        val extracted = lines.joinToString("\n\n")
        if (extracted.isBlank()) {
            throw IllegalStateException("章節正文為空，未覆蓋離線內容")
        }
        return extracted
    }

    private fun chapterSequenceNumber(title: String): Long? {
        val number = Regex("(?i)第\\s*([0-9]+|[零一二三四五六七八九十百千萬兩]+)\\s*[章話節回]")
            .find(title)?.groupValues?.get(1)
        if (number != null) return number.toLongOrNull() ?: parseChineseNumber(number)
        return Regex("(?i)\\bchapter\\s*(\\d+)\\b")
            .find(title)?.groupValues?.get(1)?.toLongOrNull()
    }

    /**
     * 從章節標題提取數字（嚴格比對第 X 章或純數字）
     */
    private fun extractChapterNumber(title: String): Long? {
        // 1. 嚴格比對「第 123 章」或開頭為「第 123 話 / 節」
        val chNumRegex = Regex("第\\s*(\\d+)\\s*[章話節篇卷]")
        val chNumMatch = chNumRegex.find(title)
        if (chNumMatch != null) {
            return chNumMatch.groupValues[1].toLongOrNull()
        }

        // 2. 嘗試擷取「第[中文數字]章」
        val chineseRegex = Regex("第\\s*([零一二三四五六七八九十百千萬]+)\\s*[章話節篇卷]")
        val chineseMatch = chineseRegex.find(title)
        if (chineseMatch != null) {
            val chStr = chineseMatch.groupValues[1]
            return parseChineseNumber(chStr)
        }

        // 3. 嘗試開頭是純數字（例如 "001 隕落的天才" 或 "1. 隕落"）
        val startNumRegex = Regex("^(\\d+)[\\s.、]")
        val startMatch = startNumRegex.find(title.trim())
        if (startMatch != null) {
            return startMatch.groupValues[1].toLongOrNull()
        }

        return null
    }

    private fun parseChineseNumber(str: String): Long? {
        val map = mapOf(
            '零' to 0L, '一' to 1L, '二' to 2L, '兩' to 2L, '三' to 3L,
            '四' to 4L, '五' to 5L, '六' to 6L, '七' to 7L, '八' to 8L, '九' to 9L
        )
        var total = 0L
        var r = 0L
        for (char in str) {
            val v = map[char]
            if (v != null) {
                r = v
            } else if (char == '十') {
                if (r == 0L) r = 1L
                total += r * 10L
                r = 0L
            } else if (char == '百') {
                if (r == 0L) r = 1L
                total += r * 100L
                r = 0L
            } else if (char == '千') {
                if (r == 0L) r = 1L
                total += r * 1000L
                r = 0L
            } else if (char == '萬') {
                total = (total + r) * 10000L
                r = 0L
            }
        }
        total += r
        return if (total > 0) total else null
    }
}
