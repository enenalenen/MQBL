package com.example.mqbl.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 앱 내 내비게이션 대상(화면)을 정의하는 Sealed Class.
 * 각 화면은 고유한 경로(route), 제목(title), 아이콘(icon) 정보를 가집니다.
 */
sealed class Screen(
    val route: String,       // NavHost에서 화면을 식별하는 경로 문자열
    val title: String,       // 하단 네비게이션 바 등에 표시될 제목
    val icon: ImageVector    // 하단 네비게이션 바 등에 표시될 아이콘
) {
    object Notifications : Screen(
        route = "ble_screen",
        title = "알림",
        icon = Icons.Filled.Notifications
    )

    // --- TCP 탭 객체 삭제 ---

    object Settings : Screen(
        route = "settings_screen",
        title = "사용자 설정",
        icon = Icons.Filled.Settings
    )
}

/**
 * 하단 네비게이션 바에 표시될 화면 아이템 목록.
 * TCP 탭을 제거하여 2개의 탭만 표시합니다.
 */
val bottomNavItems = listOf(
    Screen.Notifications,
    Screen.Settings
)
