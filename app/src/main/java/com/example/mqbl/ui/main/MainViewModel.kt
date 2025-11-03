package com.example.mqbl.ui.main

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.mqbl.service.CommunicationService
import com.example.mqbl.ui.tcp.TcpUiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

private const val TAG = "MainViewModel"

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _binder = MutableStateFlow<CommunicationService.LocalBinder?>(null)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.i(TAG, "CommunicationService Connected")
            _binder.value = service as? CommunicationService.LocalBinder
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "CommunicationService Disconnected unexpectedly")
            _binder.value = null
        }
    }

    // ▼▼▼ 추가/수정된 코드 (초기값 변경) ▼▼▼
    // MainUiState의 기본값을 MainScreen의 새 구조에 맞게 수정
    private val defaultMainUiState = MainUiState(status = "서비스 연결 대기 중...", isRecognitionActive = false)
    // ▲▲▲ 추가/수D(A)T(D)T(D)T(B)된 코드 ▲▲▲

    private val defaultDetectionLog = emptyList<DetectionEvent>()
    private val defaultCustomSoundLog = emptyList<CustomSoundEvent>()

    // ▼▼▼ 추가/수정된 코드 (복합 상태 로직) ▼▼▼
    val uiState: StateFlow<MainUiState> = _binder.flatMapLatest { binder ->
        if (binder == null) {
            return@flatMapLatest flowOf(defaultMainUiState)
        }

        // 서비스로부터 3개의 상태 플로우를 가져옴
        val mainUiStateFlow = binder.getMainUiStateFlow()
        val serverTcpUiStateFlow = binder.getServerTcpUiStateFlow()
        val phoneMicModeStateFlow = binder.getIsPhoneMicModeEnabledFlow() // 폰 마이크 모드 상태

        // 3개의 플로우를 결합하여 최종 UI 상태를 만듦
        combine(
            mainUiStateFlow,
            serverTcpUiStateFlow,
            phoneMicModeStateFlow
        ) { mainState, serverState, isPhoneMicMode ->

            val isServerConnected = serverState.isConnected
            val isEspConnected = mainState.isEspConnected

            when {
                // 1. 스마트폰 마이크 모드가 켜져 있고 서버에 연결된 경우
                isPhoneMicMode && isServerConnected -> {
                    mainState.copy(
                        status = "음성 인식 활성화 : 스마트폰 모드",
                        isRecognitionActive = true
                    )
                }
                // 2. 넥밴드 모드(폰 마이크 OFF)이고, 넥밴드와 서버가 모두 연결된 경우
                !isPhoneMicMode && isEspConnected && isServerConnected -> {
                    mainState.copy(
                        status = "음성 인식 활성화 : 넥밴드 모드",
                        isRecognitionActive = true
                    )
                }
                // 3. 넥밴드 모드인데 서버가 연결되지 않은 경우
                !isPhoneMicMode && isEspConnected && !isServerConnected -> {
                    mainState.copy(
                        status = "음성 인식 비활성화 (서버 연결 안됨)",
                        isRecognitionActive = false
                    )
                }
                // 4. 스마트폰 마이크 모드인데 서버가 연결되지 않은 경우
                isPhoneMicMode && !isServerConnected -> {
                    mainState.copy(
                        status = "음성 인식 비활성화 (서버 연결 안됨)",
                        isRecognitionActive = false
                    )
                }
                // 5. 그 외 모든 경우 (넥밴드 연결 중, 연결 끊김 등)
                else -> {
                    mainState.copy(
                        status = "음성 인식 비활성화",
                        isRecognitionActive = false
                    )
                }
            }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = defaultMainUiState
    )
    // ▲▲▲ 추가/수정된 코드 ▲▲▲

    val detectionEventLog: StateFlow<List<DetectionEvent>> = _binder.flatMapLatest { binder ->
        binder?.getDetectionLogFlow() ?: flowOf(defaultDetectionLog)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = defaultDetectionLog
    )

    val customSoundEventLog: StateFlow<List<CustomSoundEvent>> = _binder.flatMapLatest { binder ->
        binder?.getCustomSoundEventLogFlow() ?: flowOf(defaultCustomSoundLog)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = defaultCustomSoundLog
    )


    init {
        Log.d(TAG, "ViewModel Init: Binding to CommunicationService...")
        Intent(application, CommunicationService::class.java).also { intent ->
            application.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onCleared() {
        super.onCleared()
        Log.i(TAG, "ViewModel Cleared: Unbinding from CommunicationService...")
        try {
            getApplication<Application>().unbindService(serviceConnection)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Attempted to unbind ServiceConnection that was not registered.", e)
        }
        _binder.value = null
    }
}