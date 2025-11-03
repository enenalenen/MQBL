package com.example.mqbl.ui.settings

/**
 * 설정 화면 전체의 UI 상태를 나타내는 데이터 클래스.
 */
data class SettingsUiState(
    val isBackgroundExecutionEnabled: Boolean = true,
    val isRecording: Boolean = false,
    val isPhoneMicModeEnabled: Boolean = false,

    // ▼▼▼ 추가/수정된 코드 (마이크 민감도 UI 상태) ▼▼▼
    val micSensitivity: Int = 5 // 1(둔감) ~ 10(민감), 기본값 5
    // ▲▲▲ 추가/수정된 코드 ▲▲▲
)