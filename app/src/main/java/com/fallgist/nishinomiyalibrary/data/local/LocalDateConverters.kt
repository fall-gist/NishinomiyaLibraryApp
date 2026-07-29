package com.fallgist.nishinomiyalibrary.data.local

import androidx.room.TypeConverter
import com.fallgist.nishinomiyalibrary.domain.model.ReservationState
import com.fallgist.nishinomiyalibrary.domain.model.AutoReservationControlStatus
import com.fallgist.nishinomiyalibrary.domain.model.AutoReservationTermKind
import com.fallgist.nishinomiyalibrary.domain.model.ReservationPickupSubmissionOrigin
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

    @TypeConverter
    fun autoReservationTermKindToString(value: AutoReservationTermKind?): String? = value?.name

    @TypeConverter
    fun stringToAutoReservationTermKind(value: String?): AutoReservationTermKind? = value?.let(AutoReservationTermKind::valueOf)

    @TypeConverter
    fun autoReservationControlStatusToString(value: AutoReservationControlStatus?): String? = value?.name

    @TypeConverter
    fun stringToAutoReservationControlStatus(value: String?): AutoReservationControlStatus? = value?.let(AutoReservationControlStatus::valueOf)

    @TypeConverter
    fun reservationPickupSubmissionOriginToString(value: ReservationPickupSubmissionOrigin?): String? = value?.name

    @TypeConverter
    fun stringToReservationPickupSubmissionOrigin(value: String?): ReservationPickupSubmissionOrigin? = value?.let(ReservationPickupSubmissionOrigin::valueOf)
}
