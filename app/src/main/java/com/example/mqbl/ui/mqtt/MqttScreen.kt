package com.example.mqbl.ui.mqtt

import androidx.compose.foundation.BorderStroke
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
// import com.example.mqbl.ui.theme.MQBLTheme

private const val MQTT_PUBLISH_TOPIC_DISPLAY = "test/mqbl/command" // 표시용 상수

// MqttUiState, MqttMessageItem은 ui/mqtt/MqttState.kt 등 별도 파일에 정의되어 있다고 가정
// import com.example.mqbl.ui.mqtt.MqttUiState
// import com.example.mqbl.ui.mqtt.MqttMessageItem

@Composable
fun MqttScreen(
    uiState: MqttUiState, // 여전히 상태 표시에 사용될 수 있음 (예: 연결 상태에 따른 UI 변경)
    receivedMessages: List<MqttMessageItem>,
    onConnect: () -> Unit, // SettingsScreen으로 이동
    onDisconnect: () -> Unit, // SettingsScreen으로 이동
    onPublish: (payload: String) -> Unit
) {
    var publishMessage by remember { mutableStateOf("Hello MQBL!") }
    val listState = rememberLazyListState()

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
        // --- 상단 연결 상태 표시 및 연결/해제 버튼 제거 ---
        // Text(text = uiState.connectionStatus, style = MaterialTheme.typography.titleMedium) // SettingsScreen으로 이동
        // uiState.errorMessage?.let { ... } // SettingsScreen으로 이동
        // Spacer(modifier = Modifier.height(8.dp)) // 관련 Spacer 제거
        // Row { ... Button ... Button ... } // 연결/해제 버튼 Row 전체 제거
        // Spacer(modifier = Modifier.height(16.dp)) // 관련 Spacer 제거
        // --- UI 요소 제거 끝 ---

        // 현재 연결 상태에 대한 간단한 안내 (선택 사항)
        if (uiState.isConnected) {
            Text("MQTT 연결됨. Topic: $MQTT_PUBLISH_TOPIC_DISPLAY 로 메시지 발행 가능", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 8.dp))
        } else {
            Text("MQTT 연결되지 않음. '사용자 설정' 탭에서 연결하세요.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp))
        }


        // 발행 영역 (유지)
        Text("메시지 발행 (Topic: $MQTT_PUBLISH_TOPIC_DISPLAY)", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = publishMessage,
            onValueChange = { publishMessage = it },
            label = { Text("발행 메시지") },
            modifier = Modifier.fillMaxWidth(),
            enabled = uiState.isConnected // 연결 상태일 때만 발행 가능
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

        // 수신 메시지 로그 (유지)
        Text("수신 메시지:", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(8.dp))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = MaterialTheme.shapes.medium,
            border = BorderStroke(1.dp, Color.Gray)
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
                                color = Color.DarkGray
                            )
                            Text(
                                text = messageItem.payload,
                                fontSize = 14.sp
                            )
                            Text(
                                text = messageItem.timestamp,
                                fontSize = 10.sp,
                                color = Color.Gray,
                                modifier = Modifier.align(Alignment.End)
                            )
                        }
                        HorizontalDivider(thickness = 0.5.dp, color = Color.LightGray)
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, locale = "ko")
@Composable
fun MqttScreenPreview() {
    MaterialTheme {
        MqttScreen(
            uiState = MqttUiState(connectionStatus = "MQTT 상태: 미리보기", isConnected = true),
            receivedMessages = listOf(MqttMessageItem(topic = "preview", payload = "미리보기 메시지")),
            onConnect = {},
            onDisconnect = {},
            onPublish = {}
        )
    }
}
