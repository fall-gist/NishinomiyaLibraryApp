package com.fallgist.nishinomiyalibrary.data.backup

import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * バックアップJSONの `password` 欄だけを対象にした暗号化・復号(docs/design/settings-export-import.md §4)。
 *
 * **鍵は秘匿ではない。** このリポジトリはPUBLICであり、鍵をソースに書く以上、誰でも参照できる。
 * 目的は「クラウドやDownloadフォルダに残ったファイルを別のアプリやプレビューが偶然読んでしまう」ことを
 * 防ぐことであり、鍵を使って意図的に復号する行為はその「偶然」に当たらない。詳細はdocs §4参照。
 *
 * - アルゴリズム: AES-256-GCM。認証タグ128bit
 * - 鍵: この機能専用に新規生成した32バイト定数(他用途の鍵を流用しない)
 * - IV: 暗号化のたびに[SecureRandom]で12バイト生成し、暗号文の先頭に連結してからBase64化する。
 *   GCMでIVを再利用すると鍵が破れるため、固定値にしない
 */
object BackupSecret {
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val GCM_IV_LENGTH_BYTES = 12

    /**
     * この機能専用のAES-256鍵。§4の合意どおり、公開リポジトリ上で誰でも参照できることを
     * 前提とした設計であり、難読化や隠蔽は行わない。他用途の鍵と共用しないこと。
     */
    private val KEY_BYTES = byteArrayOf(
        0xB1.toByte(), 0x33, 0x15, 0x04, 0xE1.toByte(), 0xDE.toByte(), 0xA9.toByte(), 0xFD.toByte(),
        0x77, 0xBB.toByte(), 0xA6.toByte(), 0x05, 0x20, 0x27, 0x05, 0xEB.toByte(),
        0x58, 0x4E, 0x97.toByte(), 0x41, 0x44, 0x6A, 0x30, 0x63,
        0x66, 0x72, 0x67, 0xBF.toByte(), 0xC8.toByte(), 0x4B, 0x33, 0x3F,
    )

    private val secretKey = SecretKeySpec(KEY_BYTES, "AES")
    private val secureRandom = SecureRandom()

    /** 平文パスワードを `Base64(IV || ciphertext || tag)` へ暗号化する。 */
    fun encrypt(plainText: String): String {
        val iv = ByteArray(GCM_IV_LENGTH_BYTES).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + cipherText)
    }

    /**
     * [encrypt]の逆変換。復号に失敗した場合は[BackupPasswordDecryptionException]を投げる。
     * 呼び出し側は、そのメンバーのパスワードだけを未設定として扱い、インポート全体を
     * 失敗させないこと(§4)。
     */
    fun decrypt(encoded: String): String {
        val combined = try {
            Base64.getDecoder().decode(encoded)
        } catch (exception: IllegalArgumentException) {
            throw BackupPasswordDecryptionException(exception)
        }
        if (combined.size <= GCM_IV_LENGTH_BYTES) {
            throw BackupPasswordDecryptionException(null)
        }
        val iv = combined.copyOfRange(0, GCM_IV_LENGTH_BYTES)
        val cipherText = combined.copyOfRange(GCM_IV_LENGTH_BYTES, combined.size)
        return try {
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (exception: GeneralSecurityException) {
            throw BackupPasswordDecryptionException(exception)
        }
    }
}

/** パスワード欄の復号に失敗したことを表す。呼び出し側はそのメンバーだけを未設定として扱う。 */
class BackupPasswordDecryptionException(cause: Throwable?) : Exception("パスワードを復号できませんでした", cause)
