package com.fallgist.nishinomiyalibrary.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class AppSettings(
    val syncHour: Int = DEFAULT_SYNC_HOUR,
    val syncMinute: Int = DEFAULT_SYNC_MINUTE,
    val notifyReturnReminder: Boolean = DEFAULT_NOTIFY_RETURN_REMINDER,
    val notifyPickupReady: Boolean = DEFAULT_NOTIFY_PICKUP_READY,
    val defaultCalendarLibrary: String = DEFAULT_CALENDAR_LIBRARY,
)

class SettingsStore(private val dataStore: DataStore<Preferences>) {
    val settings: Flow<AppSettings> = dataStore.data.map { preferences ->
        AppSettings(
            syncHour = preferences[SYNC_HOUR] ?: DEFAULT_SYNC_HOUR,
            syncMinute = preferences[SYNC_MINUTE] ?: DEFAULT_SYNC_MINUTE,
            notifyReturnReminder = preferences[NOTIFY_RETURN_REMINDER] ?: DEFAULT_NOTIFY_RETURN_REMINDER,
            notifyPickupReady = preferences[NOTIFY_PICKUP_READY] ?: DEFAULT_NOTIFY_PICKUP_READY,
            defaultCalendarLibrary = preferences[DEFAULT_CALENDAR_LIBRARY_KEY] ?: DEFAULT_CALENDAR_LIBRARY,
        )
    }

    suspend fun update(value: AppSettings) {
        requireValidSyncTime(value.syncHour, value.syncMinute)
        dataStore.edit { preferences ->
            preferences[SYNC_HOUR] = value.syncHour
            preferences[SYNC_MINUTE] = value.syncMinute
            preferences[NOTIFY_RETURN_REMINDER] = value.notifyReturnReminder
            preferences[NOTIFY_PICKUP_READY] = value.notifyPickupReady
            preferences[DEFAULT_CALENDAR_LIBRARY_KEY] = value.defaultCalendarLibrary
        }
    }

    suspend fun updateSyncTime(hour: Int, minute: Int) {
        requireValidSyncTime(hour, minute)
        dataStore.edit { preferences ->
            preferences[SYNC_HOUR] = hour
            preferences[SYNC_MINUTE] = minute
        }
    }

    suspend fun updateNotifyReturnReminder(enabled: Boolean) {
        dataStore.edit { it[NOTIFY_RETURN_REMINDER] = enabled }
    }

    suspend fun updateNotifyPickupReady(enabled: Boolean) {
        dataStore.edit { it[NOTIFY_PICKUP_READY] = enabled }
    }

    suspend fun updateDefaultCalendarLibrary(libraryCode: String) {
        dataStore.edit { it[DEFAULT_CALENDAR_LIBRARY_KEY] = libraryCode }
    }

    private fun requireValidSyncTime(hour: Int, minute: Int) {
        require(hour in 0..23) { "同期時刻の時は0から23で指定してください" }
        require(minute in 0..59) { "同期時刻の分は0から59で指定してください" }
    }

    private companion object {
        val SYNC_HOUR = intPreferencesKey("sync_hour")
        val SYNC_MINUTE = intPreferencesKey("sync_minute")
        val NOTIFY_RETURN_REMINDER = booleanPreferencesKey("notify_return_reminder")
        val NOTIFY_PICKUP_READY = booleanPreferencesKey("notify_pickup_ready")
        val DEFAULT_CALENDAR_LIBRARY_KEY = stringPreferencesKey("default_calendar_library")
    }
}

const val DEFAULT_SYNC_HOUR = 18
const val DEFAULT_SYNC_MINUTE = 0
const val DEFAULT_NOTIFY_RETURN_REMINDER = true
const val DEFAULT_NOTIFY_PICKUP_READY = true
const val DEFAULT_CALENDAR_LIBRARY = "106"
