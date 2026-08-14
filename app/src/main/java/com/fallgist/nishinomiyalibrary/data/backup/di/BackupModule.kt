package com.fallgist.nishinomiyalibrary.data.backup.di

import com.fallgist.nishinomiyalibrary.BuildConfig
import com.fallgist.nishinomiyalibrary.data.backup.BackupExportPort
import com.fallgist.nishinomiyalibrary.data.backup.BackupExporter
import com.fallgist.nishinomiyalibrary.data.backup.BackupImportPort
import com.fallgist.nishinomiyalibrary.data.backup.BackupImporter
import com.fallgist.nishinomiyalibrary.data.local.AppDatabase
import com.fallgist.nishinomiyalibrary.data.local.SettingsStore
import com.fallgist.nishinomiyalibrary.data.local.dao.AutoReservationDao
import com.fallgist.nishinomiyalibrary.data.local.dao.MemberDao
import com.fallgist.nishinomiyalibrary.data.local.dao.ReadingRecordDao
import com.fallgist.nishinomiyalibrary.data.local.dao.ReservationCartDao
import com.fallgist.nishinomiyalibrary.data.sync.SyncScheduleStarter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object BackupModule {
    @Provides
    @Singleton
    fun provideBackupExporter(
        memberDao: MemberDao,
        settingsStore: SettingsStore,
        autoReservationDao: AutoReservationDao,
        readingRecordDao: ReadingRecordDao,
        reservationCartDao: ReservationCartDao,
    ): BackupExportPort = BackupExporter(
        memberDao = memberDao,
        settingsStore = settingsStore,
        autoReservationDao = autoReservationDao,
        readingRecordDao = readingRecordDao,
        reservationCartDao = reservationCartDao,
        appVersion = BuildConfig.VERSION_NAME,
    )

    @Provides
    @Singleton
    fun provideBackupImporter(
        database: AppDatabase,
        settingsStore: SettingsStore,
        scheduleStarter: SyncScheduleStarter,
    ): BackupImportPort = BackupImporter(
        database = database,
        settingsStore = settingsStore,
        scheduleStarter = scheduleStarter,
    )
}
