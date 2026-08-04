package com.fallgist.nishinomiyalibrary.ui

import android.content.Intent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.fallgist.nishinomiyalibrary.ui.app.LibraryApp
import com.fallgist.nishinomiyalibrary.ui.detail.BookDetailController
import com.fallgist.nishinomiyalibrary.ui.diagnostics.DiagnosticLogScreenController
import com.fallgist.nishinomiyalibrary.ui.di.MainActivityEntryPoint
import com.fallgist.nishinomiyalibrary.ui.home.HomeScreenController
import com.fallgist.nishinomiyalibrary.ui.loans.LoanExtensionUiController
import com.fallgist.nishinomiyalibrary.ui.loans.LoansScreenController
import com.fallgist.nishinomiyalibrary.ui.calendar.CalendarScreenController
import com.fallgist.nishinomiyalibrary.ui.newarrivals.NewArrivalsScreenController
import com.fallgist.nishinomiyalibrary.ui.reading.ReadingRecordsScreenController
import com.fallgist.nishinomiyalibrary.ui.reservations.ReservationCancelUiController
import com.fallgist.nishinomiyalibrary.ui.reservations.ReservationsScreenController
import com.fallgist.nishinomiyalibrary.ui.search.SearchScreenController
import com.fallgist.nishinomiyalibrary.ui.settings.SettingsScreenController
import com.fallgist.nishinomiyalibrary.ui.shelf.BookshelfScreenController
import com.fallgist.nishinomiyalibrary.ui.reservationcart.ReservationUiController
import com.fallgist.nishinomiyalibrary.ui.sync.SyncUiController
import com.fallgist.nishinomiyalibrary.ui.theme.NishinomiyaLibraryTheme
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Jetpack Composeでホーム画面を描画するランチャー。 */
open class MainActivity : ComponentActivity() {
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val homeNavigationCommands = HomeNavigationCommandStore()

    private lateinit var controller: HomeScreenController
    private lateinit var syncUiController: SyncUiController
    private lateinit var loansController: LoansScreenController
    private lateinit var loanExtensionUiController: LoanExtensionUiController
    private lateinit var reservationsController: ReservationsScreenController
    private lateinit var reservationCancelUiController: ReservationCancelUiController
    private lateinit var readingRecordsController: ReadingRecordsScreenController
    private lateinit var bookshelfController: BookshelfScreenController
    private lateinit var searchController: SearchScreenController
    private lateinit var calendarController: CalendarScreenController
    private lateinit var newArrivalsController: NewArrivalsScreenController
    private lateinit var settingsController: SettingsScreenController
    private lateinit var bookDetailController: BookDetailController
    private lateinit var reservationUiController: ReservationUiController
    private lateinit var diagnosticLogScreenController: DiagnosticLogScreenController

    override fun onCreate(savedInstanceState: Bundle?) {
        // FLAG_SECUREは付与しない。
        // 端末のスクリーンショットが真っ黒になり、不具合報告や調査で画面を共有できなくなるため。
        // パスワード・カード番号をUIへ表示する箇所は無く(暗号化ストレージに保持し、画面には出していない)、
        // 表示されるのは家族の貸出・予約状況のみであり、仕様書・設計書にもFLAG_SECUREを要求する記述は無い。
        super.onCreate(savedInstanceState)
        homeNavigationCommands.accept(intent)
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            MainActivityEntryPoint::class.java,
        )
        controller = resolveController(entryPoint)
        syncUiController = entryPoint.syncUiController()
        loansController = entryPoint.loansScreenController()
        loanExtensionUiController = entryPoint.loanExtensionUiController()
        reservationsController = entryPoint.reservationsScreenController()
        reservationCancelUiController = entryPoint.reservationCancelUiController()
        readingRecordsController = entryPoint.readingRecordsScreenController()
        bookshelfController = entryPoint.bookshelfScreenController()
        searchController = entryPoint.searchScreenController()
        calendarController = entryPoint.calendarScreenController()
        newArrivalsController = entryPoint.newArrivalsScreenController()
        settingsController = entryPoint.settingsScreenController()
        bookDetailController = entryPoint.bookDetailController()
        reservationUiController = entryPoint.reservationUiController()
        diagnosticLogScreenController = entryPoint.diagnosticLogScreenController()

        setContent {
            val state by controller.state.collectAsState()
            val syncState by syncUiController.state.collectAsState()
            val homeNavigationCommandId by homeNavigationCommands.commandId.collectAsState()
            NishinomiyaLibraryTheme {
                LibraryApp(
                    state = state,
                    syncState = syncState,
                    onSelectMember = controller::selectMember,
                    onManualSync = syncUiController::requestManualSync,
                    onConsumeSyncMessage = syncUiController::consumeMessage,
                    onRegister = controller::register,
                    onHomeVisible = controller::onHomeVisible,
                    onHomeHidden = controller::onHomeHidden,
                    onAcknowledgeAutoReservation = controller::acknowledgeLatestAutoReservationRun,
                    loansController = loansController,
                    loanExtensionUiController = loanExtensionUiController,
                    reservationsController = reservationsController,
                    reservationCancelUiController = reservationCancelUiController,
                    readingRecordsController = readingRecordsController,
                    bookshelfController = bookshelfController,
                    searchController = searchController,
                    calendarController = calendarController,
                    newArrivalsController = newArrivalsController,
                    settingsController = settingsController,
                    bookDetailController = bookDetailController,
                    reservationUiController = reservationUiController,
                    diagnosticLogScreenController = diagnosticLogScreenController,
                    homeNavigationCommandId = homeNavigationCommandId,
                    onConsumeHomeNavigationCommand = homeNavigationCommands::consume,
                )
            }
        }

        uiScope.launch { controller.onScreenLaunched() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        homeNavigationCommands.accept(intent)
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    /** 本番の依存解決はApplication EntryPointに限定する。 */
    protected open fun resolveController(entryPoint: MainActivityEntryPoint): HomeScreenController =
        entryPoint.homeScreenController()
}
