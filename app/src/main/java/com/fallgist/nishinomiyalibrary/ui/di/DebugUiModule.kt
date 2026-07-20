package com.fallgist.nishinomiyalibrary.ui.di

import com.fallgist.nishinomiyalibrary.data.sync.SyncScheduleStarter
import com.fallgist.nishinomiyalibrary.data.sync.WorkManagerSyncScheduleStarter
import com.fallgist.nishinomiyalibrary.domain.repository.FamilyRepository
import com.fallgist.nishinomiyalibrary.domain.repository.ReadingRecordRepository
import com.fallgist.nishinomiyalibrary.domain.repository.StatusRepository
import com.fallgist.nishinomiyalibrary.ui.debug.DebugScreenController
import com.fallgist.nishinomiyalibrary.ui.home.HomeScreenController
import com.fallgist.nishinomiyalibrary.ui.loans.LoansScreenController
import com.fallgist.nishinomiyalibrary.ui.reservations.ReservationsScreenController
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
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
}

/** ActivityはこのApplication EntryPointから画面用Controllerだけを取得する。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface MainActivityEntryPoint {
    fun homeScreenController(): HomeScreenController

    fun loansScreenController(): LoansScreenController

    fun reservationsScreenController(): ReservationsScreenController

    fun debugScreenController(): DebugScreenController
}
