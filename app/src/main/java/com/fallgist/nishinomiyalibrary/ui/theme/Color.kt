package com.fallgist.nishinomiyalibrary.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * home.html モックのカラートークンをそのままアプリ全体で共有するための拡張パレット。
 * Material3のColorSchemeに収まらない「補助テキスト」「罫線」「警告背景」などを保持する。
 */
@Immutable
data class AppColors(
    val paper: Color,
    val card: Color,
    val ink: Color,
    val ink2: Color,
    val line: Color,
    val green: Color,
    val greenBg: Color,
    val greenInk: Color,
    val alert: Color,
    val alertBg: Color,
    val chipBg: Color,
)

val LightAppColors = AppColors(
    paper = Color(0xFFFAF7F1),
    card = Color(0xFFFFFFFF),
    ink = Color(0xFF26221B),
    ink2 = Color(0xFF6E675C),
    line = Color(0xFFE7E1D6),
    green = Color(0xFF2E6B4F),
    greenBg = Color(0xFFE3EFE7),
    greenInk = Color(0xFF1E4A36),
    alert = Color(0xFFB3452E),
    alertBg = Color(0xFFF7E7E2),
    chipBg = Color(0xFFEFEAE0),
)

val DarkAppColors = AppColors(
    paper = Color(0xFF16140F),
    card = Color(0xFF211E17),
    ink = Color(0xFFEDE7DB),
    ink2 = Color(0xFFA39A8A),
    line = Color(0xFF37332A),
    green = Color(0xFF7FB89A),
    greenBg = Color(0xFF24382E),
    greenInk = Color(0xFFBFE0CC),
    alert = Color(0xFFE08A75),
    alertBg = Color(0xFF42281F),
    chipBg = Color(0xFF2C2921),
)

val LocalAppColors = staticCompositionLocalOf { LightAppColors }
