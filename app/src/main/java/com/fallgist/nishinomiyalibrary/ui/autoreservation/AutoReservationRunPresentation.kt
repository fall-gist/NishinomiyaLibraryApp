package com.fallgist.nishinomiyalibrary.ui.autoreservation

import com.fallgist.nishinomiyalibrary.domain.model.AutoReservationLatestItem
import com.fallgist.nishinomiyalibrary.domain.model.AutoReservationLatestRun
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 自動予約の表示専用履歴。認証情報やカード番号は保持しない。 */
data class AutoReservationRunView(
    val runId: Long,
    val completedAtText: String,
    val summaryText: String,
    val items: List<AutoReservationRunItemView>,
)

data class AutoReservationRunItemView(
    val title: String,
    val outcomeText: String,
    val matchedTermsText: String,
    val attemptsText: String,
)

/** Roomに保存されたJSONを安全な画面文言にだけ変換する純粋ビルダー。 */
object AutoReservationRunPresentationBuilder {
    private val json = Json { ignoreUnknownKeys = true }
    private val formatter = DateTimeFormatter.ofPattern("M/d(E) HH:mm", Locale.JAPANESE).withZone(ZoneId.of("Asia/Tokyo"))

    fun build(run: AutoReservationLatestRun): AutoReservationRunView = AutoReservationRunView(
        runId = run.runId,
        completedAtText = formatter.format(Instant.ofEpochMilli(run.completedAtEpochMillis)),
        summaryText = summary(run.summaryJson),
        items = run.items.map(::item),
    )

    private fun summary(value: String): String = parseObject(value)?.let { objectValue ->
        val success = objectValue.int("success")
        val skipped = objectValue.int("skipped")
        val error = objectValue.int("error")
        if (success == null || skipped == null || error == null) "詳細を読み取れません"
        else "確保済み${success}件／見送り${skipped}件／エラー${error}件"
    } ?: "詳細を読み取れません"

    private fun item(value: AutoReservationLatestItem): AutoReservationRunItemView = AutoReservationRunItemView(
        title = value.title.ifBlank { "書名不明" },
        outcomeText = outcome(value.outcome),
        matchedTermsText = rules(value.matchedRulesJson),
        attemptsText = attempts(value.attemptedMembersJson),
    )

    private fun outcome(value: String): String = when (value) {
        "SUCCESS" -> "予約を確保しました"
        "ALREADY_RESERVED" -> "すでに予約済みです"
        "UNKNOWN_AFTER_POST" -> "予約送信後の成否を確認できません"
        "ALL_MEMBERS_LIMITED" -> "全員の予約枠が不足していたため見送りました"
        "ALL_MEMBERS_PRE_SUBMIT_FAILED" -> "予約前の通信または認証に失敗しました"
        "SETTINGS_MISSING" -> "設定不足のため予約しませんでした"
        "RULE_DISABLED" -> "ルールがOFFのため予約しませんでした"
        "CIRCULATION_UNAVAILABLE" -> "利用状況を確認できなかったため見送りました"
        "REJECTED" -> "サイトで予約が受け付けられませんでした"
        "SITE_STOP" -> "サイトの応答により予約を停止しました"
        else -> "結果を読み取れません"
    }

    private fun rules(value: String): String {
        val rules = parseArray(value) ?: return "一致ルール: 詳細を読み取れません"
        val terms = rules.mapNotNull { it as? JsonObject }.flatMap { rule ->
            rule.strings("includeTerms").map { "含める語: $it" } +
                rule.strings("excludeTerms").map { "除外語: $it" }
        }
        return if (terms.isEmpty()) "一致ルール: 詳細を読み取れません" else terms.joinToString("／")
    }

    private fun attempts(value: String): String {
        val objectValue = parseObject(value) ?: return "試行: 詳細を読み取れません"
        val trials = (objectValue["trials"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { trial ->
            val name = (trial["memberName"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val result = (trial["result"] as? JsonPrimitive)?.content ?: "結果不明"
            "$name: ${attemptResult(result)}"
        }
        val assigned = ((objectValue["assignedMember"] as? JsonObject)?.get("name") as? JsonPrimitive)?.content?.let { "割当: $it" }
        val offReason = (objectValue["offReason"] as? JsonPrimitive)?.content?.let { "見送り理由: ${attemptResult(it)}" }
        return (trials + listOfNotNull(assigned, offReason)).takeIf { it.isNotEmpty() }?.joinToString("／")
            ?: "試行: なし"
    }

    private fun parseObject(value: String): JsonObject? = try { json.parseToJsonElement(value) as? JsonObject } catch (_: Exception) { null }
    private fun parseArray(value: String): JsonArray? = try { json.parseToJsonElement(value) as? JsonArray } catch (_: Exception) { null }
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.content?.toIntOrNull()
    private fun JsonObject.strings(key: String): List<String> = (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }

    private fun attemptResult(value: String): String = when (value) {
        "SUCCESS" -> "予約を確保しました"
        "ALREADY_RESERVED" -> "すでに予約済みです"
        "UNKNOWN_AFTER_POST" -> "送信後の成否を確認できません"
        "CIRCULATION_UNAVAILABLE" -> "利用状況を確認できません"
        "MEMBER_REMOVED_BEFORE_SUBMIT" -> "メンバーが削除されました"
        "MISSING_PASSWORD" -> "パスワードが未登録です"
        "RESERVATION_LIMIT_EXCEEDED" -> "予約枠が上限です"
        "AUTH" -> "認証に失敗しました"
        "NETWORK" -> "通信に失敗しました"
        "SESSION_EXPIRED_BEFORE_SUBMIT" -> "予約前にログイン状態が切れました"
        "INVALID_PICKUP_LIBRARY" -> "受取館の設定が無効です"
        "REJECTED_BY_SITE" -> "サイトで受け付けられませんでした"
        "SITE_RESPONSE_CHANGED" -> "サイトの応答を読み取れませんでした"
        "SITE_MAINTENANCE" -> "サイトがメンテナンス中です"
        "MEMBER_ABORTED_AFTER_SITE_CHANGE" -> "サイト変更のため試行を中止しました"
        "RULE_DISABLED" -> "ルールがOFFです"
        "SETTINGS_MISSING" -> "設定が不足しています"
        else -> "結果を読み取れません"
    }
}
