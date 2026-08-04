import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.testing.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// git情報の取得は configuration cache と相性の良い providers.exec を使う。
// git が使えない・リポジトリでない等で失敗してもビルドを壊さないよう例外を握りつぶし "unknown" とする。
fun runGitCommand(vararg args: String): String? = try {
    providers.exec {
        commandLine(listOf("git") + args)
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim()
} catch (_: Exception) {
    null
}

// 実行中のAPKがどのコミットからビルドされたか診断ログ・設定画面で確認できるよう、
// 短縮コミットハッシュ(未コミット変更があれば "+dirty" を付与)とビルド時刻を BuildConfig に埋め込む。
val gitShortSha = runGitCommand("rev-parse", "--short", "HEAD")
val gitDirty = runGitCommand("status", "--porcelain")
val buildGitSha = when {
    gitShortSha.isNullOrBlank() -> "unknown"
    !gitDirty.isNullOrBlank() -> "$gitShortSha+dirty"
    else -> gitShortSha
}
val buildTimeStamp: String = ZonedDateTime.now(ZoneId.of("Asia/Tokyo"))
    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

android {
    namespace = "com.fallgist.nishinomiyalibrary"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fallgist.nishinomiyalibrary"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "GIT_SHA", "\"$buildGitSha\"")
        buildConfigField("String", "BUILD_TIME", "\"$buildTimeStamp\"")
    }

    signingConfigs {
        // 家族端末に直接インストールする非公開アプリのため、全ビルドで共通の固定debug鍵を使う。
        // これでCI実行ごとに鍵が変わらず、アンインストールせずに上書き更新できる。
        // debug鍵は本来秘匿情報ではなく、パスワードも慣例の "android" 固定。
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.coil)
    implementation(libs.coil.compose)
    implementation(libs.datastore.preferences)
    implementation(libs.hilt.android)
    implementation(libs.jsoup)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.room.ktx)
    implementation(libs.room.runtime)
    implementation(libs.security.crypto)
    implementation(libs.work.runtime.ktx)

    ksp(libs.hilt.compiler)
    ksp(libs.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.robolectric)
}

val liveReservationDiagnosticClass = "**/LiveReservationDiagnosticTest.class"
val liveReservationInspectionClass = "**/LiveReservationInspectionTest.class"
val liveReservationCancelDiagnosticClass = "**/LiveReservationCancelDiagnosticTest.class"
val liveReservationHideDiagnosticClass = "**/LiveReservationHideDiagnosticTest.class"
val liveReservationListInspectionClass = "**/LiveReservationListInspectionTest.class"
val liveLoanExtensionDiagnosticClass = "**/LiveLoanExtensionDiagnosticTest.class"

// 通常の unit test / CI は本番通信を行う診断クラスを発見対象から除外する。
tasks.withType<Test>().configureEach {
    if (name != "liveReservationDiagnostic") {
        exclude(liveReservationDiagnosticClass)
    }
    if (name != "liveReservationInspect") {
        exclude(liveReservationInspectionClass)
    }
    if (name != "liveReservationCancelDiagnostic") {
        exclude(liveReservationCancelDiagnosticClass)
    }
    if (name != "liveReservationHideDiagnostic") {
        exclude(liveReservationHideDiagnosticClass)
    }
    if (name != "liveReservationListInspect") {
        exclude(liveReservationListInspectionClass)
    }
    if (name != "liveLoanExtensionDiagnostic") {
        exclude(liveLoanExtensionDiagnosticClass)
    }
}

// このタスクだけが本番サイトへ予約 POST を行い得る。環境変数が一つでも不足すれば開始前に失敗する。
tasks.register<Test>("liveReservationDiagnostic") {
    group = "verification"
    description = "明示同意済みの場合だけ本番サイトへ予約診断を一度実行する（外部副作用あり）"
    val debugUnitTest = tasks.named<Test>("testDebugUnitTest")
    testClassesDirs = debugUnitTest.get().testClassesDirs
    classpath = debugUnitTest.get().classpath
    include(liveReservationDiagnosticClass)
    testLogging {
        showStandardStreams = true
    }
    doFirst {
        val required = mapOf(
            "LICSXP_LIVE_RESERVATION" to "YES_I_UNDERSTAND",
            "LICSXP_LIVE_RESERVATION_CONFIRM" to "RESERVE_ON_PRODUCTION",
            "LICSXP_CARD_NUMBER" to null,
            "LICSXP_PASSWORD" to null,
            "LICSXP_TILCOD" to null,
            "LICSXP_PICKUP_LIBRARY" to null,
        )
        val invalid = required.filter { (name, expected) ->
            val value = System.getenv(name)
            value.isNullOrBlank() || (expected != null && value != expected)
        }.keys
        check(invalid.isEmpty()) {
            "ライブ予約診断を開始しません。不足または不正な明示環境変数: ${invalid.joinToString()}"
        }
    }
}

// 確定POSTを行わず予約確認画面の構造だけを調べる（書き込み副作用なし）。
tasks.register<Test>("liveReservationInspect") {
    group = "verification"
    description = "確定POSTを行わず予約確認画面の構造だけを調べる（書き込み副作用なし）"
    val debugUnitTest = tasks.named<Test>("testDebugUnitTest")
    testClassesDirs = debugUnitTest.get().testClassesDirs
    classpath = debugUnitTest.get().classpath
    include(liveReservationInspectionClass)
    testLogging {
        showStandardStreams = true
    }
    doFirst {
        val required = mapOf(
            "LICSXP_LIVE_RESERVATION" to "YES_I_UNDERSTAND",
            "LICSXP_LIVE_RESERVATION_DRY_RUN" to "INSPECT_ONLY",
            "LICSXP_CARD_NUMBER" to null,
            "LICSXP_PASSWORD" to null,
            "LICSXP_TILCOD" to null,
            "LICSXP_PICKUP_LIBRARY" to null,
        )
        val invalid = required.filter { (name, expected) ->
            val value = System.getenv(name)
            value.isNullOrBlank() || (expected != null && value != expected)
        }.keys
        check(invalid.isEmpty()) {
            "予約確認dry-run診断を開始しません。不足または不正な明示環境変数: ${invalid.joinToString()}"
        }
    }
}

// このタスクだけが本番サイトへ予約取消POSTを行い得る。取消は1件だけで、ループや複数件取消は行わない。
tasks.register<Test>("liveReservationCancelDiagnostic") {
    group = "verification"
    description = "明示同意済みの場合だけ本番サイトで予約取消を一度だけ実行する（外部副作用あり）"
    val debugUnitTest = tasks.named<Test>("testDebugUnitTest")
    testClassesDirs = debugUnitTest.get().testClassesDirs
    classpath = debugUnitTest.get().classpath
    include(liveReservationCancelDiagnosticClass)
    testLogging {
        showStandardStreams = true
    }
    doFirst {
        val required = mapOf(
            "LICSXP_LIVE_RESERVATION" to "YES_I_UNDERSTAND",
            "LICSXP_LIVE_CANCEL_CONFIRM" to "CANCEL_ON_PRODUCTION",
            "LICSXP_CARD_NUMBER" to null,
            "LICSXP_PASSWORD" to null,
            "LICSXP_CANCEL_TILCOD" to null,
        )
        val invalid = required.filter { (name, expected) ->
            val value = System.getenv(name)
            value.isNullOrBlank() || (expected != null && value != expected)
        }.keys
        check(invalid.isEmpty()) {
            "ライブ予約取消診断を開始しません。不足または不正な明示環境変数: ${invalid.joinToString()}"
        }
    }
}

// 明示承認済みの取消済み1行だけを非表示にする、通常test/CIから隔離したライブ診断。
tasks.register<Test>("liveReservationHideDiagnostic") {
    group = "verification"
    description = "明示承認済みの取消済み予約1件について、非表示POSTと送信後消失だけを診断します。"
    val debugUnitTest = tasks.named<Test>("testDebugUnitTest")
    testClassesDirs = debugUnitTest.get().testClassesDirs
    classpath = debugUnitTest.get().classpath
    include(liveReservationHideDiagnosticClass)
    testLogging { showStandardStreams = true }
    doFirst {
        val required = mapOf(
            "LICSXP_LIVE_RESERVATION" to "YES_I_UNDERSTAND",
            "LICSXP_LIVE_HIDE_CONFIRM" to "HIDE_ON_PRODUCTION",
            "LICSXP_CARD_NUMBER" to null,
            "LICSXP_PASSWORD" to null,
            "LICSXP_HIDE_TILCOD" to null,
        )
        val invalid = required.filter { (name, expected) ->
            val value = System.getenv(name)
            value.isNullOrBlank() || (expected != null && value != expected)
        }.keys
        check(invalid.isEmpty()) { "ライブ非表示診断を開始できません。不足または不正な環境変数: ${invalid.joinToString()}" }
    }
}

// 取消POST・非表示POST・予約POSTのいずれも送らない、予約状況一覧の構造観測専用タスク（書き込み副作用なし）。
// 12回目のライブ実測で、取消後も一覧に対象が残り「取消」状態・「非表示」ボタンへ変わることが判明したため、
// その構造（状態文字列・ボタンのonclick関数名・セルのclass属性）を消える前に読み取るための専用タスク。
// 書き込みを一切行わないため、取消の同意フラグ(LICSXP_LIVE_CANCEL_CONFIRM)は要求しない。
tasks.register<Test>("liveReservationListInspect") {
    group = "verification"
    description = "予約状況一覧の構造だけを読み取る（書き込み副作用なし。取消・非表示・予約のPOSTを一切送らない）"
    val debugUnitTest = tasks.named<Test>("testDebugUnitTest")
    testClassesDirs = debugUnitTest.get().testClassesDirs
    classpath = debugUnitTest.get().classpath
    include(liveReservationListInspectionClass)
    testLogging {
        showStandardStreams = true
    }
    doFirst {
        val required = mapOf(
            "LICSXP_LIVE_RESERVATION" to "YES_I_UNDERSTAND",
            "LICSXP_LIST_INSPECT" to "INSPECT_ONLY",
            "LICSXP_CARD_NUMBER" to null,
            "LICSXP_PASSWORD" to null,
        )
        val invalid = required.filter { (name, expected) ->
            val value = System.getenv(name)
            value.isNullOrBlank() || (expected != null && value != expected)
        }.keys
        check(invalid.isEmpty()) {
            "予約状況一覧の読み取り専用観測を開始しません。不足または不正な明示環境変数: ${invalid.joinToString()}"
        }
    }
}

// このタスクだけが本番サイトで貸出延長POSTを1件だけ実行し得る(`docs/design/loan-extension.md` §8・§11-6)。
// 対象1件について1回だけ実行し、成否不明でも再送しない(既存のexactly-once実装をそのまま使う)。
tasks.register<Test>("liveLoanExtensionDiagnostic") {
    group = "verification"
    description = "明示同意済みの場合だけ本番サイトで貸出延長を一度だけ実行する（外部副作用あり）"
    val debugUnitTest = tasks.named<Test>("testDebugUnitTest")
    testClassesDirs = debugUnitTest.get().testClassesDirs
    classpath = debugUnitTest.get().classpath
    include(liveLoanExtensionDiagnosticClass)
    testLogging {
        showStandardStreams = true
    }
    doFirst {
        val required = mapOf(
            "LICSXP_LIVE_RESERVATION" to "YES_I_UNDERSTAND",
            "LICSXP_LIVE_EXTEND_CONFIRM" to "EXTEND_ON_PRODUCTION",
            "LICSXP_CARD_NUMBER" to null,
            "LICSXP_PASSWORD" to null,
            "LICSXP_TILCOD" to null,
        )
        val invalid = required.filter { (name, expected) ->
            val value = System.getenv(name)
            value.isNullOrBlank() || (expected != null && value != expected)
        }.keys
        check(invalid.isEmpty()) {
            "ライブ貸出延長診断を開始しません。不足または不正な明示環境変数: ${invalid.joinToString()}"
        }
    }
}
