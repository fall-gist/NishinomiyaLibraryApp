package com.fallgist.nishinomiyalibrary.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [BackupPayloadCodec]のラウンドトリップ・ゴールデンJSON・拒否系テスト。
 * docs/design/settings-export-import.md §9。
 */
class BackupPayloadCodecTest {

    @Test
    fun `DTOはJSONへ変換して読み戻しても等価`() {
        val payload = samplePayload()
        val json = BackupPayloadCodec.encode(payload)
        val decoded = BackupPayloadCodec.decode(json)
        assertEquals(payload, decoded)
    }

    @Test
    fun `ゴールデンJSONと一致する(形式が意図せず変わったらここが落ちる)`() {
        val json = BackupPayloadCodec.encode(samplePayload())
        assertEquals(GOLDEN_JSON.trim(), json.trim())
    }

    @Test
    fun `メンバーが0人でも設定だけのファイルとして書き出し読み戻せる`() {
        val payload = samplePayload().copy(
            members = emptyList(),
            autoReservation = BackupAutoReservation(emptyList(), emptyList()),
            readingRecords = emptyList(),
            readingHistoryCheckpoints = emptyList(),
            reservationCartItems = emptyList(),
        )
        val json = BackupPayloadCodec.encode(payload)
        assertEquals(payload, BackupPayloadCodec.decode(json))
    }

    @Test
    fun `パース不能なJSONは拒否される`() {
        assertRejected<BackupMalformedException> { BackupPayloadCodec.decode("{ これはJSONではない") }
    }

    @Test
    fun `空文字列は拒否される`() {
        assertRejected<BackupMalformedException> { BackupPayloadCodec.decode("") }
    }

    @Test
    fun `formatVersionが欠けている場合は拒否される`() {
        val json = BackupPayloadCodec.encode(samplePayload())
        val withoutVersion = removeJsonField(json, "formatVersion")
        assertRejected<BackupMalformedException> { BackupPayloadCodec.decode(withoutVersion) }
    }

    @Test
    fun `未対応の上位formatVersionは拒否される`() {
        val json = BackupPayloadCodec.encode(samplePayload())
        val bumped = json.replaceFirst("\"formatVersion\": 1", "\"formatVersion\": 999")
        assertRejected<BackupUnsupportedFormatVersionException> { BackupPayloadCodec.decode(bumped) }
    }

    @Test
    fun `未知のterm種別は拒否される`() {
        val payload = samplePayload()
        val json = BackupPayloadCodec.encode(payload)
        val corrupted = json.replaceFirst("\"kind\": \"INCLUDE\"", "\"kind\": \"MYSTERY\"")
        assertRejected<BackupUnknownEnumException> { BackupPayloadCodec.decode(corrupted) }
    }

    @Test
    fun `未知の自動予約制御statusは拒否される`() {
        val payload = samplePayload()
        val json = BackupPayloadCodec.encode(payload)
        val corrupted = json.replaceFirst("\"status\": \"SUCCESS\"", "\"status\": \"MYSTERY\"")
        assertRejected<BackupUnknownEnumException> { BackupPayloadCodec.decode(corrupted) }
    }

    @Test
    fun `カート項目が存在しないmemberIdを参照する場合は拒否される`() {
        val payload = samplePayload().copy(
            reservationCartItems = listOf(
                BackupReservationCartItem(
                    memberId = 999L,
                    tilcod = "1000000000001",
                    title = "存在しないメンバー宛て",
                    writerLine = null,
                    addedAtEpochMillis = 1L,
                ),
            ),
        )
        val json = BackupPayloadCodec.encode(payload)
        assertRejected<BackupReferentialIntegrityException> { BackupPayloadCodec.decode(json) }
    }

    @Test
    fun `syncHourが範囲外の場合は拒否される`() {
        val payload = samplePayload().copy(settings = samplePayload().settings.copy(syncHour = 24))
        val json = BackupPayloadCodec.encode(payload)
        assertRejected<BackupValueRangeException> { BackupPayloadCodec.decode(json) }
    }

    @Test
    fun `自動予約制御のpreparedMemberIdが存在しないメンバーを参照する場合は拒否される`() {
        val payload = samplePayload().copy(
            autoReservation = samplePayload().autoReservation.copy(
                controls = listOf(samplePayload().autoReservation.controls.first().copy(preparedMemberId = 999L)),
            ),
        )
        val json = BackupPayloadCodec.encode(payload)
        assertRejected<BackupReferentialIntegrityException> { BackupPayloadCodec.decode(json) }
    }

    @Test
    fun `preparedMemberIdがnullの場合は準備中のメンバーなしとして検査対象外`() {
        val payload = samplePayload().copy(
            autoReservation = samplePayload().autoReservation.copy(
                controls = listOf(samplePayload().autoReservation.controls.first().copy(preparedMemberId = null)),
            ),
        )
        val json = BackupPayloadCodec.encode(payload)
        // 例外を投げずに読み込めることを確認する。
        assertEquals(payload, BackupPayloadCodec.decode(json))
    }

    @Test
    fun `自動予約ルールのsortOrderが重複する場合は拒否される`() {
        val rule = samplePayload().autoReservation.rules.first()
        val payload = samplePayload().copy(
            autoReservation = samplePayload().autoReservation.copy(
                rules = listOf(rule, rule.copy(id = 2)),
            ),
        )
        val json = BackupPayloadCodec.encode(payload)
        assertRejected<BackupValueRangeException> { BackupPayloadCodec.decode(json) }
    }

    @Test
    fun `自動予約ルールの検索語が複合キーで重複する場合は拒否される(REPLACE挿入のため黙って行が減る)`() {
        val rule = samplePayload().autoReservation.rules.first()
        val payload = samplePayload().copy(
            autoReservation = samplePayload().autoReservation.copy(
                rules = listOf(rule.copy(terms = rule.terms + rule.terms.first())),
            ),
        )
        val json = BackupPayloadCodec.encode(payload)
        assertRejected<BackupValueRangeException> { BackupPayloadCodec.decode(json) }
    }

    @Test
    fun `自動予約制御のtilcodが重複する場合は拒否される`() {
        val control = samplePayload().autoReservation.controls.first()
        val payload = samplePayload().copy(
            autoReservation = samplePayload().autoReservation.copy(
                controls = listOf(control, control.copy(status = "UNKNOWN_AFTER_POST")),
            ),
        )
        val json = BackupPayloadCodec.encode(payload)
        assertRejected<BackupValueRangeException> { BackupPayloadCodec.decode(json) }
    }

    @Test
    fun `予約カート項目がmemberIdとtilcodの組で重複する場合は拒否される(REPLACE挿入のため黙って行が減る)`() {
        val item = samplePayload().reservationCartItems.first()
        val payload = samplePayload().copy(reservationCartItems = listOf(item, item.copy(title = "複製")))
        val json = BackupPayloadCodec.encode(payload)
        assertRejected<BackupValueRangeException> { BackupPayloadCodec.decode(json) }
    }

    @Test
    fun `読書記録がmemberId_tilcod_loanDateの組で重複する場合は拒否される(REPLACE挿入のため黙って行が減る)`() {
        val record = samplePayload().readingRecords.first()
        val payload = samplePayload().copy(readingRecords = listOf(record, record.copy(title = "複製")))
        val json = BackupPayloadCodec.encode(payload)
        assertRejected<BackupValueRangeException> { BackupPayloadCodec.decode(json) }
    }

    @Test
    fun `読書履歴チェックポイントがmemberId_tilcod_loanDateの組で重複する場合は拒否される(REPLACE挿入のため黙って行が減る)`() {
        val checkpoint = samplePayload().readingHistoryCheckpoints.first()
        val payload = samplePayload().copy(readingHistoryCheckpoints = listOf(checkpoint, checkpoint))
        val json = BackupPayloadCodec.encode(payload)
        assertRejected<BackupValueRangeException> { BackupPayloadCodec.decode(json) }
    }

    @Test
    fun `日付が不正な場合はどの項目かをメッセージに含めて拒否される(汎用メッセージに丸めない)`() {
        val payload = samplePayload().copy(
            readingRecords = listOf(samplePayload().readingRecords.first().copy(loanDate = "2026-13-99")),
        )
        val json = BackupPayloadCodec.encode(payload)
        try {
            BackupPayloadCodec.decode(json)
            fail("拒否されるはずでした")
        } catch (exception: BackupInvalidDateException) {
            assertTrue(
                "メッセージに項目名(loanDate)が含まれていません: ${exception.message}",
                exception.message?.contains("loanDate") == true,
            )
        }
    }

    @Test
    fun `returnReminderDaysBeforeが範囲外の場合は拒否される`() {
        val payload = samplePayload().copy(
            settings = samplePayload().settings.copy(returnReminderDaysBefore = 8),
        )
        val json = BackupPayloadCodec.encode(payload)
        assertRejected<BackupValueRangeException> { BackupPayloadCodec.decode(json) }
    }

    private inline fun <reified T : BackupValidationException> assertRejected(block: () -> Unit) {
        try {
            block()
            fail("拒否されるはずでした")
        } catch (exception: Exception) {
            assertTrue(
                "期待した例外型ではありません: ${exception::class}",
                exception is T,
            )
        }
    }

    /** JSON文字列から `"field": <値>,` の1行を素朴に取り除く(テスト専用の壊し方)。 */
    private fun removeJsonField(json: String, field: String): String =
        json.lines().filterNot { it.trim().startsWith("\"$field\"") }.joinToString("\n")

    private fun samplePayload(): BackupPayload = BackupPayload(
        formatVersion = 1,
        exportedAt = "2026-08-15T10:30:00+09:00",
        appVersion = "1.1",
        sourceDbVersion = 9,
        members = listOf(
            BackupMember(
                id = 1,
                name = "一郎",
                colorHex = "#3D6DB5",
                cardNumber = "1234567890",
                sortOrder = 0,
            ),
        ),
        settings = BackupSettings(
            syncHour = 18,
            syncMinute = 0,
            notifyReturnReminder = true,
            notifyPickupReady = true,
            defaultCalendarLibrary = "106",
            returnReminderDaysBefore = 1,
            diagnosticLogEnabled = false,
            autoReservationEnabled = false,
        ),
        autoReservation = BackupAutoReservation(
            rules = listOf(
                BackupAutoReservationRule(
                    id = 1,
                    enabled = true,
                    sortOrder = 0,
                    terms = listOf(
                        BackupAutoReservationTerm(
                            kind = "INCLUDE",
                            sortOrder = 0,
                            original = "宮部みゆき",
                            normalized = "宮部みゆき",
                        ),
                    ),
                ),
            ),
            controls = listOf(
                BackupAutoReservationControl(
                    tilcod = "1000000000001",
                    firstCandidateDate = "2026-08-01",
                    expiresOn = "2026-09-01",
                    status = "SUCCESS",
                    preparedMemberId = null,
                ),
            ),
        ),
        readingRecords = listOf(
            BackupReadingRecord(
                memberId = 1,
                tilcod = "1000000000002",
                title = "火車",
                loanDate = "2026-07-01",
                library = "中央図書館",
                titleNormalized = "火車",
            ),
        ),
        readingHistoryCheckpoints = listOf(
            BackupReadingHistoryCheckpoint(
                memberId = 1,
                tilcod = "1000000000002",
                loanDate = "2026-07-01",
            ),
        ),
        reservationCartItems = listOf(
            BackupReservationCartItem(
                memberId = 1,
                tilcod = "1000000000003",
                title = "模倣犯",
                writerLine = "宮部みゆき/著",
                addedAtEpochMillis = 1_700_000_000_000L,
            ),
        ),
    )

    companion object {
        private val GOLDEN_JSON = """
{
    "formatVersion": 1,
    "exportedAt": "2026-08-15T10:30:00+09:00",
    "appVersion": "1.1",
    "sourceDbVersion": 9,
    "members": [
        {
            "id": 1,
            "name": "一郎",
            "colorHex": "#3D6DB5",
            "cardNumber": "1234567890",
            "sortOrder": 0,
            "passwordEncrypted": false,
            "password": null
        }
    ],
    "settings": {
        "syncHour": 18,
        "syncMinute": 0,
        "notifyReturnReminder": true,
        "notifyPickupReady": true,
        "defaultCalendarLibrary": "106",
        "returnReminderDaysBefore": 1,
        "diagnosticLogEnabled": false,
        "autoReservationEnabled": false
    },
    "autoReservation": {
        "rules": [
            {
                "id": 1,
                "enabled": true,
                "sortOrder": 0,
                "terms": [
                    {
                        "kind": "INCLUDE",
                        "sortOrder": 0,
                        "original": "宮部みゆき",
                        "normalized": "宮部みゆき"
                    }
                ]
            }
        ],
        "controls": [
            {
                "tilcod": "1000000000001",
                "firstCandidateDate": "2026-08-01",
                "expiresOn": "2026-09-01",
                "status": "SUCCESS",
                "preparedMemberId": null
            }
        ]
    },
    "readingRecords": [
        {
            "memberId": 1,
            "tilcod": "1000000000002",
            "title": "火車",
            "loanDate": "2026-07-01",
            "library": "中央図書館",
            "titleNormalized": "火車"
        }
    ],
    "readingHistoryCheckpoints": [
        {
            "memberId": 1,
            "tilcod": "1000000000002",
            "loanDate": "2026-07-01"
        }
    ],
    "reservationCartItems": [
        {
            "memberId": 1,
            "tilcod": "1000000000003",
            "title": "模倣犯",
            "writerLine": "宮部みゆき/著",
            "addedAtEpochMillis": 1700000000000
        }
    ]
}
        """
    }
}
