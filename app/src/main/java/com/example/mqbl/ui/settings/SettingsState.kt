package com.example.mqbl.ui.settings

// (파일 이름은 SettingsState.kt 이지만, ViewModel에서 SettingsUiState를 사용하므로
// data class 이름은 SettingsUiState라고 가정합니다.)

data class SettingsUiState(
    // --- 기존 프로퍼티 ---
    val isBackgroundExecutionEnabled: Boolean = true,
    val isPhoneMicModeEnabled: Boolean = false,
    val isRecording: Boolean = false,
    val micSensitivity: Int = 5, // VAD 민감도 (1~10)

    // --- ▼▼▼ 신규 추가 (진동 설정) ▼▼▼ ---
    val vibrationWarningLeft: Int = 200,  // 경고 (좌) (0~255)
    val vibrationWarningRight: Int = 200, // 경고 (우) (0~255)
    val vibrationVoiceLeft: Int = 100,      // 음성 (좌) (0~255)
    val vibrationVoiceRight: Int = 100    // 음성 (우) (0~255)
    // --- ▲▲▲ 신규 추가 ▲▲▲ ---
)