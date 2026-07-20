package com.fallgist.nishinomiyalibrary.data.repository.di

import android.content.Context
import androidx.work.WorkManager
import com.fallgist.nishinomiyalibrary.data.remote.openbd.BookMetadataGateway
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.LicsXpClient
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.LicsXpSession
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.LibraryGateway
import com.fallgist.nishinomiyalibrary.data.remote.openbd.OpenBdClient
import com.fallgist.nishinomiyalibrary.data.repository.CalendarRepositoryImpl
import com.fallgist.nishinomiyalibrary.data.repository.FamilyRepositoryImpl
import com.fallgist.nishinomiyalibrary.data.repository.NewArrivalRepositoryImpl
import com.fallgist.nishinomiyalibrary.data.repository.SearchRepositoryImpl
import com.fallgist.nishinomiyalibrary.data.repository.ReadingRecordRepositoryImpl
import com.fallgist.nishinomiyalibrary.data.repository.StatusRepositoryImpl
import com.fallgist.nishinomiyalibrary.data.sync.AndroidNotificationSink
import com.fallgist.nishinomiyalibrary.data.sync.NotificationService
import com.fallgist.nishinomiyalibrary.data.sync.NotificationSink
import com.fallgist.nishinomiyalibrary.data.sync.PostSyncNotifier
import com.fallgist.nishinomiyalibrary.domain.repository.CalendarRepository
import com.fallgist.nishinomiyalibrary.domain.repository.FamilyRepository
import com.fallgist.nishinomiyalibrary.domain.repository.NewArrivalRepository
import com.fallgist.nishinomiyalibrary.domain.repository.SearchRepository
import com.fallgist.nishinomiyalibrary.domain.repository.ReadingRecordRepository
import com.fallgist.nishinomiyalibrary.domain.repository.StatusRepository
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
    abstract fun bindNotificationSink(implementation: AndroidNotificationSink): NotificationSink

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
    fun provideLibraryGateway(): LibraryGateway = LicsXpClient(
        LicsXpSession(LicsXpSession.DEFAULT_BASE_URL.toHttpUrl()),
    )

    @Provides
    @Singleton
    fun provideBookMetadataGateway(): BookMetadataGateway = OpenBdClient(OpenBdClient.DEFAULT_BASE_URL.toHttpUrl())

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager = WorkManager.getInstance(context)
}
