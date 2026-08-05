package com.fallgist.nishinomiyalibrary.data.repository

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.fallgist.nishinomiyalibrary.data.local.CredentialStore
import com.fallgist.nishinomiyalibrary.data.local.SettingsStore
import com.fallgist.nishinomiyalibrary.data.local.dao.ReservationPickupSubmissionDao
import com.fallgist.nishinomiyalibrary.data.local.entity.ReservationPickupSubmissionEntity
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.*
import com.fallgist.nishinomiyalibrary.domain.model.*
import com.fallgist.nishinomiyalibrary.domain.repository.*
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AutomaticReservationCoordinatorTest {
    @Test fun `master off and no match do not read circulation`() = runTest {
        val current = Current()
        assertEquals(AutomaticReservationRunResult.SkippedMasterOff, coordinator(settings(false), current = current).run())
        assertEquals(0, current.calls)
        val noMatch = coordinator(settings(true), Controls(listOf(rule())), Arrivals(listOf(arrival("other"))), current)
        assertEquals(AutomaticReservationRunResult.NoMatch, noMatch.run())
        assertEquals(0, current.calls)
    }

    @Test fun `non cancelled reservation and loan and read are exclusions`() = runTest {
        val member = member()
        val controls = Controls(listOf(rule()))
        val active = Current { Snapshot(reservations = listOf(reservation("t", ReservationState.WAITING)), complete = false) }
        coordinator(settings(true), controls, Arrivals(listOf(arrival())), active, family = Family(listOf(member)), credentials = credentials(member)).run()
        assertEquals(AutoReservationControlStatus.EXCLUDED_RESERVED, controls.saved.last().status)
        val loan = Current { Snapshot(loans = listOf(Loan(1, "対象", "", "", java.time.LocalDate.EPOCH, java.time.LocalDate.EPOCH, "", "t2"))) }
        coordinator(settings(true), controls, Arrivals(listOf(arrival("対象2", "t2"))), loan, family = Family(listOf(member)), credentials = credentials(member)).run()
        assertEquals(AutoReservationControlStatus.EXCLUDED_LOANED, controls.saved.last().status)
    }

    @Test fun `cancelled reservation alone stays eligible but active row excludes`() = runTest {
        val member = member()
        val gateway = Gateway()
        val cancelled = Current { Snapshot(reservations = listOf(reservation("t", ReservationState.CANCELLED))) }
        val completed = coordinator(settings(true), Controls(listOf(rule())), Arrivals(listOf(arrival())), cancelled, gateway, Family(listOf(member)), credentials(member)).run() as AutomaticReservationRunResult.Completed
        assertEquals("SUCCESS", completed.items.single().outcome)
        val mixed = Current { Snapshot(reservations = listOf(reservation("t", ReservationState.CANCELLED), reservation("t", ReservationState.READY))) }
        val controls = Controls(listOf(rule()))
        coordinator(settings(true), controls, Arrivals(listOf(arrival())), mixed, Gateway(), Family(listOf(member)), credentials(member)).run()
        assertEquals(AutoReservationControlStatus.EXCLUDED_RESERVED, controls.saved.last().status)
    }

    @Test fun `all circulation failures and off only incomplete are reported without POST`() = runTest {
        val member = member()
        val gateway = Gateway()
        val failed = Current { throw IllegalStateException("network") }
        val controls = Controls(listOf(rule()))
        val result = coordinator(settings(true), controls, Arrivals(listOf(arrival())), failed, gateway, Family(listOf(member)), credentials(member)).run() as AutomaticReservationRunResult.Completed
        assertEquals("CIRCULATION_UNAVAILABLE", result.items.single().outcome)
        assertEquals(0, gateway.opens)
        val off = Controls(listOf(rule(enabled = false)))
        val offResult = coordinator(settings(true), off, Arrivals(listOf(arrival())), failed, Gateway(), Family(listOf(member)), credentials(member)).run() as AutomaticReservationRunResult.Completed
        assertEquals("CIRCULATION_UNAVAILABLE", offResult.items.single().outcome)
    }

    @Test fun `failed circulation member is not a POST target while successful incomplete member remains eligible`() = runTest {
        val first = member(1)
        val second = member(2)
        val current = PerMemberCurrent(mapOf(
            first.cardNumber to { throw IllegalStateException("offline") },
            second.cardNumber to { Snapshot(complete = false) },
        ))
        val gateway = Gateway()
        val credentials = credentials(first).also { it.savePassword(second.id, "p") }

        val result = coordinator(settings(true), Controls(listOf(rule())), Arrivals(listOf(arrival())), current, gateway, Family(listOf(first, second)), credentials).run() as AutomaticReservationRunResult.Completed

        assertEquals("SUCCESS", result.items.single().outcome)
        assertEquals(listOf(second.cardNumber), gateway.openedCards)
        assertEquals(2, current.calls)
    }

    @Test fun `OFF only is RULE_DISABLED only when every member has a complete circulation snapshot`() = runTest {
        val first = member(1)
        val second = member(2)
        val credentials = credentials(first).also { it.savePassword(second.id, "p") }
        val rules = Controls(listOf(rule(enabled = false)))
        val incomplete = PerMemberCurrent(mapOf(
            first.cardNumber to { Snapshot() },
            second.cardNumber to { Snapshot(complete = false) },
        ))
        val incompleteResult = coordinator(settings(true), rules, Arrivals(listOf(arrival())), incomplete, Gateway(), Family(listOf(first, second)), credentials).run() as AutomaticReservationRunResult.Completed
        assertEquals("CIRCULATION_UNAVAILABLE", incompleteResult.items.single().outcome)

        val completeResult = coordinator(settings(true), Controls(listOf(rule(enabled = false))), Arrivals(listOf(arrival())), Current(), Gateway(), Family(listOf(first)), credentials(first)).run() as AutomaticReservationRunResult.Completed
        assertEquals("RULE_DISABLED", completeResult.items.single().outcome)
    }

    @Test fun `terminal control is not replaced by later exclusion and preserves dates`() = runTest {
        val member = member()
        val controls = Controls(listOf(rule()))
        val original = AutoReservationControl("t", java.time.LocalDate.of(2026, 1, 1), java.time.LocalDate.of(2026, 3, 1), AutoReservationControlStatus.SUCCESS)
        controls.saveControl(original)

        coordinator(settings(true), controls, Arrivals(listOf(arrival())), Current { Snapshot(reservations = listOf(reservation("t", ReservationState.WAITING))) }, Gateway(), Family(listOf(member)), credentials(member)).run()

        assertEquals(original, controls.control("t"))
        assertTrue(controls.latest == null)
    }

    @Test fun `leftover PREPARED becomes unknown without POST and terminal control remains unchanged`() = runTest {
        val member = member()
        val controls = Controls(listOf(rule()))
        val prepared = AutoReservationControl("t", java.time.LocalDate.of(2026, 1, 1), java.time.LocalDate.of(2026, 3, 1), AutoReservationControlStatus.PREPARED, member.id)
        val terminal = AutoReservationControl("finished", java.time.LocalDate.of(2026, 1, 2), java.time.LocalDate.of(2026, 3, 2), AutoReservationControlStatus.SUCCESS)
        controls.saveControl(prepared)
        controls.saveControl(terminal)
        val gateway = Gateway()

        val result = coordinator(settings(true), controls, Arrivals(listOf(arrival())), Current(), gateway, Family(listOf(member)), credentials(member)).run() as AutomaticReservationRunResult.Completed

        assertTrue(result.items.isEmpty())
        assertEquals(AutoReservationControlStatus.UNKNOWN_AFTER_POST, controls.control("t")?.status)
        assertEquals(terminal, controls.control("finished"))
        assertEquals(1, controls.preparedMarked)
        assertEquals(0, gateway.opens)
    }

    @Test fun `PREPARED is saved immediately before POST and is absent when login fails`() = runTest {
        val member = member()
        val events = mutableListOf<String>()
        val controls = Controls(listOf(rule()), events)
        val gateway = BoundaryGateway(events)

        val preparedResult = coordinator(settings(true), controls, Arrivals(listOf(arrival())), Current(), gateway, Family(listOf(member)), credentials(member)).run() as AutomaticReservationRunResult.Completed

        assertTrue(events.indexOf("PREPARED") in 0 until events.indexOf("POST"))
        assertTrue(preparedResult.preparedReached)
        assertEquals(AutoReservationControlStatus.SUCCESS, controls.control("t")?.status)
        val loginFailureControls = Controls(listOf(rule()))
        coordinator(settings(true), loginFailureControls, Arrivals(listOf(arrival())), FailingCurrent(), BoundaryGateway(mutableListOf(), failLogin = true), Family(listOf(member)), credentials(member)).run()
        assertTrue(loginFailureControls.saved.none { it.status == AutoReservationControlStatus.PREPARED })
    }

    @Test fun `member deleted at the write boundary does not POST or escape as an exception`() = runTest {
        val member = member()
        val family = Family(listOf(member))
        val events = mutableListOf<String>()
        val controls = Controls(listOf(rule()), events)
        val gateway = BoundaryGateway(events, beforeBoundary = { family.removeMember(member.id) })

        val result = coordinator(settings(true), controls, Arrivals(listOf(arrival())), Current(), gateway, family, credentials(member)).run() as AutomaticReservationRunResult.Completed

        assertEquals("ALL_MEMBERS_PRE_SUBMIT_FAILED", result.items.single().outcome)
        assertTrue("POSTしてはならない", "POST" !in events)
        assertTrue("PREPAREDを残してはならない", controls.saved.none { it.status == AutoReservationControlStatus.PREPARED })
    }

    @Test fun `complete confirmation snapshot updates cleanup without empty NOT IN query`() = runTest {
        val member = member()
        val pickup = CleanupPickupDao()
        val result = coordinator(
            settings(true), Controls(listOf(rule())), Arrivals(listOf(arrival())), Current(),
            BoundaryGateway(mutableListOf(), snapshotReservations = emptyList()), Family(listOf(member)), credentials(member), pickup,
        ).run() as AutomaticReservationRunResult.Completed

        assertEquals("UNKNOWN_AFTER_POST", result.items.single().outcome)
        assertEquals(listOf(member.id), pickup.deletedMembers)
        assertTrue(pickup.deleteMissingArguments.isEmpty())
    }

    @Test fun `settings missing prepared and terminal controls do not overwrite history incorrectly`() = runTest {
        val member = member()
        val store = settings(true).also { it.updateDefaultCalendarLibrary("") }
        val controls = Controls(listOf(rule()))
        val result = coordinator(store, controls, Arrivals(listOf(arrival())), Current(), family = Family(listOf(member)), credentials = credentials(member)).run() as AutomaticReservationRunResult.Completed
        assertEquals("SETTINGS_MISSING", result.items.single().outcome)
        assertEquals(AutoReservationControlStatus.SETTINGS_MISSING, controls.saved.last().status)
        assertTrue(controls.latest!!.items.single().matchedRulesJson.contains("\"id\":1"))
        assertTrue(controls.latest!!.items.single().attemptedMembersJson.contains("SETTINGS_MISSING"))
    }

    @Test fun `session is reused and rejected unknown do not fall through member`() = runTest {
        val first = member(1)
        val second = member(2)
        val gateway = Gateway()
        val result = coordinator(settings(true), Controls(listOf(rule())), Arrivals(listOf(arrival("対象A", "a"), arrival("対象B", "b"))), Current(), gateway, Family(listOf(first)), credentials(first)).run() as AutomaticReservationRunResult.Completed
        assertEquals(listOf("SUCCESS", "SUCCESS"), result.items.map { it.outcome })
        assertEquals(1, gateway.opens)
        val rejected = Gateway(mutableListOf(DirectReservationAttempt.RejectedBeforeSubmit))
        val fallback = credentials(first).also { it.savePassword(second.id, "p") }
        val rejection = coordinator(settings(true), Controls(listOf(rule())), Arrivals(listOf(arrival())), Current(), rejected, Family(listOf(first, second)), fallback).run() as AutomaticReservationRunResult.Completed
        assertEquals("REJECTED", rejection.items.single().outcome)
        assertEquals(1, rejected.opens)
    }

    @Test fun `empty rules and reading records skip reservation without POST`() = runTest {
        val member = member()
        val current = Current()
        assertEquals(AutomaticReservationRunResult.SkippedNoEnabledRules, coordinator(settings(true), Controls(), Arrivals(listOf(arrival())), current, family = Family(listOf(member)), credentials = credentials(member)).run())
        assertEquals(0, current.calls)

        val controls = Controls(listOf(rule()))
        val gateway = Gateway()
        coordinator(settings(true), controls, Arrivals(listOf(arrival())), Current(), gateway, Family(listOf(member)), credentials(member), reading = Reading(listOf(ReadingInfo(member.id, java.time.LocalDate.EPOCH, "library")))).run()
        assertEquals(AutoReservationControlStatus.EXCLUDED_READ, controls.control("t")?.status)
        assertEquals(0, gateway.opens)
    }

    @Test fun `fallback failures move to next member and unknown does not`() = runTest {
        val first = member(1)
        val second = member(2)
        val both = credentials(first).also { it.savePassword(second.id, "p") }
        listOf(
            Gateway(mutableListOf(DirectReservationAttempt.LimitExceeded("limit"))),
            Gateway(openErrors = mutableMapOf(first.cardNumber to LibraryError.Auth(null))),
            Gateway(openErrors = mutableMapOf(first.cardNumber to LibraryError.Network(IllegalStateException("network")))),
            Gateway(mutableListOf(DirectReservationAttempt.SessionExpiredBeforeSubmit, DirectReservationAttempt.SessionExpiredBeforeSubmit)),
        ).forEach { gateway ->
            val completed = coordinator(settings(true), Controls(listOf(rule())), Arrivals(listOf(arrival())), Current(), gateway, Family(listOf(first, second)), both).run() as AutomaticReservationRunResult.Completed
            assertEquals("SUCCESS", completed.items.single().outcome)
            assertEquals(listOf(first.cardNumber, second.cardNumber), gateway.openedCards.takeLast(2))
        }

        val unknown = Gateway(mutableListOf(DirectReservationAttempt.IndeterminateAfterPost))
        val result = coordinator(settings(true), Controls(listOf(rule())), Arrivals(listOf(arrival())), Current(), unknown, Family(listOf(first, second)), both).run() as AutomaticReservationRunResult.Completed
        assertEquals("UNKNOWN_AFTER_POST", result.items.single().outcome)
        assertEquals(listOf(first.cardNumber), unknown.openedCards)
    }

    @Test fun `candidate reset and write generation control circulation cache`() = runTest {
        val member = member()
        val disabled = Controls(listOf(
            AutoReservationRule(1, false, 0, listOf("A")),
            AutoReservationRule(2, false, 1, listOf("B")),
        ))
        val unchanged = Current()
        coordinator(settings(true), disabled, Arrivals(listOf(arrival("A", "a"), arrival("B", "b"))), unchanged, family = Family(listOf(member)), credentials = credentials(member)).run()
        assertEquals(1, unchanged.calls)

        val changed = Current()
        val gateway = Gateway(mutableListOf(DirectReservationAttempt.LimitExceeded("limit"), DirectReservationAttempt.Submitted))
        val enabled = Controls(listOf(
            AutoReservationRule(1, true, 0, listOf("A")),
            AutoReservationRule(2, true, 1, listOf("B")),
        ))
        val completed = coordinator(settings(true), enabled, Arrivals(listOf(arrival("A", "a"), arrival("B", "b"))), changed, gateway, Family(listOf(member)), credentials(member)).run() as AutomaticReservationRunResult.Completed
        assertEquals(listOf("ALL_MEMBERS_LIMITED", "SUCCESS"), completed.items.map { it.outcome })
        assertEquals(1, changed.calls)
    }

    @Test fun `member additions are ignored and deletion only skips that member`() = runTest {
        val first = member(1)
        val second = member(2)
        val added = member(3)
        val credentials = credentials(first).also { it.savePassword(second.id, "p"); it.savePassword(added.id, "p") }
        val family = Family(listOf(first, second))
        val gateway = Gateway(onOpen = { card -> if (card == first.cardNumber) family.append(added) })
        val matchingRules = Controls(listOf(AutoReservationRule(1, true, 0, listOf("A")), AutoReservationRule(2, true, 1, listOf("B"))))
        val addedResult = coordinator(settings(true), matchingRules, Arrivals(listOf(arrival("A", "a"), arrival("B", "b"))), Current(), gateway, family, credentials).run() as AutomaticReservationRunResult.Completed
        assertEquals(listOf("SUCCESS", "SUCCESS"), addedResult.items.map { it.outcome })
        assertTrue(added.cardNumber !in gateway.openedCards)

        val deletingFamily = Family(listOf(first, second))
        val current = PerMemberCurrent(mapOf(first.cardNumber to { Snapshot() }, second.cardNumber to { Snapshot() })) { card ->
            if (card == first.cardNumber) deletingFamily.removeMember(first.id)
        }
        val lowerGateway = Gateway()
        val result = coordinator(settings(true), Controls(listOf(rule())), Arrivals(listOf(arrival())), current, lowerGateway, deletingFamily, credentials).run() as AutomaticReservationRunResult.Completed
        assertEquals("SUCCESS", result.items.single().outcome)
        assertEquals(listOf(second.cardNumber), lowerGateway.openedCards)
    }

    @Test fun `deletion at the prepared write boundary continues with the lower member`() = runTest {
        val first = member(1)
        val second = member(2)
        val family = Family(listOf(first, second))
        val credentials = credentials(first).also { it.savePassword(second.id, "p") }
        val events = mutableListOf<String>()
        val controls = Controls(listOf(rule()), events)
        val gateway = BoundaryGateway(events, beforeBoundary = { family.removeMember(first.id) })

        val result = coordinator(settings(true), controls, Arrivals(listOf(arrival())), Current(), gateway, family, credentials).run() as AutomaticReservationRunResult.Completed

        assertEquals("SUCCESS", result.items.single().outcome)
        assertEquals(1, events.count { it == "POST" })
        assertEquals(AutoReservationControlStatus.SUCCESS, controls.control("t")?.status)
    }

    @Test fun `deletion while PREPARED is saved prevents that POST and continues lower member`() = runTest {
        val first = member(1)
        val second = member(2)
        val family = Family(listOf(first, second))
        val credentials = credentials(first).also { it.savePassword(second.id, "p") }
        val controls = Controls(listOf(rule()), onPrepared = { family.removeMember(first.id) })
        val gateway = Gateway()

        val result = coordinator(settings(true), controls, Arrivals(listOf(arrival())), Current(), gateway, family, credentials).run() as AutomaticReservationRunResult.Completed

        assertEquals("SUCCESS", result.items.single().outcome)
        assertEquals(listOf(first.cardNumber, second.cardNumber), gateway.openedCards)
        assertEquals(AutoReservationControlStatus.SUCCESS, controls.control("t")?.status)
    }

    @Test fun `history JSON safely round trips every JSON control character`() = runTest {
        val special = "tab\tbackspace\bformfeed\u000Cnull\u0000quote\"slash/\\"
        val member = member().copy(name = special)
        val customRule = AutoReservationRule(1, true, 0, listOf("target", special))
        val controls = Controls(listOf(customRule))
        coordinator(settings(true), controls, Arrivals(listOf(arrival("target$special"))), Current(), Gateway(), Family(listOf(member)), credentials(member)).run()

        val item = requireNotNull(controls.latest).items.single()
        val ruleJson = Json.parseToJsonElement(item.matchedRulesJson).jsonArray.single().jsonObject
        val attemptJson = Json.parseToJsonElement(item.attemptedMembersJson).jsonObject
        assertEquals(special, ruleJson.getValue("includeTerms").jsonArray[1].jsonPrimitive.content)
        assertEquals(special, attemptJson.getValue("trials").jsonArray.single().jsonObject.getValue("memberName").jsonPrimitive.content)
    }

    @Test fun `OFF only still records ordinary exclusions before rule disabled`() = runTest {
        val member = member()
        val controls = Controls(listOf(rule(enabled = false)))
        coordinator(settings(true), controls, Arrivals(listOf(arrival())), Current(), Gateway(), Family(listOf(member)), credentials(member), reading = Reading(listOf(ReadingInfo(member.id, java.time.LocalDate.EPOCH, "library")))).run()
        assertEquals(AutoReservationControlStatus.EXCLUDED_READ, controls.control("t")?.status)
    }

    @Test fun `candidate empty still maintains expired and prepared controls`() = runTest {
        val member = member()
        val controls = Controls(listOf(rule()))
        controls.saveControl(AutoReservationControl("left", java.time.LocalDate.EPOCH, java.time.LocalDate.EPOCH.plusMonths(2), AutoReservationControlStatus.PREPARED, member.id))
        controls.saveControl(AutoReservationControl("expired", java.time.LocalDate.EPOCH.minusDays(2), java.time.LocalDate.EPOCH.minusDays(1), AutoReservationControlStatus.SUCCESS))
        assertEquals(AutomaticReservationRunResult.NoMatch, coordinator(settings(true), controls, Arrivals(emptyList()), Current(), Gateway(), Family(listOf(member)), credentials(member)).run())
        assertEquals(AutoReservationControlStatus.UNKNOWN_AFTER_POST, controls.control("left")?.status)
        assertEquals(1, controls.preparedMarked)
        assertEquals(null, controls.control("expired"))
        assertEquals(1, controls.expiredRemoved)
    }

    @Test fun `current and post complete snapshots are persisted`() = runTest {
        val member = member()
        val store = SnapshotStore()
        val current = Current { Snapshot(loans = listOf(Loan(member.id, "loan", "", "", java.time.LocalDate.EPOCH, java.time.LocalDate.EPOCH, "", "loan-code"))) }
        coordinator(settings(true), Controls(listOf(rule())), Arrivals(listOf(arrival())), current, Gateway(), Family(listOf(member)), credentials(member), snapshotStore = store).run()
        assertEquals(listOf("loan-code"), store.loanReplacements.single().second.map { it.tilcod })
        assertEquals(listOf("t"), store.reservationReplacements.last().second.map { it.tilcod })
    }

    @Test fun `external write generation between candidates refetches circulation while own writes reuse it`() = runTest {
        val member = member()
        val gate = ReservationOperationGate()
        val firstFetchStarted = CompletableDeferred<Unit>()
        val releaseFirstFetch = CompletableDeferred<Unit>()
        val current = Current {
            firstFetchStarted.complete(Unit)
            releaseFirstFetch.await()
            Snapshot()
        }
        val coordinator = coordinator(
            settings(true),
            Controls(listOf(AutoReservationRule(1, true, 0, listOf("A")), AutoReservationRule(2, true, 1, listOf("B")))),
            Arrivals(listOf(arrival("A", "a"), arrival("B", "b"))),
            current,
            Gateway(),
            Family(listOf(member)),
            credentials(member),
            gate = gate,
        )

        val run = async { coordinator.run() }
        firstFetchStarted.await()
        val externalWrite = async { gate.withOperation(ReservationOperationType.MANUAL_RESERVATION) { markWriteStarted() } }
        yield()
        releaseFirstFetch.complete(Unit)
        externalWrite.await()
        run.await()

        assertEquals(2, current.calls)
    }

    private fun coordinator(settings: SettingsStore, controls: Controls = Controls(), arrivals: NewArrivalRepository = Arrivals(emptyList()), current: CurrentCirculationGateway = Current(), gateway: ReservationGateway = Gateway(), family: Family = Family(), credentials: CredentialStore = CredentialStore(RuntimeEnvironment.getApplication() as Context), pickup: ReservationPickupSubmissionDao = PickupDao(), reading: ReadingRecordRepository = Reading(), snapshotStore: SnapshotStore = SnapshotStore(), gate: ReservationOperationGate = ReservationOperationGate()) =
        AutomaticReservationCoordinator(settings, arrivals, family, reading, controls, current, gateway, credentials, pickup, gate, Clock.fixed(Instant.EPOCH, ZoneId.of("Asia/Tokyo")), snapshotStore)
    private suspend fun settings(enabled: Boolean) = SettingsStore(PreferenceDataStoreFactory.create { File.createTempFile("settings", ".preferences_pb").also { it.deleteOnExit() } }).also { it.updateAutoReservationEnabled(enabled) }
    private fun rule(enabled: Boolean = true) = AutoReservationRule(1, enabled, 0, listOf("対象"))
    private fun arrival(title: String = "対象", code: String = "t") = NewArrival(code, title, "", "", "", "2026-01", "", null)
    private fun member(id: Long = 1) = Member(id, "会員$id", "#000", "card$id", id.toInt())
    private fun reservation(code: String, state: ReservationState) = Reservation(1, "対象", "", "", java.time.LocalDate.EPOCH, null, state, null, code)
    private fun credentials(member: Member) = CredentialStore(RuntimeEnvironment.getApplication() as Context).also { it.savePassword(member.id, "p") }

    private class Arrivals(private val values: List<NewArrival>) : NewArrivalRepository { override fun newArrivals(): Flow<List<NewArrival>> = MutableStateFlow(values); override suspend fun refresh() = Unit; override suspend fun lastFetchedAtEpochMillis(): Long? = null; override suspend fun hasCachedItems() = false }
    private class Family(values: List<Member> = emptyList()) : FamilyRepository { private val state = MutableStateFlow(values); override fun members(): Flow<List<Member>> = state; override suspend fun addMember(name: String, colorHex: String, cardNumber: String, password: String) = Unit; override suspend fun updateMember(member: Member, newPassword: String?) = Unit; override suspend fun removeMember(memberId: Long) { state.value = state.value.filterNot { it.id == memberId } }; fun append(member: Member) { state.value += member } }
    private class Reading(private val read: List<ReadingInfo> = emptyList()) : ReadingRecordRepository { override fun records(memberId: Long?) = MutableStateFlow(emptyList<ReadingRecord>()); override fun search(query: String, memberId: Long?) = MutableStateFlow(emptyList<ReadingRecord>()); override fun hasRead(tilcod: String) = MutableStateFlow(read) }
    private class Controls(private val rulesValue: List<AutoReservationRule> = emptyList(), private val events: MutableList<String>? = null, private val onPrepared: suspend () -> Unit = {}) : AutoReservationRepository { val saved = mutableListOf<AutoReservationControl>(); var latest: AutoReservationLatestRun? = null; var preparedMarked = 0; var expiredRemoved = 0; override suspend fun rules() = rulesValue; override suspend fun replaceRules(rules: List<AutoReservationRule>) = Unit; override suspend fun removeExpiredControls(today: java.time.LocalDate): Int { val expired = saved.filter { it.expiresOn.isBefore(today) }; saved.removeAll(expired); expiredRemoved += expired.size; return expired.size }; override suspend fun markPreparedControlsUnknown(): Int { val prepared = saved.filter { it.status == AutoReservationControlStatus.PREPARED }; prepared.forEach { control -> saveControl(control.copy(status = AutoReservationControlStatus.UNKNOWN_AFTER_POST, preparedMemberId = null)) }; preparedMarked += prepared.size; return prepared.size }; override suspend fun control(tilcod: String) = saved.lastOrNull { it.tilcod == tilcod }; override suspend fun saveControl(control: AutoReservationControl) { saved.removeAll { it.tilcod == control.tilcod }; saved += control; if (control.status == AutoReservationControlStatus.PREPARED) { events?.add("PREPARED"); onPrepared() } }; override fun latestRun() = MutableStateFlow(latest); override suspend fun replaceLatestRun(run: AutoReservationLatestRun) { latest = run }; override suspend fun markLatestRunAcknowledged(runId: Long) = false }
    private data class Snapshot(val loans: List<Loan> = emptyList(), val reservations: List<Reservation> = emptyList(), val complete: Boolean = true)
    private class Current(private val block: suspend () -> Snapshot = { Snapshot() }) : CurrentCirculationGateway { var calls = 0; override suspend fun fetchCurrentCirculation(cardNumber: String, password: String): CurrentCirculationSnapshot { calls++; return block().let { CurrentCirculationSnapshot(it.loans, it.reservations, it.complete) } } }
    private class PerMemberCurrent(private val values: Map<String, suspend () -> Snapshot>, private val afterFetch: suspend (String) -> Unit = {}) : CurrentCirculationGateway { var calls = 0; override suspend fun fetchCurrentCirculation(cardNumber: String, password: String): CurrentCirculationSnapshot { calls++; return values.getValue(cardNumber)().let { snapshot -> afterFetch(cardNumber); CurrentCirculationSnapshot(snapshot.loans, snapshot.reservations, snapshot.complete) } } }
    private class FailingCurrent : CurrentCirculationGateway { override suspend fun fetchCurrentCirculation(cardNumber: String, password: String) = CurrentCirculationSnapshot(emptyList(), emptyList(), true) }
    private class Gateway(private val attempts: MutableList<DirectReservationAttempt> = mutableListOf(), private val openErrors: MutableMap<String, Exception> = mutableMapOf(), private val onOpen: suspend (String) -> Unit = {}) : ReservationGateway { var opens = 0; val openedCards = mutableListOf<String>(); override suspend fun openAuthenticatedSession(cardNumber: String, password: String): ReservationSession { onOpen(cardNumber); opens++; openedCards += cardNumber; openErrors.remove(cardNumber)?.let { throw it }; return Session(attempts) }; private class Session(private val attempts: MutableList<DirectReservationAttempt>) : ReservationSession, ReservationSnapshotSource, ReservationWriteBoundaryAware { private val reservations = mutableListOf<Reservation>(); private var beforeWrite: (() -> Unit)? = null; override fun setBeforeWriteBoundary(callback: (() -> Unit)?) { beforeWrite = callback }; override suspend fun directReserve(tilcod: String, pickupLibraryCode: String): DirectReservationAttempt { beforeWrite?.invoke(); val attempt = if (attempts.isEmpty()) DirectReservationAttempt.Submitted else attempts.removeAt(0); if (attempt == DirectReservationAttempt.Submitted) reservations += Reservation(1, tilcod, "", pickupLibraryCode, java.time.LocalDate.EPOCH, null, ReservationState.WAITING, null, tilcod); return attempt }; override suspend fun fetchReservations() = reservations; override suspend fun fetchReservationSnapshot() = ReservationListSnapshot(reservations, true); override fun close() = Unit } }
    private class BoundaryGateway(private val events: MutableList<String>, private val failLogin: Boolean = false, private val beforeBoundary: suspend () -> Unit = {}, private val snapshotReservations: List<Reservation>? = null) : ReservationGateway { override suspend fun openAuthenticatedSession(cardNumber: String, password: String): ReservationSession { if (failLogin) throw LibraryError.Auth(null); return object : ReservationSession, ReservationWriteBoundaryAware, ReservationSnapshotSource { private var beforeWrite: (() -> Unit)? = null; override fun setBeforeWriteBoundary(callback: (() -> Unit)?) { beforeWrite = callback }; override suspend fun directReserve(tilcod: String, pickupLibraryCode: String): DirectReservationAttempt { beforeBoundary(); beforeWrite?.invoke(); events += "POST"; return DirectReservationAttempt.Submitted }; override suspend fun fetchReservations() = emptyList<Reservation>(); override suspend fun fetchReservationSnapshot() = ReservationListSnapshot(snapshotReservations ?: listOf(Reservation(1, "資料", "", "106", java.time.LocalDate.EPOCH, null, ReservationState.WAITING, null, "t")), true); override fun close() = Unit } } }
    private class PickupDao : ReservationPickupSubmissionDao { override suspend fun upsert(submission: ReservationPickupSubmissionEntity) = Unit; override suspend fun get(memberId: Long, tilcod: String): ReservationPickupSubmissionEntity? = null; override fun observeAll(): Flow<List<ReservationPickupSubmissionEntity>> = MutableStateFlow(emptyList()); override suspend fun deleteMissingFromCompleteSnapshot(memberId: Long, activeTilcods: List<String>) = 0; override suspend fun deleteForMember(memberId: Long) = Unit }
    private class CleanupPickupDao : ReservationPickupSubmissionDao { val deletedMembers = mutableListOf<Long>(); val deleteMissingArguments = mutableListOf<List<String>>(); override suspend fun upsert(submission: ReservationPickupSubmissionEntity) = Unit; override suspend fun get(memberId: Long, tilcod: String): ReservationPickupSubmissionEntity? = null; override fun observeAll(): Flow<List<ReservationPickupSubmissionEntity>> = MutableStateFlow(emptyList()); override suspend fun deleteMissingFromCompleteSnapshot(memberId: Long, activeTilcods: List<String>): Int { deleteMissingArguments += activeTilcods; return 0 }; override suspend fun deleteForMember(memberId: Long) { deletedMembers += memberId } }
    private class SnapshotStore : CurrentCirculationSnapshotStore { val loanReplacements = mutableListOf<Pair<Long, List<Loan>>>(); val reservationReplacements = mutableListOf<Pair<Long, List<Reservation>>>(); override suspend fun replaceLoans(memberId: Long, loans: List<Loan>) { loanReplacements += memberId to loans }; override suspend fun replaceCompleteReservations(memberId: Long, reservations: List<Reservation>) { reservationReplacements += memberId to reservations } }
}
