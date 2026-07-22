package com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import okhttp3.FormBody

/**
 * ログイン画面の successful controls を、ブラウザと同じDOM順でPOSTするためのパーサ。
 * 認証値は解析結果に保持せず、送信直前に制御フィールドへ差し込む。
 */
object LoginFormParser {
    private const val SCREEN = "login"
    private val CONTROLLED_FIELDS = setOf("username", "j_username", "j_password")

    fun parse(html: String): LoginForm {
        val candidates = Jsoup.parse(html).select("form").filter(::hasRequiredControls)
        if (candidates.size != 1) {
            throw ParseException(SCREEN, "ログインフォームを一意に特定できません")
        }
        val fields = candidates.single().select("input[name]:not([disabled])")
            .mapNotNull(::toSuccessfulField)
        return LoginForm(fields)
    }

    private fun hasRequiredControls(form: Element): Boolean {
        val controls = form.select("input[name]:not([disabled])")
            .filter(::isExpectedControlledInput)
            .map { it.attr("name") }
            .toSet()
        return CONTROLLED_FIELDS.all(controls::contains)
    }

    private fun toSuccessfulField(input: Element): LoginFormField? {
        val name = input.attr("name")
        val type = inputType(input)
        if (name in CONTROLLED_FIELDS) {
            if (!isExpectedControlledInput(input)) return null
            return LoginFormField(name, input.attr("value"), controlled = true)
        }
        if (type != "hidden") return null
        return LoginFormField(name, input.attr("value"), controlled = false)
    }

    /** 実サイトのログインフォーム契約以外は同名でも制御項目として扱わない。 */
    private fun isExpectedControlledInput(input: Element): Boolean = when (input.attr("name")) {
        "username" -> inputType(input) == "text"
        "j_username" -> inputType(input) == "hidden"
        "j_password" -> inputType(input) == "password"
        else -> false
    }

    private fun inputType(input: Element): String = input.attr("type").ifBlank { "text" }.lowercase()
}

class LoginForm internal constructor(
    private val fields: List<LoginFormField>,
) {
    /**
     * hidden値はDOM順のまま保持し、認証関連の3項目だけをブラウザ相当の値で上書きする。
     * 重複した制御フィールドは先頭だけを採用し、未知の非hiddenフィールドは送らない。
     */
    fun buildForm(cardNumber: String, password: String): FormBody {
        require(cardNumber.isNotBlank()) { "カード番号が空です" }
        require(password.isNotBlank()) { "パスワードが空です" }
        val controlledValues = mapOf(
            "username" to cardNumber,
            "j_username" to "0".repeat(CARD_NUMBER_PREFIX_LENGTH) + cardNumber,
            "j_password" to password,
        )
        val emittedControlled = mutableSetOf<String>()
        return FormBody.Builder().apply {
            fields.forEach { field ->
                if (field.controlled) {
                    if (emittedControlled.add(field.name)) add(field.name, controlledValues.getValue(field.name))
                } else {
                    add(field.name, field.value)
                }
            }
        }.build()
    }
}

internal data class LoginFormField(
    val name: String,
    val value: String,
    val controlled: Boolean,
)

private const val CARD_NUMBER_PREFIX_LENGTH = 16
