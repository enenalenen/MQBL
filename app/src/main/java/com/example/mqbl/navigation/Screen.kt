package com.example.mqbl.navigation // 패키지 경로는 실제 프로젝트 구조에 맞게 조정하세요.

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth // BLE 아이콘 예시
import androidx.compose.material.icons.filled.CloudQueue // MQTT 아이콘 예시
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
    // BLE 화면 정의
    data object Ble : Screen(
        route = "ble_screen",    // BLE 화면의 고유 경로
        title = "BLE",           // 화면 제목
        icon = Icons.Filled.Bluetooth // Material 아이콘 사용
    )

    // MQTT 화면 정의
    data object Mqtt : Screen(
        route = "mqtt_screen",   // MQTT 화면의 고유 경로
        title = "MQTT",          // 화면 제목
        icon = Icons.Filled.CloudQueue // Material 아이콘 사용
    )
}

/**
 * 하단 네비게이션 바에 표시될 화면 아이템 목록.
 * 쉽게 반복하여 UI를 구성하기 위해 사용됩니다.
 */
val bottomNavItems = listOf(
    Screen.Ble,
    Screen.Mqtt
)