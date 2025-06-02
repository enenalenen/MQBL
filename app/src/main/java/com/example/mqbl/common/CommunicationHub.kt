package com.example.mqbl.common

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object CommunicationHub {

    private val bleToTcpMutex = Mutex()
    private val tcpToBleMutex = Mutex()
    private val wifiDirectToTcpMutex = Mutex() // Wi-Fi Direct -> TCP 뮤텍스
    private val tcpToWifiDirectMutex = Mutex() // TCP -> Wi-Fi Direct 뮤텍스

    // BLE -> TCP/IP
    private val _bleToTcpFlow = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 10)
    val bleToTcpFlow = _bleToTcpFlow.asSharedFlow()

    // TCP/IP -> BLE
    private val _tcpToBleFlow = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 10)
    val tcpToBleFlow = _tcpToBleFlow.asSharedFlow()

    // Wi-Fi Direct -> TCP/IP
    private val _wifiDirectToTcpFlow = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 10)
    val wifiDirectToTcpFlow = _wifiDirectToTcpFlow.asSharedFlow()

    // TCP/IP -> Wi-Fi Direct
    private val _tcpToWifiDirectFlow = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 10)
    val tcpToWifiDirectFlow = _tcpToWifiDirectFlow.asSharedFlow()


    suspend fun emitBleToTcp(message: String) {
        bleToTcpMutex.withLock {
            _bleToTcpFlow.emit(message)
        }
    }

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
