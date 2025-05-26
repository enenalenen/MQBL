package com.example.mqbl.ui.mqtt // 실제 패키지 경로 확인

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme // 다크 모드 감지
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
// import com.example.mqbl.ui.theme.MQBLTheme // 앱 테마 import (필요시)

// MqttUiState, MqttMessageItem은 ui/mqtt/MqttState.kt 등 별도 파일에 정의되어 있다고 가정
// import com.example.mqbl.ui.mqtt.MqttUiState
// import com.example.mqbl.ui.mqtt.MqttMessageItem

private const val MQTT_PUBLISH_TOPIC_DISPLAY = "test/mqbl/command" // 표시용 상수

@Composable
fun MqttScreen(
    uiState: MqttUiState,
    receivedMessages: List<MqttMessageItem>,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onPublish: (payload: String) -> Unit
) {
    var publishMessage by remember { mutableStateOf("Hello MQBL!") }
    val listState = rememberLazyListState()
    val isDarkTheme = isSystemInDarkTheme() // 현재 테마가 다크 모드인지 확인

    LaunchedEffect(receivedMessages.size) {
        if (receivedMessages.isNotEmpty()) {
            listState.animateScrollToItem(index = 0)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // 현재 연결 상태에 대한 간단한 안내
        if (uiState.isConnected) {
            Text("MQTT 연결됨. Topic: $MQTT_PUBLISH_TOPIC_DISPLAY 로 메시지 발행 가능", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 8.dp))
        } else {
            Text("MQTT 연결되지 않음. '사용자 설정' 탭에서 연결하세요.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp))
        }
        // 오류 메시지 표시
        uiState.errorMessage?.let {
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = "오류: $it", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            Spacer(modifier = Modifier.height(4.dp))
        }

        Spacer(modifier = Modifier.height(16.dp)) // 연결/해제 버튼과의 간격 유지

        // 발행 영역
        Text("메시지 발행 (Topic: $MQTT_PUBLISH_TOPIC_DISPLAY)", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = publishMessage,
            onValueChange = { publishMessage = it },
            label = { Text("발행 메시지") },
            modifier = Modifier.fillMaxWidth(),
            enabled = uiState.isConnected
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = { onPublish(publishMessage) },
            enabled = uiState.isConnected,
            modifier = Modifier.align(Alignment.End)
        ) {
            Text("발행")
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 수신 메시지 로그
        Text("수신 메시지:", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(8.dp))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = MaterialTheme.shapes.medium,
            border = BorderStroke(1.dp, Color.Gray),
            // --- 다크 모드일 때 배경색 변경 (더 어둡게) ---
            color = if (isDarkTheme) Color(0xFF202020) else MaterialTheme.colorScheme.surface
            // ------------------------------------
        ) {
            LazyColumn(
                modifier = Modifier.padding(8.dp),
                state = listState,
                reverseLayout = true
            ) {
                if (receivedMessages.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillParentMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("수신된 메시지가 없습니다.")
                        }
                    }
                } else {
                    items(items = receivedMessages, key = { it.id }) { messageItem ->
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            Text(
                                text = "Topic: ${messageItem.topic}",
                                fontSize = 12.sp,
                                color = if (isDarkTheme) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f) else Color.DarkGray
                            )
                            Text(
                                text = messageItem.payload,
                                fontSize = 14.sp
                                // color = MaterialTheme.colorScheme.onSurface // 테마 색상 사용
                            )
                            Text(
                                text = messageItem.timestamp,
                                fontSize = 10.sp,
                                color = if (isDarkTheme) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f) else Color.Gray
                            )
                        }
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = if (isDarkTheme) MaterialTheme.colorScheme.outline.copy(alpha = 0.5f) else Color.LightGray
                        )
                    }
                }
            }
        }
    }
}

// --- Composable Preview ---
@Preview(showBackground = true, name = "Light Mode")
@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES, name = "Dark Mode")
@Composable
fun MqttScreenPreview() {
    val isDark = isSystemInDarkTheme()
    // 프리뷰용 임시 데이터 클래스 정의 (실제 클래스는 별도 파일 또는 ViewModel에서 가져옴)
    // data class PreviewMqttUiState(val connectionStatus: String, val isConnected: Boolean, val errorMessage: String?)
    // data class PreviewMqttMessageItem(val id: java.util.UUID = java.util.UUID.randomUUID(), val topic: String, val payload: String, val timestamp: String = "12:34:56")

    val previewUiState = MqttUiState( // 실제 MqttUiState 사용
        connectionStatus = "상태: 연결됨 (미리보기)",
        isConnected = true,
        errorMessage = null
    )
    val previewMessages = listOf(
        MqttMessageItem(topic = "test/topic/1", payload = "안녕하세요! MQTT!"),
        MqttMessageItem(topic = "test/topic/2", payload = "다른 메시지입니다."),
        MqttMessageItem(topic = "test/topic/1", payload = "더 많은 데이터...")
    ).reversed()

    MaterialTheme(
        colorScheme = if (isDark) MqttDarkColorSchemePreview else MqttLightColorSchemePreview // 수정된 프리뷰 색상 스킴 사용
    ) {
        MqttScreen(
            uiState = previewUiState,
            receivedMessages = previewMessages,
            onConnect = {},
            onDisconnect = {},
            onPublish = { }
        )
    }
}

// 프리뷰용 다크/라이트 색상 스킴 (실제 앱 테마는 MainActivity.kt의 MQBLTheme 사용)
private val MqttDarkColorSchemePreview = darkColorScheme( // 이름 변경 및 수정
    surface = Color(0xFF202020), // 더 어두운 회색
    onSurface = Color.White, // 어두운 표면 위 텍스트
    outline = Color.DarkGray // 구분선 색상
)
private val MqttLightColorSchemePreview = lightColorScheme( // 이름 변경
    surface = Color.White,
    onSurface = Color.Black, // 밝은 표면 위 텍스트
    outline = Color.LightGray
)
