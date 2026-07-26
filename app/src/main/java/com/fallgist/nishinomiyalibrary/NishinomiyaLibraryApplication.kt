package com.fallgist.nishinomiyalibrary

import android.app.Application
import com.fallgist.nishinomiyalibrary.data.diagnostics.DiagnosticLog
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class NishinomiyaLibraryApplication : Application() {
    @Inject
    lateinit var diagnosticLog: DiagnosticLog

    override fun onCreate() {
        super.onCreate()
        // 実行中のAPKがどのコミットからビルドされたかを診断ログ・設定画面で確認できるようにする。
        diagnosticLog.setBuildIdentity(
            gitSha = BuildConfig.GIT_SHA,
            buildTime = BuildConfig.BUILD_TIME,
            versionName = BuildConfig.VERSION_NAME,
        )
    }
}
