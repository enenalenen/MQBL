package com.example.mqbl.common // 또는 다른 공통 패키지 경로

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * ViewModel 간 또는 Service와 ViewModel 간의 메시지 전달을 위한 싱글톤 객체.
 * SharedFlow를 사용하여 이벤트를 전달합니다.
 */
object CommunicationHub {

    // 뮤텍스를 사용하여 SharedFlow 동시 접근 관리 (선택적이지만 안전)
    private val bleToTcpMutex = Mutex() // 이름 변경: bleMutex -> bleToTcpMutex
    private val tcpToBleMutex = Mutex() // 이름 변경: mqttMutex -> tcpToBleMutex

    // BLE -> TCP/IP 메시지 전달 Flow
    // replay=0: 구독 시작 후 발생하는 이벤트만 받음
    // extraBufferCapacity: 구독자가 없어도 버퍼에 저장할 이벤트 수
    private val _bleToTcpFlow = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 10) // 이름 변경: _bleToMqttFlow -> _bleToTcpFlow
    val bleToTcpFlow = _bleToTcpFlow.asSharedFlow() // 이름 변경: bleToMqttFlow -> bleToTcpFlow

    // TCP/IP -> BLE 메시지 전달 Flow
    private val _tcpToBleFlow = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 10) // 이름 변경: _mqttToBleFlow -> _tcpToBleFlow
    val tcpToBleFlow = _tcpToBleFlow.asSharedFlow() // 이름 변경: mqttToBleFlow -> tcpToBleFlow

    /** BLE에서 수신된 메시지를 TCP/IP 전송 Flow로 전달 */
    suspend fun emitBleToTcp(message: String) { // 이름 변경: emitBleToMqtt -> emitBleToTcp
        bleToTcpMutex.withLock {
            _bleToTcpFlow.emit(message) // 변경된 Flow 사용
        }
    }

    /** TCP/IP에서 수신된 메시지를 BLE 전송 Flow로 전달 */
    suspend fun emitTcpToBle(message: String) { // 이름 변경: emitMqttToBle -> emitTcpToBle
        tcpToBleMutex.withLock {
            _tcpToBleFlow.emit(message) // 변경된 Flow 사용
        }
    }
}
