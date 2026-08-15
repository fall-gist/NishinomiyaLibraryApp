package com.fallgist.nishinomiyalibrary.data.backup.di

import com.fallgist.nishinomiyalibrary.BuildConfig
import com.fallgist.nishinomiyalibrary.data.backup.BackupExportPort
import com.fallgist.nishinomiyalibrary.data.backup.BackupExporter
import com.fallgist.nishinomiyalibrary.data.backup.BackupImportPort
import com.fallgist.nishinomiyalibrary.data.backup.BackupImporter
import com.fallgist.nishinomiyalibrary.data.local.AppDatabase
import com.fallgist.nishinomiyalibrary.data.local.CredentialStore
import com.fallgist.nishinomiyalibrary.data.local.SettingsStore
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
        database: AppDatabase,
        settingsStore: SettingsStore,
    ): BackupExportPort = BackupExporter(
        database = database,
        settingsStore = settingsStore,
        appVersion = BuildConfig.VERSION_NAME,
    )

    @Provides
    @Singleton
    fun provideBackupImporter(
        database: AppDatabase,
        settingsStore: SettingsStore,
        scheduleStarter: SyncScheduleStarter,
        credentialStore: CredentialStore,
    ): BackupImportPort = BackupImporter(
        database = database,
        settingsStore = settingsStore,
        scheduleStarter = scheduleStarter,
        credentialStore = credentialStore,
    )
}
