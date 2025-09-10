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

    private val settingsRepository = SettingsRepository.getInstance(application)

    private val _binder = MutableStateFlow<CommunicationService.LocalBinder?>(null)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            _binder.value = service as? CommunicationService.LocalBinder
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            _binder.value = null
        }
    }

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState = _uiState.asStateFlow()

    private val _customKeywords = MutableStateFlow("")
    val customKeywords = _customKeywords.asStateFlow()

    private val _tcpServerIp = MutableStateFlow("")
    val tcpServerIp = _tcpServerIp.asStateFlow()

    private val _tcpServerPort = MutableStateFlow("")
    val tcpServerPort = _tcpServerPort.asStateFlow()


    init {
        Intent(application, CommunicationService::class.java).also { intent ->
            application.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }

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

        settingsRepository.tcpServerIpFlow
            .onEach { ip ->
                _tcpServerIp.value = ip
            }
            .launchIn(viewModelScope)

        settingsRepository.tcpServerPortFlow
            .onEach { port ->
                _tcpServerPort.value = port
            }
            .launchIn(viewModelScope)
    }

    fun onTcpServerIpChange(ip: String) {
        _tcpServerIp.value = ip
    }

    fun onTcpServerPortChange(port: String) {
        _tcpServerPort.value = port
    }

    fun saveTcpSettings() {
        viewModelScope.launch {
            settingsRepository.setTcpServerIp(_tcpServerIp.value)
            settingsRepository.setTcpServerPort(_tcpServerPort.value)
            Toast.makeText(getApplication(), "TCP/IP 설정이 저장되었습니다.", Toast.LENGTH_SHORT).show()
        }
    }


    fun updateCustomKeywords(keywords: String) {
        _customKeywords.value = keywords
    }

    fun saveCustomKeywords() {
        viewModelScope.launch {
            settingsRepository.setCustomKeywords(_customKeywords.value)

            val tcpUiState = _binder.value?.getTcpUiStateFlow()?.first()

            if (tcpUiState?.isConnected == true) {
                val command = "CMD_UPDATE_KEYWORDS:${_customKeywords.value}"
                _binder.value?.getService()?.sendTcpMessage(command)
                Toast.makeText(getApplication(), "단어가 로컬 및 서버에 저장되었습니다.", Toast.LENGTH_SHORT).show()
                Log.d(TAG, "Keywords saved locally and sent to server: ${_customKeywords.value}")
            } else {
                Toast.makeText(getApplication(), "단어가 로컬에 저장되었습니다. (서버 연결 안됨)", Toast.LENGTH_LONG).show()
                Log.d(TAG, "Keywords saved locally. Server not connected.")
            }
        }
    }

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

    override fun onCleared() {
        super.onCleared()
        try {
            getApplication<Application>().unbindService(serviceConnection)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Service already unbound.", e)
        }
        _binder.value = null
    }
}

