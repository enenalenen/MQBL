package com.example.mqbl.ui.settings

import android.app.Application
import android.util.Log
import android.widget.Toast
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
import android.content.Intent

private const val TAG = "SettingsViewModel"

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepository = SettingsRepository(application)

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState = _uiState.asStateFlow()

    // --- ▼▼▼ 감지 단어 UI 상태 추가 ▼▼▼ ---
    private val _customKeywords = MutableStateFlow("")
    val customKeywords = _customKeywords.asStateFlow()
    // --- ▲▲▲ 감지 단어 UI 상태 추가 끝 ▲▲▲ ---

    init {
        settingsRepository.isBackgroundExecutionEnabledFlow
            .onEach { isEnabled ->
                _uiState.update { it.copy(isBackgroundExecutionEnabled = isEnabled) }
            }
            .launchIn(viewModelScope)

        // --- ▼▼▼ 저장된 단어 불러오기 추가 ▼▼▼ ---
        settingsRepository.customKeywordsFlow
            .onEach { keywords ->
                _customKeywords.value = keywords
            }
            .launchIn(viewModelScope)
        // --- ▲▲▲ 저장된 단어 불러오기 추가 끝 ▲▲▲ ---
    }

    // --- ▼▼▼ 감지 단어 관련 함수 추가 ▼▼▼ ---
    fun updateCustomKeywords(keywords: String) {
        _customKeywords.value = keywords
    }

    fun saveCustomKeywords() {
        viewModelScope.launch {
            settingsRepository.setCustomKeywords(_customKeywords.value)
            Toast.makeText(getApplication(), "감지 단어가 저장되었습니다.", Toast.LENGTH_SHORT).show()
        }
    }
    // --- ▲▲▲ 감지 단어 관련 함수 추가 끝 ▲▲▲ ---

    fun toggleBackgroundExecution(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setBackgroundExecution(enabled)
            Log.d(TAG, "Background execution preference set to: $enabled")

            val intent = Intent(getApplication(), CommunicationService::class.java).apply {
                action = if (enabled) {
                    CommunicationService.ACTION_START_FOREGROUND
                } else {
                    CommunicationService.ACTION_STOP_FOREGROUND
                }
            }
            getApplication<Application>().startService(intent)
            Log.d(TAG, "Sent action '${intent.action}' to CommunicationService.")
        }
    }
}
