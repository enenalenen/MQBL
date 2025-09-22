package com.example.mqbl.ui.settings

/**
 * 설정 화면 전체의 UI 상태를 나타내는 데이터 클래스.
 */
data class SettingsUiState(
    val isBackgroundExecutionEnabled: Boolean = true,
    val isRecording: Boolean = false // --- ▼▼▼ 녹음 상태 플래그 추가 ▼▼▼ ---
)
