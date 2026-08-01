package com.fallgist.nishinomiyalibrary.data.sync

import com.fallgist.nishinomiyalibrary.data.repository.AutomaticReservationRunResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/** 個人情報を含まない自動予約完了通知の集計値。 */
data class AutoReservationNotificationSummary(
    val securedCount: Int,
    val skippedCount: Int,
    val errorCount: Int,
) {
    val body: String
        get() = "確保済み${securedCount}件／見送り${skippedCount}件／エラー${errorCount}件"
}

/** 自動予約履歴を通知可能な集計値へ変換する純粋な分類器。 */
object AutoReservationNotificationPlanner {
    fun plan(result: AutomaticReservationRunResult): AutoReservationNotificationSummary? {
        val completed = result as? AutomaticReservationRunResult.Completed ?: return null
        if (completed.items.isEmpty()) return null

        var secured = 0
        var skipped = 0
        var errors = 0
        var allReportableItemsAreRuleDisabled = true
        completed.items.forEach { item ->
            when (item.outcome) {
                "SUCCESS", "ALREADY_RESERVED" -> {
                    secured += 1
                    allReportableItemsAreRuleDisabled = false
                }
                "ALL_MEMBERS_LIMITED", "SETTINGS_MISSING" -> {
                    skipped += 1
                    allReportableItemsAreRuleDisabled = false
                }
                "RULE_DISABLED" -> skipped += 1
                in NORMAL_EXCLUSIONS -> Unit
                else -> {
                    errors += 1
                    allReportableItemsAreRuleDisabled = false
                }
            }
        }
        if (secured + skipped + errors == 0 || allReportableItemsAreRuleDisabled) return null
        return AutoReservationNotificationSummary(secured, skipped, errors)
    }

    private val NORMAL_EXCLUSIONS = setOf(
        "EXCLUDED_READ",
        "EXCLUDED_LOANED",
        "EXCLUDED_RESERVED",
        "NO_MATCH",
    )
}

interface AutoReservationNotificationSink {
    suspend fun postAutoReservation(summary: AutoReservationNotificationSummary): Boolean
}

interface AutoReservationCompletionNotifier {
    suspend fun notifyCompletion(result: AutomaticReservationRunResult)
}

/** 通知の失敗を、自動予約および新着更新の結果から隔離する。 */
@Singleton
class AutoReservationNotificationService @Inject constructor(
    private val sink: AutoReservationNotificationSink,
) : AutoReservationCompletionNotifier {
    override suspend fun notifyCompletion(result: AutomaticReservationRunResult) {
        val summary = AutoReservationNotificationPlanner.plan(result) ?: return
        try {
            sink.postAutoReservation(summary)
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            // 通知は付随処理なので、予約確定後の結果を変更しない。
        }
    }
}

object NoOpAutoReservationCompletionNotifier : AutoReservationCompletionNotifier {
    override suspend fun notifyCompletion(result: AutomaticReservationRunResult) = Unit
}
