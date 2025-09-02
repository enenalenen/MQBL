package com.example.mqbl.ui.settings

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.mqbl.data.SettingsRepository
import com.example.mqbl.service.CommunicationService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "SettingsViewModel"

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepository = SettingsRepository(application)

    // --- ▼▼▼ CommunicationService 바인딩 로직 추가 ▼▼▼ ---
    private val _binder = MutableStateFlow<CommunicationService.LocalBinder?>(null)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            _binder.value = service as? CommunicationService.LocalBinder
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            _binder.value = null
        }
    }
    // --- ▲▲▲ 바인딩 로직 추가 끝 ▲▲▲ ---

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState = _uiState.asStateFlow()

    private val _customKeywords = MutableStateFlow("")
    val customKeywords = _customKeywords.asStateFlow()

    init {
        // --- ▼▼▼ 서비스 바인딩 시작 ▼▼▼ ---
        Intent(application, CommunicationService::class.java).also { intent ->
            application.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
        // --- ▲▲▲ 바인딩 시작 끝 ▲▲▲ ---

        settingsRepository.isBackgroundExecutionEnabledFlow
            .onEach { isEnabled ->
                _uiState.update { it.copy(isBackgroundExecutionEnabled = isEnabled) }
            }
            .launchIn(viewModelScope)

        settingsRepository.customKeywordsFlow
            .onEach { keywords ->
                _customKeywords.value = keywords
            }
            .launchIn(viewModelScope)
    }

    fun updateCustomKeywords(keywords: String) {
        _customKeywords.value = keywords
    }

    // --- ▼▼▼ saveCustomKeywords 함수 로직 수정 ▼▼▼ ---
    fun saveCustomKeywords() {
        viewModelScope.launch {
            // 1. 기존 로직: SharedPreferences에 저장
            settingsRepository.setCustomKeywords(_customKeywords.value)

            // --- ▼▼▼ 에러 수정: getService() 호출 제거 ---
            // LocalBinder가 getTcpUiStateFlow()를 직접 가지고 있으므로 바로 호출합니다.
            val tcpUiState = _binder.value?.getTcpUiStateFlow()?.first()

            // 2. 새로운 로직: TCP 서버로 업데이트 명령어 전송
            if (tcpUiState?.isConnected == true) {
                val command = "CMD_UPDATE_KEYWORDS:${_customKeywords.value}"
                // sendTcpMessage는 Service에 있으므로 여기서는 getService() 호출이 필요합니다.
                _binder.value?.getService()?.sendTcpMessage(command)
                Toast.makeText(getApplication(), "단어가 로컬 및 서버에 저장되었습니다.", Toast.LENGTH_SHORT).show()
                Log.d(TAG, "Keywords saved locally and sent to server: ${_customKeywords.value}")
            } else {
                Toast.makeText(getApplication(), "단어가 로컬에 저장되었습니다. (서버 연결 안됨)", Toast.LENGTH_LONG).show()
                Log.d(TAG, "Keywords saved locally. Server not connected.")
            }
        }
    }
    // --- ▲▲▲ 함수 로직 수정 끝 ▲▲▲ ---

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

    // --- ▼▼▼ onCleared에 서비스 unbind 로직 추가 ▼▼▼ ---
    override fun onCleared() {
        super.onCleared()
        try {
            getApplication<Application>().unbindService(serviceConnection)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Service already unbound.", e)
        }
        _binder.value = null
    }
    // --- ▲▲▲ unbind 로직 추가 끝 ▲▲▲ ---
}

