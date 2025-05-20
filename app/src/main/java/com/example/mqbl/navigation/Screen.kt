package com.example.mqbl.navigation // 패키지 경로는 실제 프로젝트 구조에 맞게 조정하세요.

import androidx.compose.material.icons.Icons
// import androidx.compose.material.icons.filled.Bluetooth // 이전 아이콘, 더 이상 사용 안 함
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Notifications // 새로운 알림 아이콘 import
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
    // --- 기존 BLE 탭 -> 알림 탭으로 변경 ---
    object Notifications : Screen( // 클래스/객체 이름도 의미에 맞게 변경 (선택 사항이지만 권장)
        route = "ble_screen",    // 라우트 경로는 기존 BLE 화면을 그대로 사용
        title = "알림",           // 화면 제목 변경
        icon = Icons.Filled.Notifications // 아이콘 변경
    )
    // --- 알림 탭 변경 끝 ---

    object Mqtt : Screen(
        route = "mqtt_screen",
        title = "MQTT",
        icon = Icons.Filled.CloudQueue
    )

    object Settings : Screen(
        route = "settings_screen",
        title = "사용자 설정",
        icon = Icons.Filled.Settings
    )
}

/**
 * 하단 네비게이션 바에 표시될 화면 아이템 목록.
 * 첫 번째 탭을 Notifications로 변경.
 */
val bottomNavItems = listOf(
    Screen.Notifications, // 변경된 Notifications 화면 객체 사용
    Screen.Mqtt,
    Screen.Settings
)
