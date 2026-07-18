package com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser

import org.jsoup.Jsoup

object HashExtractor {
    private const val screen = "hash"

    fun extract(html: String): PageTokens {
        val document = Jsoup.parse(html)
        val form = document.selectFirst("form[name=LBForm]")
            ?: throw ParseException(screen, "form[name=LBForm] が見つかりません")
        val hash = form.selectFirst("input[name=hash]")?.`val`()
            ?: throw ParseException(screen, "input[name=hash] が見つかりません")
        val gamenId = form.selectFirst("input[name=gamenid]")?.`val`()?.takeIf { it.isNotBlank() }
            ?: throw ParseException(screen, "input[name=gamenid] が見つからないか空です")
        return PageTokens(hash = hash, gamenId = gamenId)
    }
}
