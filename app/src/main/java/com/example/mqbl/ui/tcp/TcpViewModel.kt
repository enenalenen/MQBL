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

    // --- ▼▼▼ getTcpUiStateFlow -> getServerTcpUiStateFlow로 수정 ▼▼▼ ---
    val tcpUiState: StateFlow<TcpUiState> = _binder.flatMapLatest { binder ->
        binder?.getServerTcpUiStateFlow() ?: flowOf(defaultTcpUiState)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = defaultTcpUiState
    )
    // --- ▲▲▲ 수정 끝 ▲▲▲ ---

    init {
        Intent(application, CommunicationService::class.java).also { intent ->
            application.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    // --- ▼▼▼ connect 함수가 IP와 Port를 받도록 수정하고, 서비스 함수 호출 이름 변경 ▼▼▼ ---
    fun connect(ip: String, port: Int) {
        if (ip.isBlank() || port <= 0) {
            Log.w(TAG, "Invalid IP or Port for connection.")
            return
        }
        Log.i(TAG, "UI Action: Request Server TCP connect to $ip:$port")
        _binder.value?.getService()?.requestServerTcpConnect(ip, port)
    }

    fun disconnect() {
        Log.i(TAG, "UI Action: Request Server TCP disconnect")
        _binder.value?.getService()?.requestServerTcpDisconnect()
    }

    fun sendMessage(message: String) {
        _binder.value?.getService()?.sendToServer(message)
    }
    // --- ▲▲▲ 수정 끝 ▲▲▲ ---

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