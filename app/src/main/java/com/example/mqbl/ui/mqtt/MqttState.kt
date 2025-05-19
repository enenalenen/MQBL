// 예시: ui/mqtt/MqttState.kt 파일 생성 및 내용 작성
package com.example.mqbl.ui.mqtt // Service와 동일한 패키지 또는 import 가능한 위치

import java.text.SimpleDateFormat
import java.util.*

data class MqttUiState(
    val connectionStatus: String = "상태: 연결 끊김",
    val isConnected: Boolean = false,
    val errorMessage: String? = null
)

data class MqttMessageItem(
    val id: UUID = UUID.randomUUID(),
    val topic: String,
    val payload: String,
    val timestamp: String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
)