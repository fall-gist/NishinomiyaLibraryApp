package com.fallgist.nishinomiyalibrary.data.repository

import com.fallgist.nishinomiyalibrary.data.local.entity.ClosedDayEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.LoanEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.MemberEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.NewArrivalEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.ReservationEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.ReadingInfoProjection
import com.fallgist.nishinomiyalibrary.data.local.entity.ReadingRecordEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.ShelfItemEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.ShelfItemWithShelfName
import com.fallgist.nishinomiyalibrary.data.local.entity.ShelfEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.SyncLogEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.UserSummaryEntity
import com.fallgist.nishinomiyalibrary.domain.model.ClosedDay
import com.fallgist.nishinomiyalibrary.domain.model.Loan
import com.fallgist.nishinomiyalibrary.domain.model.Member
import com.fallgist.nishinomiyalibrary.domain.model.NewArrival
import com.fallgist.nishinomiyalibrary.domain.model.Reservation
import com.fallgist.nishinomiyalibrary.domain.model.ReadingInfo
import com.fallgist.nishinomiyalibrary.domain.model.ReadingRecord
import com.fallgist.nishinomiyalibrary.domain.model.ReadingRecordTitleNormalizer
import com.fallgist.nishinomiyalibrary.domain.model.ShelfItem
import com.fallgist.nishinomiyalibrary.domain.model.Shelf
import com.fallgist.nishinomiyalibrary.domain.model.UserSummary
import com.fallgist.nishinomiyalibrary.domain.repository.SyncLog
import com.fallgist.nishinomiyalibrary.domain.repository.SyncTrigger

internal fun NewArrivalEntity.toDomain(): NewArrival = NewArrival(
    tilcod = tilcod,
    title = title,
    volume = volume,
    author = author,
    publisher = publisher,
    publishedYearMonth = publishedYearMonth,
    classification = classification,
    lendable = lendable,
)

internal fun NewArrival.toEntity(): NewArrivalEntity = NewArrivalEntity(
    tilcod = tilcod,
    title = title,
    volume = volume,
    author = author,
    publisher = publisher,
    publishedYearMonth = publishedYearMonth,
    classification = classification,
    lendable = lendable,
)

internal fun MemberEntity.toDomain(): Member = Member(id, name, colorHex, cardNumber, sortOrder)

internal fun Member.toEntity(): MemberEntity = MemberEntity(id, name, colorHex, cardNumber, sortOrder)

internal fun LoanEntity.toDomain(): Loan = Loan(
    memberId,
    title,
    materialType,
    lendingLibrary,
    loanDate,
    dueDate,
    status,
    tilcod,
)

internal fun Loan.toEntity(memberId: Long): LoanEntity = LoanEntity(
    memberId = memberId,
    title = title,
    materialType = materialType,
    lendingLibrary = lendingLibrary,
    loanDate = loanDate,
    dueDate = dueDate,
    status = status,
    tilcod = tilcod,
)

internal fun ReadingRecordEntity.toDomain(): ReadingRecord = ReadingRecord(
    memberId = memberId,
    tilcod = tilcod,
    title = title,
    loanDate = loanDate,
    library = library,
)

internal fun ReadingRecord.toEntity(): ReadingRecordEntity = ReadingRecordEntity(
    memberId = memberId,
    tilcod = tilcod,
    title = title,
    loanDate = loanDate,
    library = library,
    titleNormalized = ReadingRecordTitleNormalizer.normalize(title),
)

internal fun ReadingInfoProjection.toDomain(): ReadingInfo = ReadingInfo(memberId, loanDate, library)

internal fun ReservationEntity.toDomain(): Reservation = Reservation(
    memberId = memberId,
    title = title,
    materialType = materialType,
    pickupLibrary = pickupLibrary,
    reservedDate = reservedDate,
    queuePosition = queuePosition,
    state = state,
    holdExpiryDate = holdExpiryDate,
    tilcod = tilcod,
)

internal fun Reservation.toEntity(memberId: Long): ReservationEntity = ReservationEntity(
    memberId = memberId,
    title = title,
    materialType = materialType,
    pickupLibrary = pickupLibrary,
    reservedDate = reservedDate,
    queuePosition = queuePosition,
    state = state,
    holdExpiryDate = holdExpiryDate,
    firstReadyNotifiedAt = null,
    tilcod = tilcod,
)

internal fun ShelfItemWithShelfName.toDomain(): ShelfItem = ShelfItem(
    memberId = memberId,
    tilcod = tilcod,
    title = title,
    memo = memo,
    registeredDate = registeredDate,
    shelfNo = shelfNo,
    shelfName = shelfName,
)

internal fun ShelfItem.toEntity(memberId: Long): ShelfItemEntity = ShelfItemEntity(
    memberId,
    shelfNo,
    tilcod,
    title,
    memo,
    registeredDate,
)

internal fun Shelf.toEntity(memberId: Long): ShelfEntity = ShelfEntity(memberId, no, name)

internal fun UserSummaryEntity.toDomain(): UserSummary = UserSummary(
    memberId,
    shelfCount,
    loanCount,
    reservationCount,
    cartCount,
)

internal fun UserSummary.toEntity(memberId: Long): UserSummaryEntity = UserSummaryEntity(
    memberId,
    shelfCount,
    loanCount,
    reservationCount,
    cartCount,
)

internal fun ClosedDayEntity.toDomain(): ClosedDay = ClosedDay(libraryCode, date)

internal fun SyncLogEntity.toDomain(): SyncLog = SyncLog(
    id = id,
    startedAtEpochMillis = startedAtEpochMillis,
    finishedAtEpochMillis = finishedAtEpochMillis,
    trigger = SyncTrigger.valueOf(trigger),
    succeeded = succeeded,
    details = details,
)
