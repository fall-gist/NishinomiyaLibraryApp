package com.fallgist.nishinomiyalibrary.data.repository.di

import android.content.Context
import androidx.work.WorkManager
import com.fallgist.nishinomiyalibrary.data.diagnostics.DiagnosticLog
import com.fallgist.nishinomiyalibrary.data.diagnostics.DiagnosticLogObserver
import com.fallgist.nishinomiyalibrary.data.remote.openbd.BookMetadataGateway
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.LicsXpClient
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.LicsXpSession
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.LibraryGateway
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.CurrentCirculationGateway
import com.fallgist.nishinomiyalibrary.data.remote.openbd.OpenBdClient
import com.fallgist.nishinomiyalibrary.data.repository.CalendarRepositoryImpl
import com.fallgist.nishinomiyalibrary.data.repository.BookshelfRepositoryImpl
import com.fallgist.nishinomiyalibrary.data.repository.FamilyRepositoryImpl
import com.fallgist.nishinomiyalibrary.data.repository.NewArrivalRepositoryImpl
import com.fallgist.nishinomiyalibrary.data.repository.SearchRepositoryImpl
import com.fallgist.nishinomiyalibrary.data.repository.ReadingRecordRepositoryImpl
import com.fallgist.nishinomiyalibrary.data.repository.StatusRepositoryImpl
import com.fallgist.nishinomiyalibrary.data.repository.ReservationCartRepositoryImpl
import com.fallgist.nishinomiyalibrary.data.repository.ReservationCancelRepositoryImpl
import com.fallgist.nishinomiyalibrary.data.repository.LoanExtensionRepositoryImpl
import com.fallgist.nishinomiyalibrary.data.repository.AutoReservationRepositoryImpl
import com.fallgist.nishinomiyalibrary.data.repository.CurrentCirculationSnapshotStore
import com.fallgist.nishinomiyalibrary.data.repository.RoomCurrentCirculationSnapshotStore
import com.fallgist.nishinomiyalibrary.data.repository.AutomaticReservationCoordinator
import com.fallgist.nishinomiyalibrary.data.repository.AutomaticReservationRunner
import com.fallgist.nishinomiyalibrary.data.repository.NewArrivalUpdateCoordinator
import com.fallgist.nishinomiyalibrary.data.repository.NewArrivalUpdateRunner
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.LicsXpReservationGateway
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.ReservationGateway
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.LicsXpLoanExtensionGateway
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.LoanExtensionGateway
import com.fallgist.nishinomiyalibrary.data.sync.AndroidNotificationSink
import com.fallgist.nishinomiyalibrary.data.sync.AutoReservationCompletionNotifier
import com.fallgist.nishinomiyalibrary.data.sync.AutoReservationNotificationService
import com.fallgist.nishinomiyalibrary.data.sync.AutoReservationNotificationSink
import com.fallgist.nishinomiyalibrary.data.sync.NotificationService
import com.fallgist.nishinomiyalibrary.data.sync.NotificationSink
import com.fallgist.nishinomiyalibrary.data.sync.PostSyncNotifier
import com.fallgist.nishinomiyalibrary.domain.repository.CalendarRepository
import com.fallgist.nishinomiyalibrary.domain.repository.BookshelfRepository
import com.fallgist.nishinomiyalibrary.domain.repository.FamilyRepository
import com.fallgist.nishinomiyalibrary.domain.repository.NewArrivalRepository
import com.fallgist.nishinomiyalibrary.domain.repository.SearchRepository
import com.fallgist.nishinomiyalibrary.domain.repository.ReadingRecordRepository
import com.fallgist.nishinomiyalibrary.domain.repository.StatusRepository
import com.fallgist.nishinomiyalibrary.domain.repository.ReservationCartRepository
import com.fallgist.nishinomiyalibrary.domain.repository.ReservationCancelRepository
import com.fallgist.nishinomiyalibrary.domain.repository.LoanExtensionRepository
import com.fallgist.nishinomiyalibrary.domain.repository.AutoReservationRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import java.time.ZoneId
import javax.inject.Singleton
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryBindingModule {
    @Binds
    @Singleton
    abstract fun bindFamilyRepository(implementation: FamilyRepositoryImpl): FamilyRepository

    @Binds
    @Singleton
    abstract fun bindStatusRepository(implementation: StatusRepositoryImpl): StatusRepository

    @Binds
    @Singleton
    abstract fun bindBookshelfRepository(implementation: BookshelfRepositoryImpl): BookshelfRepository

    @Binds
    @Singleton
    abstract fun bindSearchRepository(implementation: SearchRepositoryImpl): SearchRepository

    @Binds
    @Singleton
    abstract fun bindCalendarRepository(implementation: CalendarRepositoryImpl): CalendarRepository

    @Binds
    @Singleton
    abstract fun bindReadingRecordRepository(implementation: ReadingRecordRepositoryImpl): ReadingRecordRepository

    @Binds
    @Singleton
    abstract fun bindNewArrivalRepository(implementation: NewArrivalRepositoryImpl): NewArrivalRepository

    @Binds
    @Singleton
    abstract fun bindReservationCartRepository(implementation: ReservationCartRepositoryImpl): ReservationCartRepository

    @Binds
    @Singleton
    abstract fun bindReservationCancelRepository(implementation: ReservationCancelRepositoryImpl): ReservationCancelRepository

    @Binds
    @Singleton
    abstract fun bindLoanExtensionRepository(implementation: LoanExtensionRepositoryImpl): LoanExtensionRepository

    @Binds
    @Singleton
    abstract fun bindAutoReservationRepository(implementation: AutoReservationRepositoryImpl): AutoReservationRepository

    @Binds
    @Singleton
    abstract fun bindCurrentCirculationSnapshotStore(implementation: RoomCurrentCirculationSnapshotStore): CurrentCirculationSnapshotStore

    @Binds
    @Singleton
    abstract fun bindAutomaticReservationRunner(implementation: AutomaticReservationCoordinator): AutomaticReservationRunner

    @Binds
    @Singleton
    abstract fun bindNewArrivalUpdateRunner(implementation: NewArrivalUpdateCoordinator): NewArrivalUpdateRunner

    @Binds
    @Singleton
    abstract fun bindNotificationSink(implementation: AndroidNotificationSink): NotificationSink

    @Binds
    @Singleton
    abstract fun bindAutoReservationNotificationSink(
        implementation: AndroidNotificationSink,
    ): AutoReservationNotificationSink

    @Binds
    @Singleton
    abstract fun bindAutoReservationCompletionNotifier(
        implementation: AutoReservationNotificationService,
    ): AutoReservationCompletionNotifier

    @Binds
    @Singleton
    abstract fun bindPostSyncNotifier(implementation: NotificationService): PostSyncNotifier
}

@Module
@InstallIn(SingletonComponent::class)
object RepositoryProvisionModule {
    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.system(ZoneId.of("Asia/Tokyo"))

    @Provides
    @Singleton
    fun provideRootLicsXpSession(diagnosticLog: DiagnosticLog): LicsXpSession = LicsXpSession(
        baseUrl = LicsXpSession.DEFAULT_BASE_URL.toHttpUrl(),
        client = OkHttpClient(),
        diagnosticObserver = DiagnosticLogObserver(diagnosticLog),
    )

    @Provides
    @Singleton
    fun provideLibraryGateway(session: LicsXpSession): LibraryGateway = LicsXpClient(session)

    @Provides
    @Singleton
    fun provideCurrentCirculationGateway(session: LicsXpSession): CurrentCirculationGateway = LicsXpClient(session)

    @Provides
    @Singleton
    fun provideReservationGateway(session: LicsXpSession): ReservationGateway = LicsXpReservationGateway(session)

    @Provides
    @Singleton
    fun provideLoanExtensionGateway(session: LicsXpSession): LoanExtensionGateway = LicsXpLoanExtensionGateway(session)

    @Provides
    @Singleton
    fun provideBookMetadataGateway(): BookMetadataGateway = OpenBdClient(OpenBdClient.DEFAULT_BASE_URL.toHttpUrl())

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager = WorkManager.getInstance(context)
}
