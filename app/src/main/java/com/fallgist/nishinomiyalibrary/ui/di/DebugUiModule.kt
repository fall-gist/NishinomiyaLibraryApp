package com.fallgist.nishinomiyalibrary.ui.di

import com.fallgist.nishinomiyalibrary.data.diagnostics.DiagnosticLog
import com.fallgist.nishinomiyalibrary.data.local.SettingsStore
import com.fallgist.nishinomiyalibrary.data.sync.SyncScheduleStarter
import com.fallgist.nishinomiyalibrary.data.sync.WorkManagerSyncScheduleStarter
import com.fallgist.nishinomiyalibrary.domain.repository.CalendarRepository
import com.fallgist.nishinomiyalibrary.domain.repository.FamilyRepository
import com.fallgist.nishinomiyalibrary.domain.repository.NewArrivalRepository
import com.fallgist.nishinomiyalibrary.domain.repository.ReadingRecordRepository
import com.fallgist.nishinomiyalibrary.domain.repository.ReservationCancelRepository
import com.fallgist.nishinomiyalibrary.domain.repository.ReservationCartRepository
import com.fallgist.nishinomiyalibrary.domain.repository.SearchRepository
import com.fallgist.nishinomiyalibrary.domain.repository.StatusRepository
import com.fallgist.nishinomiyalibrary.ui.calendar.CalendarScreenController
import com.fallgist.nishinomiyalibrary.ui.debug.DebugScreenController
import com.fallgist.nishinomiyalibrary.ui.detail.BookDetailController
import com.fallgist.nishinomiyalibrary.ui.diagnostics.DiagnosticLogScreenController
import com.fallgist.nishinomiyalibrary.ui.home.HomeScreenController
import com.fallgist.nishinomiyalibrary.ui.loans.LoansScreenController
import com.fallgist.nishinomiyalibrary.ui.newarrivals.NewArrivalsScreenController
import com.fallgist.nishinomiyalibrary.ui.reading.ReadingRecordsScreenController
import com.fallgist.nishinomiyalibrary.ui.reservations.ReservationCancelUiController
import com.fallgist.nishinomiyalibrary.ui.reservations.ReservationsScreenController
import com.fallgist.nishinomiyalibrary.ui.reservationcart.ReservationUiController
import com.fallgist.nishinomiyalibrary.ui.search.SearchScreenController
import com.fallgist.nishinomiyalibrary.ui.settings.SettingsScreenController
import com.fallgist.nishinomiyalibrary.ui.shelf.BookshelfScreenController
import com.fallgist.nishinomiyalibrary.ui.sync.SyncUiController
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DebugUiBindingModule {
    @Binds
    @Singleton
    abstract fun bindSyncScheduleStarter(implementation: WorkManagerSyncScheduleStarter): SyncScheduleStarter
}

@Module
@InstallIn(SingletonComponent::class)
object DebugUiProvisionModule {
    @Provides
    @Singleton
    fun provideDebugScreenController(
        familyRepository: FamilyRepository,
        statusRepository: StatusRepository,
        readingRecordRepository: ReadingRecordRepository,
        scheduleStarter: SyncScheduleStarter,
    ): DebugScreenController = DebugScreenController(
        familyRepository = familyRepository,
        statusRepository = statusRepository,
        readingRecordRepository = readingRecordRepository,
        scheduleStarter = scheduleStarter,
    )

    @Provides
    @Singleton
    fun provideHomeScreenController(
        familyRepository: FamilyRepository,
        statusRepository: StatusRepository,
        scheduleStarter: SyncScheduleStarter,
    ): HomeScreenController = HomeScreenController(
        familyRepository = familyRepository,
        statusRepository = statusRepository,
        scheduleStarter = scheduleStarter,
    )

    @Provides
    @Singleton
    fun provideSyncUiController(
        statusRepository: StatusRepository,
    ): SyncUiController = SyncUiController(statusRepository = statusRepository)

    @Provides
    @Singleton
    fun provideLoansScreenController(
        familyRepository: FamilyRepository,
        statusRepository: StatusRepository,
    ): LoansScreenController = LoansScreenController(
        familyRepository = familyRepository,
        statusRepository = statusRepository,
    )

    @Provides
    @Singleton
    fun provideReservationsScreenController(
        familyRepository: FamilyRepository,
        statusRepository: StatusRepository,
    ): ReservationsScreenController = ReservationsScreenController(
        familyRepository = familyRepository,
        statusRepository = statusRepository,
    )

    @Provides
    @Singleton
    fun provideReservationCancelUiController(
        cancelRepository: ReservationCancelRepository,
        familyRepository: FamilyRepository,
    ): ReservationCancelUiController = ReservationCancelUiController(
        cancelRepository = cancelRepository,
        familyRepository = familyRepository,
    )

    @Provides
    @Singleton
    fun provideReadingRecordsScreenController(
        familyRepository: FamilyRepository,
        readingRecordRepository: ReadingRecordRepository,
    ): ReadingRecordsScreenController = ReadingRecordsScreenController(
        familyRepository = familyRepository,
        readingRecordRepository = readingRecordRepository,
    )

    @Provides
    @Singleton
    fun provideBookshelfScreenController(
        familyRepository: FamilyRepository,
        statusRepository: StatusRepository,
    ): BookshelfScreenController = BookshelfScreenController(
        familyRepository = familyRepository,
        statusRepository = statusRepository,
    )

    @Provides
    @Singleton
    fun provideSearchScreenController(
        searchRepository: SearchRepository,
        readingRecordRepository: ReadingRecordRepository,
        familyRepository: FamilyRepository,
    ): SearchScreenController = SearchScreenController(
        searchRepository = searchRepository,
        readingRecordRepository = readingRecordRepository,
        familyRepository = familyRepository,
    )

    @Provides
    @Singleton
    fun provideCalendarScreenController(
        calendarRepository: CalendarRepository,
        settingsStore: SettingsStore,
    ): CalendarScreenController = CalendarScreenController(
        calendarRepository = calendarRepository,
        settingsStore = settingsStore,
    )

    @Provides
    @Singleton
    fun provideNewArrivalsScreenController(
        newArrivalRepository: NewArrivalRepository,
        clock: Clock,
    ): NewArrivalsScreenController = NewArrivalsScreenController(
        newArrivalRepository = newArrivalRepository,
        clock = clock,
    )

    @Provides
    @Singleton
    fun provideBookDetailController(
        searchRepository: SearchRepository,
        readingRecordRepository: ReadingRecordRepository,
        familyRepository: FamilyRepository,
    ): BookDetailController = BookDetailController(
        searchRepository = searchRepository,
        readingRecordRepository = readingRecordRepository,
        familyRepository = familyRepository,
    )

    @Provides
    @Singleton
    fun provideReservationUiController(
        cartRepository: ReservationCartRepository,
        familyRepository: FamilyRepository,
        calendarRepository: CalendarRepository,
        settingsStore: SettingsStore,
    ): ReservationUiController = ReservationUiController(
        cartRepository = cartRepository,
        familyRepository = familyRepository,
        calendarRepository = calendarRepository,
        settings = settingsStore.settings,
    )

    @Provides
    @Singleton
    fun provideSettingsScreenController(
        familyRepository: FamilyRepository,
        statusRepository: StatusRepository,
        settingsStore: SettingsStore,
        calendarRepository: CalendarRepository,
        scheduleStarter: SyncScheduleStarter,
        diagnosticLog: DiagnosticLog,
    ): SettingsScreenController = SettingsScreenController(
        familyRepository = familyRepository,
        statusRepository = statusRepository,
        settingsStore = settingsStore,
        calendarRepository = calendarRepository,
        scheduleStarter = scheduleStarter,
        diagnosticLog = diagnosticLog,
    )

    @Provides
    @Singleton
    fun provideDiagnosticLogScreenController(
        diagnosticLog: DiagnosticLog,
    ): DiagnosticLogScreenController = DiagnosticLogScreenController(
        diagnosticLog = diagnosticLog,
    )
}

/** ActivityはこのApplication EntryPointから画面用Controllerだけを取得する。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface MainActivityEntryPoint {
    fun homeScreenController(): HomeScreenController

    fun syncUiController(): SyncUiController

    fun loansScreenController(): LoansScreenController

    fun reservationsScreenController(): ReservationsScreenController

    fun reservationCancelUiController(): ReservationCancelUiController

    fun readingRecordsScreenController(): ReadingRecordsScreenController

    fun bookshelfScreenController(): BookshelfScreenController

    fun searchScreenController(): SearchScreenController

    fun calendarScreenController(): CalendarScreenController

    fun newArrivalsScreenController(): NewArrivalsScreenController

    fun bookDetailController(): BookDetailController

    fun reservationUiController(): ReservationUiController

    fun settingsScreenController(): SettingsScreenController

    fun debugScreenController(): DebugScreenController

    fun diagnosticLogScreenController(): DiagnosticLogScreenController
}
