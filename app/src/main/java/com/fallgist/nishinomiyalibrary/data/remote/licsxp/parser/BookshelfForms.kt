package com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser

import okhttp3.FormBody
import com.fallgist.nishinomiyalibrary.domain.model.Shelf
import com.fallgist.nishinomiyalibrary.domain.model.ShelfItem
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** 本棚編集で送る successful controls を、DOM順と重複を保ったまま表す。 */
internal data class BookshelfFormField(val name: String, val value: String)

internal fun Element.bookshelfFields(includeDisabled: Boolean = false): List<BookshelfFormField> =
    select("input[name], select[name], textarea[name]")
        .filter { includeDisabled || !it.hasAttr("disabled") }
        .mapNotNull { element ->
            when (element.tagName()) {
                "input" -> {
                    val type = element.attr("type").ifBlank { "text" }.lowercase()
                    when {
                        type in setOf("button", "submit", "reset", "image", "file") -> null
                        type in setOf("checkbox", "radio") && !element.hasAttr("checked") -> null
                        else -> BookshelfFormField(element.attr("name"), element.attr("value"))
                    }
                }
                "textarea" -> BookshelfFormField(element.attr("name"), element.`val`())
                "select" -> {
                    val selected = element.select("option[selected]:not([disabled])")
                    val option = when {
                        selected.size == 1 -> selected.single()
                        selected.size > 1 -> throw ParseException("bookshelf-form", "selectの選択値が一意ではありません")
                        else -> element.selectFirst("option:not([disabled])")
                            ?: throw ParseException("bookshelf-form", "selectの選択肢がありません")
                    }
                    BookshelfFormField(element.attr("name"), option.attr("value"))
                }
                else -> null
            }
        }

internal fun List<BookshelfFormField>.toFormBody(replace: (BookshelfFormField, Int) -> String = { field, _ -> field.value }): FormBody =
    FormBody.Builder().apply { forEachIndexed { index, field -> add(field.name, replace(field, index)) } }.build()

/** 書誌詳細の追加フォーム。無効状態の入力も含め、実測29項目を丸ごと再送する。 */
internal object BookshelfDetailAddFormParser {
    private const val SCREEN = "bookshelf-detail-add"
    private val expectedNames = listOf(
        "islogin", "gamentilcod", "prevORnext", "preNextTilcod", "hash", "syurui", "syuruivalue", "returnid",
        "diccod", "syuruiName", "btnflg", "execflg", "amazonUrl", "aWSAccessKeyId", "secretAccessKey", "associateTag",
        "version", "responseGroup", "amazonIsbn", "storeId", "amazonDispFlag", "kensakuFlg", "kensaku", "yoy_directtilcod",
        "tilcod", "refCode", "gamenid", "booklist", "commnt",
    )

    fun parse(html: String): BookshelfDetailAddForm {
        val forms = Jsoup.parse(html).select("form[name=LBForm]")
        if (forms.size != 1) throw ParseException(SCREEN, "詳細LBFormを一意に特定できません")
        val fields = forms.single().bookshelfFields(includeDisabled = true)
        if (fields.map { it.name } != expectedNames) throw ParseException(SCREEN, "詳細LBFormの項目または順序が実測契約と一致しません")
        val tilcod = fields.singleOrNull { it.name == "tilcod" }?.value?.takeIf { it.isNotBlank() }
            ?: throw ParseException(SCREEN, "tilcodがありません")
        return BookshelfDetailAddForm(fields, tilcod)
    }
}

internal class BookshelfDetailAddForm(private val fields: List<BookshelfFormField>, val tilcod: String) {
    fun buildForm(shelfNo: Int, memo: String): FormBody {
        if (shelfNo <= 0 || shelfNo == 998 || shelfNo.toString() == "999") {
            throw ParseException("bookshelf-detail-add", "追加先本棚番号が不正です")
        }
        return fields.toFormBody { field, _ -> when (field.name) { "booklist" -> shelfNo.toString(); "commnt" -> memo; else -> field.value } }
    }
}

/** 新規本棚作成フォーム。サイトが発行した隠し値を保持し、名前と本棚メモだけを変更する。 */
internal object BookshelfCreateFormParser {
    fun parse(html: String): BookshelfCreateForm = parseNamedForm(html, "bookshelf-create") { fields ->
        fields.count { it.name == "listname" } == 1 && fields.count { it.name == "commnt" } == 1
    }.let(::BookshelfCreateForm)
}

internal class BookshelfCreateForm(private val fields: List<BookshelfFormField>) {
    fun buildForm(name: String): FormBody = fields.toFormBody { field, _ -> when (field.name) { "listname" -> name; "commnt" -> ""; else -> field.value } }
}

/** 本棚編集フォーム。資料行は必ず4項目の繰返しであることを確認する。 */
internal object BookshelfEditFormParser {
    private val prefix = listOf("hash", "returnid", "gamenid", "tilcod", "dispflg", "otherbook", "listname", "commnt")
    private val row = listOf("bookcmnt", "eachcmnt", "sortno", "eachsortno")
    fun parse(html: String): BookshelfEditForm {
        val fields = parseNamedForm(html, "bookshelf-edit") { true }
        val names = fields.map { it.name }
        if (names.take(prefix.size) != prefix || (names.size - prefix.size) % row.size != 0 ||
            names.drop(prefix.size).chunked(row.size).any { it != row }) {
            throw ParseException("bookshelf-edit", "編集LBFormの項目または資料行順が実測契約と一致しません")
        }
        val shelfNo = fields[5].value.toIntOrNull() ?: throw ParseException("bookshelf-edit", "otherbookが不正です")
        return BookshelfEditForm(fields, shelfNo, (names.size - prefix.size) / row.size)
    }
}

internal class BookshelfEditForm(
    private val fields: List<BookshelfFormField>,
    val shelfNo: Int,
    val itemCount: Int,
) {
    /**
     * 表示した本棚と編集ページが同一であることを、送信前に fail-closed で照合する。
     * ShelfItem にサイト固有の sort 値は保存しないため、行順・資料メモと、各行の hidden/editable
     * sort 値の一致までを検証する。すべての control 自体は buildForm でそのまま保持する。
     */
    fun requireMatches(shelf: Shelf, items: List<ShelfItem>) {
        if (shelfNo != shelf.no || fields[6].value != shelf.name || itemCount != items.size) {
            throw ParseException("bookshelf-edit", "編集フォームの対象本棚が表示内容と一致しません")
        }
        val rows = fields.drop(8).chunked(4)
        rows.forEachIndexed { index, values ->
            val (bookComment, eachComment, sortNo, eachSortNo) = values.map(BookshelfFormField::value)
            if (bookComment != items[index].memo || eachComment != items[index].memo ||
                sortNo != eachSortNo || sortNo.toIntOrNull() == null) {
                throw ParseException("bookshelf-edit", "編集フォームの資料行が表示内容と一致しません")
            }
        }
    }
    /** listnameと全資料のeachcmntだけを、同じLBFormのまま一度に置換する。 */
    fun edit(name: String, memos: List<String>): FormBody {
        if (memos.size != itemCount) throw ParseException("bookshelf-edit", "資料メモ件数が一致しません")
        var rowIndex = -1
        return fields.toFormBody { field, _ ->
            when (field.name) {
                "listname" -> name
                "eachcmnt" -> memos[++rowIndex]
                else -> field.value
            }
        }
    }
    fun delete(tilcod: String): FormBody = fields.toFormBody { field, _ -> if (field.name == "tilcod") tilcod else field.value }
}

/** 一覧LBFormを本棚削除に使う。実測6項目以外は受理しない。 */
internal object BookshelfDeleteFormParser {
    private val expectedNames = listOf("hash", "returnid", "gamenid", "tilcod", "btnflg", "otherbook")
    fun parse(html: String): BookshelfDeleteForm {
        val fields = parseNamedForm(html, "bookshelf-delete") { it.map(BookshelfFormField::name) == expectedNames }
        val shelfNo = fields.last().value.toIntOrNull() ?: throw ParseException("bookshelf-delete", "otherbookが不正です")
        return BookshelfDeleteForm(fields, shelfNo)
    }
}

internal class BookshelfDeleteForm(private val fields: List<BookshelfFormField>, val shelfNo: Int) {
    fun buildForm(): FormBody = fields.toFormBody()
}

private fun parseNamedForm(html: String, screen: String, accepted: (List<BookshelfFormField>) -> Boolean): List<BookshelfFormField> {
    val forms = Jsoup.parse(html).select("form[name=LBForm]")
    if (forms.size != 1) throw ParseException(screen, "LBFormを一意に特定できません")
    val fields = forms.single().bookshelfFields()
    if (!accepted(fields)) throw ParseException(screen, "LBFormの項目が実測契約と一致しません")
    return fields
}
