package com.example.mqbl.ui.settings

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.mqbl.data.SettingsRepository
import com.example.mqbl.service.CommunicationService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "SettingsViewModel"

/**
 * 설정 화면의 UI 상태와 비즈니스 로직을 관리하는 ViewModel.
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepository = SettingsRepository(application)

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState = _uiState.asStateFlow()

    init {
        // Repository의 Flow를 구독하여 UI 상태를 최신으로 유지
        settingsRepository.isBackgroundExecutionEnabledFlow
            .onEach { isEnabled ->
                _uiState.update { it.copy(isBackgroundExecutionEnabled = isEnabled) }
            }
            .launchIn(viewModelScope)
    }

    /**
     * 백그라운드 실행 토글 상태를 변경하고 서비스에 명령을 보냅니다.
     */
    fun toggleBackgroundExecution(enabled: Boolean) {
        viewModelScope.launch {
            // 1. 설정 값을 저장합니다.
            settingsRepository.setBackgroundExecution(enabled)
            Log.d(TAG, "Background execution preference set to: $enabled")

            // 2. 서비스에 적절한 명령을 보냅니다.
            val intent = Intent(getApplication(), CommunicationService::class.java).apply {
                action = if (enabled) {
                    CommunicationService.ACTION_START_FOREGROUND
                } else {
                    CommunicationService.ACTION_STOP_FOREGROUND
                }
            }
            // 서비스를 시작하여 Intent를 전달합니다.
            // 안드로이드 8.0 이상에서는 백그라운드에서 서비스를 시작할 때 startForegroundService를 사용해야 하지만,
            // 여기서는 UI가 보이는 상태에서 호출되므로 startService도 안전합니다.
            getApplication<Application>().startService(intent)
            Log.d(TAG, "Sent action '${intent.action}' to CommunicationService.")
        }
    }
}
