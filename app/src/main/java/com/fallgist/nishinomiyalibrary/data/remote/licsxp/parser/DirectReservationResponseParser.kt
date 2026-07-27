package com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser

import com.fallgist.nishinomiyalibrary.data.remote.licsxp.extractSiteMessages
import org.jsoup.Jsoup

/**
 * 確定POST後の応答を分類する。
 *
 * 実測(2026-07-27、ライブ診断)で判明したとおり、確定POSTの応答本文は**成功時も失敗時も同じ
 * 「予約確認」画面**（`gamenid=tiles.WYoyConfirm`）であり、区別できるのはページ内スクリプトの
 * ダイアログ文言（`alert(...)` 等）だけである。したがって [Result.StayedOnConfirmation] は
 * 「確認画面が返ったこと自体」が拒否を意味するものではない。文言が抜き出せない場合の残余分類として
 * 残している。
 */
object DirectReservationResponseParser {
    sealed interface Result {
        data object LoginAfterPost : Result
        data object DuplicateDetected : Result
        /** 実測(2026-07-27)の文言例:「図書・雑誌は予約制限を1冊越えています。」。message は前後の空白・改行を除いたもの。 */
        data class LimitExceeded(val message: String) : Result
        /** 実測(2026-07-27)の文言:「予約登録しました。確認したい場合は予約状況一覧で確認して下さい。」 */
        data object Registered : Result
        /**
         * 確認画面が返ったが、上記いずれの文言も抜き出せなかった。
         * 確認画面が返ること自体は拒否を意味しない。成功時も同じ画面が返るため、
         * 呼出し側は予約一覧と照合して成否を解決する必要がある。
         */
        data object StayedOnConfirmation : Result
        data object IndeterminateAfterPost : Result
    }

    private const val DUPLICATE_MESSAGE = "予約済の書誌があります。予約できません。"
    private const val LIMIT_EXCEEDED_MARKER_1 = "予約制限を"
    private const val LIMIT_EXCEEDED_MARKER_2 = "越えています"
    private const val REGISTERED_MARKER = "予約登録しました"

    fun parse(html: String): Result {
        val document = Jsoup.parse(html)
        // 1. ログインフォーム（セッション切れ）を最優先で判定する。
        if (document.selectFirst("input[name=j_password], input[name=j_username], form[action*=j_security_check]") != null) {
            return Result.LoginAfterPost
        }
        // 2. 既知の重複alertは、以降の文言判定より先に安全側へ倒す。
        if (html.contains(DUPLICATE_MESSAGE)) return Result.DuplicateDetected
        // ダイアログ文言・div#messagesのテキストを、通常経路と同じ規則で抽出する。
        val messages = extractSiteMessages(document)
        // 3. 予約上限超過。数値（冊数）や資料区分名は可変のため、固定の2語の包含だけで判定する。
        val limitMessage = messages.firstOrNull { it.contains(LIMIT_EXCEEDED_MARKER_1) && it.contains(LIMIT_EXCEEDED_MARKER_2) }
        if (limitMessage != null) return Result.LimitExceeded(limitMessage.trim())
        // 4. 成功。
        if (messages.any { it.contains(REGISTERED_MARKER) }) return Result.Registered
        // 5. メンテナンス。
        if (MAINTENANCE_MARKERS.any(html::contains)) return Result.IndeterminateAfterPost
        // 6. 上記いずれの文言も無く確認画面のまま。拒否とは断定できない（StayedOnConfirmationのkdoc参照）。
        if (document.select("form[action*=WOpacTifDirectYoyExecAction], form:has(input[name=gamenid][value=tiles.WYoyConfirm])").isNotEmpty()) {
            return Result.StayedOnConfirmation
        }
        // 7. 既知構造のいずれにも当たらない。
        return Result.IndeterminateAfterPost
    }

    private val MAINTENANCE_MARKERS = listOf("メンテナンス中", "メンテナンスのため", "システムメンテナンス", "ただいまメンテナンス")
}
