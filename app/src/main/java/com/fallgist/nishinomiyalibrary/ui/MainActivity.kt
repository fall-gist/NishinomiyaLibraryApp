package com.fallgist.nishinomiyalibrary.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.fallgist.nishinomiyalibrary.ui.app.LibraryApp
import com.fallgist.nishinomiyalibrary.ui.di.MainActivityEntryPoint
import com.fallgist.nishinomiyalibrary.ui.home.HomeScreenController
import com.fallgist.nishinomiyalibrary.ui.loans.LoansScreenController
import com.fallgist.nishinomiyalibrary.ui.calendar.CalendarScreenController
import com.fallgist.nishinomiyalibrary.ui.reading.ReadingRecordsScreenController
import com.fallgist.nishinomiyalibrary.ui.reservations.ReservationsScreenController
import com.fallgist.nishinomiyalibrary.ui.search.SearchScreenController
import com.fallgist.nishinomiyalibrary.ui.settings.SettingsScreenController
import com.fallgist.nishinomiyalibrary.ui.shelf.BookshelfScreenController
import com.fallgist.nishinomiyalibrary.ui.theme.NishinomiyaLibraryTheme
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Jetpack Composeでホーム画面を描画するランチャー。認証情報の露出防止にFLAG_SECUREを付与する。 */
open class MainActivity : ComponentActivity() {
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var controller: HomeScreenController
    private lateinit var loansController: LoansScreenController
    private lateinit var reservationsController: ReservationsScreenController
    private lateinit var readingRecordsController: ReadingRecordsScreenController
    private lateinit var bookshelfController: BookshelfScreenController
    private lateinit var searchController: SearchScreenController
    private lateinit var calendarController: CalendarScreenController
    private lateinit var settingsController: SettingsScreenController

    override fun onCreate(savedInstanceState: Bundle?) {
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        super.onCreate(savedInstanceState)
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            MainActivityEntryPoint::class.java,
        )
        controller = resolveController(entryPoint)
        loansController = entryPoint.loansScreenController()
        reservationsController = entryPoint.reservationsScreenController()
        readingRecordsController = entryPoint.readingRecordsScreenController()
        bookshelfController = entryPoint.bookshelfScreenController()
        searchController = entryPoint.searchScreenController()
        calendarController = entryPoint.calendarScreenController()
        settingsController = entryPoint.settingsScreenController()

        setContent {
            val state by controller.state.collectAsState()
            NishinomiyaLibraryTheme {
                LibraryApp(
                    state = state,
                    onSelectMember = controller::selectMember,
                    onManualSync = { uiScope.launch { controller.requestManualSync() } },
                    onRegister = controller::register,
                    loansController = loansController,
                    reservationsController = reservationsController,
                    readingRecordsController = readingRecordsController,
                    bookshelfController = bookshelfController,
                    searchController = searchController,
                    calendarController = calendarController,
                    settingsController = settingsController,
                )
            }
        }

        uiScope.launch { controller.onScreenLaunched() }
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    /** 本番の依存解決はApplication EntryPointに限定する。 */
    protected open fun resolveController(entryPoint: MainActivityEntryPoint): HomeScreenController =
        entryPoint.homeScreenController()
}
