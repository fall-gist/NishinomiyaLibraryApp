package com.fallgist.nishinomiyalibrary.ui.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.fallgist.nishinomiyalibrary.ui.home.HomeScreen
import com.fallgist.nishinomiyalibrary.ui.home.HomeUiState
import com.fallgist.nishinomiyalibrary.ui.member.MemberRegistrationResult
import com.fallgist.nishinomiyalibrary.ui.member.RegistrationForm
import com.fallgist.nishinomiyalibrary.ui.theme.LocalAppColors

/** 下部タブの5構成。ホーム以外は本増分では準備中のプレースホルダ。 */
private enum class LibraryTab(val label: String, val emoji: String) {
    HOME("ホーム", "🏠"),
    SEARCH("さがす", "🔍"),
    SHELF("本棚", "📚"),
    CALENDAR("カレンダー", "📅"),
    SETTINGS("設定", "⚙️"),
}

@Composable
fun LibraryApp(
    state: HomeUiState,
    onSelectMember: (Long?) -> Unit,
    onManualSync: () -> Unit,
    onRegister: suspend (RegistrationForm) -> MemberRegistrationResult,
) {
    val colors = LocalAppColors.current
    val tabs = LibraryTab.entries
    var selectedIndex by rememberSaveable { mutableStateOf(0) }

    Scaffold(
        containerColor = colors.paper,
        bottomBar = {
            NavigationBar(containerColor = colors.card) {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selectedIndex == index,
                        onClick = { selectedIndex = index },
                        icon = { Text(tab.emoji, fontSize = 16.sp) },
                        label = { Text(tab.label, fontSize = 10.sp) },
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (tabs[selectedIndex]) {
                LibraryTab.HOME -> HomeScreen(
                    state = state,
                    onSelectMember = onSelectMember,
                    onManualSync = onManualSync,
                    onRegister = onRegister,
                    modifier = Modifier.fillMaxSize(),
                )

                else -> Placeholder(tabs[selectedIndex].label)
            }
        }
    }
}

@Composable
private fun Placeholder(label: String) {
    val colors = LocalAppColors.current
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "$label は準備中です",
            color = colors.ink2,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
