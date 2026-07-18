package com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser

import com.fallgist.nishinomiyalibrary.domain.model.Shelf
import org.jsoup.parser.Parser

/** コメント内を含む生HTMLから、本棚選択肢を抽出する。 */
object ShelfListParser {
    private const val screen = "shelf_list"
    private val selectRegex = Regex("""(?is)<select\b([^>]*)>(.*?)</select>""")
    private val otherBookAttributeRegex = Regex("""(?is)\b(?:name|id)\s*=\s*(['"])otherbook\1""")
    private val optionRegex = Regex("""(?is)<option\b[^>]*\bvalue\s*=\s*(['"])(\d+)\1[^>]*>(.*?)</option>""")
    private val optionStartRegex = Regex("""(?is)<option\b""")

    fun parse(html: String): List<Shelf> {
        val select = selectRegex.findAll(html).firstOrNull { match ->
            otherBookAttributeRegex.containsMatchIn(match.groupValues[1])
        } ?: throw ParseException(screen, "otherbook の select が見つかりません")
        val selectBody = select.groupValues[2]
        val options = optionRegex.findAll(selectBody).toList()
        if (options.isEmpty()) throw ParseException(screen, "本棚の option が見つかりません")
        if (optionStartRegex.findAll(selectBody).count() != options.size) {
            throw ParseException(screen, "本棚の option が壊れています")
        }

        val shelves = options.map { option ->
            val no = option.groupValues[2].toIntOrNull()
                ?: throw ParseException(screen, "本棚番号が不正です")
            val name = Parser.unescapeEntities(option.groupValues[3], false)
                .let { ParserSupport.run { it.normalized() } }
            if (name.isEmpty() || '<' in name || '>' in name) {
                throw ParseException(screen, "本棚名が不正です")
            }
            Shelf(no, name)
        }
        if (shelves.map { it.no }.distinct().size != shelves.size) {
            throw ParseException(screen, "本棚番号が重複しています")
        }
        return shelves
    }
}
