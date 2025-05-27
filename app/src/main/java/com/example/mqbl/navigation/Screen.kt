package com.example.mqbl.navigation // 패키지 경로는 실제 프로젝트 구조에 맞게 조정하세요.

import androidx.compose.material.icons.Icons
// import androidx.compose.material.icons.filled.CloudQueue // 이전 MQTT 아이콘
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi // TCP/IP용 아이콘 예시
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
        route = "ble_screen", // "알림" 탭은 기존 BLE 화면 경로 유지
        title = "알림",
        icon = Icons.Filled.Notifications
    )

    // --- 기존 MQTT 탭 -> TCP 탭으로 변경 ---
    object Tcp : Screen( // 클래스/객체 이름 변경: Mqtt -> Tcp
        route = "tcp_screen",    // 라우트 경로 변경: mqtt_screen -> tcp_screen
        title = "TCP",           // 화면 제목 변경: MQTT -> TCP
        icon = Icons.Filled.Wifi // 아이콘 변경 (네트워크 또는 소켓 관련 아이콘)
    )
    // --- TCP 탭 변경 끝 ---

    object Settings : Screen(
        route = "settings_screen",
        title = "사용자 설정",
        icon = Icons.Filled.Settings
    )
}

/**
 * 하단 네비게이션 바에 표시될 화면 아이템 목록.
 * 두 번째 탭을 Tcp로 변경.
 */
val bottomNavItems = listOf(
    Screen.Notifications,
    Screen.Tcp, // 변경된 Tcp 화면 객체 사용
    Screen.Settings
)
