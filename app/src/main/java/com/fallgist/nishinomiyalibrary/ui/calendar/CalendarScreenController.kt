package com.fallgist.nishinomiyalibrary.ui.calendar

import com.fallgist.nishinomiyalibrary.data.local.SettingsStore
import com.fallgist.nishinomiyalibrary.domain.model.Library
import com.fallgist.nishinomiyalibrary.domain.repository.CalendarRepository
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** カレンダーの1マス。dayOfMonth が null のマスは月内に日付が無い空セル。 */
data class CalendarDayCell(
    val dayOfMonth: Int?,
    val isClosed: Boolean = false,
    val isToday: Boolean = false,
    val isSunday: Boolean = false,
    val isSaturday: Boolean = false,
)

/** 1ヶ月分のカレンダー。weeks は日曜始まりの7マス×週リスト。 */
data class CalendarMonthUi(
    val label: String,
    val weeks: List<List<CalendarDayCell>>,
)

data class CalendarUiState(
    val initialized: Boolean = false,
    val libraries: List<Library> = emptyList(),
    val selectedLibraryCode: String = "",
    val selectedLibraryName: String = "",
    val months: List<CalendarMonthUi> = emptyList(),
    /** 選択館の休館日データが1件も無い(未取得の可能性)。 */
    val noClosedDayData: Boolean = false,
    /** 直近の休館日データ取得が失敗した。 */
    val refreshFailed: Boolean = false,
)

/** 月グリッドを組み立てる純関数。当月から[monthCount]ヶ月分を日曜始まりで並べる。 */
object CalendarContentBuilder {
    const val DEFAULT_MONTH_COUNT = 3

    fun months(
        today: LocalDate,
        closedDays: Set<LocalDate>,
        monthCount: Int = DEFAULT_MONTH_COUNT,
    ): List<CalendarMonthUi> = (0 until monthCount).map { offset ->
        month(YearMonth.from(today).plusMonths(offset.toLong()), today, closedDays)
    }

    private fun month(yearMonth: YearMonth, today: LocalDate, closedDays: Set<LocalDate>): CalendarMonthUi {
        val cells = mutableListOf<CalendarDayCell>()
        // 日曜始まり: 日曜=0 となる先頭の空セル数
        repeat(yearMonth.atDay(1).dayOfWeek.value % 7) { cells += CalendarDayCell(dayOfMonth = null) }
        for (day in 1..yearMonth.lengthOfMonth()) {
            val date = yearMonth.atDay(day)
            cells += CalendarDayCell(
                dayOfMonth = day,
                isClosed = date in closedDays,
                isToday = date == today,
                isSunday = date.dayOfWeek == DayOfWeek.SUNDAY,
                isSaturday = date.dayOfWeek == DayOfWeek.SATURDAY,
            )
        }
        while (cells.size % 7 != 0) cells += CalendarDayCell(dayOfMonth = null)
        return CalendarMonthUi(
            label = "${yearMonth.year}年${yearMonth.monthValue}月",
            weeks = cells.chunked(7),
        )
    }
}

/**
 * 開館カレンダー画面のController。初期選択は設定の既定館。
 * 表示館を選ぶたびに(セッション中1回だけ)休館日データを取得し直す。
 */
class CalendarScreenController(
    private val calendarRepository: CalendarRepository,
    settingsStore: SettingsStore,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val today: () -> LocalDate = { LocalDate.now(ZoneId.of("Asia/Tokyo")) },
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _state = MutableStateFlow(CalendarUiState(libraries = calendarRepository.libraries))
    val state: StateFlow<CalendarUiState> = _state

    /** null は「設定の既定館」を意味する。 */
    private val selectedOverride = MutableStateFlow<String?>(null)
    private val refreshedCodes = mutableSetOf<String>()

    init {
        @OptIn(ExperimentalCoroutinesApi::class)
        scope.launch {
            combine(settingsStore.settings, selectedOverride) { settings, override ->
                override ?: settings.defaultCalendarLibrary
            }
                .map { code -> validCodeOrFirst(code) }
                .flatMapLatest { code ->
                    refreshOnce(code)
                    calendarRepository.closedDays(code).map { closedDays -> code to closedDays }
                }
                .collect { (code, closedDays) ->
                    val closedSet = closedDays.map { it.date }.toSet()
                    _state.value = _state.value.copy(
                        initialized = true,
                        selectedLibraryCode = code,
                        selectedLibraryName = calendarRepository.libraries.find { it.code == code }?.name ?: code,
                        months = CalendarContentBuilder.months(today(), closedSet),
                        noClosedDayData = closedSet.isEmpty(),
                    )
                }
        }
    }

    fun selectLibrary(code: String) {
        selectedOverride.value = code
    }

    fun close() {
        scope.coroutineContext[Job]?.cancel()
    }

    private fun validCodeOrFirst(code: String): String =
        if (calendarRepository.libraries.any { it.code == code }) {
            code
        } else {
            calendarRepository.libraries.first().code
        }

    /** 選択された館の休館日を裏で取得する。失敗は表示フラグに残すだけで画面は既存データを出し続ける。 */
    private fun refreshOnce(code: String) {
        if (!refreshedCodes.add(code)) return
        scope.launch {
            try {
                calendarRepository.refreshClosedDays(code)
                _state.value = _state.value.copy(refreshFailed = false)
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                // 次回また試せるようにする
                refreshedCodes.remove(code)
                _state.value = _state.value.copy(refreshFailed = true)
            }
        }
    }
}
