package com.fallgist.nishinomiyalibrary.ui.version

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fallgist.nishinomiyalibrary.BuildConfig
import com.fallgist.nishinomiyalibrary.R
import com.fallgist.nishinomiyalibrary.ui.components.ScreenTopBar
import com.fallgist.nishinomiyalibrary.ui.theme.LocalAppColors

/**
 * バージョン情報ページ（docs/design/version-info-page.md 確定仕様）。
 * 上部にアプリ名とversionNameだけを出し（ビルド番号・コミットの識別子は出さない）、
 * 下部に更新履歴を新しい順に並べる。通信は行わない。
 */
@Composable
fun VersionInfoScreen(
    onOpenMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.paper)
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenTopBar(title = "バージョン情報", onOpenMenu = onOpenMenu)

        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
            Text(
                text = stringResource(R.string.app_name),
                color = colors.ink,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Ver.${BuildConfig.VERSION_NAME}",
                color = colors.ink2,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            Text(
                text = "更新履歴",
                color = colors.ink2,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            ReleaseNotes.all.forEach { note ->
                Text(
                    text = "Ver.${note.version}（${note.date}）",
                    color = colors.ink,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = note.body,
                    color = colors.ink2,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 2.dp, bottom = 14.dp),
                )
            }
        }
    }
}
