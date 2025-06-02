package com.example.mqbl.ui.wifidirect

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.wifi.p2p.WifiP2pDevice
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.mqbl.service.CommunicationService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

private const val TAG = "WifiDirectViewModel"

@OptIn(ExperimentalCoroutinesApi::class)
class WifiDirectViewModel(application: Application) : AndroidViewModel(application) {

    private val _binder = MutableStateFlow<CommunicationService.LocalBinder?>(null)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.i(TAG, "CommunicationService Connected for WifiDirectViewModel")
            _binder.value = service as? CommunicationService.LocalBinder
            // 서비스 연결 시 초기 상태 업데이트 요청
            _binder.value?.getService()?.refreshWifiDirectState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "CommunicationService Disconnected unexpectedly for WifiDirectViewModel")
            _binder.value = null
            _wifiDirectUiStateInternal.update {
                it.copy(
                    statusText = "Wi-Fi Direct: 서비스 연결 끊김",
                    errorMessage = "서비스 연결이 예기치 않게 종료되었습니다."
                )
            }
        }
    }

    // Service로부터 받는 상태를 위한 내부 StateFlow
    private val _wifiDirectUiStateInternal = MutableStateFlow(WifiDirectUiState())
    val wifiDirectUiState: StateFlow<WifiDirectUiState> = _wifiDirectUiStateInternal.asStateFlow()

    init {
        Log.d(TAG, "ViewModel Init: Binding to CommunicationService...")
        Intent(application, CommunicationService::class.java).also { intent ->
            application.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }

        // Service의 WifiDirectUiStateFlow를 구독하여 ViewModel 내부 StateFlow 업데이트
        viewModelScope.launch {
            _binder.flatMapLatest { binder ->
                binder?.getWifiDirectUiStateFlow()
                    ?: flowOf(WifiDirectUiState(statusText = "Wi-Fi Direct: 서비스 연결 안됨"))
            }.collect { serviceState ->
                _wifiDirectUiStateInternal.value = serviceState
            }
        }
    }

    fun discoverPeers() {
        Log.i(TAG, "UI Action: Request Wi-Fi Direct discover peers")
        val service = _binder.value?.getService()
        if (service != null) {
            service.discoverWifiDirectPeers()
        } else {
            Log.e(TAG, "Cannot discover peers: Service not bound.")
            _wifiDirectUiStateInternal.update { it.copy(errorMessage = "피어 검색 실패: 서비스 연결 안됨") }
            showToast("서비스에 연결할 수 없습니다.")
        }
    }

    fun connectToPeer(device: WifiP2pDevice) {
        Log.i(TAG, "UI Action: Request Wi-Fi Direct connect to ${device.deviceName}")
        val service = _binder.value?.getService()
        if (service != null) {
            service.connectToWifiDirectPeer(device)
        } else {
            Log.e(TAG, "Cannot connect to peer: Service not bound.")
            _wifiDirectUiStateInternal.update { it.copy(errorMessage = "피어 연결 실패: 서비스 연결 안됨") }
            showToast("서비스에 연결할 수 없습니다.")
        }
    }

    fun disconnect() {
        Log.i(TAG, "UI Action: Request Wi-Fi Direct disconnect")
        _binder.value?.getService()?.disconnectWifiDirect()
    }

    fun sendWifiDirectMessage(message: String) {
        if (message.isBlank()) {
            showToast("보낼 메시지를 입력하세요.")
            return
        }
        Log.d(TAG, "UI Action: Request Wi-Fi Direct send message: $message")
        val service = _binder.value?.getService()
        if (service != null) {
            service.sendWifiDirectData(message)
        } else {
            Log.e(TAG, "Cannot send Wi-Fi Direct message: Service not bound.")
            _wifiDirectUiStateInternal.update { it.copy(errorMessage = "메시지 전송 실패: 서비스 연결 안됨") }
            showToast("서비스에 연결할 수 없습니다.")
        }
    }


    private fun showToast(message: String) {
        viewModelScope.launch {
            Toast.makeText(getApplication(), message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCleared() {
        super.onCleared()
        Log.i(TAG, "ViewModel Cleared: Unbinding from CommunicationService...")
        try {
            getApplication<Application>().unbindService(serviceConnection)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Error unbinding service for WifiDirectViewModel", e)
        }
        _binder.value = null
    }
}
