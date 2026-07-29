package com.fallgist.nishinomiyalibrary.data.repository

import com.fallgist.nishinomiyalibrary.data.local.CredentialStore
import com.fallgist.nishinomiyalibrary.data.local.SettingsStore
import com.fallgist.nishinomiyalibrary.data.local.dao.ReservationPickupSubmissionDao
import com.fallgist.nishinomiyalibrary.data.local.entity.ReservationPickupSubmissionEntity
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.CurrentCirculationGateway
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.CurrentCirculationSnapshot
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.ReservationGateway
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.ReservationListSnapshot
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.ReservationSession
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.ReservationWriteBoundaryAware
import com.fallgist.nishinomiyalibrary.domain.model.*
import com.fallgist.nishinomiyalibrary.domain.repository.*
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 新着候補を、実行開始時の設定・メンバー順で安全に一件ずつ予約する。 */
@Singleton
class AutomaticReservationCoordinator @Inject constructor(
    private val settingsStore: SettingsStore,
    private val arrivals: NewArrivalRepository,
    private val family: FamilyRepository,
    private val readingRecords: ReadingRecordRepository,
    private val controls: AutoReservationRepository,
    private val circulation: CurrentCirculationGateway,
    private val reservationGateway: ReservationGateway,
    private val credentials: CredentialStore,
    private val pickupSubmissions: ReservationPickupSubmissionDao,
    private val gate: ReservationOperationGate,
    private val clock: Clock,
    private val snapshotStore: CurrentCirculationSnapshotStore,
) : AutomaticReservationRunner {
    private val resolver = ReservationSubmissionResolver(reservationGateway)

    override suspend fun run(onPreparedPersisted: () -> Unit): AutomaticReservationRunResult {
        val settings = settingsStore.settings.first()
        if (!settings.autoReservationEnabled) return AutomaticReservationRunResult.SkippedMasterOff
        val today = LocalDate.now(clock)
        controls.removeExpiredControls(today)
        controls.markPreparedControlsUnknown()
        val rules = controls.rules()
        if (rules.isEmpty()) return AutomaticReservationRunResult.SkippedNoEnabledRules
        val members = family.members().first().sortedWith(compareBy<Member> { it.sortOrder }.thenBy { it.id })
        val candidates = candidates(rules)
        if (candidates.isEmpty()) return AutomaticReservationRunResult.NoMatch

        val sessions = mutableMapOf<Long, ReservationSession>()
        val history = mutableListOf<HistoryItem>()
        var preparedReached = false
        var cached: Map<Long, MemberCirculation>? = null
        var generation = -1L
        var stop = false
        try {
            for (candidate in candidates) {
                if (stop) break
                gate.withOperation {
                    if (cached == null || currentWriteGeneration != generation) {
                        cached = fetchCirculation(members)
                        generation = currentWriteGeneration
                    }
                    var snapshots = requireNotNull(cached)

                    // 既に確定した結果は、今回の除外判定や設定不備で上書きしない。
                    val previous = controls.control(candidate.arrival.tilcod)
                    if (previous != null && previous.status !in RETRYABLE) {
                        return@withOperation
                    }

                    val earlyExclusion = exclusion(candidate.arrival.tilcod, snapshots)
                    if (earlyExclusion != null) {
                        saveControl(candidate.arrival.tilcod, today, earlyExclusion)
                        return@withOperation
                    }
                    if (candidate.offOnly) {
                        // 利用状況を全員分かつ完全に取得できた場合だけ、OFFのみを断定する。
                        val allCirculationComplete = members.isNotEmpty() && members.all { member ->
                            (snapshots[member.id] as? MemberCirculation.Success)
                                ?.snapshot
                                ?.reservationListComplete == true
                        }
                        val outcome = if (allCirculationComplete) "RULE_DISABLED" else "CIRCULATION_UNAVAILABLE"
                        history += HistoryItem(candidate, outcome, emptyList(), null, outcome)
                        return@withOperation
                    }
                    if (members.isEmpty() || settings.defaultCalendarLibrary.isBlank()) {
                        saveControl(candidate.arrival.tilcod, today, AutoReservationControlStatus.SETTINGS_MISSING)
                        history += HistoryItem(candidate, "SETTINGS_MISSING", emptyList(), null, "SETTINGS_MISSING")
                        return@withOperation
                    }
                    // 通信に失敗したメンバーは予約先から除外する。不完全一覧の成功メンバーは予約先に残す。
                    if (snapshots.values.none { it is MemberCirculation.Success }) {
                        saveControl(candidate.arrival.tilcod, today, AutoReservationControlStatus.ALL_MEMBERS_PRE_SUBMIT_FAILED)
                        history += HistoryItem(candidate, "CIRCULATION_UNAVAILABLE", emptyList(), null, "CIRCULATION_UNAVAILABLE")
                        return@withOperation
                    }

                    val attempts = mutableListOf<MemberAttempt>()
                    var assigned: Member? = null
                    var terminal: String? = null
                    var allLimited = true
                    for (member in members) {
                        if (snapshots[member.id] !is MemberCirculation.Success) {
                            attempts += MemberAttempt(member, "CIRCULATION_UNAVAILABLE")
                            allLimited = false
                            continue
                        }
                        // 実行中に削除されたメンバーへはPOSTしない。
                        if (family.members().first().none { it.id == member.id }) {
                            attempts += MemberAttempt(member, "MEMBER_REMOVED_BEFORE_SUBMIT")
                            allLimited = false
                            continue
                        }
                        val password = credentials.getPassword(member.id)
                        if (password.isNullOrBlank()) {
                            allLimited = false
                            attempts += MemberAttempt(member, "MISSING_PASSWORD")
                            continue
                        }
                        val target = ReservationTarget(null, member.id, candidate.arrival.tilcod, candidate.arrival.title)
                        val resolution = resolver.resolveMemberInSession(
                            sessions[member.id], member.cardNumber, password, target, settings.defaultCalendarLibrary,
                        ) {
                            runBlocking {
                                if (family.members().first().none { it.id == member.id }) {
                                    throw MemberRemovedBeforeSubmitException()
                                }
                                saveControl(candidate.arrival.tilcod, today, AutoReservationControlStatus.PREPARED, member.id)
                                preparedReached = true
                                onPreparedPersisted()
                                if (family.members().first().none { it.id == member.id }) {
                                    throw MemberRemovedBeforeSubmitException()
                                }
                            }
                            markWriteStarted()
                        }
                        val result = resolution.result.results.single()
                        resolution.session?.let { sessions[member.id] = it } ?: sessions.remove(member.id)
                        if (!result.sessionReusable) closeSession(sessions.remove(member.id))
                        snapshots = applyLatestReservationSnapshot(snapshots, member.id, resolution.result.latestReservationSnapshot)
                        cached = snapshots
                        if (resolution.memberRemovedBeforeSubmit) {
                            attempts += MemberAttempt(member, "MEMBER_REMOVED_BEFORE_SUBMIT")
                            allLimited = false
                            saveControl(candidate.arrival.tilcod, today, AutoReservationControlStatus.MEMBER_FALLBACK_PENDING)
                            continue
                        }
                        when (val outcome = result.outcome) {
                            ReservationOutcome.Success, ReservationOutcome.AlreadyReserved -> {
                                val status = if (outcome == ReservationOutcome.Success) AutoReservationControlStatus.SUCCESS else AutoReservationControlStatus.ALREADY_RESERVED
                                saveControl(candidate.arrival.tilcod, today, status)
                                savePickup(member.id, candidate.arrival.tilcod, settings.defaultCalendarLibrary, result)
                                assigned = member
                                terminal = status.name
                                attempts += MemberAttempt(member, terminal)
                                allLimited = false
                                break
                            }
                            is ReservationOutcome.Unknown -> {
                                saveControl(candidate.arrival.tilcod, today, AutoReservationControlStatus.UNKNOWN_AFTER_POST)
                                savePickup(member.id, candidate.arrival.tilcod, settings.defaultCalendarLibrary, result)
                                attempts += MemberAttempt(member, "UNKNOWN_AFTER_POST")
                                terminal = "UNKNOWN_AFTER_POST"
                                allLimited = false
                                break
                            }
                            is ReservationOutcome.Failure -> {
                                val name = outcome.reason.name
                                attempts += MemberAttempt(member, name)
                                when (outcome.reason) {
                                    FailureReason.RESERVATION_LIMIT_EXCEEDED -> saveControl(candidate.arrival.tilcod, today, AutoReservationControlStatus.MEMBER_FALLBACK_PENDING)
                                    FailureReason.AUTH, FailureReason.NETWORK, FailureReason.SESSION_EXPIRED_BEFORE_SUBMIT -> {
                                        allLimited = false
                                        saveControl(candidate.arrival.tilcod, today, AutoReservationControlStatus.MEMBER_FALLBACK_PENDING)
                                    }
                                    FailureReason.INVALID_PICKUP_LIBRARY -> {
                                        saveControl(candidate.arrival.tilcod, today, AutoReservationControlStatus.SETTINGS_MISSING)
                                        terminal = "SETTINGS_MISSING"
                                        break
                                    }
                                    FailureReason.REJECTED_BY_SITE -> {
                                        saveControl(candidate.arrival.tilcod, today, AutoReservationControlStatus.REJECTED)
                                        terminal = "REJECTED"
                                        break
                                    }
                                    FailureReason.SITE_RESPONSE_CHANGED, FailureReason.SITE_MAINTENANCE, FailureReason.MEMBER_ABORTED_AFTER_SITE_CHANGE -> {
                                        saveControl(candidate.arrival.tilcod, today, AutoReservationControlStatus.ALL_MEMBERS_PRE_SUBMIT_FAILED)
                                        terminal = "SITE_STOP"
                                        stop = true
                                        break
                                    }
                                }
                            }
                        }
                    }
                    val outcome = terminal ?: if (allLimited) {
                        saveControl(candidate.arrival.tilcod, today, AutoReservationControlStatus.ALL_MEMBERS_LIMITED)
                        AutoReservationControlStatus.ALL_MEMBERS_LIMITED.name
                    } else {
                        saveControl(candidate.arrival.tilcod, today, AutoReservationControlStatus.ALL_MEMBERS_PRE_SUBMIT_FAILED)
                        AutoReservationControlStatus.ALL_MEMBERS_PRE_SUBMIT_FAILED.name
                    }
                    history += HistoryItem(candidate, outcome, attempts, assigned, null)
                    generation = currentWriteGeneration
                }
            }
        } finally {
            sessions.values.forEach(::closeSession)
        }
        if (history.isNotEmpty()) saveLatest(history)
        return AutomaticReservationRunResult.Completed(
            history.map { AutomaticReservationItemResult(it.candidate.arrival.tilcod, it.candidate.arrival.title, it.outcome) },
            preparedReached,
        )
    }

    private suspend fun candidates(rules: List<AutoReservationRule>): List<Candidate> = arrivals.newArrivals().first().mapNotNull { arrival ->
        val enabled = AutoReservationMatcher.matchingEnabledRules(rules, arrival.title)
        val all = rules.filter { AutoReservationMatcher.matches(it, arrival.title) }.sortedBy { it.sortOrder }
        when {
            enabled.isNotEmpty() -> Candidate(arrival, all, false)
            all.isNotEmpty() -> Candidate(arrival, all, true)
            else -> null
        }
    }.sortedWith(compareBy<Candidate> { it.rules.filter { rule -> rule.enabled }.minOfOrNull { it.sortOrder } ?: Int.MAX_VALUE }
        .thenComparator { a, b -> AutoReservationMatcher.candidateComparator(emptyMap<String, Int>().withDefault { Int.MAX_VALUE }).compare(a.arrival, b.arrival) })

    private suspend fun fetchCirculation(members: List<Member>): Map<Long, MemberCirculation> = members.associate { member ->
        val password = credentials.getPassword(member.id)
        member.id to try {
            if (password.isNullOrBlank()) {
                MemberCirculation.Failed
            } else {
                val snapshot = circulation.fetchCurrentCirculation(member.cardNumber, password)
                snapshotStore.replaceLoans(member.id, snapshot.loans)
                if (snapshot.reservationListComplete) snapshotStore.replaceCompleteReservations(member.id, snapshot.reservations)
                MemberCirculation.Success(snapshot)
            }
        } catch (exception: Exception) {
            if (exception is CancellationException) throw exception
            MemberCirculation.Failed
        }
    }

    private suspend fun exclusion(tilcod: String, snapshots: Map<Long, MemberCirculation>): AutoReservationControlStatus? {
        if (readingRecords.hasRead(tilcod).first().isNotEmpty()) return AutoReservationControlStatus.EXCLUDED_READ
        val successful = snapshots.values.mapNotNull { (it as? MemberCirculation.Success)?.snapshot }
        if (successful.any { snapshot -> snapshot.loans.any { it.tilcod == tilcod } }) return AutoReservationControlStatus.EXCLUDED_LOANED
        if (successful.any { snapshot -> snapshot.reservations.any { it.tilcod == tilcod && it.state != ReservationState.CANCELLED } }) return AutoReservationControlStatus.EXCLUDED_RESERVED
        return null
    }

    private suspend fun saveControl(tilcod: String, date: LocalDate, status: AutoReservationControlStatus, memberId: Long? = null) {
        val existing = controls.control(tilcod)
        controls.saveControl(AutoReservationControl(tilcod, existing?.firstCandidateDate ?: date, existing?.expiresOn ?: date.plusMonths(2), status, memberId))
    }

    /**
     * POST後照合で得た予約一覧を次候補の判定に反映する。
     * 完全一覧だけは送信館記録の削除根拠にも使い、不完全一覧では既存記録を消さない。
     */
    private suspend fun applyLatestReservationSnapshot(
        snapshots: Map<Long, MemberCirculation>,
        memberId: Long,
        latest: ReservationListSnapshot?,
    ): Map<Long, MemberCirculation> {
        latest ?: return snapshots
        val current = (snapshots[memberId] as? MemberCirculation.Success)?.snapshot ?: return snapshots
        if (latest.complete) {
            snapshotStore.replaceCompleteReservations(memberId, latest.reservations)
            val activeTilcods = latest.reservations
                .filter { it.state != ReservationState.CANCELLED }
                .map { it.tilcod }
                .filter(String::isNotBlank)
                .distinct()
            if (activeTilcods.isEmpty()) {
                pickupSubmissions.deleteForMember(memberId)
            } else {
                pickupSubmissions.deleteMissingFromCompleteSnapshot(memberId, activeTilcods)
            }
        }
        return snapshots + (memberId to MemberCirculation.Success(
            current.copy(
                reservations = latest.reservations,
                reservationListComplete = latest.complete,
            ),
        ))
    }

    private suspend fun savePickup(memberId: Long, tilcod: String, pickup: String, result: ReservationSubmissionResult) {
        val origin = when (result.outcome) {
            ReservationOutcome.Success -> ReservationPickupSubmissionOrigin.CONFIRMED_SUBMISSION
            is ReservationOutcome.Unknown -> if (result.postBoundary == ReservationPostBoundary.SENT_OR_UNKNOWN) ReservationPickupSubmissionOrigin.UNVERIFIED_SUBMISSION else null
            else -> null
        } ?: return
        pickupSubmissions.upsert(ReservationPickupSubmissionEntity(memberId, tilcod, pickup, origin))
    }

    private suspend fun saveLatest(items: List<HistoryItem>) {
        val id = clock.millis()
        val success = items.count { it.outcome == "SUCCESS" || it.outcome == "ALREADY_RESERVED" }
        val skipped = items.count { it.outcome in setOf("ALL_MEMBERS_LIMITED", "SETTINGS_MISSING", "RULE_DISABLED") }
        val error = items.size - success - skipped
        val summaryJson = encodeJson(buildJsonObject {
            put("success", success)
            put("skipped", skipped)
            put("error", error)
        })
        controls.replaceLatestRun(AutoReservationLatestRun(id, id, summaryJson, false, items.map {
            AutoReservationLatestItem(id, it.candidate.arrival.tilcod, it.candidate.arrival.title, rulesJson(it.candidate.rules), attemptsJson(it.attempts, it.assigned, it.offReason), it.outcome)
        }))
    }

    private fun rulesJson(rules: List<AutoReservationRule>): String = encodeJson(
        JsonArray(rules.map { rule ->
            buildJsonObject {
                put("id", rule.id)
                put("enabled", rule.enabled)
                put("sortOrder", rule.sortOrder)
                put("includeTerms", JsonArray(rule.includeTerms.map(::JsonPrimitive)))
                put("excludeTerms", JsonArray(rule.excludeTerms.map(::JsonPrimitive)))
            }
        }),
    )

    private fun attemptsJson(attempts: List<MemberAttempt>, assigned: Member?, offReason: String?): String = encodeJson(
        buildJsonObject {
            put("trials", JsonArray(attempts.map { attempt ->
                buildJsonObject {
                    put("memberId", attempt.member.id)
                    put("memberName", attempt.member.name)
                    put("result", attempt.result)
                }
            }))
            assigned?.let { member ->
                put("assignedMember", buildJsonObject {
                    put("id", member.id)
                    put("name", member.name)
                })
            }
            offReason?.let { put("offReason", it) }
        },
    )

    private fun encodeJson(element: JsonElement): String = Json.encodeToString(JsonElement.serializer(), element)
    private fun closeSession(session: ReservationSession?) { session ?: return; (session as? ReservationWriteBoundaryAware)?.setBeforeWriteBoundary(null); session.close() }

    private data class Candidate(val arrival: NewArrival, val rules: List<AutoReservationRule>, val offOnly: Boolean)
    private data class MemberAttempt(val member: Member, val result: String)
    private data class HistoryItem(val candidate: Candidate, val outcome: String, val attempts: List<MemberAttempt>, val assigned: Member?, val offReason: String?)
    private sealed interface MemberCirculation { data class Success(val snapshot: CurrentCirculationSnapshot) : MemberCirculation; data object Failed : MemberCirculation }
    private companion object { val RETRYABLE = setOf(AutoReservationControlStatus.MEMBER_FALLBACK_PENDING, AutoReservationControlStatus.ALL_MEMBERS_LIMITED, AutoReservationControlStatus.ALL_MEMBERS_PRE_SUBMIT_FAILED, AutoReservationControlStatus.SETTINGS_MISSING) }
}

data class AutomaticReservationItemResult(val tilcod: String, val title: String, val outcome: String)
sealed interface AutomaticReservationRunResult {
    data object SkippedMasterOff : AutomaticReservationRunResult
    data object SkippedNoEnabledRules : AutomaticReservationRunResult
    data object NoMatch : AutomaticReservationRunResult
    data class Completed(
        val items: List<AutomaticReservationItemResult>,
        val preparedReached: Boolean = false,
    ) : AutomaticReservationRunResult
}
