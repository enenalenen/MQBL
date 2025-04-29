package com.example.mqbl.common // 또는 다른 공통 패키지 경로

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * ViewModel 간의 메시지 전달을 위한 싱글톤 객체.
 * SharedFlow를 사용하여 이벤트를 전달합니다.
 */
object CommunicationHub {

    // 뮤텍스를 사용하여 SharedFlow 동시 접근 관리 (선택적이지만 안전)
    private val bleMutex = Mutex()
    private val mqttMutex = Mutex()

    // BLE -> MQTT 메시지 전달 Flow
    // replay=0: 구독 시작 후 발생하는 이벤트만 받음
    // extraBufferCapacity: 구독자가 없어도 버퍼에 저장할 이벤트 수 (너무 크면 메모리 문제)
    private val _bleToMqttFlow = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 10)
    val bleToMqttFlow = _bleToMqttFlow.asSharedFlow() // 외부 공개용 (읽기 전용)

    // MQTT -> BLE 메시지 전달 Flow
    private val _mqttToBleFlow = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 10)
    val mqttToBleFlow = _mqttToBleFlow.asSharedFlow() // 외부 공개용 (읽기 전용)

    /** BLE에서 수신된 메시지를 MQTT 발행 Flow로 전달 */
    suspend fun emitBleToMqtt(message: String) {
        bleMutex.withLock {
            _bleToMqttFlow.emit(message)
        }
    }

    /** MQTT에서 수신된 메시지를 BLE 전송 Flow로 전달 */
    suspend fun emitMqttToBle(message: String) {
        mqttMutex.withLock {
            _mqttToBleFlow.emit(message)
        }
    }
}