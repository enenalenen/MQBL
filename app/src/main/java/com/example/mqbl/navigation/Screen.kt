package com.example.mqbl.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(
    val route: String,
    val title: String,
    val icon: ImageVector
) {
    object Notifications : Screen(
        route = "main_screen", // 라우트 이름 변경
        title = "알림",
        icon = Icons.Filled.Notifications
    )

    object Settings : Screen(
        route = "settings_screen",
        title = "사용자 설정",
        icon = Icons.Filled.Settings
    )
}

val bottomNavItems = listOf(
    Screen.Notifications,
    Screen.Settings
)