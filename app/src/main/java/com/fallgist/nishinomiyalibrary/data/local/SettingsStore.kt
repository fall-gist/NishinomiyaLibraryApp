package com.fallgist.nishinomiyalibrary.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class AppSettings(
    val syncHour: Int = DEFAULT_SYNC_HOUR,
    val syncMinute: Int = DEFAULT_SYNC_MINUTE,
    val notifyReturnReminder: Boolean = DEFAULT_NOTIFY_RETURN_REMINDER,
    val notifyPickupReady: Boolean = DEFAULT_NOTIFY_PICKUP_READY,
    val defaultCalendarLibrary: String = DEFAULT_CALENDAR_LIBRARY,
    /** 返却期限リマインダーを期限の何日前から通知するか(1=前日)。 */
    val returnReminderDaysBefore: Int = DEFAULT_RETURN_REMINDER_DAYS_BEFORE,
    /** 不具合調査用の通信診断ログを記録するかどうか。既定はオフ。 */
    val diagnosticLogEnabled: Boolean = DEFAULT_DIAGNOSTIC_LOG_ENABLED,
    /** 新着キーワード自動予約のマスタースイッチ。初期値は必ずOFF。 */
    val autoReservationEnabled: Boolean = DEFAULT_AUTO_RESERVATION_ENABLED,
    /**
     * 一斉操作の選択が解除される前に確認ダイアログを出すか(`docs/design/bulk-selection-followup.md` §6.3)。
     * 端末ごとのUIの好みであり、バックアップの写像には加えない(BackupPayload/BackupExporter/BackupImporterを変更しない)。
     */
    val warnBeforeClearingSelection: Boolean = DEFAULT_WARN_BEFORE_CLEARING_SELECTION,
)

class SettingsStore(private val dataStore: DataStore<Preferences>) {
    val settings: Flow<AppSettings> = dataStore.data.map { preferences ->
        AppSettings(
            syncHour = preferences[SYNC_HOUR] ?: DEFAULT_SYNC_HOUR,
            syncMinute = preferences[SYNC_MINUTE] ?: DEFAULT_SYNC_MINUTE,
            notifyReturnReminder = preferences[NOTIFY_RETURN_REMINDER] ?: DEFAULT_NOTIFY_RETURN_REMINDER,
            notifyPickupReady = preferences[NOTIFY_PICKUP_READY] ?: DEFAULT_NOTIFY_PICKUP_READY,
            defaultCalendarLibrary = preferences[DEFAULT_CALENDAR_LIBRARY_KEY] ?: DEFAULT_CALENDAR_LIBRARY,
            returnReminderDaysBefore = preferences[RETURN_REMINDER_DAYS_BEFORE]
                ?: DEFAULT_RETURN_REMINDER_DAYS_BEFORE,
            diagnosticLogEnabled = preferences[DIAGNOSTIC_LOG_ENABLED] ?: DEFAULT_DIAGNOSTIC_LOG_ENABLED,
            autoReservationEnabled = preferences[AUTO_RESERVATION_ENABLED] ?: DEFAULT_AUTO_RESERVATION_ENABLED,
            warnBeforeClearingSelection = preferences[WARN_BEFORE_CLEARING_SELECTION]
                ?: DEFAULT_WARN_BEFORE_CLEARING_SELECTION,
        )
    }

    suspend fun update(value: AppSettings) {
        requireValidSyncTime(value.syncHour, value.syncMinute)
        requireValidReminderDays(value.returnReminderDaysBefore)
        dataStore.edit { preferences ->
            preferences[SYNC_HOUR] = value.syncHour
            preferences[SYNC_MINUTE] = value.syncMinute
            preferences[NOTIFY_RETURN_REMINDER] = value.notifyReturnReminder
            preferences[NOTIFY_PICKUP_READY] = value.notifyPickupReady
            preferences[DEFAULT_CALENDAR_LIBRARY_KEY] = value.defaultCalendarLibrary
            preferences[RETURN_REMINDER_DAYS_BEFORE] = value.returnReminderDaysBefore
            preferences[DIAGNOSTIC_LOG_ENABLED] = value.diagnosticLogEnabled
            preferences[AUTO_RESERVATION_ENABLED] = value.autoReservationEnabled
            preferences[WARN_BEFORE_CLEARING_SELECTION] = value.warnBeforeClearingSelection
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

    suspend fun updateReturnReminderDaysBefore(days: Int) {
        requireValidReminderDays(days)
        dataStore.edit { it[RETURN_REMINDER_DAYS_BEFORE] = days }
    }

    suspend fun updateDiagnosticLogEnabled(enabled: Boolean) {
        dataStore.edit { it[DIAGNOSTIC_LOG_ENABLED] = enabled }
    }

    suspend fun updateAutoReservationEnabled(enabled: Boolean) {
        dataStore.edit { it[AUTO_RESERVATION_ENABLED] = enabled }
    }

    suspend fun updateWarnBeforeClearingSelection(enabled: Boolean) {
        dataStore.edit { it[WARN_BEFORE_CLEARING_SELECTION] = enabled }
    }

    /**
     * 新着資料の最終全置換取得時刻(epoch millis)。利用者設定ではなく内部状態のため、
     * [AppSettings]には含めず専用の読み書き関数として公開する。未取得ならnull。
     */
    suspend fun getLastNewArrivalFetchedAt(): Long? =
        dataStore.data.map { it[LAST_NEW_ARRIVAL_FETCHED_AT] }.first()

    suspend fun setLastNewArrivalFetchedAt(millis: Long) {
        dataStore.edit { it[LAST_NEW_ARRIVAL_FETCHED_AT] = millis }
    }

    private fun requireValidSyncTime(hour: Int, minute: Int) {
        require(hour in 0..23) { "同期時刻の時は0から23で指定してください" }
        require(minute in 0..59) { "同期時刻の分は0から59で指定してください" }
    }

    private fun requireValidReminderDays(days: Int) {
        require(days in RETURN_REMINDER_DAYS_RANGE) { "通知日数は1から7日前で指定してください" }
    }

    private companion object {
        val SYNC_HOUR = intPreferencesKey("sync_hour")
        val SYNC_MINUTE = intPreferencesKey("sync_minute")
        val NOTIFY_RETURN_REMINDER = booleanPreferencesKey("notify_return_reminder")
        val NOTIFY_PICKUP_READY = booleanPreferencesKey("notify_pickup_ready")
        val DEFAULT_CALENDAR_LIBRARY_KEY = stringPreferencesKey("default_calendar_library")
        val RETURN_REMINDER_DAYS_BEFORE = intPreferencesKey("return_reminder_days_before")
        val DIAGNOSTIC_LOG_ENABLED = booleanPreferencesKey("diagnostic_log_enabled")
        val LAST_NEW_ARRIVAL_FETCHED_AT = longPreferencesKey("last_new_arrival_fetched_at")
        val AUTO_RESERVATION_ENABLED = booleanPreferencesKey("auto_reservation_enabled")
        val WARN_BEFORE_CLEARING_SELECTION = booleanPreferencesKey("warn_before_clearing_selection")
    }
}

const val DEFAULT_SYNC_HOUR = 18
const val DEFAULT_SYNC_MINUTE = 0
const val DEFAULT_NOTIFY_RETURN_REMINDER = true
const val DEFAULT_NOTIFY_PICKUP_READY = true
const val DEFAULT_CALENDAR_LIBRARY = "106"
const val DEFAULT_RETURN_REMINDER_DAYS_BEFORE = 1
val RETURN_REMINDER_DAYS_RANGE = 1..7
const val DEFAULT_DIAGNOSTIC_LOG_ENABLED = false
const val DEFAULT_AUTO_RESERVATION_ENABLED = false
const val DEFAULT_WARN_BEFORE_CLEARING_SELECTION = true
