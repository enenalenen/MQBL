package com.example.mqbl.common

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object CommunicationHub {

    // ESP32 Audio (ByteArray) -> PC 서버 TCP (이름 변경)
    private val esp32AudioToServerMutex = Mutex()
    private val _esp32AudioToServerFlow = MutableSharedFlow<ByteArray>(replay = 0, extraBufferCapacity = 10)
    val esp32AudioToServerFlow = _esp32AudioToServerFlow.asSharedFlow()

    // PC 서버 TCP -> ESP32 (String) (이름 변경)
    private val serverToEsp32Mutex = Mutex()
    private val _serverToEsp32Flow = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 10)
    val serverToEsp32Flow = _serverToEsp32Flow.asSharedFlow()

    suspend fun emitEsp32AudioToServer(message: ByteArray) {
        esp32AudioToServerMutex.withLock {
            _esp32AudioToServerFlow.emit(message)
        }
    }

    suspend fun emitServerToEsp32(message: String) {
        serverToEsp32Mutex.withLock {
            _serverToEsp32Flow.emit(message)
        }
    }
}