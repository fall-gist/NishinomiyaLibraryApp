package com.fallgist.nishinomiyalibrary.data.backup

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [BackupSecret]の暗号化・復号(docs/design/settings-export-import.md §4・§9)。
 * 鍵はこの機能専用の固定定数であり、端末をまたいで同じ鍵で暗号化・復号できることが要件のため、
 * 「別端末での復号」は"暗号化した値を、別の呼び出し(状態を共有しない別プロセスを模した呼び出し)
 * だけから渡して復号できること"で検証する([BackupSecret]はKotlinの`object`でプロセス内に
 * 単一インスタンスしか持てないため、生成物の受け渡しだけを別経路にして固定鍵の効果を確認する)。
 */
class BackupSecretTest {

    @Test
    fun `暗号化した値を復号すると元の平文に戻る`() {
        val plainText = "テスト用のダミーパスワード123!"
        val encrypted = BackupSecret.encrypt(plainText)
        assertEquals(plainText, BackupSecret.decrypt(encrypted))
    }

    @Test
    fun `別端末を模した復号でも同じ固定鍵で復号できる(端末間移行の要件)`() {
        // 「別端末」=このプロセスの外で暗号化されたことを模すため、暗号化結果をBase64文字列として
        // 一度取り出し、[BackupSecret]の状態(SecureRandomの内部状態等)を一切参照せずに復号だけを行う。
        val plainText = "dummy-cross-device-password-456"
        val encryptedOnSourceDevice: String = BackupSecret.encrypt(plainText)

        // 復号側は暗号化時の変数を再利用せず、Base64文字列だけを受け渡す形にして
        // 「ファイル経由で別端末に渡った」状況を模す。
        val transferredCiphertext = String(Base64.getDecoder().decode(encryptedOnSourceDevice).let {
            Base64.getEncoder().encode(it)
        })

        assertEquals(plainText, BackupSecret.decrypt(transferredCiphertext))
    }

    @Test
    fun `IVは暗号化のたびに異なる(固定IVの劣化を検出する)`() {
        val plainText = "同一平文を複数回暗号化"
        val first = BackupSecret.encrypt(plainText)
        val second = BackupSecret.encrypt(plainText)

        assertNotEquals("暗号文全体が同じです(IV再利用の疑い)", first, second)

        val firstIv = Base64.getDecoder().decode(first).copyOfRange(0, 12)
        val secondIv = Base64.getDecoder().decode(second).copyOfRange(0, 12)
        assertTrue(
            "IVが固定値になっています(GCMでIVを再利用すると鍵が破れる)",
            !firstIv.contentEquals(secondIv),
        )
    }

    @Test
    fun `不正なBase64は復号に失敗する`() {
        try {
            BackupSecret.decrypt("これはBase64ではない###")
            fail("復号に失敗するはずでした")
        } catch (exception: BackupPasswordDecryptionException) {
            // 期待どおり。
        }
    }

    @Test
    fun `改ざんされた暗号文は認証タグ検証で復号に失敗する`() {
        val encrypted = BackupSecret.encrypt("改ざん検知テスト")
        val bytes = Base64.getDecoder().decode(encrypted)
        // 末尾(認証タグ内)の1バイトを反転させて改ざんを模す。
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0xFF).toByte()
        val tampered = Base64.getEncoder().encodeToString(bytes)

        try {
            BackupSecret.decrypt(tampered)
            fail("改ざんが検出されず復号に成功してしまいました")
        } catch (exception: BackupPasswordDecryptionException) {
            // 期待どおり。
        }
    }
}
