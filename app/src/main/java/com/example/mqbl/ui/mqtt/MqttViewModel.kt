package com.example.mqbl.ui.mqtt

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import android.widget.Toast // ViewModel에서 간단한 피드백용으로 유지 (선택 사항)
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.mqbl.service.CommunicationService // 생성한 서비스 import
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

// MqttUiState와 MqttMessageItem이 이 파일 또는 import 가능한 다른 파일에 정의되어 있어야 합니다.
// 예시: import com.example.mqbl.ui.mqtt.MqttUiState
// 예시: import com.example.mqbl.ui.mqtt.MqttMessageItem

private const val TAG = "MqttViewModel" // ViewModel 로그 태그

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MqttViewModel(application: Application) : AndroidViewModel(application) {

    // --- Service Binder 상태 ---
    private val _binder = MutableStateFlow<CommunicationService.LocalBinder?>(null)

    // --- Service Connection 콜백 정의 ---
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.i(TAG, "CommunicationService Connected")
            _binder.value = service as? CommunicationService.LocalBinder
            // 서비스 연결 시 초기 상태 로드/갱신 요청 (선택 사항)
            // 예: _binder.value?.getService()?.refreshMqttState() // Service에 해당 함수가 있다면
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "CommunicationService Disconnected unexpectedly")
            _binder.value = null
            // TODO: UI에 서비스 연결 끊김 상태 반영
        }
    }

    // --- UI에 노출할 상태 (Service의 StateFlow로부터 파생) ---

    // 서비스 미연결 시 기본값
    private val defaultMqttUiState = MqttUiState(connectionStatus = "서비스 연결 대기 중...")
    private val defaultReceivedMessages = emptyList<MqttMessageItem>()

    // Service의 MQTT 상태 Flow 구독
    val uiState: StateFlow<MqttUiState> = _binder.flatMapLatest { binder ->
        // 수정된 부분: binder에서 직접 flow getter 호출
        binder?.getMqttUiStateFlow()
            ?: flowOf(defaultMqttUiState)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = defaultMqttUiState
    )

    // Service의 MQTT 수신 메시지 Flow 구독
    val receivedMessages: StateFlow<List<MqttMessageItem>> = _binder.flatMapLatest { binder ->
        // 수정된 부분: binder에서 직접 flow getter 호출
        binder?.getReceivedMqttMessagesFlow()
            ?: flowOf(defaultReceivedMessages)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = defaultReceivedMessages
    )

    // --- ViewModel 초기화 ---
    init {
        Log.d(TAG, "ViewModel Init: Binding to CommunicationService...")
        // Service에 바인딩 시작
        Intent(application, CommunicationService::class.java).also { intent ->
            application.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    // --- 공개 함수 (UI 액션을 Service에 위임) ---

    /** Service에 MQTT 연결 요청 */
    fun connect() {
        Log.i(TAG, "UI Action: Request MQTT connect")
        val service = _binder.value?.getService() // Service 인스턴스는 여기서 가져옴
        if (service != null) {
            service.requestMqttConnect() // Service의 함수 호출
        } else {
            Log.e(TAG, "Cannot connect MQTT: Service not bound.")
            showToast("서비스에 연결할 수 없습니다.")
        }
    }

    /** Service에 MQTT 연결 해제 요청 */
    fun disconnect() {
        Log.i(TAG, "UI Action: Request MQTT disconnect")
        _binder.value?.getService()?.requestMqttDisconnect()
    }

    /** Service에 MQTT 토픽 구독 요청 (자동 구독 외 추가 구독 필요 시) */
    fun subscribe(topic: String) {
        Log.i(TAG, "UI Action: Request MQTT subscribe to $topic")
        val service = _binder.value?.getService()
        if (service != null) {
            service.requestMqttSubscribe(topic) // Service의 함수 호출
        } else {
            Log.e(TAG, "Cannot subscribe MQTT: Service not bound.")
            showToast("서비스에 연결할 수 없습니다.")
        }
    }

    /** Service에 MQTT 메시지 발행 요청 (고정 토픽 사용) */
    fun publish(payload: String) {
        Log.d(TAG, "UI Action: Request MQTT publish payload: $payload")
        val service = _binder.value?.getService()
        if (service != null) {
            service.requestMqttPublish(payload) // Service의 함수 호출
        } else {
            Log.e(TAG, "Cannot publish MQTT: Service not bound.")
            showToast("서비스에 연결할 수 없습니다.")
        }
    }

    // 간단 Toast 메시지 (선택 사항)
    private fun showToast(message: String) {
        viewModelScope.launch {
            Toast.makeText(getApplication(), message, Toast.LENGTH_SHORT).show()
        }
    }

    // --- ViewModel 소멸 시 Service 언바인딩 ---
    override fun onCleared() {
        super.onCleared()
        Log.i(TAG, "ViewModel Cleared: Unbinding from CommunicationService...")
        try {
            getApplication<Application>().unbindService(serviceConnection)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Attempted to unbind ServiceConnection that was not registered.", e)
        }
        _binder.value = null // Binder 참조 제거
    }
} // MqttViewModel 끝