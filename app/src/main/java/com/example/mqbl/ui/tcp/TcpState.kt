package com.example.mqbl.ui.tcp // 실제 프로젝트 구조에 맞게 패키지 경로를 설정하세요.

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * TCP/IP 통신 관련 UI 상태를 나타내는 데이터 클래스.
 */
data class TcpUiState(
    val connectionStatus: String = "TCP/IP: 연결 끊김", // 초기 상태 메시지
    val isConnected: Boolean = false,
    val errorMessage: String? = null
)

/**
 * TCP/IP 통신으로 주고받는 메시지 아이템을 나타내는 데이터 클래스.
 */
data class TcpMessageItem(
    val id: UUID = UUID.randomUUID(),
    val source: String, // 메시지 출처 (예: "서버", "클라이언트->서버", IP:Port 등)
    val payload: String,
    val timestamp: String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
)
