package com.fallgist.nishinomiyalibrary.data.repository

import androidx.room.withTransaction
import com.fallgist.nishinomiyalibrary.data.local.AppDatabase
import com.fallgist.nishinomiyalibrary.data.local.dao.AutoReservationDao
import com.fallgist.nishinomiyalibrary.data.local.entity.AutoReservationControlEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.AutoReservationLatestItemEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.AutoReservationLatestRunEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.AutoReservationRuleEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.AutoReservationTermEntity
import com.fallgist.nishinomiyalibrary.domain.model.AutoReservationControl
import com.fallgist.nishinomiyalibrary.domain.model.AutoReservationLatestItem
import com.fallgist.nishinomiyalibrary.domain.model.AutoReservationLatestRun
import com.fallgist.nishinomiyalibrary.domain.model.AutoReservationMatcher
import com.fallgist.nishinomiyalibrary.domain.model.AutoReservationRule
import com.fallgist.nishinomiyalibrary.domain.model.AutoReservationTermKind
import com.fallgist.nishinomiyalibrary.domain.repository.AutoReservationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AutoReservationRepositoryImpl @Inject constructor(
    private val database: AppDatabase,
    private val dao: AutoReservationDao,
) : AutoReservationRepository {
    override suspend fun rules(): List<AutoReservationRule> {
        val rules = dao.getRules()
        val terms = dao.getTerms(rules.map { it.id })
        return rules.map { rule ->
            val ownTerms = terms.filter { it.ruleId == rule.id }
            AutoReservationRule(
                id = rule.id,
                enabled = rule.enabled,
                sortOrder = rule.sortOrder,
                includeTerms = ownTerms.filter { it.kind == AutoReservationTermKind.INCLUDE }.map { it.original },
                excludeTerms = ownTerms.filter { it.kind == AutoReservationTermKind.EXCLUDE }.map { it.original },
            )
        }
    }

    override suspend fun replaceRules(rules: List<AutoReservationRule>) {
        require(rules.map { it.sortOrder }.distinct().size == rules.size) { "ルールの並び順が重複しています" }
        rules.forEach(AutoReservationMatcher::validate)
        database.withTransaction {
            dao.clearRules()
            rules.sortedBy { it.sortOrder }.forEach { rule ->
                val ruleId = dao.insertRule(AutoReservationRuleEntity(enabled = rule.enabled, sortOrder = rule.sortOrder))
                val terms = buildList {
                    rule.includeTerms.forEachIndexed { index, value ->
                        add(AutoReservationTermEntity(ruleId, AutoReservationTermKind.INCLUDE, index, value, com.fallgist.nishinomiyalibrary.domain.model.TextNormalizer.normalize(value)))
                    }
                    rule.excludeTerms.forEachIndexed { index, value ->
                        add(AutoReservationTermEntity(ruleId, AutoReservationTermKind.EXCLUDE, index, value, com.fallgist.nishinomiyalibrary.domain.model.TextNormalizer.normalize(value)))
                    }
                }
                dao.insertTerms(terms)
            }
        }
    }

    override suspend fun removeExpiredControls(today: java.time.LocalDate): Int = dao.deleteExpiredControls(today)

    override suspend fun markPreparedControlsUnknown(): Int = dao.markPreparedControlsUnknown()

    override suspend fun control(tilcod: String): AutoReservationControl? = dao.getControl(tilcod)?.toDomain()

    override suspend fun saveControl(control: AutoReservationControl) {
        require(control.tilcod.isNotBlank()) { "tilcodが空です" }
        require(control.expiresOn == control.firstCandidateDate.plusMonths(2)) { "制御記録の期限が不正です" }
        val existing = dao.getControl(control.tilcod)
        if (existing != null) {
            require(existing.firstCandidateDate == control.firstCandidateDate && existing.expiresOn == control.expiresOn) {
                "再試行で制御記録の期限を延長できません"
            }
        }
        dao.upsertControl(control.toEntity())
    }

    override fun latestRun(): Flow<AutoReservationLatestRun?> = dao.observeLatestRun().map { run ->
        run?.let { latest ->
            AutoReservationLatestRun(
                runId = latest.runId,
                completedAtEpochMillis = latest.completedAtEpochMillis,
                summaryJson = latest.summaryJson,
                acknowledged = latest.acknowledged,
                items = dao.getLatestItems(latest.runId).map(AutoReservationLatestItemEntity::toDomain),
            )
        }
    }

    override suspend fun replaceLatestRun(run: AutoReservationLatestRun) {
        require(run.items.all { it.runId == run.runId }) { "最新履歴項目のrunIdが一致しません" }
        require(run.items.map { it.tilcod }.distinct().size == run.items.size) { "最新履歴項目のtilcodが重複しています" }
        dao.replaceLatestRun(
            AutoReservationLatestRunEntity(
                runId = run.runId,
                completedAtEpochMillis = run.completedAtEpochMillis,
                summaryJson = run.summaryJson,
                acknowledged = run.acknowledged,
            ),
            run.items.map { it.toEntity() },
        )
    }

    override suspend fun markLatestRunAcknowledged() = dao.markLatestRunAcknowledged()
}

private fun AutoReservationControlEntity.toDomain() = AutoReservationControl(
    tilcod, firstCandidateDate, expiresOn, status, preparedMemberId,
)

private fun AutoReservationControl.toEntity() = AutoReservationControlEntity(
    tilcod, firstCandidateDate, expiresOn, status, preparedMemberId,
)

private fun AutoReservationLatestItemEntity.toDomain() = AutoReservationLatestItem(
    runId, tilcod, title, matchedRulesJson, attemptedMembersJson, outcome,
)

private fun AutoReservationLatestItem.toEntity() = AutoReservationLatestItemEntity(
    runId, tilcod, title, matchedRulesJson, attemptedMembersJson, outcome,
)
