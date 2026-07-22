package com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser

import org.jsoup.Jsoup

data class DirectReservationConfirmationPage(
    val hiddenFields: List<Pair<String, String>>,
    val pickupLibraryCodes: Set<String>,
)

/** 予約確認画面が発行した hidden を改変せず、確定POSTに渡す。 */
object DirectReservationConfirmParser {
    fun parse(html: String, expectedTilcod: String): DirectReservationConfirmationPage {
        val document = Jsoup.parse(html)
        val forms = document.select("form").filter { form ->
            // LICS-XP の確認画面は LBForm の action を JavaScript で設定するため、
            // form.action は空の場合がある。送信先ではなく、確認画面固有の hidden 値と
            // 受取館セレクトでフォームを特定する。
            form.select("input[type=hidden][name=gamenid]").any { it.attr("value") == "tiles.WEsYoyConfirm" } &&
                form.select("input[type=hidden][name=tilcod]").any { it.attr("value") == expectedTilcod } &&
                form.select("input[type=hidden][name=contactweb]").any { it.attr("value") == "4" } &&
                form.select("select[name=receivename]").size == 1
        }
        if (forms.size != 1) throw ParseException("reservation-confirm", "予約確認フォームを一意に特定できません")
        val form = forms.single()
        val hidden = mutableListOf<Pair<String, String>>()
        form.select("input[type=hidden][name]").forEach { input ->
            val name = input.attr("name")
            val value = input.attr("value")
            if (name.isNotBlank()) hidden += name to value
        }
        fun uniqueValue(name: String): String? {
            val values = hidden.filter { it.first == name }.map { it.second }
            if (values.size != 1) throw ParseException("reservation-confirm", "$name が一意ではありません")
            return values.single()
        }
        if (uniqueValue("gamenid") != "tiles.WEsYoyConfirm") {
            throw ParseException("reservation-confirm", "gamenidが予約確認画面ではありません")
        }
        // hash は画面に存在する場合だけ画面遷移トークンとして一意性を要求する。
        if (hidden.any { it.first == "hash" }) uniqueValue("hash")
        if (uniqueValue("tilcod") != expectedTilcod) {
            throw ParseException("reservation-confirm", "tilcodが要求値と一致しません")
        }
        if (uniqueValue("contactweb") != "4") {
            throw ParseException("reservation-confirm", "contactwebがEmail固定値ではありません")
        }
        val pickup = form.select("select[name=receivename] option[value]")
            .map { it.attr("value") }
            .filter(String::isNotBlank)
            .toSet()
        if (pickup.isEmpty()) throw ParseException("reservation-confirm", "受取館の選択肢がありません")
        return DirectReservationConfirmationPage(hidden, pickup)
    }
}
