package com.fallgist.nishinomiyalibrary.data.remote.licsxp

import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.LoanExtensionRequestFormParser
import com.fallgist.nishinomiyalibrary.domain.model.FailureReason
import com.fallgist.nishinomiyalibrary.domain.model.LoanExtensionOutcome
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [LoanExtensionGateway]の通信境界テスト。`docs/design/loan-extension.md` §5・§7・§10のとおり、
 * MockWebServerでプロトコルを固定する。実サイトへは一切送らない。
 */
class LoanExtensionGatewayTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    // ------------------------------------------------------------------
    // 成功系: 実サイト由来フィクスチャ(usrlend.html / usrlend_after_extend.html)で
    // tilcod照合・並び順変化・ボタン消失を固定する。
    // ------------------------------------------------------------------

    @Test
    fun `延長成功時は対象tilcodを一覧末尾へ移動しボタンを失っていても照合できる`() = runBlocking {
        // usrlend.htmlの実測LBFormから、実際に送信されるstage1フォームを組み立てる
        // (site-research.mdの値を手で書き写さず、実装と同じパーサ出力を使うことで転記誤りを避ける)。
        val stage1Form = LoanExtensionRequestFormParser.parse(fixture("usrlend.html")).buildForm(TARGET_RENEWAL_CODE)
        val stage1ResponseHtml = stage1ResponseHtml(stage1Form)

        enqueueLogin()
        // 1回目はopenAuthenticatedSession自身のメニュー取得、2回目はextendLoan開始時の再取得
        // (ReservationGateway.cancelReservationと同じく、状態変更前に毎回hash/gamenidを取り直す)。
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(fixture("usrlend.html")))
        server.enqueue(page(stage1ResponseHtml))
        server.enqueue(page("<html>stage2 response</html>"))
        server.enqueue(page(fixture("menu.html")))
        // usrlend_after_extend.htmlは、対象行(tilcod=1000000817183)が末尾へ移動し、延長ボタンを失い、
        // 返却期日が2026/08/19へ後退した状態を、実測(docs/site-research.md §10)どおりに再現している。
        server.enqueue(page(fixture("usrlend_after_extend.html")))

        val session = LicsXpLoanExtensionGateway(LicsXpSession(server.url("/"), waitForRequestSlot = {}))
            .openAuthenticatedSession("1234", "secret")
        val outcome = session.extendLoan(TARGET_TILCOD)
        session.close()

        assertEquals(LoanExtensionOutcome.Extended(LocalDate.of(2026, 8, 19)), outcome)
        assertEquals(10, server.requestCount)

        val requests = List(10) { requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
        assertEquals("/WOpacEsSchCmpdDispAction.do", requests[0].path)
        assertEquals("/OpacInitLoginAction.do?subSystemFlag=0", requests[1].path)
        assertEquals("/j_security_check?subSystemFlag=0", requests[2].path)
        assertEquals("/WOpacMnuTopInitAction.do?WebLinkFlag=1", requests[3].path)
        assertEquals("/WOpacMnuTopInitAction.do?WebLinkFlag=1", requests[4].path)
        assertEquals("/WOpacMnuTopToPwdLibraryAction.do?gamen=usrlend", requests[5].path)
        assertEquals("/WOpacUsrLendListExtendAction.do?mngFlg1_handan=1", requests[6].path)
        assertEquals("POST", requests[6].method)
        assertEquals(server.url("/WOpacMnuTopToPwdLibraryAction.do?gamen=usrlend").toString(), requests[6].getHeader("Referer"))
        assertEquals("${server.url("/").scheme}://${server.url("/").host}:${server.url("/").port}", requests[6].getHeader("Origin"))
        // 2段階目の送信先は、prevRequestForm由来のJS代入(絶対パス)をそのまま使う。stage1のpathとは異なる。
        assertEquals("/licsxp-opac/WOpacUsrLendListExtendAction.do", requests[7].path)
        assertEquals("POST", requests[7].method)
        assertEquals(server.url("/WOpacUsrLendListExtendAction.do?mngFlg1_handan=1").toString(), requests[7].getHeader("Referer"))
        assertEquals("/WOpacMnuTopInitAction.do?WebLinkFlag=1", requests[8].path)
        assertEquals("/WOpacMnuTopToPwdLibraryAction.do?gamen=usrlend", requests[9].path)

        // paraだけがrenewalCodeへ上書きされ、他のフィールドはDOM順・重複込みでそのまま送られる。
        val stage1Body = decodeFormFields(requests[6].body.readUtf8())
        assertEquals(1, stage1Body.count { it.first == "para" })
        assertEquals(TARGET_RENEWAL_CODE, stage1Body.single { it.first == "para" }.second)
        assertEquals(2, stage1Body.count { it.first == "btnflg" })
        assertEquals(2, stage1Body.count { it.first == "checkflag" })

        // 2段階目は1段階目のquery+body(=mngFlg1_handan + stage1Form)をそのまま送り、末尾にOKコードを足す。
        val stage2Body = decodeFormFields(requests[7].body.readUtf8())
        assertEquals("OPACUSR005", stage2Body.last().second)
        assertEquals(stage1Body.size + 2, stage2Body.size)
    }

    @Test
    fun `延長ボタンの無い行は対象に指定できず送信前に停止する`() = runBlocking {
        enqueueLogin()
        // 1回目はopenAuthenticatedSession自身のメニュー取得、2回目はextendLoan開始時の再取得
        // (ReservationGateway.cancelReservationと同じく、状態変更前に毎回hash/gamenidを取り直す)。
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(fixture("usrlend.html")))

        val session = LicsXpLoanExtensionGateway(LicsXpSession(server.url("/"), waitForRequestSlot = {}))
            .openAuthenticatedSession("1234", "secret")
        // usrlend.htmlの実測どおり、tilcod=1000001559709の行には延長ボタンが無い(renewalCode=null)。
        val outcome = session.extendLoan("1000001559709")

        assertEquals(LoanExtensionOutcome.Failure(FailureReason.SITE_RESPONSE_CHANGED), outcome)
        assertEquals(6, server.requestCount)
        assertTrue(List(6) { requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }.none { it.path?.contains("WOpacUsrLendListExtendAction") == true })
    }

    // ------------------------------------------------------------------
    // 成功系: 実サイト由来フィクスチャ(usrlend_extend_confirm.html)そのものでprevRequestForm解析
    // ・OK_CODES_NAME抽出・action抽出の一連の整合をGateway経由で固定する(handoff.md 項目13)。
    // このフィクスチャを書き換えてはならないため、一覧側をフィクスチャの値(para=999999999)に合わせる。
    // ------------------------------------------------------------------

    @Test
    fun `実サイト構造の確認フィクスチャを使ってもGatewayが最後まで送信できる`() = runBlocking {
        enqueueLogin()
        // 1回目はopenAuthenticatedSession自身のメニュー取得、2回目はextendLoan開始時の再取得
        // (ReservationGateway.cancelReservationと同じく、状態変更前に毎回hash/gamenidを取り直す)。
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(loanListFixture(CONFIRM_FIXTURE_TILCOD, CONFIRM_FIXTURE_RENEWAL_CODE, "2026/07/18")))
        server.enqueue(page(fixture("usrlend_extend_confirm.html")))
        server.enqueue(page("<html>stage2 response</html>"))
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(loanListFixture(CONFIRM_FIXTURE_TILCOD, null, "2026/08/01")))

        val session = LicsXpLoanExtensionGateway(LicsXpSession(server.url("/"), waitForRequestSlot = {}))
            .openAuthenticatedSession("1234", "secret")
        val outcome = session.extendLoan(CONFIRM_FIXTURE_TILCOD)

        assertEquals(LoanExtensionOutcome.Extended(LocalDate.of(2026, 8, 1)), outcome)
        assertEquals(10, server.requestCount)
        val stage2Request = List(8) { requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }[7]
        assertEquals("/licsxp-opac/WOpacUsrLendListExtendAction.do", stage2Request.path)
        val stage2Body = decodeFormFields(stage2Request.body.readUtf8())
        assertEquals(19, stage2Body.size)
        assertEquals("okCodes" to "OPACUSR005", stage2Body.last())
    }

    // ------------------------------------------------------------------
    // 対象一意性・送信前フェイルクローズ
    // ------------------------------------------------------------------

    @Test
    fun `対象tilcodが一覧に無ければ送信前に停止する`() = runBlocking {
        enqueueLogin()
        // 1回目はopenAuthenticatedSession自身のメニュー取得、2回目はextendLoan開始時の再取得
        // (ReservationGateway.cancelReservationと同じく、状態変更前に毎回hash/gamenidを取り直す)。
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(loanListFixture("9999999999998", "111111111", "2026/07/18")))

        val session = LicsXpLoanExtensionGateway(LicsXpSession(server.url("/"), waitForRequestSlot = {}))
            .openAuthenticatedSession("1234", "secret")
        val outcome = session.extendLoan("9999999999999")

        assertEquals(LoanExtensionOutcome.Failure(FailureReason.SITE_RESPONSE_CHANGED), outcome)
        assertEquals(6, server.requestCount)
    }

    @Test
    fun `対象tilcodが一覧に複数あれば送信前に停止する`() = runBlocking {
        enqueueLogin()
        // 1回目はopenAuthenticatedSession自身のメニュー取得、2回目はextendLoan開始時の再取得
        // (ReservationGateway.cancelReservationと同じく、状態変更前に毎回hash/gamenidを取り直す)。
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(
            page(
                loanListFixtureRows(
                    listOf(
                        Triple("9999999999999", "111111111", "2026/07/18"),
                        Triple("9999999999999", "222222222", "2026/07/19"),
                    ),
                ),
            ),
        )

        val session = LicsXpLoanExtensionGateway(LicsXpSession(server.url("/"), waitForRequestSlot = {}))
            .openAuthenticatedSession("1234", "secret")
        val outcome = session.extendLoan("9999999999999")

        assertEquals(LoanExtensionOutcome.Failure(FailureReason.SITE_RESPONSE_CHANGED), outcome)
        assertEquals(6, server.requestCount)
    }

    @Test
    fun `セッション切れの一覧応答は送信前に停止する`() = runBlocking {
        enqueueLogin()
        // 1回目はopenAuthenticatedSession自身のメニュー取得、2回目はextendLoan開始時の再取得
        // (ReservationGateway.cancelReservationと同じく、状態変更前に毎回hash/gamenidを取り直す)。
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(fixture("login_form.html")))

        val session = LicsXpLoanExtensionGateway(LicsXpSession(server.url("/"), waitForRequestSlot = {}))
            .openAuthenticatedSession("1234", "secret")
        val outcome = session.extendLoan("9999999999999")

        assertEquals(LoanExtensionOutcome.Failure(FailureReason.SESSION_EXPIRED_BEFORE_SUBMIT), outcome)
        assertEquals(6, server.requestCount)
    }

    // ------------------------------------------------------------------
    // POST後の通信断・不明応答はUnknown、再送しない
    // ------------------------------------------------------------------

    @Test
    fun `1段階目POSTの接続断は再送せずUnknownになる`() = runBlocking {
        enqueueLogin()
        // 1回目はopenAuthenticatedSession自身のメニュー取得、2回目はextendLoan開始時の再取得
        // (ReservationGateway.cancelReservationと同じく、状態変更前に毎回hash/gamenidを取り直す)。
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(loanListFixture(TARGET_TILCOD, TARGET_RENEWAL_CODE, "2026/07/18")))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))

        val session = LicsXpLoanExtensionGateway(LicsXpSession(server.url("/"), waitForRequestSlot = {}))
            .openAuthenticatedSession("1234", "secret")
        val outcome = session.extendLoan(TARGET_TILCOD)

        assertEquals(LoanExtensionOutcome.Unknown, outcome)
        assertEquals(7, server.requestCount)
    }

    @Test
    fun `2段階目POSTの接続断は再送せずUnknownになる`() = runBlocking {
        // Gatewayが実際に送るstage1フォームと同じ内容にするため、一覧フィクスチャから同じ方法で組み立てる
        // (手組みのFormBodyだとGateway側のexpectedStage1Fieldsと一致せずParseExceptionになってしまう)。
        val stage1Form = LoanExtensionRequestFormParser.parse(
            loanListFixture(TARGET_TILCOD, TARGET_RENEWAL_CODE, "2026/07/18"),
        ).buildForm(TARGET_RENEWAL_CODE)
        enqueueLogin()
        // 1回目はopenAuthenticatedSession自身のメニュー取得、2回目はextendLoan開始時の再取得
        // (ReservationGateway.cancelReservationと同じく、状態変更前に毎回hash/gamenidを取り直す)。
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(loanListFixture(TARGET_TILCOD, TARGET_RENEWAL_CODE, "2026/07/18")))
        server.enqueue(page(stage1ResponseHtml(stage1Form)))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))

        val session = LicsXpLoanExtensionGateway(LicsXpSession(server.url("/"), waitForRequestSlot = {}))
            .openAuthenticatedSession("1234", "secret")
        val outcome = session.extendLoan(TARGET_TILCOD)

        assertEquals(LoanExtensionOutcome.Unknown, outcome)
        assertEquals(8, server.requestCount)
    }

    @Test
    fun `OK_CODES_NAMEを抽出できない1段階目応答は2段階目を送らず返却期日不変でFailureになる`() = runBlocking {
        // 設計 §5.3(2026-08-05所有者裁定)により、2段階目を送っていないと確定できる場合は
        // Unknownではなく Failure(SITE_RESPONSE_CHANGED) になる。
        enqueueLogin()
        // 1回目はopenAuthenticatedSession自身のメニュー取得、2回目はextendLoan開始時の再取得
        // (ReservationGateway.cancelReservationと同じく、状態変更前に毎回hash/gamenidを取り直す)。
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(loanListFixture(TARGET_TILCOD, TARGET_RENEWAL_CODE, "2026/07/18")))
        // prevRequestFormはあるがOK_CODES_NAME代入もaction代入も無い不正な応答。
        server.enqueue(
            page(
                """
                <html><body>
                <form name="prevRequestForm" method="post">
                <input type="hidden" name="para" value="$TARGET_RENEWAL_CODE">
                </form>
                </body></html>
                """.trimIndent(),
            ),
        )
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(loanListFixture(TARGET_TILCOD, TARGET_RENEWAL_CODE, "2026/07/18")))

        val session = LicsXpLoanExtensionGateway(LicsXpSession(server.url("/"), waitForRequestSlot = {}))
            .openAuthenticatedSession("1234", "secret")
        val outcome = session.extendLoan(TARGET_TILCOD)

        assertEquals(LoanExtensionOutcome.Failure(FailureReason.SITE_RESPONSE_CHANGED), outcome)
        // 2段階目(WOpacUsrLendListExtendAction.doへのクエリ無しPOST)は送らない。
        assertEquals(9, server.requestCount)
        assertTrue(
            List(9) { requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
                .none { it.path == "/licsxp-opac/WOpacUsrLendListExtendAction.do" },
        )
    }

    @Test
    fun `送信先actionが想定外の1段階目応答は2段階目を送らず返却期日不変でFailureになる`() = runBlocking {
        // 設計 §10「送信先(action)の検証失敗経路」。document.prevRequestForm.actionの代入値が
        // 固定origin・固定pathと一致しない場合、2段階目を送らずに停止すること。
        val stage1Form = LoanExtensionRequestFormParser.parse(
            loanListFixture(TARGET_TILCOD, TARGET_RENEWAL_CODE, "2026/07/18"),
        ).buildForm(TARGET_RENEWAL_CODE)
        enqueueLogin()
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(loanListFixture(TARGET_TILCOD, TARGET_RENEWAL_CODE, "2026/07/18")))
        server.enqueue(
            page(stage1ResponseHtml(stage1Form, actionPath = "/licsxp-opac/WOpacUsrRsvCancelAction.do")),
        )
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(loanListFixture(TARGET_TILCOD, TARGET_RENEWAL_CODE, "2026/07/18")))

        val session = LicsXpLoanExtensionGateway(LicsXpSession(server.url("/"), waitForRequestSlot = {}))
            .openAuthenticatedSession("1234", "secret")
        val outcome = session.extendLoan(TARGET_TILCOD)

        assertEquals(LoanExtensionOutcome.Failure(FailureReason.SITE_RESPONSE_CHANGED), outcome)
        assertEquals(9, server.requestCount)
        assertTrue(
            List(9) { requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
                .none { it.path == "/licsxp-opac/WOpacUsrLendListExtendAction.do" },
        )
    }

    @Test
    fun `確認コードのokArray代入が想定外の1段階目応答は2段階目を送らず返却期日不変でFailureになる`() = runBlocking {
        // 修正1(確認コードの抽出方式化)に対応。okArrayへの代入が無い・複数・想定値と異なる場合、
        // 「表示されていない問い」へ盲目的にOKを返さないよう2段階目を送らずに停止すること。
        val stage1Form = LoanExtensionRequestFormParser.parse(
            loanListFixture(TARGET_TILCOD, TARGET_RENEWAL_CODE, "2026/07/18"),
        ).buildForm(TARGET_RENEWAL_CODE)
        enqueueLogin()
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(loanListFixture(TARGET_TILCOD, TARGET_RENEWAL_CODE, "2026/07/18")))
        // OK_CODES_NAME・actionは正常だが、確認コードのokArray代入が想定外の値になっている応答。
        server.enqueue(
            page(stage1ResponseHtmlWithConfirmationCode(stage1Form, confirmationCode = "OPACUSR999")),
        )
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(loanListFixture(TARGET_TILCOD, TARGET_RENEWAL_CODE, "2026/07/18")))

        val session = LicsXpLoanExtensionGateway(LicsXpSession(server.url("/"), waitForRequestSlot = {}))
            .openAuthenticatedSession("1234", "secret")
        val outcome = session.extendLoan(TARGET_TILCOD)

        assertEquals(LoanExtensionOutcome.Failure(FailureReason.SITE_RESPONSE_CHANGED), outcome)
        assertEquals(9, server.requestCount)
        assertTrue(
            List(9) { requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
                .none { it.path == "/licsxp-opac/WOpacUsrLendListExtendAction.do" },
        )
    }

    @Test
    fun `1段階目応答がメンテナンス画面ならFailureではなくUnknownになる`() = runBlocking {
        // 修正2に対応。旧実装はrequireNotMaintenance(stage1Page.html)がtryの外にあり、
        // LibraryError.Maintenanceが素通しされてFailure(SITE_MAINTENANCE)になっていた。
        // 1段階目は既にPOST済みのため、§5.3の3分岐(再取得もメンテナンスで失敗しUnknownへ倒れる)で扱う。
        enqueueLogin()
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(loanListFixture(TARGET_TILCOD, TARGET_RENEWAL_CODE, "2026/07/18")))
        server.enqueue(page("<html>ただいまメンテナンス中です</html>"))
        server.enqueue(page("<html>ただいまメンテナンス中です</html>"))

        val session = LicsXpLoanExtensionGateway(LicsXpSession(server.url("/"), waitForRequestSlot = {}))
            .openAuthenticatedSession("1234", "secret")
        val outcome = session.extendLoan(TARGET_TILCOD)

        assertEquals(LoanExtensionOutcome.Unknown, outcome)
        // 2段階目は送らない。
        assertEquals(8, server.requestCount)
        assertTrue(
            List(8) { requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
                .none { it.path == "/licsxp-opac/WOpacUsrLendListExtendAction.do" },
        )
    }

    @Test
    fun `送信後に対象tilcodが消えていればUnknownになる`() = runBlocking {
        // Gatewayが実際に送るstage1フォームと同じ内容にするため、一覧フィクスチャから同じ方法で組み立てる
        // (手組みのFormBodyだとGateway側のexpectedStage1Fieldsと一致せずParseExceptionになってしまう)。
        val stage1Form = LoanExtensionRequestFormParser.parse(
            loanListFixture(TARGET_TILCOD, TARGET_RENEWAL_CODE, "2026/07/18"),
        ).buildForm(TARGET_RENEWAL_CODE)
        enqueueLogin()
        // 1回目はopenAuthenticatedSession自身のメニュー取得、2回目はextendLoan開始時の再取得
        // (ReservationGateway.cancelReservationと同じく、状態変更前に毎回hash/gamenidを取り直す)。
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(loanListFixture(TARGET_TILCOD, TARGET_RENEWAL_CODE, "2026/07/18")))
        server.enqueue(page(stage1ResponseHtml(stage1Form)))
        server.enqueue(page("<html>stage2</html>"))
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(loanListFixture("9999999999998", null, "2026/07/18")))

        val session = LicsXpLoanExtensionGateway(LicsXpSession(server.url("/"), waitForRequestSlot = {}))
            .openAuthenticatedSession("1234", "secret")
        val outcome = session.extendLoan(TARGET_TILCOD)

        assertEquals(LoanExtensionOutcome.Unknown, outcome)
        assertEquals(10, server.requestCount)
    }

    @Test
    fun `送信後に返却期日が変化していなければUnknownになる`() = runBlocking {
        // Gatewayが実際に送るstage1フォームと同じ内容にするため、一覧フィクスチャから同じ方法で組み立てる
        // (手組みのFormBodyだとGateway側のexpectedStage1Fieldsと一致せずParseExceptionになってしまう)。
        val stage1Form = LoanExtensionRequestFormParser.parse(
            loanListFixture(TARGET_TILCOD, TARGET_RENEWAL_CODE, "2026/07/18"),
        ).buildForm(TARGET_RENEWAL_CODE)
        enqueueLogin()
        // 1回目はopenAuthenticatedSession自身のメニュー取得、2回目はextendLoan開始時の再取得
        // (ReservationGateway.cancelReservationと同じく、状態変更前に毎回hash/gamenidを取り直す)。
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(loanListFixture(TARGET_TILCOD, TARGET_RENEWAL_CODE, "2026/07/18")))
        server.enqueue(page(stage1ResponseHtml(stage1Form)))
        server.enqueue(page("<html>stage2</html>"))
        server.enqueue(page(fixture("menu.html")))
        // 返却期日が送信前と同じ(拒否された場合に相当)。
        server.enqueue(page(loanListFixture(TARGET_TILCOD, null, "2026/07/18")))

        val session = LicsXpLoanExtensionGateway(LicsXpSession(server.url("/"), waitForRequestSlot = {}))
            .openAuthenticatedSession("1234", "secret")
        val outcome = session.extendLoan(TARGET_TILCOD)

        assertEquals(LoanExtensionOutcome.Unknown, outcome)
        assertEquals(10, server.requestCount)
    }

    // ------------------------------------------------------------------
    // ヘルパー
    // ------------------------------------------------------------------

    private fun enqueueLogin() {
        server.enqueue(page("<html>warm</html>", cookie = true))
        server.enqueue(page(fixture("login_form.html")))
        server.enqueue(page("<html>login relay</html>"))
    }

    private fun page(body: String, cookie: Boolean = false): MockResponse = MockResponse().setBody(body).apply {
        if (cookie) addHeader("Set-Cookie", "JSESSIONID=fixture; Path=/")
    }

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader).getResource("fixtures/$name")!!.readText()

    private fun decodeFormFields(body: String): List<Pair<String, String>> = body.split('&').filter(String::isNotBlank)
        .map { part -> part.substringBefore('=') to java.net.URLDecoder.decode(part.substringAfter('=', ""), Charsets.UTF_8.name()) }

    /** stage1フォームの内容から、Gatewayが検証に使うprevRequestForm応答を組み立てる(値の転記誤りを避ける)。 */
    private fun stage1ResponseHtml(
        stage1Form: FormBody,
        actionPath: String = "/licsxp-opac/WOpacUsrLendListExtendAction.do",
        okCodesName: String = "okCodes",
        confirmationCode: String = "OPACUSR005",
    ): String = stage1ResponseHtmlWithConfirmationCode(stage1Form, actionPath, okCodesName, confirmationCode)

    /**
     * [stage1ResponseHtml]と同じだが、確認コードの想定値検証(修正1)を単独でテストできるよう
     * 引数名を明示した別名。実サイトの確認コードは`okArray[okArray.length] = "...";`という
     * 配列要素代入で現れる(`usrlend_extend_confirm.html`105行目付近)ため、その構造を模す。
     */
    private fun stage1ResponseHtmlWithConfirmationCode(
        stage1Form: FormBody,
        actionPath: String = "/licsxp-opac/WOpacUsrLendListExtendAction.do",
        okCodesName: String = "okCodes",
        confirmationCode: String = "OPACUSR005",
    ): String {
        val hiddenInputs = buildString {
            append("<input type=\"hidden\" name=\"mngFlg1_handan\" value=\"1\">\n")
            for (index in 0 until stage1Form.size) {
                append("<input type=\"hidden\" name=\"${stage1Form.name(index)}\" value=\"${escapeHtmlAttribute(stage1Form.value(index))}\">\n")
            }
        }
        return """
        <html><body>
          <form name="prevRequestForm" method="post">
            $hiddenInputs
          </form>
          <script>var OK_CODES_NAME = "$okCodesName";</script>
          <script>document.prevRequestForm.action = "$actionPath";</script>
          <script>
            var okArray = new Array();
            okArray[okArray.length] = "$confirmationCode";
          </script>
        </body></html>
        """.trimIndent()
    }

    private fun escapeHtmlAttribute(value: String): String =
        value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")

    /**
     * 貸出状況一覧の最小限の自作フィクスチャ。異常系・パラメータ組合せの網羅にだけ使う
     * (正常系のtilcod照合・並び順変化・ボタン消失は、実サイト由来のusrlend.html /
     * usrlend_after_extend.htmlで固定している。handoff.md 項目13)。
     *
     * LBFormの各hiddenは`usrlend_extend_confirm.html`のprevRequestFormと同じ値を持たせてあるため、
     * このフィクスチャで組み立てたstage1送信は同フィクスチャでの検証とも整合する。
     */
    private fun loanListFixture(tilcod: String, extendCode: String?, dueDate: String): String =
        loanListFixtureRows(listOf(Triple(tilcod, extendCode, dueDate)))

    /** rowsの各要素は(tilcod, extendCodeまたはnull, 返却期日)。 */
    private fun loanListFixtureRows(rows: List<Triple<String, String?, String>>): String {
        val header = "<h1>貸出状況一覧</h1>" +
            "<form name=\"LBForm\" method=\"post\">" +
            "<input type=\"hidden\" name=\"schkflg\" value=\"\">" +
            "<input type=\"hidden\" name=\"allschkflg\" value=\"\">" +
            "<input type=\"hidden\" name=\"hash\" value=\"0000000000000000000000000000000000000000\">" +
            "<input type=\"hidden\" name=\"islogin\" value=\"1\">" +
            "<input type=\"hidden\" name=\"btnflg\" value=\"0\">" +
            "<input type=\"hidden\" name=\"checkflag\" value=\"0\">" +
            "<input type=\"hidden\" name=\"booklistvalue\" value=\"0\">" +
            "<input type=\"hidden\" name=\"commntvalue\" value=\"メモ（任意）\">" +
            "<input type=\"hidden\" name=\"btnflg\" value=\"0\">" +
            "<input type=\"hidden\" name=\"checkflag\" value=\"0\">" +
            "<input type=\"hidden\" name=\"returnid\" value=\"https://tosho.nishi.or.jp/?v=PC\">" +
            "<input type=\"hidden\" name=\"gamenid\" value=\"tiles.WUsrLendList\">" +
            "<input type=\"hidden\" name=\"para\" value=\"\">" +
            "<input type=\"hidden\" name=\"mngFlg1\" value=\"1\">" +
            "<input type=\"hidden\" name=\"sortkeyvalue\" value=\"\">" +
            "<input type=\"hidden\" name=\"sortDefKey\" value=\"0\">" +
            "<input type=\"hidden\" name=\"booklist\" value=\"0\">" +
            "<table summary='貸出状況一覧表'><thead><tr><th>資料名</th><th>書誌種別</th>" +
            "<th>貸出館</th><th>貸出日</th><th>返却期日</th><th>状態</th></tr></thead><tbody>"
        val rowsHtml = rows.joinToString("\n") { (tilcod, extendCode, dueDate) ->
            val button = extendCode?.let { "<input type=\"button\" class=\"button exec\" value=\"延長\" onclick='javascript:extend(\"$it\")'>" }.orEmpty()
            """
            <tr>
                <td><a href='?para=$tilcod'>資料</a></td>
                <td>図書</td><td>本館</td><td>2026/07/01</td><td>$dueDate</td><td>貸出中</td>
                <td>$button</td>
            </tr>
            """.trimIndent()
        }
        return "$header$rowsHtml</tbody></table></form>"
    }

    private companion object {
        const val TARGET_TILCOD = "1000000817183"
        const val TARGET_RENEWAL_CODE = "222057515"
        const val CONFIRM_FIXTURE_TILCOD = "9990000000001"
        const val CONFIRM_FIXTURE_RENEWAL_CODE = "999999999"
    }
}
