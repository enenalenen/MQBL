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
            val binder = service as? CommunicationService.LocalBinder
            _binder.value = binder
            binder?.getIsRecordingFlow()?.onEach { isRecording ->
                _uiState.update { it.copy(isRecording = isRecording) }
            }?.launchIn(viewModelScope)
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            _binder.value = null
        }
    }

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState = _uiState.asStateFlow()

    private val _customKeywords = MutableStateFlow("")
    val customKeywords = _customKeywords.asStateFlow()

    private val _serverIp = MutableStateFlow("")
    val serverIp = _serverIp.asStateFlow()
    private val _serverPort = MutableStateFlow("")
    val serverPort = _serverPort.asStateFlow()

    private val _esp32Ip = MutableStateFlow("")
    val esp32Ip = _esp32Ip.asStateFlow()
    private val _esp32Port = MutableStateFlow("")
    val esp32Port = _esp32Port.asStateFlow()

    init {
        Intent(application, CommunicationService::class.java).also { intent ->
            application.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }

        settingsRepository.isBackgroundExecutionEnabledFlow
            .onEach { isEnabled -> _uiState.update { it.copy(isBackgroundExecutionEnabled = isEnabled) } }
            .launchIn(viewModelScope)

        settingsRepository.customKeywordsFlow
            .onEach { keywords -> _customKeywords.value = keywords }
            .launchIn(viewModelScope)

        settingsRepository.tcpServerIpFlow
            .onEach { ip -> _serverIp.value = ip }
            .launchIn(viewModelScope)

        settingsRepository.tcpServerPortFlow
            .onEach { port -> _serverPort.value = port }
            .launchIn(viewModelScope)

        settingsRepository.esp32IpFlow
            .onEach { ip -> _esp32Ip.value = ip }
            .launchIn(viewModelScope)

        settingsRepository.esp32PortFlow
            .onEach { port -> _esp32Port.value = port }
            .launchIn(viewModelScope)
    }

    // --- Recording ---
    fun startRecording() { _binder.value?.getService()?.startAudioRecording() }
    fun stopRecording() { _binder.value?.getService()?.stopAndSaveAudioRecording() }

    // --- Keywords ---
    fun updateCustomKeywords(keywords: String) { _customKeywords.value = keywords }
    fun saveCustomKeywords() {
        viewModelScope.launch {
            settingsRepository.setCustomKeywords(_customKeywords.value)
            val isServerConnected = _binder.value?.getServerTcpUiStateFlow()?.first()?.isConnected ?: false
            if (isServerConnected) {
                val command = "CMD_UPDATE_KEYWORDS:${_customKeywords.value}"
                _binder.value?.getService()?.sendToServer(command)
                showToast("단어가 로컬 및 서버에 저장되었습니다.")
            } else {
                showToast("단어가 로컬에 저장되었습니다. (서버 연결 안됨)")
            }
        }
    }

    // --- PC Server Settings ---
    fun onServerIpChange(ip: String) { _serverIp.value = ip }
    fun onServerPortChange(port: String) { _serverPort.value = port }
    fun saveServerSettings() {
        viewModelScope.launch {
            settingsRepository.setTcpServerIp(_serverIp.value)
            settingsRepository.setTcpServerPort(_serverPort.value)
            showToast("PC 서버 주소가 저장되었습니다.")
        }
    }

    // --- ESP32 Settings ---
    fun onEsp32IpChange(ip: String) { _esp32Ip.value = ip }
    fun onEsp32PortChange(port: String) { _esp32Port.value = port }
    fun saveEsp32SettingsAndConnect() {
        viewModelScope.launch {
            settingsRepository.setEsp32Ip(_esp32Ip.value)
            settingsRepository.setEsp32Port(_esp32Port.value)
            showToast("ESP32 주소가 저장되었습니다.")
            connectToEsp32()
        }
    }

    // --- Service Actions ---
    fun connectToEsp32() {
        val port = _esp32Port.value.toIntOrNull()
        if (port == null) { showToast("ESP32 포트 번호가 올바르지 않습니다."); return }
        _binder.value?.getService()?.requestEsp32Connect(_esp32Ip.value, port)
    }

    fun disconnectFromEsp32() { _binder.value?.getService()?.requestEsp32Disconnect() }

    fun connectToServer() {
        val port = _serverPort.value.toIntOrNull()
        if (port == null) { showToast("PC 서버 포트 번호가 올바르지 않습니다."); return }
        _binder.value?.getService()?.requestServerTcpConnect(_serverIp.value, port)
    }

    fun disconnectFromServer() { _binder.value?.getService()?.requestServerTcpDisconnect() }
    fun sendVibrationValue(value: Int) { _binder.value?.getService()?.sendVibrationValueToEsp32(value) }
    fun sendCommandToEsp32(command: String) { _binder.value?.getService()?.sendCommandToEsp32(command) }

    // ▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼
    // 백그라운드 실행 토글 기능을 여기에 명확하게 다시 구현합니다.
    // ▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼
    fun toggleBackgroundExecution(enabled: Boolean) {
        viewModelScope.launch {
            // 1. 설정 저장
            settingsRepository.setBackgroundExecution(enabled)

            // 2. 서비스에 적절한 명령 전송
            val serviceIntent = Intent(getApplication(), CommunicationService::class.java).apply {
                action = if (enabled) {
                    CommunicationService.ACTION_START_FOREGROUND
                } else {
                    CommunicationService.ACTION_STOP_FOREGROUND
                }
            }
            getApplication<Application>().startService(serviceIntent)
        }
    }
    // ▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲

    private fun showToast(message: String){
        Toast.makeText(getApplication(), message, Toast.LENGTH_SHORT).show()
    }

    override fun onCleared() {
        super.onCleared()
        try {
            getApplication<Application>().unbindService(serviceConnection)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Service already unbound.", e)
        }
    }
}