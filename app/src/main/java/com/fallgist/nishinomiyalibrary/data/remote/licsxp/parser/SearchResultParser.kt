package com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser

import com.fallgist.nishinomiyalibrary.domain.model.SearchHit
import com.fallgist.nishinomiyalibrary.domain.model.SearchPage
import java.net.URI
import org.jsoup.Jsoup

object SearchResultParser {
    private const val screen = "search_result"

    fun parse(html: String): SearchPage {
        val document = Jsoup.parse(html)
        ParserSupport.requireHeading(document, screen, "検索結果")
        val container = document.selectFirst("div.doclist")
            ?: throw ParseException(screen, "検索結果コンテナ div.doclist が見つかりません")
        val hits = container.select("div.doc").map { item ->
            val link = item.selectFirst(".doc-title a")
                ?: throw ParseException(screen, "書誌タイトルリンクが見つかりません")
            val tilcod = queryValue(link.attr("href"), "tilcod")
                ?: throw ParseException(screen, "タイトルコード tilcod が見つかりません")
            val writerLine = item.selectFirst(".doc-writer")?.text()?.trim()
                ?: throw ParseException(screen, "著者行 .doc-writer が見つかりません")
            val materialType = item.selectFirst(".doc-thumbnail img[alt]")?.attr("alt")?.let { ParserSupport.run { it.normalized() } }
                ?.takeIf { it.isNotEmpty() }
                ?: throw ParseException(screen, "資料種別画像が見つかりません")
            SearchHit(
                tilcod = tilcod,
                title = ParserSupport.run { link.text().normalized() },
                writerLine = writerLine,
                materialType = materialType,
            )
        }
        val countText = document.select("li").firstOrNull { it.text().contains("該当件数は") }?.text()
            ?: throw ParseException(screen, "該当件数の表示が見つかりません")
        val totalCount = Regex("該当件数は\\s*(\\d+)").find(countText)?.groupValues?.get(1)?.toIntOrNull()
            ?: throw ParseException(screen, "該当件数が数値で見つかりません")
        return SearchPage(hits, totalCount, document.selectFirst(".arrow-next") != null)
    }

    private fun queryValue(href: String, name: String): String? =
        runCatching {
            URI("https://example.invalid/$href").rawQuery
                ?.split('&')
                ?.firstOrNull { it.substringBefore('=') == name }
                ?.substringAfter('=')
        }.getOrNull()
}
