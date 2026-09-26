package com.fallgist.nishinomiyalibrary.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fallgist.nishinomiyalibrary.ui.shelf.BookshelfBulkAddProgress
import com.fallgist.nishinomiyalibrary.ui.loans.LoanExtensionBulkProgress
import com.fallgist.nishinomiyalibrary.ui.theme.LocalAppColors

/**
 * 進行中・完了を問わず、画面下の帯へ出す操作の種類(`docs/design/operation-progress-banner.md` §2.2)。
 * 完了の文言([OperationProgressContentBuilder.completionText])を、直前まで進行していた操作から
 * 引くために使う。手動同期は進行中だけ帯に出し、完了はSnackbarへ任せる(§2.8)ため、
 * [OperationProgressContentBuilder.completionText]は[SYNC]にnullを返す。
 */
enum class OperationKind {
    BOOKSHELF_BULK_ADD,
    LOAN_BULK_EXTEND,
    RESERVATION_CANCEL,
    CART_ADDITION,
    DIRECT_RESERVATION,
    CART_CONFIRM,
    BOOKSHELF_MUTATION,
    SYNC,
}

/** 画面下の帯に出す文言。[kind]は完了検出([LibraryApp]側のLaunchedEffect)に使う。 */
data class OperationProgressBanner(val text: String, val kind: OperationKind)

/**
 * 進行中の複数の操作から、画面下の帯に出す1本の文言を決める純関数(`docs/design/operation-progress-banner.md` §2.2)。
 * Android非依存にして、優先順位・件数の規則を単体テストで固定する。
 */
object OperationProgressContentBuilder {

    /**
     * 進行中の操作から帯の文言を1つ決める。複数が同時に真のときは、サイトに書き込む操作を
     * 手動同期より優先し、書き込みの中は引数の並び(§2.3の表)の順で優先する(§2.4)。
     * 何も進行していなければnull。
     */
    fun banner(
        bookshelfBulkAdd: BookshelfBulkAddProgress?,
        loanBulkExtend: LoanExtensionBulkProgress?,
        cancelProcessing: Boolean,
        cartAddProcessing: Boolean,
        directReservationProcessing: Boolean,
        cartConfirmProcessing: Boolean,
        bookshelfMutationProcessing: Boolean,
        syncing: Boolean,
    ): OperationProgressBanner? = when {
        bookshelfBulkAdd != null -> OperationProgressBanner(
            "本棚へ追加しています　${progressCount(bookshelfBulkAdd.completed, bookshelfBulkAdd.total)}件目/${bookshelfBulkAdd.total}件",
            OperationKind.BOOKSHELF_BULK_ADD,
        )
        loanBulkExtend != null -> OperationProgressBanner(
            "延長しています　${progressCount(loanBulkExtend.completed, loanBulkExtend.total)}件目/${loanBulkExtend.total}件",
            OperationKind.LOAN_BULK_EXTEND,
        )
        cancelProcessing -> OperationProgressBanner("予約を取り消しています", OperationKind.RESERVATION_CANCEL)
        cartAddProcessing -> OperationProgressBanner("カートへ追加しています", OperationKind.CART_ADDITION)
        directReservationProcessing -> OperationProgressBanner("予約しています", OperationKind.DIRECT_RESERVATION)
        cartConfirmProcessing -> OperationProgressBanner("予約しています", OperationKind.CART_CONFIRM)
        bookshelfMutationProcessing -> OperationProgressBanner("本棚を変更しています", OperationKind.BOOKSHELF_MUTATION)
        syncing -> OperationProgressBanner("同期しています", OperationKind.SYNC)
        else -> null
    }

    /** 完了時に何件目まで出すかの規則(`completed + 1`だが総数を超えない)。 */
    private fun progressCount(completed: Int, total: Int): Int = minOf(completed + 1, total)

    /**
     * 直前まで進行していた操作の種類から、完了の帯の文言を決める(§2.7)。手動同期は完了を帯に出さない
     * ため、結果は既存のSnackbarに任せてnullを返す(§2.8)。
     */
    fun completionText(finished: OperationKind): String? = when (finished) {
        OperationKind.BOOKSHELF_BULK_ADD -> "本棚への追加が終わりました"
        OperationKind.LOAN_BULK_EXTEND -> "延長が終わりました"
        OperationKind.RESERVATION_CANCEL -> "予約の取消が終わりました"
        OperationKind.CART_ADDITION -> "カートへの追加が終わりました"
        OperationKind.DIRECT_RESERVATION -> "予約が終わりました"
        OperationKind.CART_CONFIRM -> "予約が終わりました"
        OperationKind.BOOKSHELF_MUTATION -> "本棚の変更が終わりました"
        OperationKind.SYNC -> null
    }
}

/**
 * 画面下・下部ナビの直上に出す帯本体(`docs/design/operation-progress-banner.md` §2.1)。
 * 進行中は回転インジケータ付き、完了はタップで消せる文言だけを描く。[onDismiss]は完了の帯にだけ渡す
 * (進行中の帯はタップで消せない、§2「タップで消せるのは完了の帯だけ」)。
 */
@Composable
fun OperationProgressBannerBar(
    text: String,
    inProgress: Boolean,
    onDismiss: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.greenBg)
            .let { if (!inProgress && onDismiss != null) it.clickable(onClick = onDismiss) else it }
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (inProgress) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = colors.greenInk,
            )
            Spacer(Modifier.size(8.dp))
        }
        Text(text, color = colors.greenInk, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}
