package com.example.mqbl.ui.tcp

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
import kotlinx.coroutines.flow.*

private const val TAG = "TcpViewModel"

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TcpViewModel(application: Application) : AndroidViewModel(application) {

    private val _binder = MutableStateFlow<CommunicationService.LocalBinder?>(null)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            _binder.value = service as? CommunicationService.LocalBinder
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            _binder.value = null
        }
    }

    private val defaultTcpUiState = TcpUiState(connectionStatus = "서비스 연결 대기 중...")

    val tcpUiState: StateFlow<TcpUiState> = _binder.flatMapLatest { binder ->
        binder?.getTcpUiStateFlow() ?: flowOf(defaultTcpUiState)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = defaultTcpUiState
    )

    // ▼▼▼ IP/Port StateFlow 및 update 함수 제거됨 ▼▼▼

    init {
        Intent(application, CommunicationService::class.java).also { intent ->
            application.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    fun connect() {
        // ▼▼▼ 여기에 IP 주소와 포트를 직접 입력하세요 ▼▼▼
        val hardcodedIp = "192.168.0.5"
        val hardcodedPort = 12345
        // ▲▲▲ 여기에 IP 주소와 포트를 직접 입력하세요 ▲▲▲

        Log.i(TAG, "UI Action: Request TCP connect to hardcoded address $hardcodedIp:$hardcodedPort")
        _binder.value?.getService()?.requestTcpConnect(hardcodedIp, hardcodedPort)
    }

    fun disconnect() {
        Log.i(TAG, "UI Action: Request TCP disconnect")
        _binder.value?.getService()?.requestTcpDisconnect()
    }

    // ▼▼▼ sendMessage 함수는 내부 통신용으로 유지 ▼▼▼
    fun sendMessage(message: String) {
        _binder.value?.getService()?.sendTcpMessage(message)
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
