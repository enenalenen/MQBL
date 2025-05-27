package com.example.mqbl.ui.tcp // 패키지 경로 예시 (기존 ui/mqtt에서 변경)

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
import com.example.mqbl.service.CommunicationService // 서비스 import
// --- 수정: ui.tcp 패키지에서 직접 import ---
import com.example.mqbl.ui.tcp.TcpUiState // TcpState.kt 파일의 실제 경로로 수정
import com.example.mqbl.ui.tcp.TcpMessageItem // TcpState.kt 파일의 실제 경로로 수정
// --- 수정 끝 ---
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

private const val TAG = "TcpViewModel"

// 기본 서버 IP 및 포트 (UI에서 변경 가능하도록 할 예정)
private const val DEFAULT_SERVER_IP = "192.168.0.18"
private const val DEFAULT_SERVER_PORT = 12345

@OptIn(ExperimentalCoroutinesApi::class) // flatMapLatest 사용
class TcpViewModel(application: Application) : AndroidViewModel(application) {

    // --- Service Binder 상태 ---
    private val _binder = MutableStateFlow<CommunicationService.LocalBinder?>(null)

    // --- Service Connection 콜백 ---
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.i(TAG, "CommunicationService Connected")
            _binder.value = service as? CommunicationService.LocalBinder
            // 서비스 연결 시 초기 상태 업데이트 요청 (선택 사항)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "CommunicationService Disconnected unexpectedly")
            _binder.value = null
            // ViewModel의 상태도 연결 끊김으로 업데이트
            _tcpUiStateInternal.update { it.copy(isConnected = false, connectionStatus = "TCP/IP: 서비스 연결 끊김", errorMessage = "서비스 연결이 예기치 않게 종료되었습니다.") }
        }
    }

    // --- UI에 직접 노출할 상태 (TextField 입력 값 등) ---
    private val _serverIp = MutableStateFlow(DEFAULT_SERVER_IP)
    val serverIp: StateFlow<String> = _serverIp.asStateFlow()

    private val _serverPort = MutableStateFlow(DEFAULT_SERVER_PORT.toString()) // Port는 문자열로 관리
    val serverPort: StateFlow<String> = _serverPort.asStateFlow()

    // --- Service로부터 받는 상태를 위한 내부 StateFlow ---
    // Service가 연결 해제되었을 때 ViewModel이 자체적으로 기본 상태를 유지하기 위함
    private val _tcpUiStateInternal = MutableStateFlow(TcpUiState(connectionStatus = "TCP/IP: 서비스 연결 대기 중..."))
    val tcpUiState: StateFlow<TcpUiState> = _tcpUiStateInternal.asStateFlow()

    private val _receivedTcpMessagesInternal = MutableStateFlow<List<TcpMessageItem>>(emptyList())
    val receivedTcpMessages: StateFlow<List<TcpMessageItem>> = _receivedTcpMessagesInternal.asStateFlow()


    // --- ViewModel 초기화 ---
    init {
        Log.d(TAG, "ViewModel Init: Binding to CommunicationService...")
        Intent(application, CommunicationService::class.java).also { intent ->
            application.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }

        // Service의 StateFlow를 구독하여 ViewModel 내부 StateFlow 업데이트
        viewModelScope.launch {
            _binder.flatMapLatest { binder ->
                // --- 수정: binder에서 직접 getTcpUiStateFlow() 호출 ---
                binder?.getTcpUiStateFlow()
                    ?: flowOf(TcpUiState(connectionStatus = "TCP/IP: 서비스 연결 안됨"))
                // --- 수정 끝 ---
            }.collect { serviceState ->
                _tcpUiStateInternal.value = serviceState
            }
        }
        viewModelScope.launch {
            _binder.flatMapLatest { binder ->
                // --- 수정: binder에서 직접 getReceivedTcpMessagesFlow() 호출 ---
                binder?.getReceivedTcpMessagesFlow()
                    ?: flowOf(emptyList())
                // --- 수정 끝 ---
            }.collect { messages ->
                _receivedTcpMessagesInternal.value = messages
            }
        }
    }

    // --- UI 입력 값 업데이트 함수 ---
    fun updateServerIp(ip: String) {
        _serverIp.value = ip
    }

    fun updateServerPort(port: String) {
        _serverPort.value = port
    }

    // --- 공개 함수 (UI 액션을 Service에 위임) ---
    fun connect() {
        val ip = _serverIp.value
        val port = _serverPort.value.toIntOrNull()

        if (ip.isBlank() || port == null) {
            showToast("올바른 서버 IP와 포트를 입력하세요.")
            _tcpUiStateInternal.update { it.copy(errorMessage = "IP 또는 포트 입력 오류") }
            return
        }

        Log.i(TAG, "UI Action: Request TCP connect to $ip:$port")
        val service = _binder.value?.getService()
        if (service != null) {
            service.requestTcpConnect(ip, port)
        } else {
            Log.e(TAG, "Cannot connect TCP: Service not bound.")
            _tcpUiStateInternal.update { it.copy(errorMessage = "서비스에 연결할 수 없습니다.") }
            showToast("서비스에 연결할 수 없습니다.")
        }
    }

    fun disconnect() {
        Log.i(TAG, "UI Action: Request TCP disconnect")
        _binder.value?.getService()?.requestTcpDisconnect()
    }

    fun sendMessage(message: String) {
        if (message.isBlank()) {
            showToast("보낼 메시지를 입력하세요.")
            return
        }
        Log.d(TAG, "UI Action: Request TCP send message: $message")
        val service = _binder.value?.getService()
        if (service != null) {
            service.sendTcpMessage(message)
        } else {
            Log.e(TAG, "Cannot send TCP message: Service not bound.")
            _tcpUiStateInternal.update { it.copy(errorMessage = "메시지 전송 실패: 서비스 연결 안됨") }
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
            Log.w(TAG, "Error unbinding service", e)
        }
        _binder.value = null
    }
}
