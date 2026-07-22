package com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser

import org.jsoup.Jsoup

/** 成功alertは未検証のため使わず、ログイン・既知重複・認証済み画面だけを区別する。 */
object DirectReservationResponseParser {
    enum class Result { LoginAfterPost, DuplicateDetected, IndeterminateAfterPost }

    private const val DUPLICATE_MESSAGE = "予約済の書誌があります。予約できません。"

    fun parse(html: String): Result {
        val document = Jsoup.parse(html)
        if (document.selectFirst("input[name=j_password], input[name=j_username], form[action*=j_security_check]") != null) {
            return Result.LoginAfterPost
        }
        if (html.contains(DUPLICATE_MESSAGE)) return Result.DuplicateDetected
        if (MAINTENANCE_MARKERS.any(html::contains)) return Result.IndeterminateAfterPost
        if (document.select("form[action*=WOpacEsTifDirectYoyExecAction], form:has(input[name=gamenid][value=tiles.WEsYoyConfirm])").isNotEmpty()) {
            return Result.IndeterminateAfterPost
        }
        // 成功alertと、対象tilcodに結び付く成功済み表示のライブHTML構造は未取得である。
        // #stat-login の存在だけでは別資料や単なるメニューでも成立するため、成功と推測しない。
        return Result.IndeterminateAfterPost
    }

    private val MAINTENANCE_MARKERS = listOf("メンテナンス中", "メンテナンスのため", "システムメンテナンス", "ただいまメンテナンス")
}
