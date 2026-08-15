package com.fallgist.nishinomiyalibrary.data.remote.licsxp

import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.BookshelfConfirmationFormParser
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.BookshelfConfirmationKind
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.BookshelfCompletionFormParser
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.BookshelfCreateFormParser
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.BookshelfDeleteFormParser
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.BookshelfDetailAddFormParser
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.BookshelfEditFormParser
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.BookshelfFormField
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.LoginFormParser
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.ParseException
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.ParserSupport
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.ShelfListParser
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.ShelfParseResult
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.ShelfParser
import com.fallgist.nishinomiyalibrary.domain.model.FailureReason
import com.fallgist.nishinomiyalibrary.domain.model.BookshelfMutationExpectation
import com.fallgist.nishinomiyalibrary.domain.model.BookshelfEditItem
import com.fallgist.nishinomiyalibrary.domain.model.Shelf
import com.fallgist.nishinomiyalibrary.domain.model.ShelfItem
import kotlinx.coroutines.CancellationException
import okhttp3.FormBody
import org.jsoup.Jsoup

/** Repository層へ漏らさない、サイトで対象を固定するための操作要求。 */
sealed interface RemoteBookshelfMutation {
    val expected: BookshelfMutationExpectation

    data class AddItem(val shelfNo: Int, val tilcod: String, val memo: String, override val expected: BookshelfMutationExpectation) : RemoteBookshelfMutation
    data class DeleteItem(val shelfNo: Int, val tilcod: String, override val expected: BookshelfMutationExpectation) : RemoteBookshelfMutation
    data class CreateShelf(val name: String, override val expected: BookshelfMutationExpectation) : RemoteBookshelfMutation
    data class EditShelf(
        val shelfNo: Int,
        val newName: String,
        val items: List<BookshelfEditItem>,
        override val expected: BookshelfMutationExpectation,
    ) : RemoteBookshelfMutation
    data class DeleteShelf(val shelfNo: Int, override val expected: BookshelfMutationExpectation) : RemoteBookshelfMutation
}

/** Gateway内で照合済みの完全スナップショット。Room反映は次段階のRepositoryだけが行う。 */
sealed interface RemoteBookshelfOutcome {
    data class Applied(val shelves: List<Shelf>, val items: List<ShelfItem>) : RemoteBookshelfOutcome
    data class AlreadyRegistered(val shelves: List<Shelf>, val items: List<ShelfItem>) : RemoteBookshelfOutcome
    data object Unknown : RemoteBookshelfOutcome
    data class Failure(val reason: FailureReason, val diagnosticCode: String? = null) : RemoteBookshelfOutcome
}

/** 本棚操作を安全停止した理由。値はサポート向けの固定・非機密な識別子に限る。 */
internal enum class BookshelfStopDiagnosticCode(val value: String, val reason: String) {
    STATE_CHANGED("BS_STATE_CHANGED", "state-changed"),
    PRECONDITION_MISMATCH("BS_PRECONDITION_MISMATCH", "precondition-mismatch"),
    EDIT_ITEM_COUNT("BS_EDIT_ITEM_COUNT", "edit-item-count"),
    EDIT_ITEM_ID("BS_EDIT_ITEM_ID", "edit-item-id"),
    EDIT_ITEM_CONTENT("BS_EDIT_ITEM_CONTENT", "edit-item-content"),
    EDIT_PAGE("BS_EDIT_PAGE", "edit-page"),
    EDIT_FORM("BS_EDIT_FORM", "edit-form"),
    PARSE_EXCEPTION("BS_PARSE_EXCEPTION", "parse-exception"),
}

interface BookshelfGateway {
    suspend fun openAuthenticatedSession(cardNumber: String, password: String): BookshelfSession
}

interface BookshelfSession {
    suspend fun mutate(mutation: RemoteBookshelfMutation): RemoteBookshelfOutcome
    fun close()
}

/** Cookieは利用者ごとに隔離し、root sessionとはリクエスト間隔だけを共有する。 */
class LicsXpBookshelfGateway(private val rootSession: LicsXpSession) : BookshelfGateway {
    override suspend fun openAuthenticatedSession(cardNumber: String, password: String): BookshelfSession {
        if (cardNumber.isBlank() || password.isBlank()) throw LibraryError.Auth(memberName = null)
        val session = rootSession.newIsolatedSession()
        try {
            session.get("WOpacEsSchCmpdDispAction.do")
            val login = session.get("OpacInitLoginAction.do", mapOf("subSystemFlag" to "0"))
            requireBookshelfNotMaintenance(login)
            session.post("j_security_check", mapOf("subSystemFlag" to "0"), LoginFormParser.parse(login).buildForm(cardNumber, password))
            val menu = session.get("WOpacMnuTopInitAction.do", mapOf("WebLinkFlag" to "1"))
            classifyBookshelfLogin(menu)
            session.updateTokens(menu)
            return LicsXpBookshelfSession(session)
        } catch (exception: ParseException) {
            throw LibraryError.Parse(exception.screen, exception.reason)
        }
    }
}

internal class LicsXpBookshelfSession(private val session: LicsXpSession) : BookshelfSession {
    override suspend fun mutate(mutation: RemoteBookshelfMutation): RemoteBookshelfOutcome {
        val stateChangePost = StateChangePostTracker()
        return try {
            session.withExclusiveRequestSequence { mutateExclusively(mutation, stateChangePost) }
        } catch (exception: CancellationException) {
            if (stateChangePost.started) RemoteBookshelfOutcome.Unknown else throw exception
        } catch (exception: LibraryError.Auth) {
            RemoteBookshelfOutcome.Failure(FailureReason.AUTH)
        } catch (exception: LibraryError.Maintenance) {
            RemoteBookshelfOutcome.Failure(FailureReason.SITE_MAINTENANCE)
        } catch (exception: LibraryError.Network) {
            RemoteBookshelfOutcome.Failure(FailureReason.NETWORK)
        } catch (_: ParseException) {
            changedFailure(BookshelfStopDiagnosticCode.PARSE_EXCEPTION)
        } catch (_: LibraryError.Parse) {
            changedFailure(BookshelfStopDiagnosticCode.PARSE_EXCEPTION)
        }
    }

    override fun close() = Unit

    private suspend fun LicsXpSession.ExclusiveRequestSequence.mutateExclusively(mutation: RemoteBookshelfMutation, stateChangePost: StateChangePostTracker): RemoteBookshelfOutcome {
        val before = fetchAllShelves()
        if (!mutation.expected.matches(before)) return changedFailure(BookshelfStopDiagnosticCode.PRECONDITION_MISMATCH)
        return when (mutation) {
            is RemoteBookshelfMutation.AddItem -> add(before, mutation, stateChangePost)
            is RemoteBookshelfMutation.CreateShelf -> create(before, mutation, stateChangePost)
            is RemoteBookshelfMutation.EditShelf -> editShelf(before, mutation, stateChangePost)
            is RemoteBookshelfMutation.DeleteItem -> deleteItem(before, mutation, stateChangePost)
            is RemoteBookshelfMutation.DeleteShelf -> deleteShelf(before, mutation, stateChangePost)
        }
    }

    private suspend fun LicsXpSession.ExclusiveRequestSequence.add(before: BookshelfSnapshot, mutation: RemoteBookshelfMutation.AddItem, stateChangePost: StateChangePostTracker): RemoteBookshelfOutcome {
        if (!before.hasShelf(mutation.shelfNo)) return changedFailure()
        if (before.itemsFor(mutation.shelfNo).count { it.tilcod == mutation.tilcod } > 0) return before.alreadyRegistered()
        if (mutation.shelfNo <= 0 || mutation.shelfNo == 998 || mutation.shelfNo.toString() == "999") return changedFailure()
        val detail = get("WOpacMsgNewListToTifTilDetailAction.do", mapOf("urlNotFlag" to "1", "tilcod" to mutation.tilcod))
        requireBookshelfNotMaintenance(detail)
        if (isBookshelfLoginForm(detail)) return RemoteBookshelfOutcome.Failure(FailureReason.SESSION_EXPIRED_BEFORE_SUBMIT)
        val form = BookshelfDetailAddFormParser.parse(detail)
        if (form.tilcod != mutation.tilcod) return changedFailure()
        // 追加は唯一の状態変更POST。以降の失敗では再送せず、全棚再取得でだけ照合する。
        try {
            val response = postExactlyOnce(
                "WOpacTifDetailAddBookListAction.do",
                form = form.buildForm(mutation.shelfNo, mutation.memo),
                onRequestStarted = stateChangePost::markStarted,
            )
            session.updateTokensIfPresent(response)
        } catch (_: LibraryError.Network) { }
        val after = refetchOrNull() ?: return RemoteBookshelfOutcome.Unknown
        val matched = after.itemsFor(mutation.shelfNo).filter { it.tilcod == mutation.tilcod }
        return when {
            matched.size == 1 && normalized(matched.single().memo) == normalized(mutation.memo) &&
                after.shelves == before.shelves &&
                sameItems(
                    before.itemsFor(mutation.shelfNo),
                    after.itemsFor(mutation.shelfNo).filter { it.tilcod != mutation.tilcod },
                ) &&
                after.itemsFor(mutation.shelfNo).size == before.itemsFor(mutation.shelfNo).size + 1 &&
                unchangedOutsideShelf(before, after, mutation.shelfNo) -> after.applied()
            else -> RemoteBookshelfOutcome.Unknown
        }
    }

    private suspend fun LicsXpSession.ExclusiveRequestSequence.create(before: BookshelfSnapshot, mutation: RemoteBookshelfMutation.CreateShelf, stateChangePost: StateChangePostTracker): RemoteBookshelfOutcome {
        val page = before.currentPage ?: return changedFailure()
        val nav = BookshelfDeleteFormParser.parse(page).buildForm()
        val input = post("WOpacSdiBookListToInputAction.do", form = nav)
        requireBookshelfNotMaintenance(input)
        val stage1 = BookshelfCreateFormParser.parse(input).buildForm(mutation.name)
        return twoStage(before, "WOpacSdiBookListExecAction.do", emptyMap(), stage1, BookshelfConfirmationKind.CREATE, stateChangePost) { after ->
            val added = after.shelves.filter { shelf -> before.shelves.none { it.no == shelf.no } }
            added.size == 1 && added.single().name == mutation.name && unchangedExistingShelves(before, after)
        }
    }

    private suspend fun LicsXpSession.ExclusiveRequestSequence.edit(
        before: BookshelfSnapshot,
        shelfNo: Int,
        kind: BookshelfConfirmationKind,
        transform: (com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.BookshelfEditForm) -> FormBody,
        expectedItemCount: Int? = null,
        success: ((BookshelfSnapshot) -> Boolean)? = null,
        stateChangePost: StateChangePostTracker,
    ): RemoteBookshelfOutcome {
        val edit = try {
            openEditPage(shelfNo)
        } catch (_: ParseException) {
            return changedFailure(BookshelfStopDiagnosticCode.EDIT_PAGE)
        } ?: return changedFailure(BookshelfStopDiagnosticCode.EDIT_PAGE)
        val form = try {
            BookshelfEditFormParser.parse(edit)
        } catch (_: ParseException) {
            return changedFailure(BookshelfStopDiagnosticCode.EDIT_FORM)
        }
        if (form.shelfNo != shelfNo) return changedFailure(BookshelfStopDiagnosticCode.EDIT_FORM)
        if (expectedItemCount != null && form.itemCount != expectedItemCount) {
            return changedFailure(BookshelfStopDiagnosticCode.EDIT_ITEM_COUNT)
        }
        val shelf = before.shelves.singleOrNull { it.no == shelfNo } ?: return changedFailure(BookshelfStopDiagnosticCode.EDIT_PAGE)
        try {
            form.requireMatches(shelf, before.itemsFor(shelfNo))
        } catch (_: ParseException) {
            return changedFailure(BookshelfStopDiagnosticCode.EDIT_FORM)
        }
        val stage1 = try {
            transform(form)
        } catch (_: ParseException) {
            return changedFailure(BookshelfStopDiagnosticCode.EDIT_FORM)
        }
        return twoStage(before, "WOpacSdiBookListUpdateAction.do", emptyMap(), stage1, kind, stateChangePost) { after -> success?.invoke(after) ?: false }
    }

    private suspend fun LicsXpSession.ExclusiveRequestSequence.editShelf(before: BookshelfSnapshot, mutation: RemoteBookshelfMutation.EditShelf, stateChangePost: StateChangePostTracker): RemoteBookshelfOutcome {
        val beforeItems = before.itemsFor(mutation.shelfNo)
        if (mutation.items.size != beforeItems.size) return changedFailure(BookshelfStopDiagnosticCode.EDIT_ITEM_COUNT)
        if (mutation.items.any { it.tilcod.isBlank() } || beforeItems.any { it.tilcod.isBlank() }) {
            return changedFailure(BookshelfStopDiagnosticCode.EDIT_ITEM_ID)
        }
        val mutationByTilcod = mutation.items.associateBy { it.tilcod }
        val beforeByTilcod = beforeItems.associateBy { it.tilcod }
        if (mutationByTilcod.size != mutation.items.size || beforeByTilcod.size != beforeItems.size ||
            mutationByTilcod.keys != beforeByTilcod.keys
        ) return changedFailure(BookshelfStopDiagnosticCode.EDIT_ITEM_ID)
        if (!beforeByTilcod.all { (tilcod, actual) ->
                val expected = mutationByTilcod.getValue(tilcod)
                actual.title == expected.title && normalized(actual.memo) == normalized(expected.originalMemo)
            }) return changedFailure(BookshelfStopDiagnosticCode.EDIT_ITEM_CONTENT)
        return edit(
            before = before,
            shelfNo = mutation.shelfNo,
            kind = BookshelfConfirmationKind.UPDATE,
            expectedItemCount = beforeItems.size,
            transform = { form ->
                form.edit(mutation.newName, beforeItems.map { mutationByTilcod.getValue(it.tilcod).newMemo })
            },
            success = { after ->
                val afterItems = after.itemsFor(mutation.shelfNo)
                val afterByTilcod = afterItems.associateBy { it.tilcod }
                after.shelves == before.shelves.map { shelf -> if (shelf.no == mutation.shelfNo) shelf.copy(name = mutation.newName) else shelf } &&
                    afterByTilcod.size == afterItems.size && afterByTilcod.keys == beforeByTilcod.keys &&
                    beforeByTilcod.all { (tilcod, old) ->
                        val new = afterByTilcod.getValue(tilcod)
                        old.title == new.title && old.registeredDate == new.registeredDate &&
                            normalized(new.memo) == normalized(mutationByTilcod.getValue(tilcod).newMemo)
                    } && unchangedOutsideShelf(before, after, mutation.shelfNo)
            },
            stateChangePost = stateChangePost,
        )
    }


    private suspend fun LicsXpSession.ExclusiveRequestSequence.deleteItem(before: BookshelfSnapshot, mutation: RemoteBookshelfMutation.DeleteItem, stateChangePost: StateChangePostTracker): RemoteBookshelfOutcome {
        val beforeItems = before.itemsFor(mutation.shelfNo)
        val target = beforeItems.withIndex().filter { it.value.tilcod == mutation.tilcod }
        if (target.size != 1) return changedFailure()
        val expectedItems = beforeItems.filterIndexed { index, _ -> index != target.single().index }
        val edit = openEditPage(mutation.shelfNo) ?: return changedFailure()
        val form = BookshelfEditFormParser.parse(edit)
        if (form.shelfNo != mutation.shelfNo || form.itemCount != beforeItems.size) return changedFailure()
        form.requireMatches(before.shelves.singleOrNull { it.no == mutation.shelfNo } ?: return changedFailure(), beforeItems)
        return twoStage(before, "WOpacSdiBookDelAction.do", mapOf("flg" to "1"), form.delete(mutation.tilcod), BookshelfConfirmationKind.DELETE_ITEM, stateChangePost) { after ->
            after.shelves == before.shelves &&
                sameItems(after.itemsFor(mutation.shelfNo), expectedItems) &&
                unchangedOutsideShelf(before, after, mutation.shelfNo)
        }
    }

    private suspend fun LicsXpSession.ExclusiveRequestSequence.deleteShelf(before: BookshelfSnapshot, mutation: RemoteBookshelfMutation.DeleteShelf, stateChangePost: StateChangePostTracker): RemoteBookshelfOutcome {
        if (!before.hasShelf(mutation.shelfNo)) return changedFailure()
        val page = switchToShelf(mutation.shelfNo) ?: return changedFailure()
        val form = BookshelfDeleteFormParser.parse(page)
        if (form.shelfNo != mutation.shelfNo) return changedFailure()
        // 本棚1件だけを削除した場合に限り、削除後の「otherbookのselectが無い」応答を0件成立と解釈する
        // (docs/design/account-and-bookshelf-fixes.md §2.3.B)。2件以上からの解析不能はUnknownのまま。
        val zeroShelfFallback: (() -> RemoteBookshelfOutcome)? =
            if (before.shelves.size == 1) ({ RemoteBookshelfOutcome.Applied(emptyList(), emptyList()) }) else null
        return twoStage(
            before, "WOpacSdiBookListDelAction.do", mapOf("delflg" to "1"), form.buildForm(),
            BookshelfConfirmationKind.DELETE_SHELF, stateChangePost, zeroShelfFallback,
        ) { after ->
            val expectedShelves = before.shelves.filter { it.no != mutation.shelfNo }
            after.shelves == expectedShelves &&
                expectedShelves.all { shelf -> sameItems(before.itemsFor(shelf.no), after.itemsFor(shelf.no)) }
        }
    }

    /**
     * 対象本棚をカレントへ切り替え、切り替え応答のHTMLを返す。キャッシュ済みスナップショットのHTMLは
     * 使わない(サイトはotherbookではなくセッション上のカレント本棚を変更対象にするため、末尾巡回後の
     * キャッシュのまま送ると対象と異なる本棚を操作してしまう)。応答の本棚番号が対象と一致しなければ
     * nullを返し、呼び出し側は状態変更POSTを送らずに停止する(フェイルクローズ)。
     */
    private suspend fun LicsXpSession.ExclusiveRequestSequence.switchToShelf(shelfNo: Int): String? {
        val page = post("WOpacSdiBookListToOtherBookDispAction.do", mapOf("flg" to "1"), switchForm(shelfNo))
        requireBookshelfNotMaintenance(page)
        if (isBookshelfLoginForm(page)) throw LibraryError.Auth(memberName = null)
        session.updateTokens(page)
        val result = try { ShelfParser.parse(page) } catch (_: ParseException) { return null }
        return page.takeIf { result.shelf.no == shelfNo }
    }

    private suspend fun LicsXpSession.ExclusiveRequestSequence.openEditPage(shelfNo: Int): String? {
        val page = switchToShelf(shelfNo) ?: return null
        val form = BookshelfDeleteFormParser.parse(page)
        if (form.shelfNo != shelfNo) return null
        return post("WOpacSdiBookListToSdiMainteAction.do", form = form.buildForm()).also(::requireBookshelfNotMaintenance)
    }

    private suspend fun LicsXpSession.ExclusiveRequestSequence.twoStage(
        before: BookshelfSnapshot,
        path: String,
        query: Map<String, String>,
        stage1: FormBody,
        kind: BookshelfConfirmationKind,
        stateChangePost: StateChangePostTracker,
        zeroShelfFallback: (() -> RemoteBookshelfOutcome)? = null,
        success: (BookshelfSnapshot) -> Boolean,
    ): RemoteBookshelfOutcome {
        val response = try { postExactlyOnce(path, query, stage1) } catch (_: LibraryError.Network) { return beforeStage2(before, success) }
        // stage1は状態変更前。メンテナンス画面を含む解析不能応答はstage2を送らず、三分岐で裁定する。
        if (isBookshelfMaintenance(response)) return beforeStage2(before, success)
        val expected = buildList {
            query.forEach { (name, value) -> add(BookshelfFormField(name, value)) }
            for (index in 0 until stage1.size) add(BookshelfFormField(stage1.name(index), stage1.value(index)))
        }
        val confirm = try { BookshelfConfirmationFormParser.parse(response, expected, kind) } catch (_: ParseException) { return beforeStage2(before, success) }
        // action属性は使わず、確認ページJSから固定path・query無しまで検証済みのactionを使う。
        if (confirm.action != path || confirm.action.contains('?')) return beforeStage2(before, success)
        try {
            val response = postExactlyOnce(confirm.action, form = confirm.buildForm(), onRequestStarted = stateChangePost::markStarted)
            if (kind != BookshelfConfirmationKind.UPDATE) {
                session.updateTokensIfPresent(response)
                return afterPost(before, success, zeroShelfFallback)
            }
            // UPDATEだけは実測どおり、完了ダイアログを閉じる表示遷移POSTまでを同一の状態変更列として送る。
            // フォームまたはscriptが検証できなければ、送信せず既存どおり操作後照合だけを行う。
            val completion = try {
                BookshelfCompletionFormParser.parse(response, confirm.fieldsWithConfirmationCode())
            } catch (_: ParseException) {
                return afterPost(before, success, zeroShelfFallback)
            }
            try {
                val display = postExactlyOnce(
                    "WOpacSdiBookListDispAction.do",
                    form = completion.buildForm(),
                    onRequestStarted = stateChangePost::markStarted,
                )
                session.updateTokensIfPresent(display)
            } catch (_: LibraryError.Network) { }
        } catch (_: LibraryError.Network) { }
        return afterPost(before, success, zeroShelfFallback)
    }

    private suspend fun LicsXpSession.ExclusiveRequestSequence.beforeStage2(before: BookshelfSnapshot, success: (BookshelfSnapshot) -> Boolean): RemoteBookshelfOutcome {
        val after = refetchOrNull() ?: return RemoteBookshelfOutcome.Unknown
        return when { success(after) -> after.applied(); after.sameAs(before) -> changedFailure(); else -> RemoteBookshelfOutcome.Unknown }
    }

    /**
     * zeroShelfFallbackが与えられ、かつ再取得が「otherbookのselectが見つからない」(=新規作成/0件画面)
     * ParseExceptionで失敗した場合だけ、その結果を採用する。それ以外の解析不能は従来どおりUnknown。
     * (docs/design/account-and-bookshelf-fixes.md §2.3.B: 本棚1件からの削除限定で0件成立と解釈する)
     */
    private suspend fun LicsXpSession.ExclusiveRequestSequence.afterPost(
        before: BookshelfSnapshot,
        success: (BookshelfSnapshot) -> Boolean,
        zeroShelfFallback: (() -> RemoteBookshelfOutcome)? = null,
    ): RemoteBookshelfOutcome {
        val after = try {
            fetchAllShelves()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: ParseException) {
            return if (zeroShelfFallback != null && exception.screen == "shelf_list") zeroShelfFallback() else RemoteBookshelfOutcome.Unknown
        } catch (_: Exception) {
            return RemoteBookshelfOutcome.Unknown
        }
        return if (success(after)) after.applied() else RemoteBookshelfOutcome.Unknown
    }

    private fun changedFailure(code: BookshelfStopDiagnosticCode = BookshelfStopDiagnosticCode.STATE_CHANGED): RemoteBookshelfOutcome.Failure {
        session.noteDiagnostic("bookshelf-stop", "code=${code.value} screen=bookshelf-mutation reason=${code.reason}")
        return RemoteBookshelfOutcome.Failure(FailureReason.SITE_RESPONSE_CHANGED, code.value)
    }

    private suspend fun LicsXpSession.ExclusiveRequestSequence.refetchOrNull(): BookshelfSnapshot? = try {
        fetchAllShelves()
    } catch (exception: CancellationException) {
        throw exception
    } catch (_: Exception) {
        null
    }

    private suspend fun LicsXpSession.ExclusiveRequestSequence.fetchAllShelves(): BookshelfSnapshot {
        val tokens = session.requireTokens()
        val currentPage = post("WOpacMnuTopToPwdLibraryAction.do", mapOf("gamen" to "mybooklist"), tokenForm(tokens))
        requireBookshelfNotMaintenance(currentPage)
        if (isBookshelfLoginForm(currentPage)) throw LibraryError.Auth(memberName = null)
        session.updateTokens(currentPage)
        val listed = ShelfListParser.parse(currentPage)
        val current = ShelfParser.parse(currentPage)
        if (listed.singleOrNull { it.no == current.shelf.no }?.name != current.shelf.name) throw ParseException("shelf", "現在の本棚が一覧と一致しません")
        val parsed = linkedMapOf(current.shelf.no to current)
        for (shelf in listed) {
            if (shelf.no == current.shelf.no) continue
            val page = post("WOpacSdiBookListToOtherBookDispAction.do", mapOf("flg" to "1"), switchForm(shelf.no))
            requireBookshelfNotMaintenance(page)
            session.updateTokens(page)
            val result = ShelfParser.parse(page)
            if (result.shelf != shelf) throw ParseException("shelf", "切替後の本棚が要求対象と一致しません")
            parsed[shelf.no] = result
        }
        // このスナップショットのcurrentPageは取得直後の表示にすぎない。状態変更操作の直前には必ず
        // switchToShelf()で対象へ改めて切り替え、その応答からフォームを組み立てる(キャッシュ不使用)。
        return BookshelfSnapshot(listed, parsed, currentPage)
    }

    private fun tokenForm(tokens: com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.PageTokens): FormBody =
        FormBody.Builder().add("hash", tokens.hash).add("gamenid", tokens.gamenId).build()

    private fun LicsXpSession.ExclusiveRequestSequence.switchForm(shelfNo: Int): FormBody {
        val tokens = session.requireTokens()
        return FormBody.Builder().add("hash", tokens.hash).add("gamenid", "tiles.WSdiBookList").add("otherbook", shelfNo.toString()).add("tilcod", "").add("btnflg", "").build()
    }
}

private data class BookshelfSnapshot(
    val shelves: List<Shelf>,
    val parsed: Map<Int, ShelfParseResult>,
    val currentPage: String?,
) {
    fun itemsFor(shelfNo: Int): List<ShelfItem> = parsed[shelfNo]?.items.orEmpty()
    fun hasShelf(shelfNo: Int): Boolean = shelves.any { it.no == shelfNo }
    fun applied(): RemoteBookshelfOutcome.Applied = RemoteBookshelfOutcome.Applied(shelves, shelves.flatMap { itemsFor(it.no) })
    fun alreadyRegistered(): RemoteBookshelfOutcome.AlreadyRegistered = RemoteBookshelfOutcome.AlreadyRegistered(shelves, shelves.flatMap { itemsFor(it.no) })
    fun sameAs(other: BookshelfSnapshot): Boolean = shelves == other.shelves && shelves.all { shelf -> sameItems(itemsFor(shelf.no), other.itemsFor(shelf.no)) }
}

/** 状態変更POSTが開始した後は、取消されても送信結果を未確定として扱う。 */
private class StateChangePostTracker {
    var started: Boolean = false
        private set

    fun markStarted() {
        started = true
    }
}

private fun BookshelfMutationExpectation.matches(snapshot: BookshelfSnapshot): Boolean {
    if (snapshot.shelves.size != shelfCount) return false
    val expectedShelf = shelf ?: return item == null
    val actualShelf = snapshot.shelves.singleOrNull { it.no == expectedShelf.shelfNo } ?: return false
    if (actualShelf.name != expectedShelf.name || snapshot.itemsFor(actualShelf.no).size != expectedShelf.itemCount) return false
    val expectedItem = item ?: return true
    val matches = snapshot.itemsFor(actualShelf.no).filter { it.tilcod == expectedItem.tilcod }
    return matches.size == 1 && matches.single().title == expectedItem.title && normalized(matches.single().memo) == normalized(expectedItem.memo)
}

private fun unchangedOutsideShelf(before: BookshelfSnapshot, after: BookshelfSnapshot, shelfNo: Int): Boolean {
    val beforeOutside = before.shelves.filter { it.no != shelfNo }
    val afterOutside = after.shelves.filter { it.no != shelfNo }
    return beforeOutside == afterOutside && beforeOutside.all { shelf -> sameItems(before.itemsFor(shelf.no), after.itemsFor(shelf.no)) }
}

private fun unchangedExistingShelves(before: BookshelfSnapshot, after: BookshelfSnapshot): Boolean =
    after.shelves.filter { afterShelf -> before.shelves.any { it.no == afterShelf.no } } == before.shelves &&
        before.shelves.all { shelf -> sameItems(before.itemsFor(shelf.no), after.itemsFor(shelf.no)) }

private fun sameItems(left: List<ShelfItem>, right: List<ShelfItem>): Boolean = left.size == right.size && left.indices.all { i ->
    val a = left[i]; val b = right[i]
    a.tilcod == b.tilcod && a.title == b.title && normalized(a.memo) == normalized(b.memo) && a.registeredDate == b.registeredDate
}

/** 表示由来のメモと利用者入力メモの比較にだけ使う正規化。ShelfParser の表示メモ正規化と揃える。 */
private fun normalized(value: String): String = ParserSupport.normalizeWhitespace(value)
// HTML全文へのcontainsだと、共通JavaScript定数や非表示要素中の語で誤検出しうるため、
// script/styleを除いた表示テキストだけを判定対象にする。
private fun isBookshelfMaintenance(html: String): Boolean {
    val document = Jsoup.parse(html)
    document.select("script, style").remove()
    val visibleText = document.text()
    return listOf("メンテナンス中", "メンテナンスのため", "システムメンテナンス", "ただいまメンテナンス").any(visibleText::contains)
}
private fun requireBookshelfNotMaintenance(html: String) { if (isBookshelfMaintenance(html)) throw LibraryError.Maintenance() }
private fun isBookshelfLoginForm(html: String): Boolean = Jsoup.parse(html).selectFirst("input[name=j_password], input[name=j_username], form[action*=j_security_check]") != null
private fun classifyBookshelfLogin(html: String) {
    val document = Jsoup.parse(html)
    if (document.selectFirst("#stat-login") != null || document.select("a, [id], [class]").any { it.id().contains("logout", true) || it.className().contains("logout", true) || it.text().contains("ログアウト") }) return
    if (isBookshelfLoginForm(html)) throw LibraryError.Auth(memberName = null)
    requireBookshelfNotMaintenance(html)
    throw ParseException("login", "ログイン後メニューを判定できません")
}
