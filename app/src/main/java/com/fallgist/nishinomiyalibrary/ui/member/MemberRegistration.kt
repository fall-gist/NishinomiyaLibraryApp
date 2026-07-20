package com.fallgist.nishinomiyalibrary.ui.member

/**
 * メンバー登録フォームの入力値。秘密情報を含むため、文字列表現には値を含めない。
 */
class RegistrationForm(
    val name: String,
    val colorHex: String,
    val cardNumber: String,
    val password: String,
) {
    override fun toString(): String = "RegistrationForm(秘密情報は非表示)"
}

/** 検証を通過した登録値。ログ用途に利用しない。 */
class ValidatedRegistration internal constructor(
    val name: String,
    val colorHex: String,
    val cardNumber: String,
    val password: String,
) {
    override fun toString(): String = "ValidatedRegistration(秘密情報は非表示)"
}

data class RegistrationErrors(
    val name: String? = null,
    val colorHex: String? = null,
    val cardNumber: String? = null,
    val password: String? = null,
) {
    val isValid: Boolean
        get() = name == null && colorHex == null && cardNumber == null && password == null
}

sealed interface RegistrationValidation {
    data class Invalid(val errors: RegistrationErrors) : RegistrationValidation

    class Valid internal constructor(val value: ValidatedRegistration) : RegistrationValidation
}

/** メンバー登録の実行結果。UI層はこの結果だけを見てフォームの表示を切り替える。 */
sealed interface MemberRegistrationResult {
    data object Saved : MemberRegistrationResult

    data class Invalid(val errors: RegistrationErrors) : MemberRegistrationResult

    data object Failed : MemberRegistrationResult
}

/** Android APIに依存しないメンバー登録入力の検証器。 */
object RegistrationValidator {
    private val colorPattern = Regex("^#[0-9a-fA-F]{6}$")
    private val cardNumberPattern = Regex("^[0-9]+$")

    fun validate(form: RegistrationForm): RegistrationValidation {
        val normalizedName = form.name.trim()
        val normalizedColor = form.colorHex.trim()
        val normalizedCardNumber = form.cardNumber.trim()
        val errors = RegistrationErrors(
            name = if (normalizedName.isBlank()) "表示名を入力してください" else null,
            colorHex = if (!colorPattern.matches(normalizedColor)) "識別色は #RRGGBB 形式で入力してください" else null,
            cardNumber = when {
                normalizedCardNumber.isBlank() -> "カード番号を入力してください"
                !cardNumberPattern.matches(normalizedCardNumber) -> "カード番号は数字で入力してください"
                else -> null
            },
            password = if (form.password.isBlank()) "パスワードを入力してください" else null,
        )
        if (!errors.isValid) return RegistrationValidation.Invalid(errors)

        return RegistrationValidation.Valid(
            ValidatedRegistration(
                name = normalizedName,
                colorHex = normalizedColor.uppercase(),
                cardNumber = normalizedCardNumber,
                password = form.password,
            ),
        )
    }
}
