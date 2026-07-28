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
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import com.fallgist.nishinomiyalibrary.ui.calendar.CalendarScreen
import com.fallgist.nishinomiyalibrary.ui.calendar.CalendarScreenController
import com.fallgist.nishinomiyalibrary.ui.detail.BookDetailController
import com.fallgist.nishinomiyalibrary.ui.detail.BookDetailView
import com.fallgist.nishinomiyalibrary.ui.diagnostics.DiagnosticLogScreen
import com.fallgist.nishinomiyalibrary.ui.diagnostics.DiagnosticLogScreenController
import com.fallgist.nishinomiyalibrary.ui.home.HomeScreen
import com.fallgist.nishinomiyalibrary.ui.home.HomeUiState
import com.fallgist.nishinomiyalibrary.ui.loans.LoansScreen
import com.fallgist.nishinomiyalibrary.ui.loans.LoansScreenController
import com.fallgist.nishinomiyalibrary.ui.member.MemberRegistrationResult
import com.fallgist.nishinomiyalibrary.ui.member.RegistrationForm
import com.fallgist.nishinomiyalibrary.ui.newarrivals.NewArrivalsScreen
import com.fallgist.nishinomiyalibrary.ui.newarrivals.NewArrivalsScreenController
import com.fallgist.nishinomiyalibrary.ui.reading.ReadingRecordsScreen
import com.fallgist.nishinomiyalibrary.ui.reading.ReadingRecordsScreenController
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
import com.fallgist.nishinomiyalibrary.ui.shelf.BookshelfScreenController
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
    onSelectMember: (Long?) -> Unit,
    onManualSync: () -> Unit,
    onRegister: suspend (RegistrationForm) -> MemberRegistrationResult,
    loansController: LoansScreenController,
    reservationsController: ReservationsScreenController,
    reservationCancelUiController: ReservationCancelUiController,
    readingRecordsController: ReadingRecordsScreenController,
    bookshelfController: BookshelfScreenController,
    searchController: SearchScreenController,
    calendarController: CalendarScreenController,
    newArrivalsController: NewArrivalsScreenController,
    settingsController: SettingsScreenController,
    bookDetailController: BookDetailController,
    reservationUiController: ReservationUiController,
    diagnosticLogScreenController: DiagnosticLogScreenController,
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
    // tilcodを持つどの一覧からでも共通の書誌詳細を開く
    val openDetail: (String, String) -> Unit = bookDetailController::open

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
                    when (current) {
                        Destination.HOME -> HomeScreen(
                            state = state,
                            onSelectMember = onSelectMember,
                            onManualSync = onManualSync,
                            onRegister = onRegister,
                            onOpenMenu = openMenu,
                            onOpenDetail = openDetail,
                            modifier = Modifier.fillMaxSize(),
                        )

                        Destination.LOANS -> {
                            val loansState by loansController.state.collectAsState()
                            LoansScreen(
                                state = loansState,
                                onSelectMember = loansController::selectMember,
                                onOpenMenu = openMenu,
                                onOpenDetail = openDetail,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }

                        Destination.RESERVATIONS -> {
                            val reservationsState by reservationsController.state.collectAsState()
                            val cancelState by reservationCancelUiController.state.collectAsState()
                            ReservationsScreen(
                                state = reservationsState,
                                cancelState = cancelState,
                                onSelectMember = reservationsController::selectMember,
                                onOpenMenu = openMenu,
                                onOpenDetail = openDetail,
                                onToggleSelection = reservationCancelUiController::toggleSelection,
                                onRequestSingleCancel = reservationCancelUiController::requestSingleCancelConfirmation,
                                onRequestBulkCancel = reservationCancelUiController::requestBulkCancelConfirmation,
                                onConfirmCancel = reservationCancelUiController::confirmPending,
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
                                onSelectMember = bookshelfController::selectMember,
                                onOpenMenu = openMenu,
                                onOpenDetail = openDetail,
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
                    val detailState by bookDetailController.state.collectAsState()
                    if (detailState.open) {
                        if (current == Destination.RESERVATION_CART) {
                            // 予約カートからの起動だけは全画面オーバーレイではなくダイアログで重ねる。
                            // カート画面自体の状態(受取館選択・結果表示等)は下に隠れたまま保たれる。
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
