package com.example.mqbl.ui.mqtt // 실제 패키지 경로 확인

import android.app.Application
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import info.mqtt.android.service.MqttAndroidClient // hannesa2 라이브러리 import
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.eclipse.paho.client.mqttv3.*
import java.text.SimpleDateFormat
import java.util.*
import javax.net.ssl.SSLSocketFactory // SSL 소켓 팩토리 사용
import com.example.mqbl.common.CommunicationHub

const val MQTT_SUBSCRIBE_TOPIC = "test/mqbl/status" // 자동 구독할 토픽
const val MQTT_PUBLISH_TOPIC = "test/mqbl/command" // 고정 발행 토픽

// 상수 정의
private const val TAG = "MqttViewModel"
private const val MAX_MQTT_LOG_SIZE = 50 // MQTT 로그 최대 크기

// --- 상태 표현을 위한 데이터 클래스 정의 ---
// (ViewModel 또는 별도 state 파일로 이동 가능)
data class MqttUiState(
    val connectionStatus: String = "상태: 연결 끊김",
    val isConnected: Boolean = false,
    val errorMessage: String? = null
)

data class MqttMessageItem(
    val id: UUID = UUID.randomUUID(), // LazyColumn key 용 고유 ID
    val topic: String,
    val payload: String,
    val timestamp: String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()) // 생성 시 타임스탬프
)
// --- 상태 클래스 정의 끝 ---

class MqttViewModel(application: Application) : AndroidViewModel(application) {

    // --- MQTT 클라이언트 설정 ---
    // 주의: 실제 환경에서는 보안을 위해 하드코딩 대신 안전한 방법 사용 권장
    private val serverUri = "ssl://980ce8dfb90a4c1f923f97df872e7302.s1.eu.hivemq.cloud:8883"
    private val clientId: String = MqttClient.generateClientId() // 고유 클라이언트 ID 생성
    private val username = "poiu0987" // HiveMQ Cloud 사용자 이름
    private val password = "Qwer1234".toCharArray() // HiveMQ Cloud 비밀번호

    private lateinit var mqttClient: MqttAndroidClient

    // --- UI 상태 관리를 위한 StateFlow ---
    private val _uiState = MutableStateFlow(MqttUiState())
    val uiState: StateFlow<MqttUiState> = _uiState.asStateFlow()

    private val _receivedMessages = MutableStateFlow<List<MqttMessageItem>>(emptyList())
    val receivedMessages: StateFlow<List<MqttMessageItem>> = _receivedMessages.asStateFlow()

    // --- ViewModel 초기화 ---
    init {
        initializeMqttClient()
        listenForBleMessages()
    }

    // --- 공개 함수 (UI에서 호출) ---

    /** MQTT 브로커에 연결 시도 */
    fun connect() {
        if (uiState.value.isConnected) {
            showToast("이미 연결되어 있습니다.")
            return
        }
        if (!::mqttClient.isInitialized) {
            initializeMqttClient() // 초기화 안됐으면 다시 시도
        }

        val options = MqttConnectOptions().apply {
            isAutomaticReconnect = true
            isCleanSession = true
            // connectionTimeout = 10 // 필요시 설정
            // keepAliveInterval = 20 // 필요시 설정
            userName = this@MqttViewModel.username // ViewModel의 username 사용
            password = this@MqttViewModel.password // ViewModel의 password 사용
            // SSL 설정 (ssl:// 스킴 사용 시 기본 SSL 소켓 팩토리)
            try {
                socketFactory = SSLSocketFactory.getDefault()
            } catch (e: Exception) {
                Log.e(TAG, "Error setting SSL socket factory", e)
                viewModelScope.launch {
                    _uiState.update { it.copy(
                        isConnected = false,
                        connectionStatus = "상태: SSL 오류",
                        errorMessage = "SSL 설정 오류: ${e.message}"
                    )}
                }
                return // 연결 시도 중단
            }
        }

        viewModelScope.launch { _uiState.update { it.copy(connectionStatus = "상태: 연결 중...", errorMessage = null) } }

        try {
            mqttClient.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.d(TAG, "MQTT Connect Action Success (Callback will confirm)")
                    // 실제 연결 완료는 connectComplete 콜백에서 처리됨
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    val errorMsg = exception?.message ?: "알 수 없는 오류"
                    Log.e(TAG, "MQTT Connect Action Failed: $errorMsg")
                    viewModelScope.launch {
                        _uiState.update { it.copy(
                            isConnected = false,
                            connectionStatus = "상태: 연결 실패",
                            errorMessage = "연결 실패: $errorMsg"
                        )}
                    }
                }
            })
        } catch (e: MqttException) {
            Log.e(TAG, "MQTT Connect Exception: ${e.message}")
            viewModelScope.launch {
                _uiState.update { it.copy(
                    isConnected = false,
                    connectionStatus = "상태: 연결 예외",
                    errorMessage = "연결 예외: ${e.message}"
                )}
            }
        }
    }

    /** MQTT 브로커 연결 해제 */
    fun disconnect() {
        if (!uiState.value.isConnected) {
            showToast("이미 연결이 끊겨 있습니다.")
            return
        }
        if (!::mqttClient.isInitialized || !mqttClient.isConnected) {
            Log.w(TAG,"Client not initialized or not connected for disconnect.")
            // 상태 강제 업데이트 (혹시 모를 불일치 대비)
            viewModelScope.launch { _uiState.update { it.copy(isConnected = false, connectionStatus = "상태: 연결 끊김", errorMessage = null) } }
            return
        }

        viewModelScope.launch { _uiState.update { it.copy(connectionStatus = "상태: 연결 해제 중...") } }

        try {
            // 연결 해제 시에는 콜백 대신 즉시 실행 시도 가능 (또는 콜백 사용)
            mqttClient.disconnect(null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.d(TAG, "MQTT Disconnect Action Success")
                    // 상태 업데이트는 connectionLost 또는 여기서 직접 처리
                    viewModelScope.launch {
                        _uiState.update { it.copy(
                            isConnected = false,
                            connectionStatus = "상태: 연결 끊김",
                            errorMessage = null
                        )}
                    }
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    val errorMsg = exception?.message ?: "알 수 없는 오류"
                    Log.e(TAG, "MQTT Disconnect Action Failed: $errorMsg")
                    // 실패 시에도 상태는 연결 끊김으로 간주하거나 오류 표시
                    viewModelScope.launch { _uiState.update { it.copy(errorMessage = "연결 해제 실패: $errorMsg") } }
                }
            })
        } catch (e: MqttException) {
            Log.e(TAG, "MQTT Disconnect Exception: ${e.message}")
            viewModelScope.launch { _uiState.update { it.copy(errorMessage = "연결 해제 예외: ${e.message}") } }
        }
    }

    // --- subscribe 함수는 내부용으로 변경 (private) ---
    private fun subscribe(topic: String) {
        if (topic.isBlank()) {
            Log.w(TAG,"Subscribe attempt with blank topic.")
            return
        }
        if (!uiState.value.isConnected) {
            Log.w(TAG,"Subscribe attempt while not connected.")
            // 연결 완료 콜백에서 호출되므로 이 경우는 거의 없지만 방어 코드
            return
        }
        try {
            val qos = 1
            mqttClient.subscribe(topic, qos, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.i(TAG, "Successfully subscribed to topic: $topic")
                    showToast("$topic 구독 성공") // 구독 성공 피드백
                }
                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    val errorMsg = exception?.message ?: "알 수 없는 오류"
                    Log.e(TAG, "Failed to subscribe to topic $topic: $errorMsg")
                    showToast("$topic 구독 실패: $errorMsg")
                }
            })
        } catch (e: MqttException) {
            Log.e(TAG, "Subscribe Exception: ${e.message}")
            showToast("구독 예외: ${e.message}")
        }
    }
    // --- subscribe 함수 끝 ---

    /** 지정된 토픽으로 메시지 발행 */
    fun publish(payload: String) {
        val topic = MQTT_PUBLISH_TOPIC // 고정된 발행 토픽 사용
        if (payload.isBlank()) {
            showToast("발행할 메시지를 입력하세요.")
            return
        }
        if (!uiState.value.isConnected) {
            showToast("먼저 브로커에 연결하세요.")
            return
        }
        if (!::mqttClient.isInitialized || !mqttClient.isConnected) {
            Log.w(TAG, "Client not ready for publish.")
            return
        }

        try {
            val message = MqttMessage(payload.toByteArray()).apply {
                this.qos = 1
                this.isRetained = false
            }
            mqttClient.publish(topic, message, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.d(TAG, "Message publish action success to topic $topic")
                }
                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    val errorMsg = exception?.message ?: "알 수 없는 오류"
                    Log.e(TAG, "Failed to publish message: $errorMsg")
                    showToast("발행 실패: $errorMsg")
                }
            })
        } catch (e: MqttException) {
            Log.e(TAG, "Publish Exception: ${e.message}")
            showToast("발행 예외: ${e.message}")
        }
    }
    // --- publish 함수 수정 끝 ---

    // --- 내부 로직 함수 ---

    // --- BLE -> MQTT 메시지 처리 로직 ---
    /** CommunicationHub를 통해 BLE로부터 오는 메시지를 구독하고 MQTT로 발행 */
    private fun listenForBleMessages() {
        viewModelScope.launch {
            CommunicationHub.bleToMqttFlow.collect { message ->
                Log.i(TAG, "Message received from Hub (BLE->MQTT): $message")
                // 현재 MQTT 브로커에 연결되어 있는지 확인
                if (uiState.value.isConnected) {
                    Log.d(TAG, "Publishing message from BLE to MQTT topic $MQTT_PUBLISH_TOPIC")
                    // 수신된 메시지를 고정된 발행 토픽으로 발행
                    publish(payload = message) // 수정된 publish 함수 사용
                } else {
                    Log.w(TAG, "MQTT not connected. Cannot publish message from BLE.")
                    // TODO: 사용자에게 메시지 발행 실패 피드백 고려 또는 메시지 큐잉
                }
            }
        }
    }

    /** MQTT 클라이언트 초기화 및 콜백 설정 */
    private fun initializeMqttClient() {
        val context = getApplication<Application>().applicationContext
        // 클라이언트 객체 생성 (이미 초기화되었으면 중복 생성 방지)
        if (::mqttClient.isInitialized) {
            Log.w(TAG,"MQTT Client already initialized.")
            return
        }
        mqttClient = MqttAndroidClient(context, serverUri, clientId)
        mqttClient.setCallback(object : MqttCallbackExtended {
            // 연결 완료 시 (재연결 포함)
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                Log.i(TAG, "MQTT Connect Complete. Reconnect: $reconnect")
                viewModelScope.launch {
                    _uiState.update { it.copy(
                        isConnected = true,
                        connectionStatus = "상태: 연결됨 (${serverURI})",
                        errorMessage = null
                    )}
                    Log.i(TAG, "Auto-subscribing to $MQTT_SUBSCRIBE_TOPIC")
                    subscribe(MQTT_SUBSCRIBE_TOPIC) // 정의된 상수로 구독
                }
            }

            // 연결 끊김 시
            override fun connectionLost(cause: Throwable?) {
                val errorMsg = cause?.message ?: "알 수 없는 이유"
                Log.e(TAG, "MQTT Connection Lost: $errorMsg", cause)
                viewModelScope.launch {
                    _uiState.update { it.copy(
                        isConnected = false,
                        connectionStatus = "상태: 연결 끊김",
                        errorMessage = "연결 끊김: $errorMsg"
                    )}
                }
                showToast("MQTT 연결이 끊어졌습니다!")
            }

            // 메시지 도착 시
            override fun messageArrived(topic: String?, message: MqttMessage?) {
                if (topic != null && message != null) {
                    val msgPayload = message.toString()
                    val newItem = MqttMessageItem(topic = topic, payload = msgPayload)
                    Log.d(TAG, "MQTT Message Arrived: ${newItem.payload} on topic ${newItem.topic}")

                    // 로컬 메시지 로그 업데이트 (기존 로직)
                    viewModelScope.launch {
                        val currentList = _receivedMessages.value
                        val updatedList = (listOf(newItem) + currentList).take(MAX_MQTT_LOG_SIZE)
                        _receivedMessages.value = updatedList
                    }

                    // --- 구독 토픽으로 온 메시지인지 확인하고 BLE Flow로 전달 ---
                    if (topic == MQTT_SUBSCRIBE_TOPIC) {
                        viewModelScope.launch {
                            Log.i(TAG, "Forwarding message to Hub (MQTT->BLE): $msgPayload")
                            // 수신된 메시지 페이로드를 그대로 Hub로 전달
                            CommunicationHub.emitMqttToBle(msgPayload)
                        }
                    }
                    // ---------------------------------------------------
                }
            }

            // 메시지 발행 완료 시
            override fun deliveryComplete(token: IMqttDeliveryToken?) {
                try {
                    Log.d(TAG, "MQTT Delivery Complete for message: ${token?.message?.id}")
                    // showToast("메시지 전달 완료 (ID: ${token?.messageId})")
                } catch (e: Exception) {
                    Log.e(TAG, "Error in deliveryComplete", e)
                }
            }
        })
    }

    /** 간단한 Toast 메시지 표시 (Main 스레드 보장) */
    private fun showToast(message: String) {
        // ViewModel에서는 직접 Context를 사용하기보다 Application Context 사용
        // Toast는 Main 스레드에서 실행되어야 하므로 viewModelScope.launch 사용
        viewModelScope.launch { // 기본적으로 Main 디스패처에서 실행됨 (설정 따라 다를 수 있음)
            Toast.makeText(getApplication(), message, Toast.LENGTH_SHORT).show()
        }
    }

    // --- ViewModel 소멸 시 자원 정리 ---
    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "ViewModel cleared. Disconnecting MQTT client.")
        try {
            if (::mqttClient.isInitialized && mqttClient.isConnected) {
                mqttClient.disconnect() // 콜백 없이 즉시 연결 해제 시도
            }
            // 필요한 경우 mqttClient.unregisterResources() 호출 (라이브러리 문서 확인)
        } catch (e: Exception) { // MqttException 포함 모든 예외 처리
            Log.e(TAG, "Error during MQTT client cleanup in onCleared", e)
        }
    }

} // MqttViewModel 끝