package com.fallgist.nishinomiyalibrary.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** パスワード専用の暗号化ストア。Roomや通常の設定ストアには保存しない。 */
class CredentialStore(context: Context) {
    private val applicationContext = context.applicationContext

    private val preferences: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            applicationContext,
            PREFERENCES_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun savePassword(memberId: Long, password: String) {
        check(preferences.edit().putString(passwordKey(memberId), password).commit()) {
            "パスワードを保存できませんでした"
        }
    }

    fun getPassword(memberId: Long): String? = preferences.getString(passwordKey(memberId), null)

    fun delete(memberId: Long) {
        check(preferences.edit().remove(passwordKey(memberId)).commit()) {
            "パスワードを削除できませんでした"
        }
    }

    /**
     * 保存済みの全パスワードを消去する。設定インポート(全置換)専用
     * (docs/design/settings-export-import.md §6.2)。
     *
     * インポートは[MemberDao.clearAll]を直接呼ぶため、通常のメンバー削除経路
     * ([FamilyRepositoryImpl]の`credentialStore.delete`)を通らない。消去しないと、
     * 同じidを持つ別人のパスワードが残ったまま新しいメンバーへ結び付いてしまう。
     */
    fun clearAll() {
        check(preferences.edit().clear().commit()) {
            "パスワードを全消去できませんでした"
        }
    }

    private fun passwordKey(memberId: Long): String = "pw_member_$memberId"

    internal companion object {
        const val PREFERENCES_FILE_NAME = "encrypted_credentials"
    }
}
