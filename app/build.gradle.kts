import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.fallgist.nishinomiyalibrary"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fallgist.nishinomiyalibrary"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
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

// 通常の unit test / CI は本番通信を行う診断クラスを発見対象から除外する。
tasks.withType<Test>().configureEach {
    if (name != "liveReservationDiagnostic") {
        exclude(liveReservationDiagnosticClass)
    }
    if (name != "liveReservationInspect") {
        exclude(liveReservationInspectionClass)
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
