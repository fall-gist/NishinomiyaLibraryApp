package com.fallgist.nishinomiyalibrary.ui.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.LicsXpSession
import com.fallgist.nishinomiyalibrary.domain.model.ReservationCancelTarget
import com.fallgist.nishinomiyalibrary.ui.calendar.CalendarScreen
import com.fallgist.nishinomiyalibrary.ui.calendar.CalendarScreenController
import com.fallgist.nishinomiyalibrary.ui.detail.BookDetailCancelTarget
import com.fallgist.nishinomiyalibrary.ui.detail.BookDetailContentBuilder
import com.fallgist.nishinomiyalibrary.ui.detail.BookDetailController
import com.fallgist.nishinomiyalibrary.ui.detail.BookDetailView
import com.fallgist.nishinomiyalibrary.ui.diagnostics.DiagnosticLogScreen
import com.fallgist.nishinomiyalibrary.ui.diagnostics.DiagnosticLogScreenController
import com.fallgist.nishinomiyalibrary.ui.home.HomeScreen
import com.fallgist.nishinomiyalibrary.ui.home.HomeUiState
import com.fallgist.nishinomiyalibrary.ui.loans.LoanExtensionUiController
import com.fallgist.nishinomiyalibrary.ui.loans.LoansScreen
import com.fallgist.nishinomiyalibrary.ui.loans.LoansScreenController
import com.fallgist.nishinomiyalibrary.ui.member.MemberRegistrationResult
import com.fallgist.nishinomiyalibrary.ui.member.RegistrationForm
import com.fallgist.nishinomiyalibrary.ui.newarrivals.NewArrivalsScreen
import com.fallgist.nishinomiyalibrary.ui.newarrivals.NewArrivalsScreenController
import com.fallgist.nishinomiyalibrary.ui.reading.ReadingRecordsScreen
import com.fallgist.nishinomiyalibrary.ui.reading.ReadingRecordsScreenController
import com.fallgist.nishinomiyalibrary.ui.reservations.ReservationCancelCandidate
import com.fallgist.nishinomiyalibrary.ui.reservations.ReservationCancelUiController
import com.fallgist.nishinomiyalibrary.ui.reservations.ReservationsScreen
import com.fallgist.nishinomiyalibrary.ui.reservations.ReservationsScreenController
import com.fallgist.nishinomiyalibrary.ui.reservationcart.ReservationCartScreen
import com.fallgist.nishinomiyalibrary.ui.reservationcart.ReservationConfirmDialog
import com.fallgist.nishinomiyalibrary.ui.reservationcart.ReservationUiController
import com.fallgist.nishinomiyalibrary.ui.search.SearchScreen
import com.fallgist.nishinomiyalibrary.ui.search.SearchScreenController
import com.fallgist.nishinomiyalibrary.ui.settings.SettingsScreen
import com.fallgist.nishinomiyalibrary.ui.settings.SettingsScreenController
import com.fallgist.nishinomiyalibrary.ui.shelf.BookshelfScreen
import com.fallgist.nishinomiyalibrary.ui.shelf.BookshelfEditingUiController
import com.fallgist.nishinomiyalibrary.ui.shelf.BookshelfEditingDialogs
import com.fallgist.nishinomiyalibrary.ui.shelf.BookshelfScreenController
import com.fallgist.nishinomiyalibrary.ui.sync.SyncUiState
import com.fallgist.nishinomiyalibrary.ui.theme.LocalAppColors
import kotlinx.coroutines.launch

/** ドロワー最下部の公式サイトリンク。アプリ内WebViewは使わず、端末の既定ブラウザで開く。 */
private const val OFFICIAL_SITE_URL = "https://tosho.nishi.or.jp/"

/**
 * アプリの遷移先。[primary] が true のものだけ下部ナビに出す。
 * それ以外(蔵書検索・カレンダー・設定)は右上☰のドロワーから開く。
 */
private enum class Destination(val label: String, val emoji: String, val primary: Boolean) {
    HOME("ホーム", "🏠", true),
    SHELF("本棚", "📚", true),
    LOANS("貸出中", "📖", true),
    RESERVATIONS("予約中", "🔖", true),
    READING("読書記録", "📗", true),
    // ドロワーの並びはこの宣言順に従う。予約カートは利用頻度が高いので先頭に置く。
    RESERVATION_CART("予約カート", "🛒", false),
    SEARCH("蔵書検索", "🔍", false),
    NEW_ARRIVALS("新着資料", "🆕", false),
    CALENDAR("カレンダー", "📅", false),
    SETTINGS("設定", "⚙️", false),
}

@Composable
fun LibraryApp(
    state: HomeUiState,
    syncState: SyncUiState,
    onSelectMember: (Long?) -> Unit,
    onManualSync: () -> Unit,
    onConsumeSyncMessage: (Long) -> Unit,
    onRegister: suspend (RegistrationForm) -> MemberRegistrationResult,
    onHomeVisible: () -> Unit,
    onHomeHidden: () -> Unit,
    onAcknowledgeAutoReservation: (Long) -> Unit,
    loansController: LoansScreenController,
    loanExtensionUiController: LoanExtensionUiController,
    reservationsController: ReservationsScreenController,
    reservationCancelUiController: ReservationCancelUiController,
    readingRecordsController: ReadingRecordsScreenController,
    bookshelfController: BookshelfScreenController,
    bookshelfEditingUiController: BookshelfEditingUiController,
    searchController: SearchScreenController,
    calendarController: CalendarScreenController,
    newArrivalsController: NewArrivalsScreenController,
    settingsController: SettingsScreenController,
    bookDetailController: BookDetailController,
    reservationUiController: ReservationUiController,
    diagnosticLogScreenController: DiagnosticLogScreenController,
    homeNavigationCommandId: Long? = null,
    onConsumeHomeNavigationCommand: (Long) -> Unit = {},
) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val reservationState by reservationUiController.state.collectAsState()
    val primaryTabs = Destination.entries.filter { it.primary }
    var currentName by rememberSaveable { mutableStateOf(Destination.HOME.name) }
    val current = Destination.valueOf(currentName)
    // 診断ログ閲覧は設定画面からだけ開ける、書誌詳細と同様の全画面オーバーレイとして扱う(ドロワー/下部ナビには出さない)。
    var diagnosticLogOpen by rememberSaveable { mutableStateOf(false) }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val openMenu: () -> Unit = { scope.launch { drawerState.open() } }
    LaunchedEffect(current) {
        if (current == Destination.HOME) onHomeVisible() else onHomeHidden()
    }
    LaunchedEffect(homeNavigationCommandId) {
        homeNavigationCommandId?.let { commandId ->
            currentName = Destination.HOME.name
            bookDetailController.close()
            diagnosticLogOpen = false
            drawerState.close()
            onConsumeHomeNavigationCommand(commandId)
        }
    }
    // 手動同期の結果(成功・一部失敗・失敗)をSnackbarへ通知する。開始時のメッセージは
    // SyncUiController側で流していないので、ここで表示するのは完了・失敗時のみ。
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(syncState.message) {
        syncState.message?.let { message ->
            snackbarHostState.showSnackbar(message.text)
            onConsumeSyncMessage(message.id)
        }
    }
    // tilcodを持つどの一覧からでも共通の書誌詳細を開く
    val openDetail: (String, String) -> Unit = bookDetailController::open
    // 公開書誌詳細は、固定HTTPS originから組み立てたURLだけを既定ブラウザで開く。
    val openOfficialBookDetail: (String) -> Unit = { tilcod ->
        LicsXpSession.officialBookDetailUrl(tilcod)?.let { url ->
            try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url.toString()))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (_: ActivityNotFoundException) {
                // 対応するブラウザが端末に無い場合は何もしない(クラッシュさせない)
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = colors.card) {
                Text(
                    text = "メニュー",
                    color = colors.ink2,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp),
                )
                Destination.entries.forEachIndexed { index, dest ->
                    if (index == primaryTabs.size) {
                        HorizontalDivider(
                            color = colors.line,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                    NavigationDrawerItem(
                        label = { Text("${dest.emoji}  ${dest.label}") },
                        selected = dest == current,
                        onClick = {
                            currentName = dest.name
                            bookDetailController.close()
                            diagnosticLogOpen = false
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
                HorizontalDivider(
                    color = colors.line,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
                NavigationDrawerItem(
                    label = { Text("🌐  公式サイト") },
                    selected = false,
                    onClick = {
                        try {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(OFFICIAL_SITE_URL))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        } catch (_: ActivityNotFoundException) {
                            // 対応するブラウザが端末に無い場合は何もしない(クラッシュさせない)
                        }
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        },
    ) {
        Scaffold(
            containerColor = colors.paper,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                NavigationBar(containerColor = colors.card) {
                    primaryTabs.forEach { dest ->
                        NavigationBarItem(
                            selected = current == dest,
                            onClick = {
                                currentName = dest.name
                                bookDetailController.close()
                                // 重ねて表示しているオーバーレイを閉じないと、下部ナビをタップしても画面が変わらない。
                                diagnosticLogOpen = false
                            },
                            icon = { Text(dest.emoji, fontSize = 16.sp) },
                            label = { Text(dest.label, fontSize = 10.sp) },
                        )
                    }
                }
            },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                // 画面本体をSelectionContainerで包み、長押しでのテキスト選択・コピーを可能にする。
                // 書誌詳細・診断ログのオーバーレイやダイアログもこのBoxの内側にあるため、まとめて対象になる。
                SelectionContainer {
                    // 書誌詳細オーバーレイの状態。RESERVATIONS分岐でのonOpenDetail組み立てと、
                    // オーバーレイ本体の描画、取消確定時の自動クローズ判定の3箇所で共有する。
                    val detailState by bookDetailController.state.collectAsState()
                    val editingState by bookshelfEditingUiController.state.collectAsState()
                    // 経路3: 書誌詳細の「この予約を取り消す」ボタン。第1段階のrequestSingleCancelConfirmation
                    // (経路1と同じ確認文言)へそのまま委譲する。新しい確認の仕組みは作らない。
                    val onRequestCancelFromDetail: (BookDetailCancelTarget) -> Unit = { target ->
                        reservationCancelUiController.requestSingleCancelConfirmation(
                            ReservationCancelCandidate(
                                target = ReservationCancelTarget(target.memberId, detailState.tilcod, target.cancelCode),
                                title = detailState.title,
                            ),
                        )
                    }
                    // 確認ダイアログの確定操作。ReservationsScreen(下記RESERVATIONS分岐)の
                    // ReservationCancelConfirmDialogはこの1つを経路1・2・3すべてで共有する。
                    // 経路3(書誌詳細にcancelTargetが設定されている状態で確定した場合)だけ、確定と
                    // 同時にポップアップを閉じる。設計判断の理由はBookDetailContentBuilder
                    // .shouldCloseDetailAfterCancelConfirmのKDoc参照。
                    val onConfirmCancelFromDetail: () -> Unit = {
                        reservationCancelUiController.confirmPending()
                        if (BookDetailContentBuilder.shouldCloseDetailAfterCancelConfirm(detailState.cancelTarget)) {
                            bookDetailController.close()
                        }
                    }
                    when (current) {
                        Destination.HOME -> HomeScreen(
                            state = state,
                            isRefreshing = syncState.isSyncing,
                            onSelectMember = onSelectMember,
                            onManualSync = onManualSync,
                            onRefresh = onManualSync,
                            onRegister = onRegister,
                            onOpenMenu = openMenu,
                            // ホーム画面から書誌詳細を開くのは「うけとれる予約」「返す本」の2箇所のみ
                            // (readyGroups・dueGroups)。どちらも既に予約済み・貸出中の資料を見ているため、
                            // 予約セクションは出さない。
                            onOpenDetail = { tilcod, title ->
                                bookDetailController.open(
                                    tilcod,
                                    title,
                                    cancelTarget = null,
                                    hideReservationSectionAsAlreadyReservedOrOnLoan = true,
                                )
                            },
                            onAcknowledgeAutoReservation = onAcknowledgeAutoReservation,
                            onOpenReservations = {
                                currentName = Destination.RESERVATIONS.name
                                bookDetailController.close()
                            },
                            modifier = Modifier.fillMaxSize(),
                        )

                        Destination.LOANS -> {
                            val loansState by loansController.state.collectAsState()
                            val extensionState by loanExtensionUiController.state.collectAsState()
                            LoansScreen(
                                state = loansState,
                                extensionState = extensionState,
                                isRefreshing = syncState.isSyncing,
                                onRefresh = onManualSync,
                                onSelectMember = loansController::selectMember,
                                onOpenMenu = openMenu,
                                // 貸出中一覧から開く書誌詳細は既に貸出中の資料なので、予約セクションは出さない。
                                onOpenDetail = { tilcod, title ->
                                    bookDetailController.open(
                                        tilcod,
                                        title,
                                        cancelTarget = null,
                                        hideReservationSectionAsAlreadyReservedOrOnLoan = true,
                                    )
                                },
                                onRequestExtend = loanExtensionUiController::requestConfirmation,
                                onConfirmExtend = loanExtensionUiController::confirmPending,
                                onDismissExtendConfirmation = loanExtensionUiController::dismissConfirmation,
                                onClearExtendResult = loanExtensionUiController::clearResult,
                                onClearExtendError = loanExtensionUiController::clearError,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }

                        Destination.RESERVATIONS -> {
                            val reservationsState by reservationsController.state.collectAsState()
                            val cancelState by reservationCancelUiController.state.collectAsState()
                            ReservationsScreen(
                                state = reservationsState,
                                cancelState = cancelState,
                                isRefreshing = syncState.isSyncing,
                                onRefresh = onManualSync,
                                onSelectMember = reservationsController::selectMember,
                                onOpenMenu = openMenu,
                                // 予約中一覧から開く書誌詳細は既に予約中の資料なので、予約セクションは出さない。
                                // 取消ボタンの表示可否(cancelTarget)とは独立に指定する。
                                onOpenDetail = { tilcod, title, cancelTarget ->
                                    bookDetailController.open(
                                        tilcod,
                                        title,
                                        cancelTarget,
                                        hideReservationSectionAsAlreadyReservedOrOnLoan = true,
                                    )
                                },
                                onToggleSelection = reservationCancelUiController::toggleSelection,
                                onRequestSingleCancel = reservationCancelUiController::requestSingleCancelConfirmation,
                                onRequestBulkCancel = reservationCancelUiController::requestBulkCancelConfirmation,
                                // 経路3から開始した確認の確定操作も同じダイアログ・同じ関数を通る(第1段階の使い回し)。
                                onConfirmCancel = onConfirmCancelFromDetail,
                                onDismissCancelConfirmation = reservationCancelUiController::dismissConfirmation,
                                onClearCancelResults = reservationCancelUiController::clearResults,
                                onClearCancelError = reservationCancelUiController::clearError,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }

                        Destination.READING -> {
                            val readingState by readingRecordsController.state.collectAsState()
                            ReadingRecordsScreen(
                                state = readingState,
                                isRefreshing = syncState.isSyncing,
                                onRefresh = onManualSync,
                                onSelectMember = readingRecordsController::selectMember,
                                onQueryChange = readingRecordsController::updateQuery,
                                onOpenMenu = openMenu,
                                onOpenDetail = openDetail,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }

                        Destination.SHELF -> {
                            val shelfState by bookshelfController.state.collectAsState()
                            BookshelfScreen(
                                state = shelfState,
                                editingState = editingState,
                                isRefreshing = syncState.isSyncing,
                                onRefresh = onManualSync,
                                onSelectMember = bookshelfController::selectMember,
                                onOpenMenu = openMenu,
                                onOpenDetail = openDetail,
                                onRequestCreateShelf = bookshelfEditingUiController::requestCreateShelf,
                                onRequestEditShelf = bookshelfEditingUiController::requestEditShelf,
                                onRequestDeleteShelf = bookshelfEditingUiController::requestDeleteShelf,
                                onRequestDeleteItem = bookshelfEditingUiController::requestDeleteItem,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }

                        Destination.SEARCH -> {
                            val searchState by searchController.state.collectAsState()
                            SearchScreen(
                                state = searchState,
                                onQueryChange = searchController::updateQuery,
                                onSearch = searchController::search,
                                onLoadMore = searchController::loadMore,
                                onOpenDetail = openDetail,
                                onOpenMenu = openMenu,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }

                        Destination.NEW_ARRIVALS -> {
                            val newArrivalsState by newArrivalsController.state.collectAsState()
                            LaunchedEffect(Unit) { newArrivalsController.onScreenLaunched() }
                            NewArrivalsScreen(
                                state = newArrivalsState,
                                onQueryChange = newArrivalsController::updateQuery,
                                onRefresh = newArrivalsController::refresh,
                                onOpenMenu = openMenu,
                                onOpenDetail = openDetail,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }

                        Destination.CALENDAR -> {
                            val calendarState by calendarController.state.collectAsState()
                            CalendarScreen(
                                state = calendarState,
                                onSelectLibrary = calendarController::selectLibrary,
                                onOpenMenu = openMenu,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }

                        Destination.SETTINGS -> {
                            val settingsState by settingsController.state.collectAsState()
                            SettingsScreen(
                                state = settingsState,
                                onSaveMember = settingsController::saveMember,
                                onMoveMemberUp = settingsController::moveMemberUp,
                                onMoveMemberDown = settingsController::moveMemberDown,
                                onRemoveMember = settingsController::removeMember,
                                onUpdateSyncTime = settingsController::updateSyncTime,
                                onSetNotifyReturnReminder = settingsController::setNotifyReturnReminder,
                                onSetNotifyPickupReady = settingsController::setNotifyPickupReady,
                                onSetReturnReminderDaysBefore = settingsController::setReturnReminderDaysBefore,
                                onSetDefaultCalendarLibrary = settingsController::setDefaultCalendarLibrary,
                                onSetDiagnosticLogEnabled = settingsController::setDiagnosticLogEnabled,
                                onOpenDiagnosticLog = { diagnosticLogOpen = true },
                                onSetAutoReservationEnabled = settingsController::setAutoReservationEnabled,
                                onSaveAutoReservationRule = settingsController::saveAutoReservationRule,
                                onRemoveAutoReservationRule = settingsController::removeAutoReservationRule,
                                onSetAutoReservationRuleEnabled = settingsController::setAutoReservationRuleEnabled,
                                onMoveAutoReservationRule = settingsController::moveAutoReservationRule,
                                onOpenMenu = openMenu,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }

                        Destination.RESERVATION_CART -> {
                            ReservationCartScreen(
                                state = reservationState,
                                onSelectPickupLibrary = reservationUiController::selectPickupLibrary,
                                onRemoveFromCart = reservationUiController::removeFromCart,
                                onRequestConfirmation = reservationUiController::requestCartConfirmation,
                                onOpenSearch = {
                                    currentName = Destination.SEARCH.name
                                    bookDetailController.close()
                                },
                                onClearResults = reservationUiController::clearCartFeedback,
                                onOpenMenu = openMenu,
                                onOpenDetail = openDetail,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }

                    // どの画面の上にも重ねられる共通の書誌詳細オーバーレイ
                    if (detailState.open) {
                        if (current == Destination.RESERVATION_CART || current == Destination.RESERVATIONS) {
                            // 予約カート・予約中一覧からの起動は全画面オーバーレイではなくダイアログで重ねる。
                            // 下の画面自体の状態(受取館選択・結果表示、選択チェック・スクロール位置等)は
                            // 隠れたまま保たれる。
                            // 経路3(RESERVATIONS)の確認・結果ダイアログはここでは重ねて描画しない。
                            // ReservationsScreen(上のRESERVATIONS分岐)が同じcancelStateを購読して
                            // 既にAlertDialogを出しており(第1段階のものをそのまま使う)、ここでも描画すると
                            // 二重表示になる。書誌詳細のDialogは別のAndroid Windowなので、その上に
                            // ReservationsScreen側のAlertDialogが問題なく重なって見える。
                            Dialog(
                                onDismissRequest = bookDetailController::close,
                                properties = DialogProperties(usePlatformDefaultWidth = false),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(0.94f)
                                        .fillMaxHeight(0.86f)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(colors.paper),
                                ) {
                                    BookDetailView(
                                        detail = detailState,
                                        onBack = bookDetailController::close,
                                        reservation = reservationState,
                                        onSelectReservationMember = reservationUiController::selectMember,
                                        onSelectPickupLibrary = reservationUiController::selectPickupLibrary,
                                        onAddToCart = reservationUiController::addToCart,
                                        onRequestReserveNow = reservationUiController::requestImmediateConfirmation,
                                        onOpenOfficialBookDetail = openOfficialBookDetail,
                                        bookshelfEditing = editingState,
                                        onRequestAddToBookshelf = bookshelfEditingUiController::requestAddItem,
                                        onRequestCancel = onRequestCancelFromDetail,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }
                        } else {
                            BookDetailView(
                                detail = detailState,
                                onBack = bookDetailController::close,
                                reservation = reservationState,
                                onSelectReservationMember = reservationUiController::selectMember,
                                onSelectPickupLibrary = reservationUiController::selectPickupLibrary,
                                onAddToCart = reservationUiController::addToCart,
                                onRequestReserveNow = reservationUiController::requestImmediateConfirmation,
                                onOpenOfficialBookDetail = openOfficialBookDetail,
                                bookshelfEditing = editingState,
                                onRequestAddToBookshelf = bookshelfEditingUiController::requestAddItem,
                                onRequestCancel = onRequestCancelFromDetail,
                                modifier = Modifier.fillMaxSize().background(colors.paper),
                            )
                        }
                    }
                    if (diagnosticLogOpen) {
                        val diagnosticLogState by diagnosticLogScreenController.state.collectAsState()
                        DiagnosticLogScreen(
                            state = diagnosticLogState,
                            onQueryChange = diagnosticLogScreenController::updateQuery,
                            onToggleCategory = diagnosticLogScreenController::toggleCategory,
                            onCopyVisible = diagnosticLogScreenController::formattedVisibleLog,
                            onClear = diagnosticLogScreenController::clear,
                            onExportFile = diagnosticLogScreenController::exportFile,
                            onBack = { diagnosticLogOpen = false },
                            modifier = Modifier.fillMaxSize().background(colors.paper),
                        )
                    }
                    BookshelfEditingDialogs(
                        editingState = editingState,
                        onSelectCreateMember = bookshelfEditingUiController::selectCreateMember,
                        onSelectAddItemMember = bookshelfEditingUiController::selectAddItemMember,
                        onSelectAddItemShelf = bookshelfEditingUiController::selectAddItemShelf,
                                    onUpdateInput = bookshelfEditingUiController::updateInput,
                                    onUpdateEditShelfMemo = bookshelfEditingUiController::updateEditShelfMemo,
                        onRequestInputConfirmation = bookshelfEditingUiController::requestInputConfirmation,
                        onDismissDialog = bookshelfEditingUiController::dismissDialog,
                        onConfirm = bookshelfEditingUiController::confirmPending,
                        onDismissConfirmation = bookshelfEditingUiController::dismissConfirmation,
                        onClearResult = bookshelfEditingUiController::clearResult,
                        onClearError = bookshelfEditingUiController::clearError,
                    )
                    reservationState.pendingConfirmation?.let { request ->
                        ReservationConfirmDialog(
                            request = request,
                            libraryName = reservationState.libraries.find { it.code == reservationState.pickupLibraryCode }?.name
                                ?: reservationState.pickupLibraryCode,
                            members = reservationState.members,
                            onConfirm = reservationUiController::confirmPending,
                            onDismiss = reservationUiController::dismissConfirmation,
                        )
                    }
                }
            }
        }
    }
}
