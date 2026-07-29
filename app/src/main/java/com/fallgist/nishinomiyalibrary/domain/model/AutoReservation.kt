package com.fallgist.nishinomiyalibrary.domain.model

import java.time.LocalDate

data class AutoReservationRule(
    val id: Long = 0,
    val enabled: Boolean,
    val sortOrder: Int,
    val includeTerms: List<String>,
    val excludeTerms: List<String> = emptyList(),
)

enum class AutoReservationTermKind { INCLUDE, EXCLUDE }

enum class AutoReservationControlStatus {
    SUCCESS,
    ALREADY_RESERVED,
    EXCLUDED_RESERVED,
    EXCLUDED_LOANED,
    EXCLUDED_READ,
    REJECTED,
    UNKNOWN_AFTER_POST,
    MEMBER_FALLBACK_PENDING,
    ALL_MEMBERS_LIMITED,
    ALL_MEMBERS_PRE_SUBMIT_FAILED,
    SETTINGS_MISSING,
    PREPARED,
}

data class AutoReservationControl(
    val tilcod: String,
    val firstCandidateDate: LocalDate,
    val expiresOn: LocalDate,
    val status: AutoReservationControlStatus,
    val preparedMemberId: Long? = null,
)

data class AutoReservationLatestRun(
    val runId: Long,
    val completedAtEpochMillis: Long,
    val summaryJson: String,
    val acknowledged: Boolean,
    val items: List<AutoReservationLatestItem> = emptyList(),
)

data class AutoReservationLatestItem(
    val runId: Long,
    val tilcod: String,
    val title: String,
    val matchedRulesJson: String,
    val attemptedMembersJson: String,
    val outcome: String,
)

/** 受取館をアプリが送信した時点の出自。 */
enum class ReservationPickupSubmissionOrigin { CONFIRMED_SUBMISSION, UNVERIFIED_SUBMISSION }

data class ReservationPickupSubmission(
    val memberId: Long,
    val tilcod: String,
    val pickupLibraryCode: String,
    val origin: ReservationPickupSubmissionOrigin,
)

/**
 * UIやRoomに依存しない新着ルール照合。ルール内は含める語AND、除外語は一つでも一致すれば除外する。
 */
object AutoReservationMatcher {
    fun matches(rule: AutoReservationRule, title: String): Boolean {
        val normalizedTitle = TextNormalizer.normalize(title)
        val includes = rule.includeTerms.map(TextNormalizer::normalize)
        val excludes = rule.excludeTerms.map(TextNormalizer::normalize)
        return includes.all(normalizedTitle::contains) && excludes.none(normalizedTitle::contains)
    }

    /** 保存時にも使うので、原文ではなく正規化後の値で検証する。 */
    fun validate(rule: AutoReservationRule) {
        val includes = rule.includeTerms.map(TextNormalizer::normalize)
        val excludes = rule.excludeTerms.map(TextNormalizer::normalize)
        require(includes.isNotEmpty() && includes.none(String::isBlank)) { "含める語を1件以上入力してください" }
        require(excludes.none(String::isBlank)) { "除外語に空白だけの語は指定できません" }
        require(includes.distinct().size == includes.size) { "含める語が重複しています" }
        require(excludes.distinct().size == excludes.size) { "除外語が重複しています" }
        require(excludes.none { exclude -> includes.any { include -> include.contains(exclude) } }) {
            "除外語が含める語の一部になっています"
        }
    }

    /** 有効ルールに一致した最小順序を返す。複数一致時も候補は資料単位で一つに名寄せする。 */
    fun matchingEnabledRules(rules: List<AutoReservationRule>, title: String): List<AutoReservationRule> =
        rules.filter { it.enabled && matches(it, title) }.sortedBy { it.sortOrder }

    fun candidateComparator(ruleOrderByTilcod: Map<String, Int>): Comparator<NewArrival> = Comparator { a, b ->
        compareValues(ruleOrderByTilcod.getValue(a.tilcod), ruleOrderByTilcod.getValue(b.tilcod))
            .takeIf { it != 0 }
            ?: comparePublishedYearMonthDescending(a.publishedYearMonth, b.publishedYearMonth)
            .takeIf { it != 0 }
            ?: a.title.compareTo(b.title)
            .takeIf { it != 0 }
            ?: a.volume.compareTo(b.volume)
            .takeIf { it != 0 }
            ?: a.tilcod.compareTo(b.tilcod)
            .takeIf { it != 0 }
            ?: a.publishedYearMonth.compareTo(b.publishedYearMonth)
    }

    private fun comparePublishedYearMonthDescending(a: String, b: String): Int {
        val aValue = parseYearMonth(a)
        val bValue = parseYearMonth(b)
        return when {
            aValue != null && bValue != null -> bValue.compareTo(aValue)
            aValue != null -> -1
            bValue != null -> 1
            else -> 0
        }
    }

    private fun parseYearMonth(value: String): Int? {
        val match = Regex("^(\\d{4})[-/.年]?(\\d{1,2})(?:月)?$").matchEntire(value.trim()) ?: return null
        val year = match.groupValues[1].toIntOrNull() ?: return null
        val month = match.groupValues[2].toIntOrNull()?.takeIf { it in 1..12 } ?: return null
        return year * 100 + month
    }
}
