package com.example.mqbl.common

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object CommunicationHub {

    // --- ▼▼▼ BLE 오디오(ByteArray) -> TCP 통신을 위한 Flow 추가 ▼▼▼ ---
    private val bleAudioToTcpMutex = Mutex()
    private val _bleAudioToTcpFlow = MutableSharedFlow<ByteArray>(replay = 0, extraBufferCapacity = 10)
    val bleAudioToTcpFlow = _bleAudioToTcpFlow.asSharedFlow()
    // --- ▲▲▲ Flow 추가 끝 ▲▲▲ ---


    // TCP/IP -> BLE (String) - 기존 유지
    private val tcpToBleMutex = Mutex()
    private val _tcpToBleFlow = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 10)
    val tcpToBleFlow = _tcpToBleFlow.asSharedFlow()

    // Wi-Fi Direct -> TCP/IP (String) - 기존 유지
    private val wifiDirectToTcpMutex = Mutex()
    private val _wifiDirectToTcpFlow = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 10)
    val wifiDirectToTcpFlow = _wifiDirectToTcpFlow.asSharedFlow()

    // TCP/IP -> Wi-Fi Direct (String) - 기존 유지
    private val tcpToWifiDirectMutex = Mutex()
    private val _tcpToWifiDirectFlow = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 10)
    val tcpToWifiDirectFlow = _tcpToWifiDirectFlow.asSharedFlow()


    // --- ▼▼▼ ByteArray를 emit하는 함수 추가 ▼▼▼ ---
    suspend fun emitBleAudioToTcp(message: ByteArray) {
        bleAudioToTcpMutex.withLock {
            _bleAudioToTcpFlow.emit(message)
        }
    }
    // --- ▲▲▲ 함수 추가 끝 ▲▲▲ ---


    suspend fun emitTcpToBle(message: String) {
        tcpToBleMutex.withLock {
            _tcpToBleFlow.emit(message)
        }
    }

    suspend fun emitWifiDirectToTcp(message: String) {
        wifiDirectToTcpMutex.withLock {
            _wifiDirectToTcpFlow.emit(message)
        }
    }

    suspend fun emitTcpToWifiDirect(message: String) {
        tcpToWifiDirectMutex.withLock {
            _tcpToWifiDirectFlow.emit(message)
        }
    }
}
