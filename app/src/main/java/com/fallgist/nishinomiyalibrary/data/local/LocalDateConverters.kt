package com.fallgist.nishinomiyalibrary.data.local

import androidx.room.TypeConverter
import com.fallgist.nishinomiyalibrary.domain.model.ReservationState
import java.time.LocalDate

class LocalDateConverters {
    @TypeConverter
    fun localDateToString(value: LocalDate?): String? = value?.toString()

    @TypeConverter
    fun stringToLocalDate(value: String?): LocalDate? = value?.let(LocalDate::parse)

    @TypeConverter
    fun reservationStateToString(value: ReservationState?): String? = value?.name

    @TypeConverter
    fun stringToReservationState(value: String?): ReservationState? = value?.let(ReservationState::valueOf)
}
